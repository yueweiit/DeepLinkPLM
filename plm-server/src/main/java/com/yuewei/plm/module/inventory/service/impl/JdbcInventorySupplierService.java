package com.yuewei.plm.module.inventory.service.impl;

import com.yuewei.plm.common.constant.ErrorCodeConstants;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import com.yuewei.plm.module.inventory.dto.SupplierSupplySideSaveDTO;
import com.yuewei.plm.module.inventory.service.InventorySupplierService;
import com.yuewei.plm.module.inventory.vo.InventorySupplierCenterSnapshotVO;
import com.yuewei.plm.module.inventory.vo.InventorySupplierCenterSnapshotVO.Metric;
import com.yuewei.plm.module.inventory.vo.InventorySupplierCenterSnapshotVO.ProjectItem;
import com.yuewei.plm.module.inventory.vo.InventorySupplierCenterSnapshotVO.QualificationItem;
import com.yuewei.plm.module.inventory.vo.InventorySupplierCenterSnapshotVO.RiskItem;
import com.yuewei.plm.module.inventory.vo.InventorySupplierCenterSnapshotVO.SupplierDetail;
import com.yuewei.plm.module.inventory.vo.InventorySupplierCenterSnapshotVO.SupplyRecord;
import com.yuewei.plm.module.operationlog.constant.OperationActionConstants;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class JdbcInventorySupplierService implements InventorySupplierService {

    private final JdbcTemplate jdbcTemplate;
    @Autowired private com.yuewei.plm.module.partner.SupplierProfileService profiles;
    private final OperationLogService operationLogService;

    public JdbcInventorySupplierService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null);
    }

    @Autowired
    public JdbcInventorySupplierService(JdbcTemplate jdbcTemplate, OperationLogService operationLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.operationLogService = operationLogService;
    }

    @Override
    public InventorySupplierCenterSnapshotVO snapshot() {
        List<InventorySupplierRow> rows = jdbcTemplate.query(sql(), this::mapRow);
        Map<String, SupplierAccumulator> suppliers = new LinkedHashMap<>();
        for (InventorySupplierRow row : rows) {
            if (!StringUtils.hasText(row.supplierName())) {
                continue;
            }
            suppliers.computeIfAbsent(supplierKey(row), ignored -> new SupplierAccumulator(row)).add(row);
        }

        List<SupplierDetail> supplierDetails = suppliers.values().stream()
            .map(SupplierAccumulator::toDetail)
            .sorted(Comparator.comparing(SupplierDetail::updatedAt).reversed())
            .toList();

        if (profiles != null) supplierDetails = profiles.decorate(supplierDetails);
        return new InventorySupplierCenterSnapshotVO(metrics(supplierDetails), supplierDetails, risks(rows));
    }

    @Override @Transactional
    public SupplierDetail create(SupplierSupplySideSaveDTO dto, HttpServletRequest request) {
        String code = profiles.save(null,dto);
        return snapshot().suppliers().stream().filter(s -> s.supplierCode().equals(code)).findFirst().orElseThrow();
    }
    @Override @Transactional
    public SupplierDetail update(String code,SupplierSupplySideSaveDTO dto,HttpServletRequest request) {
        profiles.save(code,dto);
        return snapshot().suppliers().stream().filter(s -> s.supplierCode().equals(code)).findFirst().orElseThrow();
    }

    private String sql() {
        if (supplierItemTableExists()) {
            return """
                select i.inventory_id, i.inventory_code, i.inventory_name, i.inventory_type,
                       i.product_id, p.product_code, p.product_name, i.unit_cost, i.currency_code,
                       i.lead_time_days, i.status, i.updated_at,
                       coalesce(nullif(trim(i.supplier_name), ''), nullif(trim(si.supplier_name), ''), nullif(trim(si.supplier_code), '')) as supplier_name,
                       nullif(trim(si.supplier_code), '') as supplier_code,
                       nullif(trim(si.supplier_contact_person), '') as supplier_contact_person,
                       nullif(trim(si.supplier_contact_phone), '') as supplier_contact_phone,
                       nullif(trim(si.supplier_contact_email), '') as supplier_contact_email,
                       nullif(trim(si.supplier_region), '') as supplier_region,
                       nullif(trim(si.supply_categories), '') as supply_categories,
                       nullif(trim(si.payment_term), '') as payment_term,
                       nullif(trim(si.cooperation_level), '') as cooperation_level,
                       nullif(trim(si.delivery_risk), '') as delivery_risk,
                       nullif(trim(si.supplier_short_name), '') as supplier_short_name
                from plm_inventory i
                left join plm_inventory_supplier_item si
                  on si.inventory_id = i.inventory_id and si.deleted_flag = 0
                left join plm_product p
                  on p.product_id = i.product_id and p.deleted_flag = 0
                where i.deleted_flag = 0
                  and coalesce(nullif(trim(i.supplier_name), ''), nullif(trim(si.supplier_name), ''), nullif(trim(si.supplier_code), '')) is not null
                order by i.updated_at desc, i.inventory_id desc
                """;
        }
        return """
            select i.inventory_id, i.inventory_code, i.inventory_name, i.inventory_type,
                   i.product_id, p.product_code, p.product_name, i.unit_cost, i.currency_code,
                   i.lead_time_days, i.status, i.updated_at,
                   nullif(trim(i.supplier_name), '') as supplier_name,
                   null as supplier_code,
                   null as supplier_contact_person,
                   null as supplier_contact_phone,
                   null as supplier_contact_email,
                   null as supplier_region,
                   null as supply_categories,
                   null as payment_term,
                   null as cooperation_level,
                   null as delivery_risk,
                   null as supplier_short_name
            from plm_inventory i
            left join plm_product p
              on p.product_id = i.product_id and p.deleted_flag = 0
            where i.deleted_flag = 0
              and nullif(trim(i.supplier_name), '') is not null
            order by i.updated_at desc, i.inventory_id desc
            """;
    }

    private boolean supplierItemTableExists() {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.tables where table_name = 'plm_inventory_supplier_item'",
            Integer.class
        );
        return count != null && count > 0;
    }

    private InventorySupplierRow mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        return new InventorySupplierRow(
            resultSet.getLong("inventory_id"),
            resultSet.getString("inventory_code"),
            resultSet.getString("inventory_name"),
            resultSet.getString("inventory_type"),
            nullableLong(resultSet, "product_id"),
            resultSet.getString("product_code"),
            resultSet.getString("product_name"),
            resultSet.getBigDecimal("unit_cost"),
            defaultText(resultSet.getString("currency_code"), "CNY"),
            nullableInteger(resultSet, "lead_time_days"),
            defaultText(resultSet.getString("status"), "draft"),
            updatedAt == null ? null : updatedAt.toLocalDateTime(),
            resultSet.getString("supplier_name"),
            resultSet.getString("supplier_code"),
            resultSet.getString("supplier_contact_person"),
            resultSet.getString("supplier_contact_phone"),
            resultSet.getString("supplier_contact_email"),
            resultSet.getString("supplier_region"),
            resultSet.getString("supply_categories"),
            resultSet.getString("payment_term"),
            resultSet.getString("cooperation_level"),
            resultSet.getString("delivery_risk"),
            resultSet.getString("supplier_short_name")
        );
    }










    private String inventoryType(SupplierSupplySideSaveDTO dto) {
        String first = normalizedCategories(dto).stream().findFirst().orElse("");
        return switch (first) {
            case "模具" -> "tooling";
            case "治具" -> "fixture";
            case "包材" -> "packaging";
            case "半成品" -> "semi_finished";
            case "成品" -> "finished";
            default -> "material";
        };
    }



    private List<String> normalizedCategories(SupplierSupplySideSaveDTO dto) {
        Set<String> values = new LinkedHashSet<>();
        if (dto.getSupplyCategories() != null) {
            for (String category : dto.getSupplyCategories()) {
                if (StringUtils.hasText(category)) {
                    values.add(category.trim());
                }
            }
        }
        if (values.isEmpty()) {
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "供应品类不能为空");
        }
        return new ArrayList<>(values);
    }

    private String currentUserName() {
        return CurrentUserContext.get()
            .map(CurrentUser::displayName)
            .filter(StringUtils::hasText)
            .orElse("system");
    }

    private static String supplierDisplayCode(InventorySupplierRow row) {
        return StringUtils.hasText(row.supplierCode())
            ? row.supplierCode().trim()
            : fallbackSupplierCode(row.supplierName());
    }

    private static String fallbackSupplierCode(String supplierName) {
        int hash = Math.floorMod(Objects.hashCode(supplierName), 100000);
        return "SUP-" + String.format("%05d", hash % 100000);
    }

    private static String required(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, message);
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private List<Metric> metrics(List<SupplierDetail> suppliers) {
        long activeCount = suppliers.stream().filter(item -> "active".equals(item.status())).count();
        int recordCount = suppliers.stream().mapToInt(item -> item.supplyRecords().size()).sum();
        long riskCount = suppliers.stream().filter(item -> !"low".equals(item.deliveryRisk())).count();
        return List.of(
            new Metric("合作供应商", String.valueOf(activeCount), "来自 Inventory 供应侧资料", "/suppliers?status=active"),
            new Metric("供应物料", String.valueOf(recordCount), "物料 / 包材 / 模具治具", "/inventories"),
            new Metric("交期风险", String.valueOf(riskCount), "按交期天数和库存状态粗略识别", "/suppliers?risk=delivery")
        );
    }

    private List<RiskItem> risks(List<InventorySupplierRow> rows) {
        return rows.stream()
            .filter(this::isRiskRow)
            .limit(8)
            .map(row -> new RiskItem(
                row.supplierName() + " - " + row.inventoryName(),
                row.leadTimeDays() != null && row.leadTimeDays() > 45 ? "high" : "medium",
                "采购",
                row.leadTimeDays() == null ? "补齐供应资料状态" : "确认交期 " + row.leadTimeDays() + " 天是否影响项目",
                "/inventories"
            ))
            .toList();
    }

    private boolean isRiskRow(InventorySupplierRow row) {
        return row.leadTimeDays() != null && row.leadTimeDays() > 30
            || "draft".equals(row.status())
            || "closed".equals(row.status())
            || "consumed".equals(row.status());
    }

    private String supplierKey(InventorySupplierRow row) {
        String code = trim(row.supplierCode());
        if (StringUtils.hasText(code)) {
            return code.toUpperCase(Locale.ROOT);
        }
        return row.supplierName().trim().toUpperCase(Locale.ROOT);
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private record InventorySupplierRow(
        Long inventoryId,
        String inventoryCode,
        String inventoryName,
        String inventoryType,
        Long productId,
        String productCode,
        String productName,
        BigDecimal unitCost,
        String currencyCode,
        Integer leadTimeDays,
        String status,
        LocalDateTime updatedAt,
        String supplierName,
        String supplierCode,
        String supplierContactPerson,
        String supplierContactPhone,
        String supplierContactEmail,
        String supplierRegion,
        String supplyCategories,
        String paymentTerm,
        String cooperationLevel,
        String deliveryRisk,
        String supplierShortName
    ) {
    }

    private static final class SupplierAccumulator {
        private final long supplierId;
        private final String supplierCode;
        private final String supplierName;
        private final Set<String> supplyCategories = new LinkedHashSet<>();
        private final Map<Long, SupplyRecord> records = new LinkedHashMap<>();
        private final Map<Long, ProjectItem> projects = new LinkedHashMap<>();
        private String shortName;
        private String contactPerson;
        private String contactPhone;
        private String contactEmail;
        private String region;
        private String paymentTerm;
        private String cooperationLevel;
        private String deliveryRisk;
        private LocalDateTime updatedAt;
        private boolean inactiveOnly = true;
        private boolean draftExists = false;
        private Integer maxLeadTimeDays = null;

        private SupplierAccumulator(InventorySupplierRow firstRow) {
            this.supplierCode = StringUtils.hasText(firstRow.supplierCode())
                ? firstRow.supplierCode().trim()
                : fallbackSupplierCode(firstRow.supplierName());
            this.supplierName = firstRow.supplierName().trim();
            this.supplierId = Integer.toUnsignedLong(Objects.hash(this.supplierCode, this.supplierName));
        }

        private void add(InventorySupplierRow row) {
            addSupplyCategories(row);
            shortName = firstText(shortName, row.supplierShortName(), supplierName);
            contactPerson = firstText(contactPerson, row.supplierContactPerson(), "采购维护");
            contactPhone = firstText(contactPhone, row.supplierContactPhone(), "");
            contactEmail = firstText(contactEmail, row.supplierContactEmail(), "");
            region = firstText(region, row.supplierRegion(), "未维护");
            paymentTerm = firstText(paymentTerm, row.paymentTerm(), "在物料/模具资料维护");
            cooperationLevel = firstText(cooperationLevel, row.cooperationLevel(), "Inventory供应侧资料");
            deliveryRisk = firstText(deliveryRisk, row.deliveryRisk(), null);
            records.putIfAbsent(row.inventoryId(), new SupplyRecord(
                row.inventoryId(),
                supplyType(row.inventoryType()),
                defaultText(row.inventoryCode(), "--"),
                defaultText(row.inventoryName(), "--"),
                defaultText(row.productName(), "--"),
                row.unitCost() == null ? BigDecimal.ZERO : row.unitCost(),
                defaultText(row.currencyCode(), "CNY"),
                "",
                defaultText(row.status(), "draft"),
                "/inventories"
            ));
            if (row.productId() != null) {
                projects.putIfAbsent(row.productId(), new ProjectItem(
                    defaultText(row.productCode(), "--"),
                    defaultText(row.productName(), "--"),
                    categoryLabel(row.inventoryType()) + "供应",
                    "Inventory供应侧资料",
                    "/products/" + row.productId()
                ));
            }
            if (row.updatedAt() != null && (updatedAt == null || row.updatedAt().isAfter(updatedAt))) {
                updatedAt = row.updatedAt();
            }
            inactiveOnly = inactiveOnly && ("closed".equals(row.status()) || "consumed".equals(row.status()));
            draftExists = draftExists || "draft".equals(row.status());
            if (row.leadTimeDays() != null) {
                maxLeadTimeDays = maxLeadTimeDays == null ? row.leadTimeDays() : Math.max(maxLeadTimeDays, row.leadTimeDays());
            }
        }

        private SupplierDetail toDetail() {
            return new SupplierDetail(
                supplierId,
                supplierCode,
                supplierName,
                defaultText(shortName, supplierName),
                defaultText(contactPerson, "采购维护"),
                defaultText(contactPhone, ""),
                defaultText(contactEmail, ""),
                new ArrayList<>(supplyCategories),
                defaultText(region, "未维护"),
                status(),
                updatedAt == null ? "" : updatedAt.toString(),
                defaultText(cooperationLevel, "Inventory供应侧资料"),
                defaultText(paymentTerm, "在物料/模具资料维护"),
                deliveryRisk(),
                new ArrayList<>(records.values()),
                new ArrayList<>(projects.values()),
                List.<QualificationItem>of()
            );
        }

        private String status() {
            if (inactiveOnly) return "inactive";
            if (draftExists) return "draft";
            return "active";
        }

        private String deliveryRisk() {
            if (StringUtils.hasText(deliveryRisk)) return deliveryRisk;
            if (maxLeadTimeDays == null) return "low";
            if (maxLeadTimeDays > 45) return "high";
            if (maxLeadTimeDays > 30) return "medium";
            return "low";
        }

        private void addSupplyCategories(InventorySupplierRow row) {
            if (StringUtils.hasText(row.supplyCategories())) {
                for (String category : row.supplyCategories().split(",")) {
                    if (StringUtils.hasText(category)) {
                        supplyCategories.add(category.trim());
                    }
                }
                return;
            }
            supplyCategories.add(categoryLabel(row.inventoryType()));
        }

        private static String firstText(String current, String next, String fallback) {
            if (StringUtils.hasText(current)) return current;
            if (StringUtils.hasText(next)) return next.trim();
            return fallback;
        }

        private static String supplyType(String inventoryType) {
            return switch (defaultText(inventoryType, "")) {
                case "tooling", "fixture" -> "tooling";
                case "packaging" -> "packaging";
                default -> "material";
            };
        }

        private static String categoryLabel(String inventoryType) {
            return switch (defaultText(inventoryType, "")) {
                case "tooling" -> "模具";
                case "fixture" -> "治具";
                case "packaging" -> "包材";
                case "semi_finished" -> "半成品";
                case "finished" -> "成品";
                default -> "原材料";
            };
        }
    }
}

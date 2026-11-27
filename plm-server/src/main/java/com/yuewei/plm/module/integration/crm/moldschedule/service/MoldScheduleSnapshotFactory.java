package com.yuewei.plm.module.integration.crm.moldschedule.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.collaboration.CollaborationRepository;
import com.yuewei.plm.module.collaboration.MoldSchedulePolicy;
import com.yuewei.plm.module.collaboration.MoldScheduleTypes;
import com.yuewei.plm.module.collaboration.MoldWorkflowRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class MoldScheduleSnapshotFactory {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final CollaborationRepository repository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MoldScheduleSnapshotFactory(CollaborationRepository repository, JdbcTemplate jdbc, ObjectMapper json) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.json = json;
    }

    public Map<String, Object> create(long moldSetId) {
        Map<String, Object> row = repository.row("mold_set", moldSetId);
        new MoldWorkflowRepository(jdbc).enrich(List.of(row));
        MoldScheduleTypes.enrich(row);
        LocalDate asOfDate = MoldSchedulePolicy.today();
        MoldSchedulePolicy.enrichTasks(row, asOfDate);

        List<Long> phoneIds = jdbc.queryForList("""
            select phone_model_id from plm_collaboration_phone_set_compatibility
             where mold_set_id=? and pc_confirmed and tpu_confirmed
               and nullif(btrim(pc_evidence),'') is not null and nullif(btrim(tpu_evidence),'') is not null
             order by phone_model_id
            """, Long.class, moldSetId);
        Map<Long, String> ownerNames = ownerNames(row);
        Map<String, Object> baseline = latestBaseline(moldSetId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("moldSetId", text(row.get("moldSetId")));
        data.put("itemId", text(row.get("itemId")));
        data.put("assetNumber", nullableText(row.get("assetNumber")));
        data.put("setNo", integer(row.get("setNo")));
        data.put("setName", nullableText(row.get("setName")));
        data.put("productLineId", nullableText(row.get("productLineId")));
        data.put("productLineName", nullableText(row.get("productLineName")));
        data.put("styleName", nullableText(row.get("styleName")));
        data.put("brand", nullableText(row.get("brand")));
        data.put("phoneModelCode", nullableText(row.get("phoneModelCode")));
        data.put("compatibleModels", nullableText(row.get("compatibleModels")));
        data.put("compatiblePhoneModelIds", phoneIds.stream().map(String::valueOf).toList());
        data.put("crmLinks", map(
            "mappingStatus", "unmapped", "seriesIds", List.of(), "phoneModelIds", List.of(), "skuIds", List.of()));
        data.put("plan", plan(row, baseline));
        data.put("forecast", map(
            "forecastDeliveryDate", row.get("forecastDeliveryDate"),
            "forecastFactoryDate", row.get("forecastFactoryDate"),
            "forecastAcceptanceDate", row.get("forecastAcceptanceDate"),
            "forecastProductionDate", row.get("forecastProductionDate")));
        data.put("actual", map(
            "openedDate", row.get("openedDate"),
            "actualSupplierTrialDate", row.get("actualSupplierTrialDate"),
            "actualShipDate", row.get("actualShipDate"),
            "actualMexicoArrivalDate", row.get("actualMexicoArrivalDate"),
            "actualPortArrivalDate", row.get("actualPortArrivalDate"),
            "actualTrialDate", row.get("actualTrialDate"),
            "actualProductionDate", row.get("actualProductionDate"),
            "marketReadyDate", row.get("marketReadyDate")));
        data.put("parts", List.of(part(row, "PC"), part(row, "TPU")));
        data.put("owners", owners(row, ownerNames));
        data.put("status", status(row, asOfDate));
        data.put("scheduleNotes", nullableText(row.get("scheduleNotes")));
        data.put("updatedAt", OffsetDateTime.now(BUSINESS_ZONE).toString());
        return data;
    }

    private Map<String, Object> plan(Map<String, Object> row, Map<String, Object> baseline) {
        Number version = (Number) baseline.getOrDefault("version", 0);
        @SuppressWarnings("unchecked") Map<String, Object> snapshot = (Map<String, Object>) baseline.getOrDefault("snapshot", Map.of());
        boolean needsReview = !snapshot.isEmpty() && java.util.stream.Stream.concat(
            MoldScheduleTypes.PLAN_KEYS.stream(), java.util.stream.Stream.of("styleName", "compatibleModels"))
            .anyMatch(key -> !Objects.equals(normal(row.get(key)), normal(snapshot.get(key))));
        return map(
            "planStartDate", row.get("planStartDate"),
            "manufacturingDays", integer(row.get("manufacturingDays")),
            "holidayDays", integer(row.get("holidayDays")),
            "extraDays", integer(row.get("extraDays")),
            "plannedMoldDeliveryDate", row.get("plannedMoldDeliveryDate"),
            "plannedFactoryArrivalDate", row.get("plannedFactoryArrivalDate"),
            "plannedAcceptanceDate", row.get("plannedAcceptanceDate"),
            "plannedProductionDate", row.get("plannedProductionDate"),
            "planVersion", String.valueOf(version.longValue()),
            "baselineNeedsReview", needsReview);
    }

    private Map<String, Object> latestBaseline(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select version_no,plan_snapshot::text as snapshot from plm_mold_schedule_plan
             where mold_set_id=? order by version_no desc limit 1
            """, id);
        if (rows.isEmpty()) return Map.of("version", 0, "snapshot", Map.of());
        Map<String, Object> row = rows.get(0);
        try {
            Map<String, Object> snapshot = json.readValue(Objects.toString(row.get("snapshot"), "{}"), new TypeReference<>() {});
            return Map.of("version", row.get("version_no"), "snapshot", snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read mold schedule plan baseline", e);
        }
    }

    private Map<String, Object> part(Map<String, Object> row, String type) {
        String prefix = type.equals("PC") ? "pc" : "tpu";
        Object statusCode = row.get(prefix + "Status");
        Object physicalStatusCode = row.get(prefix + "PhysicalStatus");
        Object acceptanceStatusCode = row.get(prefix + "AcceptanceStatus");
        return map(
            "partType", type,
            "statusCode", statusCode,
            "statusName", partStatusName(statusCode),
            "physicalStatusCode", physicalStatusCode,
            "physicalStatusName", physicalStatusName(physicalStatusCode),
            "signedDate", row.get(prefix + "SignedDate"),
            "factoryArrivalDate", row.get(prefix + "FactoryArrivalDate"),
            "acceptanceStatusCode", acceptanceStatusCode,
            "acceptanceStatusName", acceptanceStatusName(acceptanceStatusCode),
            "acceptanceDate", row.get(prefix + "AcceptanceDate"),
            "acceptanceIssues", nullableText(row.get(prefix + "AcceptanceIssues")));
    }

    private Map<String, Object> owners(Map<String, Object> row, Map<Long, String> names) {
        return map(
            "mold", owner(row.get("ownerUserId"), row.get("ownerName"), names),
            "logistics", owner(row.get("logisticsOwnerUserId"), null, names),
            "acceptance", owner(row.get("mexicoOwnerUserId"), null, names),
            "production", owner(row.get("productionOwnerUserId"), null, names));
    }

    private Map<Long, String> ownerNames(Map<String, Object> row) {
        List<Long> ids = List.of("ownerUserId", "logisticsOwnerUserId", "mexicoOwnerUserId", "productionOwnerUserId")
            .stream().map(row::get).filter(Number.class::isInstance).map(value -> ((Number) value).longValue()).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        String marks = ids.stream().map(ignored -> "?").collect(Collectors.joining(","));
        Map<Long, String> names = new LinkedHashMap<>();
        jdbc.queryForList("select user_id,display_name from sys_user where user_id in (" + marks + ")", ids.toArray())
            .forEach(user -> names.put(((Number) user.get("user_id")).longValue(), nullableText(user.get("display_name"))));
        return names;
    }

    private Map<String, Object> owner(Object id, Object knownName, Map<Long, String> names) {
        Long userId = id instanceof Number n ? n.longValue() : null;
        return map("userId", userId == null ? null : String.valueOf(userId),
            "displayName", userId == null ? null : nullableText(knownName == null ? names.get(userId) : knownName));
    }

    private Map<String, Object> status(Map<String, Object> row, LocalDate asOfDate) {
        @SuppressWarnings("unchecked") List<MoldScheduleTypes.Task> tasks = (List<MoldScheduleTypes.Task>) row.getOrDefault("overdueTasks", List.of());
        List<Map<String, Object>> overdue = new ArrayList<>();
        long overdueDays = 0;
        for (MoldScheduleTypes.Task task : tasks) {
            if (!task.overdue()) continue;
            overdueDays = Math.max(overdueDays, task.overdueDays());
            overdue.add(map("taskKey", task.taskKey(), "title", task.title(), "dueDate", task.dueDate(), "overdueDays", Math.toIntExact(task.overdueDays())));
        }
        String code = nullableText(row.get("scheduleStatus"));
        return map(
            "scheduleStatusCode", code,
            "scheduleStatusName", scheduleStatusName(code),
            "schedulePaused", Boolean.TRUE.equals(row.get("schedulePaused")),
            "marketEligible", Boolean.TRUE.equals(row.get("marketEligible")),
            "overdue", !overdue.isEmpty(),
            "overdueDays", overdue.isEmpty() ? 0 : Math.toIntExact(overdueDays),
            "overdueTasks", overdue,
            "asOfDate", asOfDate.toString());
    }

    private static String partStatusName(Object value) {
        if (value == null) return null;
        return switch (value.toString()) { case "pending" -> "待制作"; case "making" -> "制作中"; case "ready" -> "已完成"; default -> value.toString(); };
    }
    private static String physicalStatusName(Object value) {
        if (value == null) return null;
        return switch (value.toString()) { case "unknown" -> "未知"; case "making" -> "制作中"; case "confirmed" -> "已核对"; default -> value.toString(); };
    }
    private static String acceptanceStatusName(Object value) {
        if (value == null) return null;
        return switch (value.toString()) { case "pending" -> "待验收"; case "passed" -> "验收通过"; case "failed" -> "验收不通过"; default -> value.toString(); };
    }
    private static String scheduleStatusName(String value) {
        if (value == null) return null;
        return switch (value) {
            case "pending" -> "待开模";
            case "making" -> "制作中";
            case "ready" -> "待发模";
            case "shipped" -> "已发出";
            case "mexico_arrived" -> "抵达墨西哥";
            case "partially_arrived" -> "部分到厂";
            case "awaiting_acceptance" -> "已到厂，待验收";
            case "partially_accepted" -> "部分验收";
            case "acceptance_failed" -> "验收不通过";
            case "delivered" -> "交付完成";
            case "market_ready" -> "已确认上市";
            default -> value;
        };
    }

    private static Object normal(Object value) { return value instanceof Number ? value.toString() : value; }
    private static Integer integer(Object value) { return value instanceof Number n ? n.intValue() : null; }
    private static String text(Object value) { return Objects.toString(value); }
    private static String nullableText(Object value) { return value == null ? null : value.toString(); }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }
}

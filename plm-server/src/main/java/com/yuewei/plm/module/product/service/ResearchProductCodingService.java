package com.yuewei.plm.module.product.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.util.ProductCodeGenerator;
import com.yuewei.plm.module.bom.service.ProductionConfirmationService;
import com.yuewei.plm.module.code.entity.CodeItem;
import com.yuewei.plm.module.code.service.CodeItemService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.masterdata.ProductMoldLifecycleService;
import com.yuewei.plm.repository.ProductRepository;
import com.yuewei.plm.repository.entity.Product;
import com.yuewei.plm.service.ProductService;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal continuation of an authorized research application; it owns no approval or production facts. */
@Service
@RequiredArgsConstructor
public class ResearchProductCodingService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProductRepository products;
    private final ProductService productService;
    private final CodeItemService colors;
    private final ProductionConfirmationService production;
    private final ProductCodeGenerator productCodes;
    private final PilotAccessService access;

    @Transactional
    public Map<String,Object> ensurePrefix(long researchProjectId, String actor) {
        String prefix = productService.ensureResearchProductSpecificCode(researchProjectId, actor);
        return Map.of("status", "ready", "productSpecificCode", prefix, "productCodePrefix", "N" + prefix,
            "missingFields", List.of(), "missingMessages", List.of(), "missing", List.of());
    }

    public List<Map<String,Object>> operationOptions() {
        var result = new LinkedHashMap<String,Map<String,Object>>();
        for (var row : jdbc.queryForList("""
            with recursive active_products(product_id) as (
              select product_id from plm_product
              where parent_product_id is null and deleted_flag=0 and master_data_status='active'
              union all
              select child.product_id from plm_product child
              join active_products parent on parent.product_id=child.parent_product_id
              where child.deleted_flag=0 and child.master_data_status='active'
            )
            select * from (
              select process_name name,coalesce(nullif(operation_craft_code,''),process_param_json->>'operationCraftCode') code,
                finished_product_flag,material_status_code,process_param_json::text metadata
              from plm_process where process_type='operation_master' and deleted_flag=0 and status in ('confirmed','locked','enabled')
              union all
              select coalesce(master.process_name,'已配置最终工序') name,
                coalesce(nullif(p.operation_craft_code,''),p.process_param_json->>'operationCraftCode') code,
                (coalesce(p.finished_product_flag,false) or product.product_code='N'||product.product_specific_code||
                  coalesce(nullif(p.operation_craft_code,''),p.process_param_json->>'operationCraftCode')) finished_product_flag,
                p.material_status_code,p.process_param_json::text metadata
              from plm_process p join plm_process route on route.process_id=p.parent_process_id
                join plm_product product on product.product_id=route.product_id
                join active_products active_product on active_product.product_id=product.product_id
                left join plm_process master on master.process_id=p.operation_master_process_id
              where p.process_type='operation' and p.deleted_flag=0 and p.status in ('confirmed','locked','enabled')
                and route.deleted_flag=0 and route.status<>'archived'
                and (p.operation_master_process_id is null or (master.deleted_flag=0 and master.status in ('confirmed','locked','enabled')))
            ) configured order by code,name
            """)) {
            String code = text(row.get("code"));
            var metadata = decode(row.get("metadata"));
            var defaults = map(metadata.get("defaultProcessParamJson"));
            String state = text(row.get("material_status_code"));
            if (state.isBlank()) state = text(defaults.getOrDefault("materialStatusCode", metadata.get("materialStatusCode")));
            boolean finished = Boolean.TRUE.equals(row.get("finished_product_flag"))
                || Boolean.TRUE.equals(metadata.get("finishedProductFlag")) || Boolean.TRUE.equals(defaults.get("finishedProductFlag"))
                || Set.of("40", "FINISHED", "FINISHED_PRODUCT", "成品").contains(state.toUpperCase(Locale.ROOT));
            if (code.matches("[0-9]{4}") && finished) result.putIfAbsent(code, Map.of("code", code, "name", text(row.get("name"))));
        }
        return List.copyOf(result.values());
    }

    public String requireOperationCode(String code) {
        String selected = text(code);
        if (operationOptions().stream().noneMatch(option -> selected.equals(option.get("code"))))
            throw new BusinessException(40001, "请选择已启用主数据中的最终工序，不自动使用4030");
        return selected;
    }

    @Transactional
    public Map<String,Object> provision(long researchProjectId, long phoneModelId, Long applicationId, String actor) {
        access.requireProduct(researchProjectId);
        ProductMoldLifecycleService.requireActive(jdbc, "product", researchProjectId);
        var result = new LinkedHashMap<>(ensurePrefix(researchProjectId, actor));
        Product project = products.selectIdentityForUpdate(researchProjectId);
        var fields = new ArrayList<String>();
        var messages = new ArrayList<String>();
        var ids = new ArrayList<Long>();
        var generated = new ArrayList<String>();
        var productRows = new ArrayList<Map<String,Object>>();
        var createdProductIds = new LinkedHashSet<Long>();
        var options = operationOptions();
        result.put("operationOptions", options);
        var application = applicationId == null ? Map.<String,Object>of() : jdbc.queryForMap("""
            select project_id,product_line_id,phone_model_id,execution_resolution_json::text resolution
            from plm_mold_application where application_id=? and deleted_flag=0
            """, applicationId);
        if (!application.isEmpty() && (number(application.get("phone_model_id")) != phoneModelId
            || number(application.get("project_id")) != researchProjectId && number(application.get("product_line_id")) != researchProjectId))
            throw new BusinessException(40001, "编码申请的原项目或标准手机不一致");
        var phones = jdbc.queryForList("""
            select p.id,p.model_name,c.code_value from plm_phone_model_member p join plm_phone_brand b on b.id=p.brand_id
            join plm_code_item c on c.code_item_id=p.code_item_id and c.code_type='phone_model'
            where p.id=? and coalesce((to_jsonb(p)->>'deleted_flag')::int,0)=0 and p.status='enabled'
              and coalesce((to_jsonb(b)->>'deleted_flag')::int,0)=0 and b.status='enabled'
              and c.deleted_flag=0 and c.status='enabled' and c.code_value~'^[0-9]{4}$'
            """, phoneModelId);
        if (phones.size() != 1) { fields.add("phoneModelId"); messages.add("申请的标准手机或原四位编码待核对"); }
        var adoptedBasis = adoptedBasis(researchProjectId);
        String operation = text(map(decode(application.get("resolution")).get("researchCoding")).get("operationCode"));
        if (operation.isBlank()) operation = adoptedOperation(researchProjectId, options, adoptedBasis);
        final String selectedOperation = operation;
        if (options.stream().noneMatch(option -> selectedOperation.equals(option.get("code")))) {
            fields.add("operationCode"); messages.add("最终工序待补，请选择已启用的工序主数据");
        }
        List<CodeItem> scopedColors;
        try { scopedColors = scopedColors(project, phoneModelId, adoptedBasis); }
        catch (BusinessException invalidColor) {
            scopedColors = List.of(); fields.add("colors"); messages.add(invalidColor.getMessage());
        }
        if (fields.isEmpty() && !scopedColors.isEmpty()) {
            var phone = phones.get(0);
            var variantDraft = variant(project, phoneModelId, text(phone.get("code_value")), text(phone.get("model_name")), actor);
            if (variantDraft == null) { fields.add("phoneModelId"); messages.add("原项目的型号资料存在身份或历史编码冲突，已保留原记录"); }
            else {
                Product variant = variantDraft.product();
                if (variantDraft.created()) createdProductIds.add(variant.getProductId());
                for (var color : scopedColors) {
                    var skuDraft = production.researchSkuDraft(variant, color, selectedOperation, actor);
                    if (skuDraft == null) { fields.add("productCodes"); messages.add("型号 " + phone.get("code_value") + "／标准色 " + color.getCodeValue() + " 已有不同编码或全局冲突，原码保留"); }
                    else {
                        Product sku = skuDraft.product();
                        if (skuDraft.created()) createdProductIds.add(sku.getProductId());
                        else {
                            access.requireProduct(sku.getProductId());
                            ProductMoldLifecycleService.requireActive(jdbc, "product", sku.getProductId());
                        }
                        ids.add(sku.getProductId()); generated.add(sku.getFinishedProductCode());
                        productRows.add(Map.of("productId", sku.getProductId(), "code", sku.getFinishedProductCode(),
                            "finishedProductCode", sku.getFinishedProductCode(), "phoneModelId", phoneModelId,
                            "phoneModelCode", text(phone.get("code_value")), "modelName", text(phone.get("model_name")),
                            "colorCode", color.getCodeValue(), "colorName", text(color.getCodeName()).isBlank() ? text(color.getCodeNameZh()) : text(color.getCodeName()),
                            "operationCode", selectedOperation));
                    }
                }
            }
        }
        if (!createdProductIds.isEmpty() && !access.access().allObjects()) {
            long actorId = access.actorId();
            for (Long productId : createdProductIds) jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) values(?,'product',?) on conflict do nothing", actorId, productId);
        }
        result.put("status", fields.isEmpty() ? "ready" : "pending");
        result.put("missingFields", fields.stream().distinct().toList());
        result.put("missingMessages", messages); result.put("missing", messages);
        result.put("skuIds", ids); result.put("codes", generated); result.put("generatedProductCodes", generated);
        result.put("products", productRows);
        result.put("operationCode", selectedOperation);
        result.put("scopeStatus", scopedColors.isEmpty() ? "prefix_only" : "specific_colors");
        return result;
    }

    private List<CodeItem> scopedColors(Product project, long phoneModelId, Map<String,Object> adoptedBasis) {
        var result = new LinkedHashMap<Long,CodeItem>();
        for (var row : jdbc.queryForList("""
            select distinct c.code_item_id,c.code_value from plm_code_item c join (
              select color_code from plm_product where deleted_flag=0 and (product_id=? or
                (parent_product_id=? and phone_model_member_id=?) or parent_product_id in
                  (select product_id from plm_product where parent_product_id=? and phone_model_member_id=? and deleted_flag=0))
              union select color_code from plm_product_variant_color where project_product_id=? and selected_flag=1 and snapshot_status='active' and deleted_flag=0
              union select color_code from plm_product_production_color_decision where product_id=? and selected_flag=1 and status='confirmed' and deleted_flag=0
            ) scope on scope.color_code=c.code_value where c.code_type='color' and c.deleted_flag=0 order by c.code_value
            """, project.getProductId(), project.getProductId(), phoneModelId, project.getProductId(), phoneModelId,
                project.getProductId(), project.getProductId())) {
            CodeItem color = colors.requireEnabledColor(number(row.get("code_item_id")), text(row.get("code_value")));
            result.put(color.getCodeItemId(), color);
        }
        var references = references(adoptedBasis);
        var boms = references.stream().filter(ref -> "bom".equals(ref.get("type"))).toList();
        var routes = references.stream().filter(ref -> "process".equals(ref.get("type"))).toList();
        if (boms.size() == 1 && routes.size() == 1) for (var row : jdbc.queryForList("""
            select distinct c.code_item_id,c.code_value from plm_product_bom_route_color chosen
            join plm_product_bom_route r on r.product_bom_route_id=chosen.product_bom_route_id
            join plm_product_bom b on b.product_bom_id=r.product_bom_id
            join plm_process p on p.process_id=r.process_id and p.product_id=b.product_id
            join plm_code_item c on c.code_type='color' and c.code_value=chosen.color_code and c.deleted_flag=0
            where b.product_id=? and b.product_bom_id=? and p.process_id=?
              and b.deleted_flag=0 and b.status<>'archived' and p.deleted_flag=0 and p.status<>'archived'
              and r.deleted_flag=0 and r.status='active' and chosen.deleted_flag=0 and chosen.status='active'
            order by c.code_value
            """, project.getProductId(), number(boms.get(0).get("id")), number(routes.get(0).get("id")))) {
            long id = number(row.get("code_item_id"));
            if (!result.containsKey(id)) result.put(id, colors.requireEnabledColor(id, text(row.get("code_value"))));
        }
        return List.copyOf(result.values());
    }

    private record VariantDraft(Product product, boolean created) {}
    private VariantDraft variant(Product project, long phoneModelId, String phoneCode, String model, String actor) {
        var matches = jdbc.queryForList("""
            select product_id from plm_product where parent_product_id=? and product_type='model_variant'
              and deleted_flag=0 and (phone_model_member_id=? or phone_model_code=?) order by product_id
            """, project.getProductId(), phoneModelId, phoneCode);
        if (matches.size() > 1) return null;
        if (!matches.isEmpty()) {
            long id = number(matches.get(0).get("product_id"));
            access.requireProduct(id);
            ProductMoldLifecycleService.requireActive(jdbc, "product", id);
            Product existing = products.selectIdentityForUpdate(id);
            return !Set.of("archived", "abandoned").contains(text(existing.getStatus()))
                && !Set.of("frozen", "abandoned").contains(text(existing.getLockStatus()))
                && Objects.equals(existing.getPhoneModelMemberId(), phoneModelId)
                && Objects.equals(existing.getPhoneModelCode(), phoneCode)
                && Objects.equals(existing.getProductSpecificCode(), project.getProductSpecificCode()) ? new VariantDraft(existing, false) : null;
        }
        Product value = new Product();
        value.setParentProductId(project.getProductId()); value.setProductType("model_variant");
        value.setProductName(project.getProductName() + " · " + model);
        value.setProductCode(productCodes.generate(value.getProductName()));
        value.setProductSpecificCode(project.getProductSpecificCode()); value.setPhoneModelMemberId(phoneModelId);
        value.setPhoneModelCode(phoneCode); value.setModel(model); value.setSeriesName(project.getSeriesName());
        value.setMaterial(project.getMaterial()); value.setPackageType(project.getPackageType());
        value.setSurfaceProcess(project.getSurfaceProcess()); value.setCoreProcess(project.getCoreProcess());
        value.setComposition(project.getComposition()); value.setVersionNo(project.getVersionNo());
        value.setStatus("draft"); value.setDeletedFlag(0); value.setCreatedAt(LocalDateTime.now());
        value.setUpdatedAt(value.getCreatedAt()); value.setCreatedBy(actor); value.setUpdatedBy(actor);
        products.insert(value);
        return new VariantDraft(value, true);
    }

    private Map<String,Object> adoptedBasis(long projectId) {
        var states = jdbc.queryForList("select data::text value from plm_product_development_state where product_id=?", projectId);
        var state = states.isEmpty() ? Map.<String,Object>of() : decode(states.get(0).get("value"));
        var basis = map(map(state.get("stageDrafts")).get("5"));
        if (!basis.containsKey("references")) basis = map(state.get("stage5Basis"));
        if (!basis.containsKey("references")) basis = map(state.get("engineeringBasis"));
        return basis;
    }

    private List<Map<String,Object>> references(Map<String,Object> basis) {
        return basis.get("references") instanceof List<?> list ? list.stream().map(this::map).toList() : List.of();
    }

    private String adoptedOperation(long projectId, List<Map<String,Object>> options, Map<String,Object> basis) {
        var processes = references(basis).stream().filter(ref -> "process".equals(ref.get("type"))).toList();
        long processId;
        if (processes.size() == 1) processId = number(processes.get(0).get("id"));
        else if (processes.isEmpty()) {
            var configured = jdbc.queryForList("select process_id from plm_process where product_id=? and process_type='routing' and deleted_flag=0 and status<>'archived'", projectId);
            if (configured.size() != 1) return "";
            processId = number(configured.get(0).get("process_id"));
        } else return "";
        var operations = jdbc.queryForList("""
            select distinct coalesce(nullif(p.operation_craft_code,''),p.process_param_json->>'operationCraftCode') code
            from plm_process p join plm_process route on route.process_id=p.parent_process_id
            where route.process_id=? and route.product_id=? and route.deleted_flag=0 and route.status<>'archived'
              and p.deleted_flag=0 and p.status<>'archived'
            """, processId, projectId).stream().map(row -> text(row.get("code")))
            .filter(code -> options.stream().anyMatch(option -> code.equals(option.get("code")))).distinct().toList();
        Product project = products.selectById(projectId);
        String configured = project == null ? "" : text(project.getProductCode());
        String prefix = project == null ? "" : "N" + text(project.getProductSpecificCode());
        if (configured.matches("N[A-Z]{2}[0-9]{4}") && configured.startsWith(prefix)) {
            String terminal = configured.substring(3);
            if (operations.contains(terminal)) return terminal;
        }
        return operations.size() == 1 ? operations.get(0) : "";
    }

    private Map<String,Object> decode(Object value) {
        if (value == null) return Map.of();
        try { return json.readValue(value.toString(), new TypeReference<>() {}); }
        catch (java.io.IOException failure) { throw new IllegalStateException("编码采用资料读取失败", failure); }
    }
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object value) { return value instanceof Map<?,?> ? (Map<String,Object>) value : Map.of(); }
    private String text(Object value) { return value == null ? "" : value.toString().trim(); }
    private long number(Object value) { return value instanceof Number n ? n.longValue() : 0L; }
}

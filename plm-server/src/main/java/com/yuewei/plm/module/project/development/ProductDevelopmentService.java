package com.yuewei.plm.module.project.development;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.constant.ErrorCodeConstants;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.controller.dto.ProductLifecycleActionDTO;
import com.yuewei.plm.module.attachment.entity.Attachment;
import com.yuewei.plm.module.attachment.repository.AttachmentRepository;
import com.yuewei.plm.module.attachment.vo.AttachmentVO;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.project.constant.TimelineNodeConstants;
import com.yuewei.plm.repository.ProductRepository;
import com.yuewei.plm.repository.entity.Product;
import com.yuewei.plm.service.vo.ProductReleaseGateCheckVO;
import com.yuewei.plm.service.vo.ProductReleaseGateMissingItemVO;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.project.development.DevelopmentModels.*;
import static com.yuewei.plm.module.project.development.DevelopmentRules.roundStage;

/** Product-owned development history; documents, BOMs, processes and timeline keep their existing writers. */
@Service
@RequiredArgsConstructor
public class ProductDevelopmentService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PilotAccessService access;
    private final ProductRepository products;
    private final AttachmentRepository attachments;
    private final OperationLogService logs;
    private final DevelopmentWorkflowStore workflow;
    private final com.yuewei.plm.module.inventory.service.InventorySupplierService inventorySuppliers;

    private final LaunchDevelopmentWorkflow launch;
    private final LaunchReviewWorkflow reviews;
    private final DevelopmentUnitCostWorkflow unitCosts;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private EngineeringBasisService engineering;
    @org.springframework.beans.factory.annotation.Autowired
    private SupplierFeedbackWorkflow supplierFeedback;

    public static boolean isLaunch(Product product) {return product!=null && "product_line".equals(product.getProductType()) && "launch_v1".equals(product.getDevelopmentMode());}
    public void registerCreated(Product product) {
        long actor = access.actorId();
        var initial=workflow.load(product.getProductId());initial.put("phaseModel","eight_step");workflow.save(product.getProductId(),initial);
        jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) select user_id,'product',? from plm_pilot_account where user_id=? on conflict do nothing", product.getProductId(), actor);
        audit(product.getProductId(), "PRODUCT_RESEARCH_CREATE", Map.of("productName", product.getProductName(), "developmentMode", product.getDevelopmentMode()));
    }

    private Product product(long id, boolean lock) {
        access.requireDomesticView();
        access.requirePermission("project:view");
        access.requireProduct(id);
        if (lock) jdbc.queryForList("select product_id from plm_product where product_id=? for update", id);
        Product product = products.selectById(id);
        if (!isLaunch(product) || Integer.valueOf(1).equals(product.getDeletedFlag())) {
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "该新品项目已停用或不属于新版新品研发");
        }
        return product;
    }

    public Product requireEditable(long id) {
        access.requirePermission("documents:write");
        Product product = product(id, true);
        if (Set.of("released","archived").contains(product.getStatus())
            || Set.of("frozen", "abandoned").contains(Objects.toString(product.getLockStatus(), ""))) {
            throw new BusinessException(ErrorCodeConstants.VERSION_FROZEN, "已发布、冻结或停止的研发档案只读，请通过新版本继续开发");
        }
        return product;
    }

    Product requireDevelopmentEditable(long id) {
        access.requirePermission("documents:write");Product p=product(id,true);
        if("archived".equals(p.getStatus())||Set.of("frozen","abandoned").contains(text(p.getLockStatus()))||("released".equals(p.getStatus())&&!hasActiveRevision(id)))throw invalid("已发布项目请先发起资料修订；冻结、停止或归档项目只读");
        return p;
    }

    public Map<String, Object> page(String keyword, String state, int page, int size, Integer stage) {
        return page(keyword,state,page,size,stage,"launch_v1");
    }
    public Map<String,Object> page(String keyword,String state,int page,int size,Integer stage,String flow) {
        DevelopmentRules.require("launch_v1".equals(flow),"研发流程版本无效");
        access.requireDomesticView();
        access.requirePermission("project:view");
        int normalizedPage = Math.max(1, page), normalizedSize = Math.max(1, Math.min(100, size));
        String condition = "p.product_type='product_line' and p.development_mode=? and p.deleted_flag=0 and "
            + access.visibleSqlClause("product", "p.product_id");
        List<Object> args = new ArrayList<>();args.add(flow);
        if (stage != null) {
            if (stage < 1 || stage > 8) throw invalid("研发阶段必须在 1 至 8 之间");
            if ("active".equals(state)) { condition += " and coalesce((select (s.data->'revision'->>'stage')::int from plm_product_development_state s where s.product_id=p.product_id),p.current_step_no,1)=?"; args.add(stage); }
            else if ("completed".equals(state)) {
                condition += " and exists(select 1 from plm_product_development_event e where e.product_id=p.product_id and e.kind='handover' and e.data->>'phaseModel'='eight_step' and (e.data->>'stage')::int=?)";args.add(stage);
            } else { condition += " and (coalesce(p.current_step_no,1)>=? or exists(select 1 from plm_product_development_event e where e.product_id=p.product_id and e.kind='handover' and e.data->>'phaseModel'='eight_step' and (e.data->>'stage')::int=?))"; args.add(stage);args.add(stage); }
        }
        if (keyword != null && !keyword.isBlank()) {
            condition += " and concat_ws(' ',p.product_name,p.product_code,p.model,p.development_requirements,(select (s.data->'profileDraft'->'phones')::text from plm_product_development_state s where s.product_id=p.product_id)) ilike ?";
            args.add("%" + keyword.trim() + "%");
        }
        condition += switch (Objects.toString(state, "active")) {
            case "active" -> " and (not (p.status in ('released','archived') or (p.current_step_no=8 and p.timeline_current_confirmed)) or (p.status='released' and exists(select 1 from plm_product_development_state s where s.product_id=p.product_id and s.data->'revision' is not null))) and coalesce(p.lock_status,'')<>'abandoned'";
            case "completed" -> stage==null?" and (p.status in ('released','archived') or (p.current_step_no=8 and p.timeline_current_confirmed)) and coalesce(p.lock_status,'')<>'abandoned'":"";
            case "all" -> "";
            default -> throw invalid("研发状态不支持");
        };
        long total = jdbc.queryForObject("select count(*) from plm_product p where " + condition, Long.class, args.toArray());
        args.add(normalizedSize); args.add((normalizedPage - 1L) * normalizedSize);
        var rows = jdbc.queryForList("""
            select p.product_id as "productId",p.product_code as "productCode",p.product_name as "productName",
              p.model,p.status,(p.current_step_no=8 and p.timeline_current_confirmed and not exists(select 1 from plm_product_development_state rs where rs.product_id=p.product_id and rs.data->'revision' is not null)) as "workflowComplete",coalesce((select (s.data->'revision'->>'stage')::int from plm_product_development_state s where s.product_id=p.product_id),p.current_step_no) as "currentStepNo",p.expected_delivery_date as "expectedDeliveryDate",
              p.target_cost as "targetCost",coalesce(u.display_name,u.user_name) as "ownerName",
              (select jsonb_build_object('planDraft',s.data->'planDraft','plan',s.data->'plan')::text from plm_product_development_state s where s.product_id=p.product_id) as "planJson",
              (select count(*) from plm_product_development_round r where r.product_id=p.product_id and r.deleted_flag=0) as "roundCount"
            from plm_product p left join sys_user u on u.user_id=p.owner_user_id where
            """ + condition + " order by p.created_at desc,p.product_id desc limit ? offset ?", args.toArray());
        boolean canReadSuppliers=access.hasPermission("supplier:view"),canReadFinance=access.hasPermission("finance:read");
        var options=engineering!=null&&canReadSuppliers?engineering.supplierOptions():List.<Map<String,Object>>of();
        var supplierNames=new HashMap<String,String>();
        for(var supplier:options)supplierNames.put(text(supplier.get("supplierCode")),text(supplier.get("supplierName")));
        var supplierIds=readableSupplierIds(options);var feedbackEvents=supplierSummaryEvents(rows,canReadSuppliers);
        for (var row : rows) {
            var plan=EngineeringBasisService.currentPlan(DevelopmentWorkflowStore.map(readJson(row.remove("planJson"))));
            row.put("supplierNames",LaunchReviewWorkflow.records(plan.get("supplierAssignments")).stream().filter(a->"supplier".equals(a.get("mode"))&&!text(a.get("supplierCode")).isBlank()).map(a->supplierNames.getOrDefault(text(a.get("supplierCode")),"供应商受限")).distinct().toList());
            if(supplierFeedback!=null)row.put("supplierFeedbackSummary",supplierFeedback.summary(feedbackEvents.getOrDefault(number(row.get("productId")),List.of()),plan,supplierIds,canReadSuppliers));
            if(!canReadFinance)row.put("targetCost",null);
            int step = row.get("currentStepNo") instanceof Number number ? number.intValue() : 1;
            row.put("currentStage", TimelineNodeConstants.LAUNCH_PRODUCT_NODES.get(Math.max(0, Math.min(7, step - 1))).nodeName());
            row.put("nextAction",List.of("负责人确认立项","采用代表型号与3D图纸","登记本轮实际收样","负责人确认代表样","确认设计与原申请授权","逐套试模与验证","试产、技术资料及成本定型","确认实际发模批次").get(Math.max(0,Math.min(7,step-1))));
            if(Boolean.TRUE.equals(row.get("workflowComplete")))row.put("nextAction",Set.of("released","archived").contains(text(row.get("status")))?"发模已完成":"发模已完成，可形成正式资料包");
        }
        return Map.of("content", rows, "page", normalizedPage, "size", normalizedSize, "totalElements", total,
            "totalPages", (total + normalizedSize - 1) / normalizedSize);
    }

    private Map<String,Long> readableSupplierIds(List<Map<String,Object>> options) {
        var ids=new HashMap<String,Long>();
        for(var option:options)ids.put(text(option.get("supplierCode")),number(option.get("supplierId")));
        return ids;
    }

    /** One page-scoped read, with no reasons/photos or per-project detail/authorization queries. */
    private Map<Long,List<Map<String,Object>>> supplierSummaryEvents(List<Map<String,Object>> rows,boolean canReadSuppliers) {
        var grouped=new HashMap<Long,List<Map<String,Object>>>();
        if(!canReadSuppliers||supplierFeedback==null||rows.isEmpty())return grouped;
        var ids=rows.stream().map(row->number(row.get("productId"))).toList();
        var events=jdbc.queryForList("""
            select e.product_id as "productId",e.event_id as "eventId",e.kind,
              (case when e.kind='supplier_feedback' then jsonb_build_object(
                'feedbackId',e.data->'feedbackId','result',e.data->'result','voided',e.data->'voided','supplierSnapshot',e.data->'supplierSnapshot')
              else jsonb_build_object('replacementPending',e.data->'replacementPending','before',e.data->'before','after',e.data->'after') end)::text as "summaryJson"
            from plm_product_development_event e where e.product_id in (
            """+String.join(",",Collections.nCopies(ids.size(),"?"))+"""
            ) and (e.kind='supplier_feedback' or (e.kind='supplier_change' and
              jsonb_path_exists(e.data,'$.before.supplierAssignments[*] ? (@.mode == "supplier" && @.role == "mold")')))
            order by e.product_id,e.event_id
            """,ids.toArray());
        for(var event:events){long id=number(event.remove("productId"));event.put("data",readJson(event.remove("summaryJson")));grouped.computeIfAbsent(id,key->new ArrayList<>()).add(event);}
        return grouped;
    }

    public Product lockForPublication(long id) {
        access.requirePermission("documents:publish");
        return product(id, true);
    }

    /** OA preparation follows project editability; incomplete research facts are completed in its editor. */
    private Map<String,Object> moldApplicationEntry(Product product,Map<String,Object> state) {
        boolean canCreate=access.hasPermission("documents:write")&&access.hasPermission("collaboration:view")&&access.hasPermission("mold-application:create")&&!access.isExternal();
        String reason=!canCreate?"没有发起开模申请的权限，请联系项目工程人员":null;
        if(reason==null&&("archived".equals(product.getStatus())||Set.of("frozen","abandoned").contains(text(product.getLockStatus()))||("released".equals(product.getStatus())&&!hasActiveRevision(product.getProductId()))))reason="冻结、停止、归档或已发布的项目不能新建申请；已有审批记录继续保留";
        var plan=EngineeringBasisService.currentPlan(state);
        var phones=DevelopmentRules.ids(plan.containsKey("phoneModelIds")?plan.get("phoneModelIds"):DevelopmentWorkflowStore.map(state.get("profileDraft")).get("phoneModelIds"));
        var result=new LinkedHashMap<String,Object>();result.put("eligible",reason==null);result.put("reason",reason);result.put("canCreate",canCreate);result.put("phoneModelIds",phones);return result;
    }

    /** Caller holds the selected phone lock before the project lock for writes; previews acquire neither. */
    public Map<String,Object> moldApplicationDraftContext(long id,Long phoneId,Long version) {
        return moldApplicationDraftContext(id,phoneId,version,true);
    }
    public Map<String,Object> moldApplicationPreviewContext(long id,Long phoneId,Long version) {
        return moldApplicationDraftContext(id,phoneId,version,false);
    }
    private Map<String,Object> moldApplicationDraftContext(long id,Long phoneId,Long version,boolean lock) {
        access.requirePermission("documents:write");var product=lock?requireDevelopmentEditable(id):product(id,false);var state=workflow.load(id);workflow.check(state,version);
        var entry=moldApplicationEntry(product,state);DevelopmentRules.require(Boolean.TRUE.equals(entry.get("eligible")),text(entry.get("reason")));
        var profile=DevelopmentWorkflowStore.map(state.get("profileDraft"));
        var knownPhones=DevelopmentRules.ids(entry.get("phoneModelIds"));if(!lock&&phoneId==null&&knownPhones.size()==1)phoneId=knownPhones.get(0);
        Map<String,Object> phone=phoneId==null?Map.of():launch.phoneSnapshots(List.of(phoneId)).get(0);var result=new LinkedHashMap<String,Object>();
        result.put("projectId",id);result.put("productName",profile.getOrDefault("productName",product.getProductName()));result.put("productCode",product.getProductCode());
        result.put("productSpecificCode",product.getProductSpecificCode());result.put("canMaintainProductIdentity",ResearchLaunchService.canMaintainProductIdentity(product.getProductSpecificCode(),product.getStatus(),product.getLockStatus(),access));
        var revision=DevelopmentWorkflowStore.map(state.get("revision"));result.put("currentStepNo",revision.isEmpty()?Objects.requireNonNullElse(product.getCurrentStepNo(),1):(int)number(revision.get("stage")));
        result.put("phoneModelId",phoneId);result.put("phoneModelName",phone.get("modelName"));result.put("phoneModelIds",entry.get("phoneModelIds"));result.put("model",profile.getOrDefault("model",product.getModel()));result.put("ownerUserId",profile.getOrDefault("ownerUserId",product.getOwnerUserId()));
        var owners=jdbc.queryForList("select coalesce(display_name,user_name,username) as name from sys_user where user_id=?",result.get("ownerUserId"));
        result.put("ownerName",owners.isEmpty()?null:owners.get(0).get("name"));result.put("requirements",profile.getOrDefault("requirements",product.getDevelopmentRequirements()));
        var currentPlan=EngineeringBasisService.currentPlan(state);Object suppliers=Map.of("supplierAssignments",currentPlan.getOrDefault("supplierAssignments",List.of()));
        var scoped=engineering==null?Map.<String,Object>of():DevelopmentWorkflowStore.map(engineering.redact(suppliers));
        result.put("suppliers",engineering==null?List.of():engineering.supplierSnapshots(LaunchReviewWorkflow.records(scoped.get("supplierAssignments"))));result.put("suppliersRestricted",Boolean.TRUE.equals(scoped.get("suppliersRestricted")));
        result.put("supplierPlanConfirmed",engineering!=null&&engineering.supplierPlanConfirmed(state));
        var refs=new ArrayList<MaterialReference>();
        for(Object value:List.of(DevelopmentWorkflowStore.map(state.get("requirement")),DevelopmentWorkflowStore.map(state.get("profileDraft")),currentPlan,DevelopmentWorkflowStore.map(DevelopmentWorkflowStore.map(state.get("stageDrafts")).get(String.valueOf(product.getCurrentStepNo())))))refs.addAll(refs(DevelopmentWorkflowStore.map(value).get("references")));
        result.put("references",snapshotReferences(id,refs.stream().filter(ref->"file".equals(ref.type())).toList(),lock));result.put("workflowVersion",state.get("version"));result.put("capturedAt",java.time.LocalDateTime.now().toString());
        Object expected=profile.getOrDefault("expectedDeliveryDate",product.getExpectedDeliveryDate());if(expected!=null&&!text(expected).isBlank())result.put("expectedDeliveryDate",expected.toString());
        var missing=new ArrayList<String>();if(text(product.getProductSpecificCode()).isBlank())missing.add("产品特定编码");if(phoneId==null)missing.add("标准手机型号");if(text(result.get("requirements")).isBlank())missing.add("开发要求");if(!result.containsKey("expectedDeliveryDate"))missing.add("期望交付日期");result.put("missingFields",missing);
        return result;
    }

    public boolean canEditResearchMoldDraft(long projectId) {
        try{if(!access.hasPermission("documents:write"))return false;var product=product(projectId,false);return !"archived".equals(product.getStatus())&&!Set.of("frozen","abandoned").contains(text(product.getLockStatus()))&&(!"released".equals(product.getStatus())||hasActiveRevision(projectId));}catch(BusinessException denied){return false;}
    }
    public void lockResearchMoldDraft(long projectId){requireDevelopmentEditable(projectId);}

    public boolean canEditMoldApplicationProductScope(Long projectId) {
        return projectId==null||!isLaunch(products.selectById(projectId))||canEditResearchMoldDraft(projectId);
    }
    /** All application origins retain project permissions and editability; research stage confirmations are independent. */
    public void assertMoldApplicationProductScope(Long projectId) {
        if(projectId!=null&&isLaunch(products.selectById(projectId)))requireDevelopmentEditable(projectId);
    }

    /** Application readers retain the original project and supplier object boundaries. */
    public Map<String,Object> redactMoldApplicationSource(Map<String,Object> source) {
        var result=new LinkedHashMap<>(source);
        try{product(number(source.get("projectId")),false);}catch(BusinessException denied){return Map.of("projectId",source.get("projectId"),"restricted",true);}
        var scoped=engineering==null?Map.<String,Object>of():DevelopmentWorkflowStore.map(engineering.redact(Map.of("supplierAssignments",source.getOrDefault("suppliers",List.of()))));
        result.put("suppliers",scoped.getOrDefault("supplierAssignments",List.of()));result.put("suppliersRestricted",Boolean.TRUE.equals(source.get("suppliersRestricted"))||Boolean.TRUE.equals(scoped.get("suppliersRestricted")));
        var references=new ArrayList<Map<String,Object>>();
        for(var ref:LaunchReviewWorkflow.records(source.get("references")))try{if(!"file".equals(ref.get("type")))continue;access.requireFile(number(ref.get("id")));references.add(ref);}catch(BusinessException denied){result.put("referencesRestricted",true);}
        result.put("references",references);return result;
    }

    public Map<String,Object> previewUnitCost(long id,Map<String,Object> input) {
        product(id,false);return unitCosts.preview(input);
    }

    @Transactional
    public void deleteProjectDraft(long id,Long expectedVersion) {
        var product=requireEditable(id);var state=workflow.load(id);workflow.check(state,expectedVersion);
        DevelopmentRules.require(Objects.requireNonNullElse(product.getCurrentStepNo(),1)==1&&!state.containsKey("requirement"),"仅未确认立项的新品草稿可删除，已推进项目请停止或修订");
        Long downstream=jdbc.queryForObject("""
            select (select count(*) from plm_product where parent_product_id=? and deleted_flag=0)
              +(select count(*) from plm_product_bom where product_id=? and deleted_flag=0)
              +(select count(*) from plm_process where product_id=? and deleted_flag=0)
              +(select count(*) from plm_mold_application where (project_id=? or product_line_id=?) and deleted_flag=0)
              +(select count(*) from plm_product_development_round where product_id=? and deleted_flag=0)
              +(select count(*) from plm_product_development_release where product_id=?)
              +(select count(*) from plm_development_budget where product_line_id=? and deleted_flag=0)
              +(select count(*) from plm_project_cost_item where (product_line_id=? or product_id=?) and deleted_flag=0)
            """,Long.class,id,id,id,id,id,id,id,id,id,id);
        DevelopmentRules.require(downstream==0,"已有下游BOM、工艺、申请、费用依据或开发记录，不能删除，请保留档案并停止开发");
        DevelopmentRules.require(LaunchReviewWorkflow.records(state.get("unitCostVersions")).isEmpty(),"已有已确认成本版本，不能删除新品档案");
        audit(id,"PRODUCT_RESEARCH_DRAFT_DELETE",Map.of("productName",product.getProductName()));
        jdbc.update("update plm_product set deleted_flag=1,updated_at=now(),updated_by=? where product_id=?",access.actor().displayName(),id);
    }

    public Map<String, Object> detail(long id) {
        Product product = product(id, false);
        var result = new LinkedHashMap<String, Object>();
        result.put("developmentMode",product.getDevelopmentMode()); result.put("productId", id); result.put("productName", product.getProductName());
        result.put("productCode", product.getProductCode()); result.put("versionNo", product.getVersionNo());
        result.put("model", product.getModel()); result.put("requirements", product.getDevelopmentRequirements());
        result.put("targetCost", product.getTargetCost()); result.put("expectedDeliveryDate", product.getExpectedDeliveryDate());
        result.put("ownerUserId", product.getOwnerUserId()); result.put("status", product.getStatus());
        result.put("lockStatus", product.getLockStatus());
        var owners = jdbc.queryForList("select coalesce(display_name,user_name,username) as name from sys_user where user_id=?", product.getOwnerUserId());
        result.put("ownerName", owners.isEmpty() ? null : owners.get(0).get("name"));
        result.put("rounds", rounds(id));
        result.put("bomRoutes",jdbc.queryForList("select r.product_bom_route_id as \"routeId\",r.product_bom_id as \"bomId\",r.process_id as \"processId\",r.route_name as name from plm_product_bom_route r join plm_product_bom b on b.product_bom_id=r.product_bom_id where b.product_id=? and b.deleted_flag=0 and r.deleted_flag=0 order by r.product_bom_route_id",id));
        result.put("materials", materialOptions(id));
        var releases = jdbc.queryForList("""
            select release_id as "releaseId",version_no as "versionNo",package_no as "packageNo",basis_json::text as "basisJson",references_json::text as "referencesJson",
              product_snapshot::text as "productSnapshotJson",rounds_snapshot::text as "roundsSnapshotJson",
              missing_items::text as "missingItemsJson",exception_reason as "exceptionReason",
              supplement_due_date as "supplementDueDate",responsible_user_id as "responsibleUserId",
              published_at as "publishedAt",published_by as "publishedBy"
            from plm_product_development_release where product_id=? order by package_no desc
            """, id);
        for (var release : releases) decodeRelease(release);
        result.put("release", releases.isEmpty() ? null : releases.get(0));
        result.put("releases",releases);
        var state=workflow.load(id);result.put("workflow",redact(state));result.put("moldApplicationEntry",moldApplicationEntry(product,state));
        var events=workflow.events(id);result.put("events",redact(events));
        if(supplierFeedback!=null){var feedback=supplierFeedback.projection(events);result.put("supplierFeedback",redact(feedback.get("items")));result.put("supplierFeedbackRestricted",feedback.get("restricted"));
            boolean canRead=access.hasPermission("supplier:view");var options=engineering!=null&&canRead?engineering.supplierOptions():List.<Map<String,Object>>of();
            result.put("supplierFeedbackSummary",supplierFeedback.summary(events,EngineeringBasisService.currentPlan(state),readableSupplierIds(options),canRead));}
        result.put("currentStepNo",product.getCurrentStepNo());result.put("steps",TimelineNodeConstants.LAUNCH_PRODUCT_NODES);
        result.put("canPublish",access.hasPermission("documents:publish"));
        if(!access.hasPermission("finance:read"))result.put("targetCost",null);
        result.put("canWrite", access.hasPermission("documents:write"));
        if(isLaunch(product)){
            launch.enrich(id,result);result.put("reviewGates",reviews.summary(id,state,this));
            if(engineering!=null){result.put("engineering",engineeringSummary(id,product,state));boolean writable=access.hasPermission("documents:write")&&access.canMaintainEngineeringData()&&!Set.of("frozen","abandoned").contains(text(product.getLockStatus()))&&!"archived".equals(product.getStatus())&&(!"released".equals(product.getStatus())||hasActiveRevision(id));result.put("canConfirmEngineering",writable);result.put("canChangeSupplier",writable&&access.hasPermission("supplier:view")&&(!"released".equals(product.getStatus())||Boolean.TRUE.equals(DevelopmentWorkflowStore.map(state.get("revision")).get("technical"))));}
        }
        return result;
    }

    @Transactional
    public Map<String, Object> updateProfile(long id, ProfileInput input) {
        Product product=requireDevelopmentEditable(id);var state=workflow.load(id);workflow.check(state,input.version());DevelopmentRules.atStage(product.getCurrentStepNo(),state,1);
        DevelopmentRules.filled(input.productName(),"产品名称");
        putProfileDraft(id,product,input,state,input.phoneModelIds());
        workflow.save(id,state);event(id,"draft_saved",state.get("profileDraft"),null);
        audit(id,"PRODUCT_RESEARCH_PROFILE",state.get("profileDraft"));return detail(id);
    }

    @Transactional
    public Map<String,Object> saveDesignDraft(long id,DesignDraftInput input) {
        var product=requireDevelopmentEditable(id);var state=workflow.load(id);workflow.check(state,input.profile().version());DevelopmentRules.atStage(product.getCurrentStepNo(),state,1);
        putProfileDraft(id,product,input.profile(),state,DevelopmentRules.ids(input.plan().get("phoneModelIds")));
        var previous=DevelopmentWorkflowStore.map(state.get("planDraft"));var plan=engineering==null?new LinkedHashMap<>(input.plan()):engineering.preparePlan(input.plan(),previous);
        launch.action(id,product,state,"plan_draft",plan,this);
        recordSupplierChange(id,previous,plan,1);
        workflow.save(id,state);event(id,"design_draft",Map.of("profile",state.get("profileDraft"),"plan",state.get("planDraft")),null);
        audit(id,"PRODUCT_RESEARCH_DESIGN_DRAFT",Map.of("productName",input.profile().productName()));return detail(id);
    }
    private void putProfileDraft(long id,Product product,ProfileInput input,Map<String,Object> state,List<Long> selectedPhones) {
        DevelopmentRules.filled(input.productName(),"产品名称");
        var priorPhones=new LinkedHashSet<>(DevelopmentRules.ids(DevelopmentWorkflowStore.map(state.get("profileDraft")).get("phoneModelIds")));priorPhones.addAll(DevelopmentRules.ids(DevelopmentWorkflowStore.map(state.get("requirement")).get("phoneModelIds")));
        var draft=profileDraft(product,input,state,selectedPhones);
        draft.put("references",snapshotReferences(id,input.references()));
        draft.put("phones",validatePhones(DevelopmentRules.ids(draft.get("phoneModelIds")),new ArrayList<>(priorPhones)));
        state.put("profileDraft",draft);
        if(!state.containsKey("requirement"))applyProfile(id,draft,new ArrayList<>(priorPhones));
    }
    private Map<String,Object> profileDraft(Product product,ProfileInput input,Map<String,Object> state,List<Long> selectedPhones){
        var draft=new LinkedHashMap<String,Object>();
        draft.put("productName",text(input.productName()).trim());draft.put("model",input.model());
        draft.put("requirements",text(input.requirements()));draft.put("expectedDeliveryDate",input.expectedDeliveryDate());
        if(access.hasPermission("finance:read")){draft.put("targetCost",input.targetCost());draft.put("currency",input.currency());draft.put("costUnit",input.costUnit());}
        else {var old=DevelopmentWorkflowStore.map(state.get("profileDraft"));draft.put("targetCost",old.getOrDefault("targetCost",product.getTargetCost()));draft.put("currency",old.get("currency"));draft.put("costUnit",old.get("costUnit"));}
        draft.put("notes",input.notes());draft.put("pendingReason",text(input.pendingReason()));
        draft.put("phoneModelIds",selectedPhones==null?List.of():selectedPhones);
        draft.put("ownerUserId",input.ownerUserId());
        draft.put("changeReason",text(input.changeReason()));
        return draft;
    }

    /** Preview current form input with the write gates, without saving drafts or confirming evidence. */
    public Map<String,Object> designReadiness(long id,DesignDraftInput input){
        var product=product(id,false);var state=new LinkedHashMap<>(workflow.load(id));
        DevelopmentRules.atStage(product.getCurrentStepNo(),state,1);
        DevelopmentRules.require(input!=null&&input.profile()!=null&&input.plan()!=null,"请提供当前立项设计资料");
        var draft=profileDraft(product,input.profile(),state,DevelopmentRules.ids(input.plan().get("phoneModelIds")));
        var plan=engineering==null?new LinkedHashMap<>(input.plan()):engineering.preparePlan(input.plan(),DevelopmentWorkflowStore.map(state.get("planDraft")));state.put("profileDraft",draft);state.put("planDraft",plan);
        var checks=new ArrayList<>(DevelopmentRules.requirementChecks(draft,false));
        if(number(draft.get("ownerUserId"))>0){checks.removeIf(c->"OWNER".equals(c.code()));checks.add(DevelopmentRules.check("OWNER","项目负责人","owner",()->requireProfileOwner(draft)));}
        checks.addAll(launch.planChecks(state));
        checks.add(DevelopmentRules.check("MATERIAL_ACCESS","已关联资料的有效性","drawings",()->snapshotReferences(id,input.profile().references())));
        checks.add(DevelopmentRules.check("REQUIREMENT_CHANGE","需求变更原因","requirement-change",()->requireRequirementChange(state,draft)));
        checks.add(DevelopmentRules.check("INIT_OWNER_CONFIRMATION","项目负责人确认立项","owner",()->requireResponsibleConfirmation(state)));
        return Map.of("version",number(state.get("version")),"ready",checks.stream().allMatch(DevelopmentRules.ReadinessCheck::complete),"checks",checks);
    }

    public Map<String,Object> stageReadiness(long id,StageReadinessInput input){
        var product=product(id,false);
        DevelopmentRules.require(isLaunch(product),"请在新品开发中检查阶段资料");
        DevelopmentRules.require(input!=null&&input.stage()>=2&&input.stage()<=8,"阶段无效");
        // Validators may derive yield or resolve application links; preview never changes stored facts.
        var state=json.convertValue(workflow.load(id),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        DevelopmentRules.atStage(product.getCurrentStepNo(),state,input.stage());
        if(input.stage()==6&&input.sampleRoundId()!=null){state.put("selectedRoundId",input.sampleRoundId());state.put("validationSampleId",input.sampleRoundId());}
        if(input.stage()==7&&input.releaseDraft()!=null){
            var draft=new LinkedHashMap<>(input.releaseDraft());
            // Same inherited sample and scope as release_draft; this remains a read-only projection.
            try{var source=launch.currentSample(id,state,this);draft.put("sampleRoundId",source.get("roundId"));if(DevelopmentRules.ids(draft.get("phoneModelIds")).isEmpty())draft.put("phoneModelIds",DevelopmentWorkflowStore.map(source.get("details")).get("phoneModelIds"));}
            catch(com.yuewei.plm.common.exception.BusinessException missing){/* Reported by the shared sample check. */}
            state.put("releaseDraft",draft);
        }
        if(input.draft()!=null){
            var draft=new LinkedHashMap<>(input.draft());
            if(draft.containsKey("references"))draft.put("references",snapshotReferences(id,refs(draft.get("references"))));
            var drafts=new LinkedHashMap<>(DevelopmentWorkflowStore.map(state.get("stageDrafts")));drafts.put(String.valueOf(input.stage()),draft);state.put("stageDrafts",drafts);
            if(input.stage()==2&&draft.get("phoneModelIds") instanceof List<?>){var plan=new LinkedHashMap<>(DevelopmentWorkflowStore.map(state.get("planDraft")));plan.put("phoneModelIds",draft.get("phoneModelIds"));state.put("planDraft",plan);}
        }
        var checks=launch.stageChecks(id,input.stage(),state,this);
        return Map.of("version",number(state.get("version")),"ready",checks.stream().allMatch(DevelopmentRules.ReadinessCheck::complete),"checks",checks);
    }

    private List<Map<String, Object>> rounds(long id) { return rounds(id,true); }
    List<Map<String,Object>> allRounds(long id) {return rounds(id,false);}
    private List<Map<String, Object>> rounds(long id,boolean hideMoney) {
        var rows = jdbc.queryForList("""
            select round_id as "roundId",round_no as "roundNo",kind,record_date as "recordDate",title,result,
              issues,changes,retest_result as "retestResult",references_json::text as "referencesJson",status,
              confirmed_at as "confirmedAt",confirmed_by as "confirmedBy",created_at as "createdAt",details_json::text as "detailsJson"
            from plm_product_development_round where product_id=? and deleted_flag=0 order by round_no desc
            """, id);
        rows.forEach(row -> {
            Object refs = readJson(row.remove("referencesJson"));
            row.put("references", hideMoney?redact(refs):refs);
            Object facts=readJson(row.remove("detailsJson"));row.put("details",hideMoney?redact(facts):facts);row.put("legacyBasis",!DevelopmentWorkflowStore.map(facts).containsKey("requirementVersion"));row.put("voided",LaunchReviewWorkflow.roundVoided(workflow.load(id),number(row.get("roundId"))));
        });
        return rows;
    }

    @Transactional
    public Map<String, Object> saveRound(long id, Long roundId, RoundInput input) {
        var product=requireDevelopmentEditable(id); // Serializes numbering, edits, confirmation and publication per product.
        var state=workflow.load(id);
        if(roundId==null&&input.requestId()!=null){var repeated=jdbc.queryForList("select round_id from plm_product_development_round where product_id=? and request_id=?",Long.class,id,input.requestId());if(!repeated.isEmpty())return rounds(id).stream().filter(r->Objects.equals(r.get("roundId"),repeated.get(0))).findFirst().orElseThrow();}
        workflow.check(state,input.version());
        DevelopmentRules.atStage(product.getCurrentStepNo(),state,roundStage(input.kind(),input.details()==null?Map.of():input.details()));
        var facts=new LinkedHashMap<String,Object>(input.details()==null?Map.of():input.details());
        String supplierCode=text(facts.get("supplierCode"));if(!supplierCode.isEmpty()&&(roundId==null||!supplierCode.equals(text(DevelopmentWorkflowStore.map(rawRound(id,roundId).get("details")).get("supplierCode")))))engineering.supplierForNewUse(supplierCode);
        var retainedPhones=roundId!=null&&!input.confirm()?DevelopmentRules.ids(DevelopmentWorkflowStore.map(rawRound(id,roundId).get("details")).get("phoneModelIds")):List.<Long>of();
        validateRound(id,input,facts,state,retainedPhones);
        long number;
        if (roundId != null) {
            var existing = jdbc.queryForList("select round_no,status,kind from plm_product_development_round where product_id=? and round_id=? and deleted_flag=0 for update", id, roundId);
            if (existing.isEmpty()) throw invalid("开发记录不存在");
            if ("confirmed".equals(existing.get(0).get("status"))) throw invalid("已确认轮次保留历史，请新增一轮记录");
            DevelopmentRules.require(input.kind().equals(existing.get(0).get("kind")),"不能更改已有轮次的类型");
            number = ((Number) existing.get(0).get("round_no")).longValue();
        } else {
            number = jdbc.queryForObject("select coalesce(max(round_no),0)+1 from plm_product_development_round where product_id=? and kind=?", Long.class, id,input.kind());
        }
        var snapshots = snapshotReferences(id, input.references());
        String retest=text(input.retestResult());
        if(retest.isBlank()&&"passed".equals(input.result())&&(number(facts.get("previousRoundId"))>0||number(facts.get("feedbackRoundId"))>0))retest=text(facts.get("actualResult"));
        String title = input.title() == null || input.title().isBlank()
            ? "第" + number + "轮" + ("pilot".equals(input.kind()) ? "试产" : "test".equals(input.kind()) ? "测试" : "打样") : input.title().trim();
        String actor = access.actor().displayName();
        if (roundId == null) {
            roundId = jdbc.queryForObject("""
                insert into plm_product_development_round(product_id,round_no,kind,record_date,title,result,
                  issues,changes,retest_result,references_json,status,confirmed_at,confirmed_by,created_by,updated_by,details_json,request_id)
                values(?,?,?,?,?,?,?,?,?,?::jsonb,?,case when ? then now() end,?,?,?,?::jsonb,?) returning round_id
                """, Long.class, id, number, input.kind(), input.recordDate(), title, input.result(),
                text(input.issues()), text(input.changes()), retest, encode(snapshots),
                input.confirm() ? "confirmed" : "draft", input.confirm(), input.confirm() ? actor : null, actor, actor, encode(facts),input.requestId());
        } else {
            jdbc.update("""
                update plm_product_development_round set kind=?,record_date=?,title=?,result=?,issues=?,changes=?,
                  retest_result=?,references_json=?::jsonb,status=?,confirmed_at=case when ? then now() end,
                  confirmed_by=?,updated_at=now(),updated_by=?,details_json=?::jsonb where product_id=? and round_id=?
                """, input.kind(), input.recordDate(), title, input.result(), text(input.issues()), text(input.changes()),
                retest, encode(snapshots), input.confirm() ? "confirmed" : "draft", input.confirm(),
                input.confirm() ? actor : null, actor,encode(facts), id, roundId);
        }
        if("sample".equals(input.kind())&&input.confirm()){if("prototype_3d".equals(facts.get("samplePurpose")))state.put("prototypeRoundId",roundId);else state.put("selectedRoundId",roundId);}
        var pendingFeedback=DevelopmentWorkflowStore.map(state.get("feedback"));
        if(input.confirm()&&"passed".equals(input.result())&&input.kind().equals(text(DevelopmentWorkflowStore.map(pendingFeedback.get("source")).get("kind")))&&number(facts.get("feedbackRoundId"))>0&&number(facts.get("feedbackRoundId"))==number(pendingFeedback.get("sourceRoundId")))pendingFeedback.put("completedByRoundId",roundId);
        workflow.save(id,state);
        audit(id, "PRODUCT_RESEARCH_ROUND", Map.of("roundId", roundId, "confirmed", input.confirm()));
        long savedId = roundId;
        return rounds(id).stream().filter(r -> ((Number) r.get("roundId")).longValue() == savedId).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteDraft(long id, long roundId, Long version) {
        var product=requireDevelopmentEditable(id);var state=workflow.load(id);workflow.check(state,version);
        DevelopmentRules.atStage(product.getCurrentStepNo(),state,roundStage(text(rawRound(id,roundId).get("kind")),DevelopmentWorkflowStore.map(rawRound(id,roundId).get("details"))));
        int count = jdbc.update("update plm_product_development_round set deleted_flag=1,updated_at=now(),updated_by=? where product_id=? and round_id=? and status='draft' and deleted_flag=0", access.actor().displayName(), id, roundId);
        if (count != 1) throw invalid("只能删除本项目未确认的草稿记录");
        workflow.save(id,state);
        audit(id, "PRODUCT_RESEARCH_DRAFT_DELETE", Map.of("roundId", roundId));
    }

    public List<Map<String, Object>> materialOptions(long id) {
        product(id, false);
        List<Map<String, Object>> options = new ArrayList<>();
        var fileIds = jdbc.queryForList("""
            select a.attachment_id from plm_attachment a where a.deleted_flag=0 and
              ((lower(a.owner_object_type) in ('product','project') and a.owner_object_id=?) or
                exists(select 1 from plm_document_reference r where r.family_id=coalesce(a.family_id,a.attachment_id)
                  and r.object_type='product' and r.object_id=?)) and
            """ + access.visibleSqlClause("file", "a.attachment_id") + " order by a.created_at desc,a.attachment_id desc", Long.class, id, id);
        if (access.hasPermission("file:read") && !fileIds.isEmpty()) {
            var byId = new HashMap<Long, Attachment>();
            attachments.selectBatchIds(fileIds).forEach(file -> byId.put(file.getAttachmentId(), file));
            for (long fileId : fileIds) {
                var file = byId.get(fileId);
                if (file != null) {
                    var option = option("file", fileId, file.getOriginalFileName(), file.getVersionNo(), file.getStatus());
                    option.put("category", file.getFileCategory()); option.put("familyId", file.getFamilyId() == null ? fileId : file.getFamilyId());
                    option.put("attachment", AttachmentVO.from(file)); options.add(option);
                }
            }
        }
        for (var row : jdbc.queryForList("select product_bom_id,bom_name,version_no,status,source_type,confirmed_by from plm_product_bom where product_id=? and deleted_flag=0 order by created_at desc,product_bom_id desc", id)) {
            var bomOption=option("bom", ((Number) row.get("product_bom_id")).longValue(), row.get("bom_name"), row.get("version_no"), row.get("status"));bomOption.put("sourceType",row.get("source_type"));bomOption.put("engineeringConfirmed",row.get("confirmed_by")!=null&&"released".equals(row.get("status")));options.add(bomOption);
        }
        for (var row : jdbc.queryForList("select process_id,process_name,version_no,status from plm_process where product_id=? and process_type='routing' and deleted_flag=0 order by created_at desc,process_id desc", id)) {
            options.add(option("process", ((Number) row.get("process_id")).longValue(), row.get("process_name"), row.get("version_no"), row.get("status")));
        }
        for(var e:workflow.events(id))if("link".equals(e.get("kind"))){var d=DevelopmentWorkflowStore.map(e.get("data"));var link=option("link",number(e.get("eventId")),d.get("title"),"L"+e.get("eventId"),"available");link.put("content",d);options.add(link);}
        return options;
    }

    private Map<String, Object> option(String type, long id, Object name, Object version, Object status) {
        var result = new LinkedHashMap<String, Object>();
        result.put("type", type); result.put("id", id); result.put("name", name);
        result.put("versionNo", version); result.put("status", status);
        return result;
    }

    /** IDs never mean 'latest'. Copy the selected revision and engineering content at confirmation time. */
    List<Map<String, Object>> snapshotReferences(long id, List<MaterialReference> references) {
        return snapshotReferences(id,references,true);
    }
    private List<Map<String, Object>> snapshotReferences(long id, List<MaterialReference> references,boolean lock) {
        var available = materialOptions(id);
        List<Map<String, Object>> snapshots = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var reference : references == null ? List.<MaterialReference>of() : references) {
            if (reference == null || reference.id() == null || reference.type() == null) throw invalid("资料引用不能为空");
            String key = reference.type() + ":" + reference.id();
            if (!seen.add(key)) continue;
            var option = available.stream().filter(o -> reference.type().equals(o.get("type")) && reference.id().equals(o.get("id"))).findFirst()
                .orElseThrow(() -> invalid("资料不存在、未关联本项目或没有访问权限"));
            var snapshot = new LinkedHashMap<>(option);
            snapshot.remove("attachment");
            switch (reference.type()) {
                case "file" -> {
                    access.requireFile(reference.id());
                    // Use the same row lock as delete/version operations.
                    if(lock)jdbc.queryForList("select attachment_id from plm_attachment where attachment_id=? and deleted_flag=0 for update", reference.id());
                    var file = attachments.selectById(reference.id());
                    if (file == null || Integer.valueOf(1).equals(file.getDeletedFlag())) throw invalid("所选文件已删除，请刷新");
                    snapshot.put("checksum", file.getChecksum());
                    snapshot.put("fileExt", file.getFileExt());
                }
                case "link" -> snapshot.put("content",eventData(id,reference.id(),"link"));
                case "bom" -> snapshot.put("content", bomSnapshot(reference.id()));
                case "process" -> snapshot.put("content", processSnapshot(reference.id()));
                default -> throw invalid("资料类型不支持");
            }
            snapshots.add(snapshot);
        }
        return snapshots;
    }

    private Object bomSnapshot(long id) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("header", readJson(jdbc.queryForObject("select to_jsonb(b)::text from plm_product_bom b where product_bom_id=? and deleted_flag=0 for share", String.class, id)));
        snapshot.put("items", jsonRows("select to_jsonb(i)::text from plm_product_bom_item i where product_bom_id=? and deleted_flag=0 order by line_no,product_bom_item_id", id));
        snapshot.put("routes", jsonRows("select to_jsonb(r)::text from plm_product_bom_route r where product_bom_id=? and deleted_flag=0 order by product_bom_route_id", id));
        snapshot.put("colors", jsonRows("select to_jsonb(c)::text from plm_product_bom_route_color c join plm_product_bom_route r using(product_bom_route_id) where r.product_bom_id=? and r.deleted_flag=0 and c.deleted_flag=0 order by c.product_bom_route_color_id", id));
        snapshot.put("materialTargets",jsonRows("select to_jsonb(t)::text from plm_bom_item_target t join plm_product_bom_item i using(product_bom_item_id) where t.product_bom_id=? and i.deleted_flag=0 order by t.product_bom_item_id,t.product_bom_route_id",id));
        return snapshot;
    }

    private Object processSnapshot(long id) {
        return Map.of("route", readJson(jdbc.queryForObject("select to_jsonb(p)::text from plm_process p where process_id=? and deleted_flag=0 for share", String.class, id)),
            "operations", jsonRows("select to_jsonb(p)::text from plm_process p where parent_process_id=? and deleted_flag=0 order by sequence_no,process_id", id),
            "edges",jsonRows("select to_jsonb(e)::text from plm_process_edge e where route_id=? order by edge_no,edge_id",id));
    }

    private List<Object> jsonRows(String sql, long id) {
        return jdbc.queryForList(sql, String.class, id).stream().map(this::readJson).toList();
    }

    public List<ProductReleaseGateMissingItemVO> warnings(long id) {
        try { validateFinalBasis(id,workflow.load(id),false);return isLaunch(products.selectById(id))?releaseGaps(id,snapshotReferences(id,refs(DevelopmentWorkflowStore.map(workflow.load(id).get("releaseDraft")).get("references")))):List.of(); }
        catch(BusinessException failure){return List.of(ProductReleaseGateMissingItemVO.builder().code("DEVELOPMENT_BASIS_INVALID").message(failure.getMessage()).severity("blocker").build());}
    }

    /** Invoked by the existing Product publication transaction, so status, snapshot and audit commit together. */
    public void captureRelease(Product product, ProductLifecycleActionDTO input, ProductReleaseGateCheckVO gate) {
        if (!isLaunch(product)) return;
        access.requirePermission("documents:publish");
        product(product.getProductId(), true);
        long id=product.getProductId();var state=workflow.load(id);
        if(isLaunch(product))workflow.check(state,input==null?null:input.getDevelopmentVersion());
        DevelopmentRules.require(engineeringStage(product,state)==8,"请先完成试产并进入发模步骤");
        DevelopmentRules.requireChecks(launch.stageChecks(id,8,state,this));
        DevelopmentRules.require(!Set.of("frozen","abandoned").contains(text(product.getLockStatus())),"冻结或停止的项目不能发布");
        validateFinalBasis(id,state,true);
        var draft=DevelopmentWorkflowStore.map(state.get("releaseDraft"));
        var snapshots=snapshotReferences(id,refs(draft.get("references")));
        if(input!=null&&input.getDevelopmentReferences()!=null&&!sameReferenceIds(input.getDevelopmentReferences(),refs(draft.get("references"))))throw invalid("发布版本与已确认清单不同，请重新核对定型");
        var missing=releaseGaps(id,snapshots);
        if(!missing.isEmpty()&&(input==null||!Boolean.TRUE.equals(input.getRiskConfirmed())||text(input.getExceptionReason()).isBlank()||input.getSupplementDueDate()==null))throw invalid("例外发布请确认资料缺口，填写原因和补齐日期");
        long responsible=numberOr(draft.get("responsibleUserId"),product.getOwnerUserId()==null?access.actorId():product.getOwnerUserId());
        if(input!=null&&input.getDevelopmentResponsibleUserId()!=null)responsible=input.getDevelopmentResponsibleUserId();
        DevelopmentRules.require(jdbc.queryForObject("select count(*) from sys_user where user_id=? and deleted_flag=0 and status='active'",Long.class,responsible)==1,"补齐责任人不存在或已停用");
        long packageNo=jdbc.queryForObject("select coalesce(max(package_no),0)+1 from plm_product_development_release where product_id=?",Long.class,id);
        var basis=new LinkedHashMap<String,Object>();basis.put("requirement",state.get("requirement"));basis.put("compatibleRequirementVersions",state.get("compatibleRequirementVersions"));basis.put("releaseDraft",draft);basis.put("revision",state.get("revision"));basis.put("plan",state.get("plan"));basis.put("sample",DevelopmentWorkflowStore.map(state.get("validationBasis")).get("sample"));basis.put("molds",DevelopmentWorkflowStore.map(state.get("validationBasis")).get("molds"));basis.put("stageEvidence",Map.of("design",DevelopmentWorkflowStore.map(state.get("stage1Basis")),"mold",DevelopmentWorkflowStore.map(state.get("stage2Basis")),"validation",DevelopmentWorkflowStore.map(state.get("validationBasis"))));basis.put("flow",product.getDevelopmentMode());
        basis.put("reviews",state.get("reviewRecords"));basis.put("reviewSetup",state.get("reviewSetup"));basis.put("unitCostVersions",state.get("unitCostVersions"));basis.put("voidedRounds",state.get("voidedRounds"));basis.put("engineeringBasis",state.get("engineeringBasis"));
        basis.put("responsibleSnapshot",Map.of("userId",responsible,"name",jdbc.queryForObject("select coalesce(display_name,user_name,username) from sys_user where user_id=?",String.class,responsible)));
        Long releaseId=jdbc.queryForObject("""
            insert into plm_product_development_release(product_id,version_no,package_no,references_json,product_snapshot,
              rounds_snapshot,missing_items,exception_reason,supplement_due_date,responsible_user_id,published_by,basis_json)
            values(?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?, ?,?::jsonb) returning release_id
            """,Long.class,id,"R"+packageNo,packageNo,encode(snapshots),encode(product),encode(rounds(id,false)),encode(missing),
            input==null?null:input.getExceptionReason(),input==null?null:input.getSupplementDueDate(),
            responsible,access.actor().displayName(),encode(basis));
        state.put("lastReviewRevisionNo",LaunchReviewWorkflow.revisionNo(state));state.remove("revision");state.remove("finalized");state.put("currentReleaseId",releaseId);workflow.save(id,state);
        event(id,"published",Map.of("releaseId",releaseId,"packageNo",packageNo,"basis",basis),null);
        audit(product.getProductId(), "PRODUCT_RESEARCH_RELEASE_SNAPSHOT", Map.of("references", snapshots, "missingItems", missing));
    }

    public boolean fileReferenced(long attachmentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from plm_product_development_round r,
              jsonb_array_elements(r.references_json) ref where r.deleted_flag=0 and ref->>'type'='file' and (ref->>'id')::bigint=?)
              or exists(select 1 from plm_product_development_release r,
              jsonb_array_elements(r.references_json) ref where ref->>'type'='file' and (ref->>'id')::bigint=?)
            or exists(select 1 from plm_product_development_event e where jsonb_path_exists(e.data,?::jsonpath))
              or exists(select 1 from plm_product_development_state s where jsonb_path_exists(s.data,?::jsonpath))
              or exists(select 1 from plm_attachment f join plm_mold_application a on a.application_id=f.owner_object_id and a.deleted_flag=0
                where f.owner_object_type='MoldApplication' and f.deleted_flag=0 and f.source_file_id=?)
            """, Boolean.class, attachmentId, attachmentId,
            "$.** ? (@.type == \"file\" && @.id == "+attachmentId+")",
            "$.** ? (@.type == \"file\" && @.id == "+attachmentId+")", "research-file:"+attachmentId));
    }

    public void lockFileForDeletion(long attachmentId) {
        jdbc.queryForList("select attachment_id from plm_attachment where attachment_id=? for update", attachmentId);
    }

    private void decodeRelease(Map<String, Object> release) {
        String packageLabel="R"+release.get("packageNo");
        if(!packageLabel.equals(release.get("versionNo"))){release.put("legacyVersionNo",release.get("versionNo"));release.put("versionNo",packageLabel);}
        for (String name : List.of("references", "productSnapshot", "roundsSnapshot", "missingItems", "basis")) {
            release.put(name, readJson(release.remove(name + "Json")));
        }
        {
            release.put("references", redact(release.get("references")));
            release.put("productSnapshot",redact(release.get("productSnapshot")));
            release.put("basis",redact(release.get("basis")));
            release.put("roundsSnapshot", redact(release.get("roundsSnapshot")));
        }
    }

    private Object withoutMoney(Object value) {return FinancialSnapshotRedaction.withoutMoney(value);}

    private void audit(long id, String action, Object detail) {
        logs.logRequired(OperationLogCreateCommand.builder().action(action).businessType("PRODUCT")
            .businessId(Long.toString(id)).detailJson(encode(detail)).build(), access.actor());
    }

    public boolean hasActiveRevision(long id) { return !DevelopmentWorkflowStore.map(workflow.load(id).get("revision")).isEmpty(); }
    Object redact(Object value){var scoped=FinancialSnapshotRedaction.scopedMold(value,this::canReadProject);if(supplierFeedback!=null)scoped=supplierFeedback.redact(scoped);if(engineering!=null)scoped=engineering.redact(scoped);return access.hasPermission("finance:read")?scoped:withoutMoney(scoped);}
    private boolean canReadProject(long id){try{access.requireProduct(id);return true;}catch(BusinessException denied){return false;}}
    private long number(Object value){return DevelopmentWorkflowStore.number(value);}
    private long numberOr(Object value,long fallback){return value==null?fallback:number(value);}
    private long event(long id,String kind,Object data,String request){return workflow.event(id,kind,data,request,access.actor().displayName());}
    List<MaterialReference> refs(Object value){
        if(!(value instanceof List<?> rows))return List.of();
        DevelopmentRules.require(rows.size()<=100,"一次最多采用100份资料");
        return rows.stream().map(row->{var m=DevelopmentWorkflowStore.map(row);return new MaterialReference(text(m.get("type")),number(m.get("id")));}).toList();
    }
    private boolean sameReferenceIds(List<MaterialReference> a,List<MaterialReference> b){return new HashSet<>(a).equals(new HashSet<>(b));}
    private List<Map<String,Object>> validatePhones(List<Long> ids){return launch.phoneSnapshots(ids);}
    private List<Map<String,Object>> validatePhones(List<Long> ids,List<Long> retained){return launch.phoneSnapshots(ids,retained);}
    private void applyProfile(long id,Map<String,Object> draft){applyProfile(id,draft,List.of());}
    private void applyProfile(long id,Map<String,Object> draft,List<Long> retained){
        long owner=number(draft.get("ownerUserId"));
        requireProfileOwner(draft);
        var phones=validatePhones(DevelopmentRules.ids(draft.get("phoneModelIds")),retained);
        String model=phones.isEmpty()?text(draft.get("model")):phones.stream().map(p->text(p.get("brandName"))+" "+text(p.get("modelName"))).collect(java.util.stream.Collectors.joining(" / "));
        if(model.codePointCount(0,model.length())>128)model=model.substring(0,model.offsetByCodePoints(0,110))+" 等"+phones.size()+"款";
        jdbc.update("update plm_product set product_name=?,model=?,development_requirements=?,target_cost=?,expected_delivery_date=?::date,owner_user_id=?,updated_at=now(),updated_by=? where product_id=?",
            text(draft.get("productName")),model,text(draft.get("requirements")),draft.get("targetCost"),text(draft.get("expectedDeliveryDate")).isBlank()?null:text(draft.get("expectedDeliveryDate")),owner==0?null:owner,access.actor().displayName(),id);
    }
    private void requireProfileOwner(Map<String,Object> draft){
        long owner=number(draft.get("ownerUserId"));
        if(owner>0&&jdbc.queryForObject("select count(*) from sys_user where user_id=? and deleted_flag=0 and status='active'",Long.class,owner)!=1)throw invalid("负责人不存在或已停用");
    }
    private void requireRequirementChange(Map<String,Object> state,Map<String,Object> draft){
        if(state.containsKey("requirement")&&!DevelopmentRules.sameRequirementDraft(DevelopmentWorkflowStore.map(state.get("requirement")),draft))DevelopmentRules.filled(draft.get("changeReason"),"需求变更原因");
    }
    void requireCurrentDesignBasis(long id,Map<String,Object> state){
        var draft=new LinkedHashMap<>(DevelopmentWorkflowStore.map(state.get("profileDraft")));draft.put("references",snapshotReferences(id,refs(draft.get("references"))));
        DevelopmentRules.require(DevelopmentRules.sameRequirementDraft(DevelopmentWorkflowStore.map(state.get("requirement")),draft)&&launch.planCurrent(state),"当前需求或开发计划尚未确认，请先确认需求与计划；本次评审输入可继续保留");
    }
    private void confirmRequirement(long id,Map<String,Object> state){
        var draft=new LinkedHashMap<>(DevelopmentWorkflowStore.map(state.get("profileDraft")));DevelopmentRules.requirement(draft,!isLaunch(products.selectById(id)));
        var allReferences=snapshotReferences(id,refs(draft.get("references")));draft.put("references",allReferences);
        if(DevelopmentRules.sameRequirementDraft(DevelopmentWorkflowStore.map(state.get("requirement")),draft)){var confirmed=new LinkedHashMap<>(DevelopmentWorkflowStore.map(state.get("requirement")));var profile=new LinkedHashMap<>(confirmed);profile.put("references",allReferences);state.put("profileDraft",profile);applyProfile(id,confirmed);return;}
        requireRequirementChange(state,draft);
        draft.put("phones",validatePhones(DevelopmentRules.ids(draft.get("phoneModelIds"))));
        draft.put("references",DevelopmentRules.requirementReferences(allReferences));
        var oldRequirement=DevelopmentWorkflowStore.map(state.get("requirement"));
        boolean sameBasis=!oldRequirement.isEmpty()&&text(oldRequirement.get("requirements")).trim().equals(text(draft.get("requirements")).trim())&&DevelopmentRules.ids(oldRequirement.get("phoneModelIds")).equals(DevelopmentRules.ids(draft.get("phoneModelIds")))&&sameReferenceIds(refs(DevelopmentRules.requirementReferences(oldRequirement.get("references"))),refs(draft.get("references")));
        List<Long> compatible=new ArrayList<>();
        if(sameBasis){var previous=state.get("compatibleRequirementVersions");if(previous instanceof List<?> list)list.forEach(v->compatible.add(number(v)));else compatible.add(number(oldRequirement.get("requirementVersion")));}
        long next=number(state.get("requirementVersion"))+1;compatible.add(next);state.put("compatibleRequirementVersions",compatible);draft.put("requirementVersion",next);
        draft.put("confirmedAt",java.time.LocalDateTime.now().toString());draft.put("confirmedBy",access.actor().displayName());
        applyProfile(id,draft);
        draft.put("ownerName",jdbc.queryForObject("select coalesce(display_name,user_name,username) from sys_user where user_id=? and deleted_flag=0 and status='active'",String.class,number(draft.get("ownerUserId"))));
        state.put("requirement",draft);state.put("requirementVersion",next);var profile=new LinkedHashMap<>(draft);profile.put("references",allReferences);state.put("profileDraft",profile);state.remove("finalized");
        event(id,"requirement_confirmed",draft,null);
    }
    boolean compatibleRequirement(Map<String,Object> state,Object version){
        var versions=state.get("compatibleRequirementVersions");
        return versions instanceof List<?> list?list.stream().anyMatch(v->number(v)==number(version)):number(version)==number(state.get("requirementVersion"));
    }
    Map<String,Object> rawRound(long id,long roundId){
        var rows=jdbc.queryForList("select round_id as \"roundId\",round_no as \"roundNo\",kind,status,result,record_date as \"recordDate\",details_json::text as details,references_json::text as refs,issues,retest_result as \"retestResult\" from plm_product_development_round where product_id=? and round_id=? and deleted_flag=0",id,roundId);
        if(rows.isEmpty())throw invalid("来源记录不存在或不属于本项目");var row=rows.get(0);row.put("details",readJson(row.get("details")));row.put("references",readJson(row.remove("refs")));return row;
    }
    Map<String,Object> confirmedSource(long id,long roundId,String kind,boolean passed){
        var row=rawRound(id,roundId);DevelopmentRules.require(kind.equals(row.get("kind"))&&"confirmed".equals(row.get("status")),"请选择已确认的"+("sample".equals(kind)?"样品":"test".equals(kind)?"测试":"试产批次"));
        DevelopmentRules.require(!LaunchReviewWorkflow.roundVoided(workflow.load(id),roundId),"来源记录已作废，请选择有效记录");
        if(passed)DevelopmentRules.require("passed".equals(row.get("result")),"来源验证尚未通过，不能作为后续有效依据");return row;
    }
    private Object technical(Object value){
        if(value instanceof List<?> rows)return rows.stream().filter(row->DevelopmentRules.technicalReference(DevelopmentWorkflowStore.map(row))).map(this::technicalContent).toList();
        return value;
    }
    private Object technicalContent(Object value){
        return EngineeringBasisService.technicalContent(value);
    }
    void sameTechnical(Object source,Object actual){
        sameTechnical(source,actual,false);
    }
    /** Trial is the first stage that may introduce production instructions; existing instructions stay exact. */
    void sameTechnical(Object source,Object actual,boolean allowFirstProductionInstructions){
        DevelopmentRules.require(source instanceof List<?> && actual instanceof List<?>,"来源技术资料版本缺失，请返回对应步骤明确采用版本后重新确认");
        var compared=actual;
        if(allowFirstProductionInstructions&&actual instanceof List<?> rows){
            var originals=LaunchReviewWorkflow.records(source);
            var known=originals.stream().filter(this::productionInstruction).map(r->number(r.get("familyId"))).collect(java.util.stream.Collectors.toSet());
            compared=rows.stream().filter(r->{var ref=DevelopmentWorkflowStore.map(r);String type=text(ref.get("type"));
                if(Set.of("bom","process").contains(type)&&originals.stream().noneMatch(o->type.equals(o.get("type"))))return false;
                return !productionInstruction(ref)||known.contains(number(ref.get("familyId")));
            }).toList();
        }
        var a=(List<?>)technical(source);var b=(List<?>)technical(compared);
        DevelopmentRules.require(new HashSet<>(a).equals(new HashSet<>(b)),"采用的结构／材料／工艺版本或内容与来源验证不同，请补充对应验证");
    }
    private boolean productionInstruction(Map<String,Object> ref){return "file".equals(ref.get("type"))&&Set.of("sop","sip").contains(text(ref.get("category")));}

    private void validateRound(long id,RoundInput input,Map<String,Object> facts,Map<String,Object> state,List<Long> retainedPhones){
        if(!Set.of("sample","test","pilot").contains(input.kind()))throw invalid("开发记录类型无效");
        if(!state.containsKey("requirement")&&input.confirm())throw invalid("请先确认需求立项");
        facts.put("requirementVersion",numberOr(facts.get("requirementVersion"),number(state.get("requirementVersion"))));
        long revisionNo=LaunchReviewWorkflow.revisionNo(state);
        if(input.confirm()&&facts.get("revisionNo")!=null)DevelopmentRules.require(number(facts.get("revisionNo"))==revisionNo,"本轮属于其他修订，请按当前修订新建实际记录");
        facts.put("revisionNo",revisionNo);
        var feedback=DevelopmentWorkflowStore.map(state.get("feedback"));
        if(!feedback.containsKey("completedByRoundId")&&input.kind().equals(text(DevelopmentWorkflowStore.map(feedback.get("source")).get("kind")))&&number(feedback.get("sourceRoundId"))>0)facts.putIfAbsent("feedbackRoundId",feedback.get("sourceRoundId"));
        if(number(facts.get("feedbackRoundId"))>0){var source=rawRound(id,number(facts.get("feedbackRoundId")));DevelopmentRules.require("confirmed".equals(source.get("status"))&&Set.of("sample","test","pilot").contains(text(source.get("kind"))),"问题来源必须是本项目已确认的收样、测试或试产");facts.put("feedbackSnapshot",source);}
        var scope=DevelopmentRules.ids(facts.get("phoneModelIds"));if(!scope.isEmpty())facts.put("phones",validatePhones(scope,retainedPhones));
        if(input.confirm())DevelopmentRules.require(compatibleRequirement(state,facts.get("requirementVersion")),"需求依据已变更，请核对本轮采用的需求版本");
        long previous=number(facts.get("previousRoundId"));if(previous>0){var p=rawRound(id,previous);DevelopmentRules.require(input.kind().equals(p.get("kind"))&&"confirmed".equals(p.get("status")),"前轮来源类型或状态无效");if(input.confirm()&&"failed".equals(p.get("result"))&&"passed".equals(input.result()))DevelopmentRules.require(!text(input.retestResult()).isBlank()||!text(facts.get("actualResult")).isBlank(),"请记录原问题的本次复验结果");}
        if(!text(facts.get("supplierCode")).isBlank()){
            var supplier=inventorySuppliers.snapshot().suppliers().stream().filter(r->Objects.equals(r.supplierCode(),facts.get("supplierCode"))).findFirst().orElseThrow(()->invalid("供应商已不存在，请从供应商资料中选择"));facts.put("makerName",supplier.supplierName());
        }
        long quote=number(facts.get("quoteEventId"));if(quote>0){var q=eventData(id,quote,"quote");DevelopmentRules.require(Objects.equals(q.get("supplierCode"),facts.get("supplierCode")),"报价与本轮供应商不一致");facts.put("adoptedQuote",q);}
        if("sample".equals(input.kind()))launch.validateSample(id,input,facts,state); else {DevelopmentRules.round(input,facts);reviews.validateRound(id,input,facts,state,this);}
        if(!"sample".equals(input.kind())&&number(facts.get("sourceRoundId"))>0){
            var source=confirmedSource(id,number(facts.get("sourceRoundId")),"test".equals(input.kind())?"sample":"test","pilot".equals(input.kind()));
            var sourceFacts=DevelopmentWorkflowStore.map(source.get("details"));DevelopmentRules.covers(sourceFacts,facts);
            if(input.confirm()){
                DevelopmentRules.require(compatibleRequirement(state,sourceFacts.get("requirementVersion")),"来源记录采用旧需求，请补充当前需求的验证");
                sameTechnical(source.get("references"),snapshotReferences(id,input.references()),"pilot".equals(input.kind()));
            }
        }
    }
    Map<String,Object> eventData(long id,long eventId,String kind){
        var rows=jdbc.queryForList("select data::text from plm_product_development_event where product_id=? and event_id=? and kind=?",String.class,id,eventId,kind);
        if(rows.isEmpty())throw invalid("关联的历史记录不存在或不属于本项目");return DevelopmentWorkflowStore.map(readJson(rows.get(0)));
    }
    /** Called inside the original timeline transaction: confirmation evidence and advancement commit together. */
    public void confirmStage(long id,String nodeKey){confirmStage(id,nodeKey,null);}
    public void confirmStage(long id,String nodeKey,Long expectedVersion){
        var p=requireEditable(id);var state=workflow.load(id);int stage=p.getCurrentStepNo()==null?1:p.getCurrentStepNo();
        if(isLaunch(p))workflow.check(state,expectedVersion);
        DevelopmentRules.require(TimelineNodeConstants.LAUNCH_PRODUCT_NODES.get(stage-1).nodeCode().equals(nodeKey),"研发阶段已变化，请重新载入");
        confirmStageEvidence(id,state,stage);workflow.save(id,state);
    }
    void requireResponsibleConfirmation(Map<String,Object> state){
        var draft=DevelopmentWorkflowStore.map(state.get("profileDraft"));
        requireProfileOwner(draft);
        DevelopmentRules.require(number(draft.get("ownerUserId"))==access.actorId(),"请由项目负责人确认立项");
    }
    Map<String,Object> prototype(long id,Map<String,Object> state){return launch.currentPrototype(id,state,this);}
    public Map<String,Object> projectLogs(long id,com.yuewei.plm.module.operationlog.dto.OperationLogQueryDTO query){
        product(id,false);long n=query.getPage(),limit=Math.min(100,query.getSize());
        String where="product_id=?";var args=new ArrayList<Object>();args.add(id);
        if(query.getAction()!=null&&!query.getAction().isBlank()){where+=" and kind=?";args.add(query.getAction().trim());}
        if(query.getCreatedFrom()!=null){where+=" and created_at>=?";args.add(query.getCreatedFrom());}
        if(query.getCreatedTo()!=null){where+=" and created_at<=?";args.add(query.getCreatedTo());}
        long total=jdbc.queryForObject("select count(*) from plm_product_development_event where "+where,Long.class,args.toArray());
        args.add(limit);args.add((n-1L)*limit);
        var rows=jdbc.queryForList("select event_id as \"eventId\",kind,data::text,created_at as \"createdAt\",created_by as \"createdBy\" from plm_product_development_event where "+where+" order by event_id desc limit ? offset ?",args.toArray());
        for(var row:rows)row.put("data",redact(readJson(row.get("data"))));
        return Map.of("content",rows,"page",n,"size",limit,"totalElements",total,"totalPages",(total+limit-1)/limit);
    }
    private void confirmStageEvidence(long id,Map<String,Object> state,int stage){
        if(stage==1){requireResponsibleConfirmation(state);confirmRequirement(id,state);}
        launch.confirmStage(id,stage,state,this);
        event(id,"handover",Map.of("phaseModel","eight_step","stage",stage,"requirementVersion",number(state.get("requirementVersion")),"roundId",number(state.get("selectedRoundId")),"basis",state.get("stage"+stage+"Basis")),null);
    }
    private void validateFinalBasis(long id,Map<String,Object> state,boolean requireFinalized){launch.validateFinal(id,state,requireFinalized,this);}
    /** Missing formal documents are gaps; replacing technical evidence still needs validation. */
    void validateReleaseVersions(Object source,List<Map<String,Object>> selected,Map<String,Object> draft){
        @SuppressWarnings("unchecked") var originals=(List<Map<String,Object>>)source;
        boolean documentationOnly=Boolean.TRUE.equals(draft.get("documentationOnly"));
        if(documentationOnly)DevelopmentRules.filled(draft.get("documentationReason"),"资料更正不影响已验证方案的说明");
        for(String group:List.of("bom","process","drawing","sop","sip")){
            var current=selected.stream().filter(r->group.equals(r.get("type"))||group.equals(r.get("category"))).toList();
            if(current.isEmpty())continue; // Kept as a visible publication gap, never claimed to be verified.
            var old=originals.stream().filter(r->group.equals(r.get("type"))||group.equals(r.get("category"))).toList();
            boolean files=!Set.of("bom","process").contains(group);
            if(files&&documentationOnly){
                var originalFamilies=old.stream().map(r->number(r.get("familyId"))).collect(java.util.stream.Collectors.toSet());
                var selectedFamilies=current.stream().map(r->number(r.get("familyId"))).collect(java.util.stream.Collectors.toSet());
                DevelopmentRules.require(originalFamilies.isEmpty()||originalFamilies.equals(selectedFamilies),"更正原图纸／生产文件时请沿用原文件版本族；方案改变需补验证");
            }else sameTechnical(old,current);
        }
    }
    List<ProductReleaseGateMissingItemVO> releaseGaps(long id,List<Map<String,Object>> selected){return launch.releaseGaps(workflow.load(id),selected);}

    void validateEngineeringFinal(long id,Map<String,Object> state,List<Map<String,Object>> references){
        Objects.requireNonNull(engineering,"EngineeringBasisService is required for final publication and launch").requireFinal(id,state,references);
    }

    private void recordSupplierChange(long id,Map<String,Object> previous,Map<String,Object> next,int stage){
        if(engineering==null)return;var evidence=engineering.supplierChangeEvidence(previous,next,stage);if(evidence.isEmpty())return;
        event(id,"supplier_change",evidence,null);audit(id,"PRODUCT_RESEARCH_SUPPLIER_CHANGE",evidence);
    }

    private Object engineeringSummary(long id,Product product,Map<String,Object> state){
        try{return redact(engineering.summary(id,state,engineeringReferences(id,product,state,null)));}
        catch(BusinessException missing){return Map.of("confirmed",!DevelopmentWorkflowStore.map(state.get("engineeringBasis")).isEmpty(),"current",false,"basis",redact(DevelopmentWorkflowStore.map(state.get("engineeringBasis"))),"checks",List.of(new DevelopmentRules.ReadinessCheck("ENGINEERING_REFERENCES","工程方案采用资料","engineering-materials",false,missing.getMessage())));}
    }

    private int engineeringStage(Product product,Map<String,Object> state){
        var revision=DevelopmentWorkflowStore.map(state.get("revision"));return revision.isEmpty()?Objects.requireNonNullElse(product.getCurrentStepNo(),1):(int)number(revision.get("stage"));
    }

    /** Reuses each stage's saved references; this command never accepts a parallel editable BOM/process selection. */
    private List<Map<String,Object>> engineeringReferences(long id,Product product,Map<String,Object> state,Map<String,Object> data){
        int stage=engineeringStage(product,state);Object references;
        if(stage==1)references=DevelopmentWorkflowStore.map(state.get("profileDraft")).get("references");
        else if(stage>=7)references=DevelopmentWorkflowStore.map(state.get("releaseDraft")).get("references");
        else{
            var draft=DevelopmentWorkflowStore.map(DevelopmentWorkflowStore.map(state.get("stageDrafts")).get(Integer.toString(stage)));
            references=draft.getOrDefault("references",DevelopmentWorkflowStore.map(state.get("profileDraft")).get("references"));
            if(stage==6){
                long round=data==null?0:number(data.get("sampleRoundId"));
                if(round==0)round=number(DevelopmentWorkflowStore.map(state.get("engineeringBasis")).get("sourceRoundId"));
                if(round==0)round=number(state.get("selectedRoundId"));
                if(round>0){var source=rawRound(id,round);DevelopmentRules.require("sample".equals(source.get("kind"))&&!LaunchReviewWorkflow.roundVoided(state,round),"请选择本项目有效的样品记录确认工程方案");references=source.get("references");}
            }
        }
        return snapshotReferences(id,refs(references));
    }

    @Transactional
    public Map<String,Object> workflowAction(long id,WorkflowInput input){
        Product p=product(id,true);access.requirePermission("documents:write");
        if(workflow.repeated(id,input.requestId()))return detail(id);
        var state=workflow.load(id);workflow.check(state,input.version());var data=new LinkedHashMap<>(input.data());String action=input.action();
        if("supplier_feedback".equals(action)){
            requireDevelopmentEditable(id);DevelopmentRules.require(isLaunch(p),"供应商反馈请从新品研发办理");int stage=engineeringStage(p,state);
            DevelopmentRules.require(number(data.get("stage"))==stage,"研发阶段已变化，请重新载入后记录供应商反馈");
            DevelopmentRules.require(!"released".equals(p.getStatus())||Boolean.TRUE.equals(DevelopmentWorkflowStore.map(state.get("revision")).get("technical")),"已发布项目记录供应商反馈请先发起技术修订");
            var evidence=Objects.requireNonNull(supplierFeedback,"SupplierFeedbackWorkflow is required for supplier feedback").prepare(id,stage,data,workflow.events(id),this);
            Map<String,Object> change=Map.of();
            if(Boolean.TRUE.equals(evidence.get("releaseCurrent"))){
                String code=text(DevelopmentWorkflowStore.map(evidence.get("supplierSnapshot")).get("supplierCode"));
                DevelopmentRules.require(code.equals(text(data.get("previousSupplierCode"))),"解除承接方必须与本次无法承接的供应商一致");
                var release=new LinkedHashMap<String,Object>();release.put("previousSupplierCode",code);release.put("supplierCode","");release.put("replacementPending",true);
                release.put("role",data.get("role"));release.put("scope",data.get("assignmentScope"));release.put("reason",evidence.get("reason"));
                change=Objects.requireNonNull(engineering,"EngineeringBasisService is required for supplier changes").changeSupplier(id,state,stage,release);
            }
            workflow.save(id,state);long feedbackEvent=event(id,action,evidence,input.requestId());audit(id,"PRODUCT_RESEARCH_SUPPLIER_FEEDBACK",evidence);
            if(!change.isEmpty()){var linkedChange=new LinkedHashMap<>(change);linkedChange.put("supplierFeedbackEventId",feedbackEvent);event(id,"supplier_change",linkedChange,null);audit(id,"PRODUCT_RESEARCH_SUPPLIER_CHANGE",linkedChange);}
            return detail(id);
        }
        if("supplier_change".equals(action)){
            requireDevelopmentEditable(id);DevelopmentRules.require(isLaunch(p),"供应商更换请从新品研发办理");int stage=engineeringStage(p,state);
            DevelopmentRules.require(number(data.get("stage"))==stage,"研发阶段已变化，请重新载入后更换供应商");
            DevelopmentRules.require(!"released".equals(p.getStatus())||Boolean.TRUE.equals(DevelopmentWorkflowStore.map(state.get("revision")).get("technical")),"已发布项目更换供应商请先发起技术修订");
            var evidence=Objects.requireNonNull(engineering,"EngineeringBasisService is required for supplier changes").changeSupplier(id,state,stage,data);
            workflow.save(id,state);event(id,action,evidence,input.requestId());audit(id,"PRODUCT_RESEARCH_SUPPLIER_CHANGE",evidence);return detail(id);
        }
        if("engineering_confirm".equals(action)){
            requireDevelopmentEditable(id);int stage=engineeringStage(p,state);
            DevelopmentRules.require(!data.containsKey("references")&&!data.containsKey("supplierAssignments"),"工程方案确认读取本阶段已保存的资料与设计阶段供方分工，请先保存草稿");
            if(data.containsKey("stage"))DevelopmentRules.require(number(data.get("stage"))==stage,"研发阶段已变化，请重新载入后确认工程方案");
            DevelopmentRules.require(stage==7,"BOM与工艺方案请在试产步骤确认");
            var policy=Objects.requireNonNull(engineering,"EngineeringBasisService is required for engineering confirmation");
            var basis=policy.confirm(id,state,engineeringReferences(id,p,state,data),text(data.get("reason")));
            basis.put("sourceStage",stage);if(stage==6&&number(data.get("sampleRoundId"))>0)basis.put("sourceRoundId",number(data.get("sampleRoundId")));
            workflow.save(id,state);event(id,action,basis,input.requestId());audit(id,"PRODUCT_RESEARCH_ENGINEERING_CONFIRM",basis);return detail(id);
        }
        if("plan_draft".equals(action)&&engineering!=null){var normalized=engineering.preparePlan(data,DevelopmentWorkflowStore.map(state.get("planDraft")));data.clear();data.putAll(normalized);}
        switch(action){
            case "confirm_requirement","confirm_design_basis" -> throw invalid("旧立项确认入口已下线，请刷新后使用八步流程");
            case "inspection","quantity_correction" -> DevelopmentRules.atStage(p.getCurrentStepNo(),state,7);
            case "release_draft","finalize" -> DevelopmentRules.atStage(p.getCurrentStepNo(),state,7);
            case "supplement","gap_plan","transfer","receipt","start_revision" -> DevelopmentRules.require(engineeringStage(p,state)>=7,"请先完成试产");
            case "select_round" -> DevelopmentRules.atStage(p.getCurrentStepNo(),state,roundStage(text(rawRound(id,number(data.get("roundId"))).get("kind")),DevelopmentWorkflowStore.map(rawRound(id,number(data.get("roundId"))).get("details"))));
        }
        if(reviews.handles(action)||unitCosts.handles(action)){
            if(unitCosts.handles(action)){
                DevelopmentRules.require(!Set.of("frozen","abandoned").contains(text(p.getLockStatus()))&&!"archived".equals(p.getStatus()),"冻结、停止或归档项目只读");
                unitCosts.action(id,state,action,data,this);
            }else reviews.action(id,state,action,data,this);
            workflow.save(id,state);event(id,action,data,input.requestId());audit(id,"PRODUCT_RESEARCH_"+action.toUpperCase(Locale.ROOT),data);return detail(id);
        }
        if(isLaunch(p)&&launch.handles(action)){
            DevelopmentRules.require(!Set.of("frozen","abandoned").contains(text(p.getLockStatus()))&&!"archived".equals(p.getStatus()),"冻结、停止或归档项目只读");
            var previous=DevelopmentWorkflowStore.map(state.get("planDraft"));launch.action(id,p,state,action,data,this);if("plan_draft".equals(action))recordSupplierChange(id,previous,data,1);
            workflow.save(id,state);event(id,action,data,input.requestId());audit(id,"PRODUCT_RESEARCH_"+action.toUpperCase(Locale.ROOT),data);return detail(id);
        }
        if(Set.of("supplement","gap_plan","transfer","receipt","start_revision").contains(action)){
            DevelopmentRules.require(!Set.of("frozen","abandoned").contains(text(p.getLockStatus()))&&!"archived".equals(p.getStatus()),"冻结、停止或归档项目只读");
            long releaseId=number(data.get("releaseId"));releaseRow(id,releaseId);
            if("start_revision".equals(action)){
                DevelopmentRules.require("released".equals(p.getStatus()),"只有已发布项目可以发起修订");DevelopmentRules.require(!hasActiveRevision(id),"已有进行中的修订，请继续办理");
                DevelopmentRules.filled(data.get("reason"),"修订原因");DevelopmentRules.filled(data.get("impact"),"影响范围");
                data.put("revisionNo",jdbc.queryForObject("select max(package_no)+1 from plm_product_development_release where product_id=?",Long.class,id));data.put("stage",Boolean.TRUE.equals(data.get("technical"))?2:7);state.put("revision",data);state.remove("finalized");
                var nextDraft=new LinkedHashMap<>(DevelopmentWorkflowStore.map(state.get("releaseDraft")));nextDraft.put("confirmationDate",null);nextDraft.put("documentationOnly",!Boolean.TRUE.equals(data.get("technical")));nextDraft.put("documentationReason",Boolean.TRUE.equals(data.get("technical"))?"":text(data.get("reason"))+"；"+text(data.get("impact")));state.put("releaseDraft",nextDraft);
            }else if("supplement".equals(action)||"gap_plan".equals(action)){
                var release=releaseRow(id,releaseId);Object gaps=readJson(release.get("missing_items"));String code=text(data.get("gapCode"));
                DevelopmentRules.require(gaps instanceof List<?> list&&list.stream().anyMatch(g->code.equals(DevelopmentWorkflowStore.map(g).get("code"))),"该缺口不属于所选正式包");
                if("supplement".equals(action)){
                    var selected=snapshotReferences(id,refs(data.get("references")));DevelopmentRules.require(!selected.isEmpty(),"请选择用于补齐的具体资料版本");
                    var old=readJson(release.get("references_json"));var merged=new ArrayList<Map<String,Object>>();if(old instanceof List<?> l)l.forEach(r->merged.add(DevelopmentWorkflowStore.map(r)));merged.addAll(selected);
                    DevelopmentRules.require(releaseGaps(id,merged).stream().noneMatch(g->code.equals(g.getCode())),"所选资料类型与待补缺口不一致");
                    for(var ref:selected)if(Set.of("bom","process").contains(text(ref.get("type"))))DevelopmentRules.require(Set.of("released","frozen","locked").contains(text(ref.get("status"))),"补齐的 BOM／工艺必须为正式状态");
                    data.put("references",selected);date(data,"actualDate");DevelopmentRules.filled(data.get("note"),"补齐说明");
                }else{date(data,"dueDate");DevelopmentRules.filled(data.get("reason"),"修改补齐计划的原因");}
            }else{
                date(data,"actualDate");DevelopmentRules.filled(data.get("recipient"),"接收方／接收人");
                if("receipt".equals(action)){
                    long transferId=number(data.get("transferEventId"));var transfer=eventData(id,transferId,"transfer");DevelopmentRules.require(number(transfer.get("releaseId"))==releaseId,"接收记录必须对应同一正式包的移交");DevelopmentRules.filled(data.get("note"),"实际接收反馈");
                }
            }
        }else{
            if("quote".equals(action))DevelopmentRules.require(!Set.of("frozen","abandoned").contains(text(p.getLockStatus()))&&!"archived".equals(p.getStatus()),"冻结、停止或归档项目只读");
            else requireDevelopmentEditable(id);
            switch(action){
                case "revision_handover" -> {
                    var revision=DevelopmentWorkflowStore.map(state.get("revision"));DevelopmentRules.require(!revision.isEmpty(),"当前没有进行中的修订");
                    int stage=(int)number(revision.get("stage"));DevelopmentRules.require(number(data.get("stage"))==stage,"修订阶段已变化，请重新载入");
                    confirmStageEvidence(id,state,stage);if(stage<8)revision.put("stage",stage+1);
                }
                case "revision_return" -> {
                    var revision=DevelopmentWorkflowStore.map(state.get("revision"));DevelopmentRules.require(!revision.isEmpty(),"当前没有进行中的修订");DevelopmentRules.filled(data.get("reason"),"退回原因");long target=number(data.get("stage"));DevelopmentRules.require(target>=1&&target<number(revision.get("stage")),"只能退回本次修订的前序阶段");var feedback=returnEvidence(id,(int)number(revision.get("stage")),(int)target,text(data.get("reason")),number(data.get("sourceRoundId")));prepareReturn(id,state,feedback);data.put("feedback",feedback);revision.put("stage",target);state.remove("finalized");
                }
                case "select_round" -> {confirmedSource(id,number(data.get("roundId")),"sample",true);state.put("selectedRoundId",data.get("roundId"));state.put("validationSampleId",data.get("roundId"));}
                case "release_draft" -> {
                    var source=launch.currentSample(id,state,this);data.put("references",snapshotReferences(id,refs(data.get("references"))));
                    if(DevelopmentRules.ids(data.get("phoneModelIds")).isEmpty())data.put("phoneModelIds",DevelopmentWorkflowStore.map(source.get("details")).get("phoneModelIds"));
                    data.put("sampleRoundId",source.get("roundId"));state.put("releaseDraft",data);state.remove("finalized");
                }
                case "finalize" -> {validateFinalBasis(id,state,false);state.put("finalized",state.get("releaseDraft"));}
                case "quote" -> {
                    access.requireFinanceWrite();DevelopmentRules.filled(data.get("supplierCode"),"供应商");DevelopmentRules.filled(data.get("currency"),"报价币种");DevelopmentRules.filled(data.get("unit"),"计价口径");
                    engineering.supplierForNewUse(text(data.get("supplierCode")));var supplier=inventorySuppliers.snapshot().suppliers().stream().filter(r->Objects.equals(r.supplierCode(),data.get("supplierCode"))).findFirst().orElseThrow(()->invalid("供应商不存在"));data.put("supplierName",supplier.supplierName());
                    try{DevelopmentRules.require(new java.math.BigDecimal(text(data.get("amount"))).signum()>=0,"报价金额不能为负数");}catch(NumberFormatException f){throw invalid("请填写有效报价金额");}
                    data.put("references",snapshotReferences(id,refs(data.get("references"))));
                    long previous=number(data.get("previousEventId"));if(previous>0)eventData(id,previous,"quote");
                }
                case "link" -> {
                    DevelopmentRules.filled(data.get("title"),"链接名称");try{var uri=java.net.URI.create(text(data.get("url")));DevelopmentRules.require(Set.of("https","http").contains(uri.getScheme())&&uri.getHost()!=null,"请输入有效的 http／https 链接");}catch(IllegalArgumentException f){throw invalid("请输入有效链接");}
                    long previous=number(data.get("previousEventId"));if(previous>0)eventData(id,previous,"link");
                }
                case "inspection","quantity_correction" -> {
                    var batch=confirmedSource(id,number(data.get("roundId")),"pilot",false);DevelopmentRules.filled(data.get("reason"),"追加／更正原因");date(data,"actualDate");
                    Map<String,Object> facts=new LinkedHashMap<>(DevelopmentWorkflowStore.map(batch.get("details")));
                    for(var old:workflow.events(id))if(Set.of("quantity_correction","inspection").contains(old.get("kind"))&&number(DevelopmentWorkflowStore.map(old.get("data")).get("roundId"))==number(data.get("roundId"))){facts=DevelopmentWorkflowStore.map(DevelopmentWorkflowStore.map(old.get("data")).get("after"));break;}
                    data.put("before",new LinkedHashMap<>(facts));
                    var updated=new LinkedHashMap<>(facts);for(String key:List.of("producedQty","inspectedQty","defectQty"))if(data.containsKey(key))updated.put(key,data.get(key));
                    if("inspection".equals(action)){
                        DevelopmentRules.filled(data.get("actualResult"),"补充检验结果");DevelopmentRules.filled(data.get("inspectionScope"),"补充检验范围");
                        DevelopmentRules.require(Set.of("additional","recheck").contains(text(data.get("inspectionMode"))),"请选择新增未检样本或原样本复检");
                        DevelopmentRules.quantities(Map.of("producedQty",facts.get("producedQty"),"inspectedQty",data.getOrDefault("inspectedQty",0),"defectQty",data.getOrDefault("defectQty",0)));
                        updated=new LinkedHashMap<>(facts);
                        if("additional".equals(data.get("inspectionMode"))){updated.put("inspectedQty",number(facts.get("inspectedQty"))+number(data.get("inspectedQty")));updated.put("defectQty",number(facts.get("defectQty"))+number(data.get("defectQty")));}
                        else DevelopmentRules.require(number(data.get("inspectedQty"))<=number(facts.get("inspectedQty")),"原样本复检数量不能超过此前检验数量");
                        data.put("references",snapshotReferences(id,refs(data.get("references"))));
                    }
                    DevelopmentRules.quantities(updated);data.put("after",updated);var corrections=new LinkedHashMap<>(DevelopmentWorkflowStore.map(state.get("pilotCorrections")));corrections.put(text(data.get("roundId")),data);state.put("pilotCorrections",corrections);state.remove("finalized");
                }
                default -> throw invalid("不支持的研发操作");
            }
        }
        workflow.save(id,state);event(id,action,data,input.requestId());audit(id,"PRODUCT_RESEARCH_"+action.toUpperCase(Locale.ROOT),data);return detail(id);
    }
    private Map<String,Object> returnEvidence(long id,int from,int target,String reason,long sourceId){
        var data=new LinkedHashMap<String,Object>();data.put("fromStage",from);data.put("targetStage",target);data.put("reason",reason);
        if(sourceId>0){var source=rawRound(id,sourceId);DevelopmentRules.require("confirmed".equals(source.get("status")),"请选择已确认的反馈来源");
            data.put("sourceRoundId",sourceId);data.put("source",source);
            if("test".equals(source.get("kind")))data.put("baseSampleRoundId",DevelopmentWorkflowStore.map(source.get("details")).get("sourceRoundId"));
            if("pilot".equals(source.get("kind"))){long testId=number(DevelopmentWorkflowStore.map(source.get("details")).get("sourceRoundId"));if(testId>0)data.put("baseSampleRoundId",DevelopmentWorkflowStore.map(rawRound(id,testId).get("details")).get("sourceRoundId"));}
        }return data;
    }
    private void carryFeedback(Map<String,Object> state,Map<String,Object> data){
        long target=number(data.get("targetStage"));
        if(data.get("source") instanceof Map<?,?> source&&Set.of("sample","test","pilot").contains(text(source.get("kind")))&&target>=1&&target<=6)state.put("feedback",data);
    }
    private void prepareReturn(long id,Map<String,Object> state,Map<String,Object> data){
        carryFeedback(state,data);int target=(int)number(data.get("targetStage"));
        var oldBasis=new LinkedHashMap<String,Object>();for(int step=target;step<=8;step++){String key="stage"+step+"Basis";if(state.containsKey(key))oldBasis.put(key,state.remove(key));}
        if(!oldBasis.isEmpty())data.put("previousStepBasis",oldBasis);state.remove("finalized");
        if(target<=3){
            long last=allRounds(id).stream().filter(r->"prototype_3d".equals(DevelopmentWorkflowStore.map(r.get("details")).get("samplePurpose"))).mapToLong(r->number(r.get("roundId"))).max().orElse(0);
            state.put("prototypeAfterRoundId",last);state.remove("prototypeRoundId");
            LaunchReviewWorkflow.records(state.get("reviewRecords")).stream().filter(r->"design".equals(r.get("gate"))&&"confirmed".equals(r.get("status"))).reduce((a,b)->b).ifPresent(review->data.put("sourceReview",review));
        }
    }
    @Transactional
    public void recordReturn(long id,int from,int target,String reason,Long sourceId){recordReturn(id,from,target,reason,sourceId,null);}
    public void recordReturn(long id,int from,int target,String reason,Long sourceId,Long expectedVersion){
        var p=requireDevelopmentEditable(id);var state=workflow.load(id);if(isLaunch(p))workflow.check(state,expectedVersion);var data=returnEvidence(id,from,target,reason,sourceId==null?0:sourceId);
        prepareReturn(id,state,data);
        workflow.save(id,state);event(id,"returned",data,null);audit(id,"PRODUCT_RESEARCH_RETURNED",data);
    }
    void date(Map<String,Object> data,String key){try{java.time.LocalDate.parse(text(data.get(key)));}catch(Exception e){throw invalid("请填写实际日期或计划日期");}}
    private Map<String,Object> releaseRow(long id,long releaseId){
        var rows=jdbc.queryForList("select missing_items::text,references_json::text,package_no from plm_product_development_release where product_id=? and release_id=?",id,releaseId);if(rows.isEmpty())throw invalid("正式包不存在或不属于本项目");return rows.get(0);
    }
    void requireMoldSupplier(Map<String,Object> state){
        var plan=DevelopmentWorkflowStore.map(state.get("planDraft"));if(plan.isEmpty())plan=DevelopmentWorkflowStore.map(state.get("plan"));
        Objects.requireNonNull(engineering,"EngineeringBasisService is required for supplier confirmation").requireMoldSupplier(plan);
    }
    public Map<String,Object> ownerOptions(){access.requireDomesticView();access.requirePermission("project:view");return Map.of("owners",jdbc.queryForList("select user_id as \"userId\",coalesce(display_name,user_name,username) as name from sys_user where deleted_flag=0 and status='active' order by user_id"),"suppliers",engineering==null?List.of():engineering.supplierOptions(),"canSelectSupplier",access.hasPermission("supplier:view"));}

    private ProductReleaseGateMissingItemVO warning(String code, String message) {
        return ProductReleaseGateMissingItemVO.builder().code(code).message(message).severity("warning").build();
    }
    private Object readJson(Object value) {
        try { return json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(Objects.toString(value, "null"), new TypeReference<Object>() {}); }
        catch (Exception failure) { throw new IllegalStateException("研发历史资料无法读取", failure); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("研发资料无法保存", failure); }
    }
    private static String text(Object value) { return Objects.toString(value, ""); }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, message); }
}

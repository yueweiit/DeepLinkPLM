package com.yuewei.plm.module.project.development;

import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.partner.SupplierProfileService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.yuewei.plm.module.project.development.DevelopmentRules.*;
import static com.yuewei.plm.module.project.development.DevelopmentWorkflowStore.*;

/** One adopted engineering basis. Existing BOM/process writers and stage reference lists own the editable facts. */
@Service
@RequiredArgsConstructor
public class EngineeringBasisService {
    private static final Set<String> ROLES = Set.of("mold", "component", "processing", "finished_product");
    private final JdbcTemplate jdbc;
    private final PilotAccessService access;
    private final SupplierProfileService suppliers;

    public List<Map<String,Object>> supplierOptions() {
        return suppliers.options();
    }

    /** Whitelist draft fields; older clients omitting the new field cannot erase an existing supply plan. */
    public Map<String,Object> preparePlan(Map<String,Object> incoming,Map<String,Object> previous) {
        var plan=new LinkedHashMap<>(incoming);
        if (!incoming.containsKey("supplierAssignments")) {
            if (previous.containsKey("supplierAssignments")) plan.put("supplierAssignments",previous.get("supplierAssignments"));
            return plan;
        }
        var rows=normalizeAssignments(incoming.get("supplierAssignments"),false);
        var old=normalizeAssignments(previous.get("supplierAssignments"),false);
        if (!rows.equals(old)) {
            if (rows.stream().anyMatch(r->"supplier".equals(r.get("mode")))||old.stream().anyMatch(r->"supplier".equals(r.get("mode")))) access.requirePermission("supplier:view");
            if (supplierReplaced(old,rows)) {filled(incoming.get("reason"),"供应商更换原因");require(text(incoming.get("reason")).length()<=2000,"供应商更换原因最多2000字");for(var row:old)if(!text(row.get("supplierCode")).isBlank())suppliers.requireSupplier(text(row.get("supplierCode")));}
            var previousCodes=old.stream().map(row->text(row.get("supplierCode"))).filter(code->!code.isBlank()).collect(java.util.stream.Collectors.toSet());
            for(String code:rows.stream().map(row->text(row.get("supplierCode"))).filter(code->!code.isBlank()).distinct().sorted().toList()){var supplier=previousCodes.contains(code)?suppliers.requireSupplier(code):suppliers.requireSupplierForNewUse(code);if(rows.stream().anyMatch(row->code.equals(row.get("supplierCode"))&&"mold".equals(row.get("role")))&&old.stream().noneMatch(row->code.equals(row.get("supplierCode"))&&"mold".equals(row.get("role"))))SupplierProfileService.requireBusinessCategory(supplier,"mold");}
        }
        plan.put("supplierAssignments",rows);
        return plan;
    }

    private boolean supplierReplaced(List<Map<String,Object>> before,List<Map<String,Object>> after) {
        return before.stream().filter(row->"supplier".equals(row.get("mode"))&&!text(row.get("supplierCode")).isBlank()).anyMatch(row->after.stream().noneMatch(next->"supplier".equals(next.get("mode"))&&Objects.equals(row.get("supplierCode"),next.get("supplierCode"))&&(text(row.get("scope")).isBlank()||Objects.equals(row.get("role"),next.get("role"))&&Objects.equals(row.get("scope"),next.get("scope")))));
    }

    /** The original workflow event stores both supplier snapshots; plan drafts remain the sole editable supply plan. */
    public Map<String,Object> supplierChangeEvidence(Map<String,Object> previous,Map<String,Object> next,int stage) {
        var before=normalizeAssignments(previous.get("supplierAssignments"),false);var after=normalizeAssignments(next.get("supplierAssignments"),false);
        if(!supplierReplaced(before,after))return Map.of();
        filled(next.get("reason"),"供应商更换原因");
        return Map.of("stage",stage,"reason",text(next.get("reason")),"before",Map.of("supplierAssignments",supplierSnapshots(before)),"after",Map.of("supplierAssignments",supplierSnapshots(after)));
    }

    public Map<String,Object> changeSupplier(long productId,Map<String,Object> state,int stage,Map<String,Object> input) {
        access.requirePermission("documents:write");access.requireEngineeringMaintenance();access.requirePermission("supplier:view");access.requireProduct(productId);
        String reason=text(input.get("reason"));filled(reason,"供应商更换原因");require(reason.length()<=2000,"供应商更换原因最多2000字");
        String previousCode=text(input.get("previousSupplierCode")),code=text(input.get("supplierCode"));
        boolean pending=Boolean.TRUE.equals(input.get("replacementPending"));
        if(pending)require(code.isBlank(),"下一家待定时请勿同时选择新供应商");
        else {filled(code,"新供应商");require(!code.equals(previousCode),"请选择与原供方不同的新供应商");}
        var plan=currentPlan(state);var before=normalizeAssignments(plan.get("supplierAssignments"),false);var matches=before.stream().filter(row->supplierTarget(row,input)).toList();
        require(matches.size()==1,"原供应商分工已变化或不唯一，请刷新后核对职责和范围");
        var previous=matches.get(0);var oldSnapshot=supplierSnapshots(List.of(previous));
        var replacement=new LinkedHashMap<>(previous);
        if(!pending){
            var supplier=suppliers.requireSupplierForNewUse(code);if("mold".equals(previous.get("role")))SupplierProfileService.requireBusinessCategory(supplier,"mold");require(!"inactive".equals(supplier.get("status"))&&!Boolean.TRUE.equals(supplier.get("needsReview")),"新供应商已停用或身份待核对，请先补齐供应商资料");
            replacement.put("supplierCode",code);replacement.put("basis",text(input.get("basis")));require(text(replacement.get("basis")).length()<=2000,"采用依据最多2000字");
        }
        var after=before.stream().filter(row->!pending||row!=previous).map(row->row==previous?replacement:row).toList();
        var draft=new LinkedHashMap<>(plan);draft.put("supplierAssignments",after);var newSnapshot=pending?List.<Map<String,Object>>of():supplierSnapshots(List.of(replacement));Map<String,Object> confirmed=null;
        if(stage>1){
            confirmed=new LinkedHashMap<>(map(state.get("plan")));var confirmedRows=normalizeAssignments(confirmed.get("supplierAssignments"),false);
            // Older projects could confirm mold design while retaining their first supplier only in planDraft.
            // The persisted draft remains the editable authority; conflicting nonempty confirmed plans still need review.
            if(confirmedRows.isEmpty()&&!before.isEmpty())confirmedRows=before;
            require(confirmedRows.stream().filter(row->supplierTarget(row,input)).count()==1,"开发计划与供方草稿不一致，请先核对原分工");
            confirmed.put("supplierAssignments",confirmedRows.stream().filter(row->!pending||!supplierTarget(row,input)).map(row->supplierTarget(row,input)?replacement:row).toList());
        }
        state.put("planDraft",draft);if(confirmed!=null)state.put("plan",confirmed);
        state.remove("finalized");
        return Map.of("stage",stage,"reason",reason,"replacementPending",pending,"before",Map.of("supplierAssignments",oldSnapshot),"after",Map.of("supplierAssignments",newSnapshot));
    }

    private boolean supplierTarget(Map<String,Object> row,Map<String,Object> input) {
        return "supplier".equals(row.get("mode"))&&!text(input.get("previousSupplierCode")).isBlank()&&text(input.get("previousSupplierCode")).equals(row.get("supplierCode"))&&text(input.get("role")).equals(row.get("role"))&&text(input.get("scope")).equals(row.get("scope"));
    }

    /** Confirmation timestamps/version stamps do not make an unchanged supply plan a new draft. */
    public boolean supplierPlanConfirmed(Map<String,Object> state) {
        var confirmed=map(state.get("plan"));var current=currentPlan(state);
        return !confirmed.isEmpty()&&Objects.equals(confirmed.get("method"),current.get("method"))&&Objects.equals(confirmed.get("needsMold"),current.get("needsMold"))
            &&new HashSet<>(normalizeAssignments(confirmed.get("supplierAssignments"),false)).equals(new HashSet<>(normalizeAssignments(current.get("supplierAssignments"),false)));
    }

    /** Shared readable identity snapshots for engineering history and LEMOS draft provenance. */
    public List<Map<String,Object>> supplierSnapshots(List<Map<String,Object>> rows) {
        return rows.stream().map(row->{var snapshot=new LinkedHashMap<>(row);if("supplier".equals(row.get("mode"))&&!text(row.get("supplierCode")).isBlank()){access.requirePermission("supplier:view");var supplier=suppliers.requireSupplier(text(row.get("supplierCode")));for(String key:List.of("supplierId","supplierName","version"))snapshot.put(key,supplier.get(key));}return (Map<String,Object>)snapshot;}).toList();
    }

    public Map<String,Object> confirm(long productId,Map<String,Object> state,List<Map<String,Object>> references,String reason) {
        access.requirePermission("documents:write");access.requireEngineeringMaintenance();access.requireProduct(productId);
        var plan=currentPlan(state);
        validatePlan(plan);
        var assignments=confirmedAssignments(plan);
        validateTechnical(productId,plan,references,false);
        var previous=map(state.get("engineeringBasis"));
        var identity=identity(plan,references);
        if (!previous.isEmpty() && !same(previous.get("identity"),identity)) filled(reason,"工程方案变更原因");
        var basis=new LinkedHashMap<String,Object>();
        basis.put("revision",number(previous.get("revision"))+1);
        basis.put("confirmedAt",LocalDateTime.now().toString());basis.put("confirmedBy",access.actor().displayName());
        basis.put("planVersion",number(map(state.get("plan")).get("planVersion")));
        basis.put("method",text(plan.get("method")));basis.put("needsMold",plan.get("needsMold"));
        basis.put("supplierAssignments",assignments);basis.put("references",technical(references));
        basis.put("identity",identity);basis.put("reason",text(reason));
        state.put("engineeringBasis",basis);state.remove("finalized");
        return basis;
    }

    public Map<String,Object> summary(long productId,Map<String,Object> state,List<Map<String,Object>> references) {
        var plan=currentPlan(state);var basis=map(state.get("engineeringBasis"));
        var checks=List.of(
            check("ENGINEERING_PLAN","开发方式与是否需要开模","engineering-suppliers",()->validatePlan(plan)),
            check("ENGINEERING_SUPPLIERS","供应商与内部制作分工","engineering-suppliers",()->confirmedAssignments(plan)),
            check("ENGINEERING_TECHNICAL","采用的BOM与工艺具体版本","engineering-materials",()->validateTechnical(productId,plan,references,false)),
            check("ENGINEERING_CONFIRMATION","当前工程方案确认","engineering-confirm",()->requireCurrent(state,references))
        );
        return Map.of("confirmed",!basis.isEmpty(),"current",checks.stream().allMatch(ReadinessCheck::complete),"basis",redact(basis),"checks",checks);
    }

    /** Final publication and launch must validate the same adopted supply plan and the actual formal BOM/route pair. */
    public void requireFinal(long productId,Map<String,Object> state,List<Map<String,Object>> references) {
        access.requireProduct(productId);
        var plan=currentPlan(state);
        validatePlan(plan);
        confirmedAssignments(plan);requireCurrent(state,references);validateTechnical(productId,plan,references,true);
        if (direct(plan)) {
            var costId=number(map(state.get("releaseDraft")).get("unitCostVersionId"));
            var cost=records(state.get("unitCostVersions")).stream().filter(c->number(c.get("id"))==costId).findFirst().orElse(Map.of());
            if ("supplier_quote".equals(cost.get("basisType"))) {
                String code=text(map(cost.get("adoptedQuote")).get("supplierCode"));
                require(normalizeAssignments(plan.get("supplierAssignments"),true).stream().anyMatch(r->"supplier".equals(r.get("mode"))&&"finished_product".equals(r.get("role"))&&code.equals(r.get("supplierCode"))),"采购成品成本报价的供应商与已确认成品供方不一致，请核对工程方案与成本版本");
            }
        }
    }

    private void requireCurrent(Map<String,Object> state,List<Map<String,Object>> references) {
        var basis=map(state.get("engineeringBasis"));
        require(!basis.isEmpty(),"请在供应商与工程方案区域确认本产品采用的方案");
        require(same(basis.get("identity"),identity(currentPlan(state),references)),"供应商分工或采用的BOM／工艺版本已变化，请重新确认工程方案");
    }

    static Map<String,Object> currentPlan(Map<String,Object> state) {
        var draft=map(state.get("planDraft"));return draft.isEmpty()?map(state.get("plan")):draft;
    }

    private Map<String,Object> identity(Map<String,Object> plan,List<Map<String,Object>> references) {
        var value=new LinkedHashMap<String,Object>();
        value.put("method",text(plan.get("method")));value.put("needsMold",plan.get("needsMold"));
        value.put("phoneModelIds",ids(plan.get("phoneModelIds")));value.put("supplierAssignments",normalizeAssignments(plan.get("supplierAssignments"),false));
        value.put("references",technical(references).stream().map(r->{
            var ref=new LinkedHashMap<String,Object>();ref.put("type",r.get("type"));ref.put("id",number(r.get("id")));
            ref.put("versionNo",r.get("versionNo"));ref.put("content",technicalContent(r.get("content")));return ref;
        }).toList());return value;
    }

    public Map<String,Object> supplierForNewUse(String code){return suppliers.requireSupplierForNewUse(code);}
    public void requireMoldSupplier(Map<String,Object> plan){
        var rows=normalizeAssignments(plan.get("supplierAssignments"),false).stream().filter(row->"supplier".equals(row.get("mode"))&&"mold".equals(row.get("role"))).toList();
        require(!rows.isEmpty(),"请选择开模供应商");for(var row:rows)SupplierProfileService.requireBusinessCategory(requireActiveSupplier(text(row.get("supplierCode"))),"mold");
    }
    private Map<String,Object> requireActiveSupplier(String code){
        access.requirePermission("supplier:view");var supplier=suppliers.requireSupplier(code);
        require("active".equals(supplier.get("status"))&&!Boolean.TRUE.equals(supplier.get("needsReview")),"供应商尚未启用或身份待核对，请在供应商资料中补齐后重试");return supplier;
    }
    private List<Map<String,Object>> confirmedAssignments(Map<String,Object> plan) {
        var rows=normalizeAssignments(plan.get("supplierAssignments"),true);
        require(!rows.isEmpty(),"请登记供应商分工或明确内部制作方；未确定时可保存草稿");
        var result=new ArrayList<Map<String,Object>>();
        for (var row:rows) {
            var snapshot=new LinkedHashMap<>(row);
            if ("supplier".equals(row.get("mode"))) {
                var supplier=requireActiveSupplier(text(row.get("supplierCode")));
                if("mold".equals(row.get("role")))SupplierProfileService.requireBusinessCategory(supplier,"mold");
                for (String key:List.of("supplierId","supplierName","version")) snapshot.put(key,supplier.get(key));
            }
            result.add(snapshot);
        }
        if (direct(plan)) require(rows.stream().anyMatch(r->"supplier".equals(r.get("mode"))&&"finished_product".equals(r.get("role"))),"直接采购成品请明确成品供货供应商");
        return result;
    }

    private List<Map<String,Object>> normalizeAssignments(Object value,boolean complete) {
        if (value==null) return List.of();
        require(value instanceof List<?>,"供应商分工格式无效");var source=(List<?>)value;require(source.size()<=50,"供应商分工最多50项");
        var result=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();
        for (Object raw:source) {
            require(raw instanceof Map<?,?>,"供应商分工格式无效");var row=map(raw);var clean=new LinkedHashMap<String,Object>();
            for (String key:List.of("mode","supplierCode","internalName","role","scope","basis")) {
                String text=text(row.get(key));require(text.length()<=2000,"供应商分工内容过长");clean.put(key,text);
            }
            String mode=text(clean.get("mode")),role=text(clean.get("role"));
            require(mode.isBlank()||Set.of("supplier","internal").contains(mode),"供应商分工来源类型无效");
            require(role.isBlank()||ROLES.contains(role),"供应商分工职责无效");
            if ("internal".equals(mode)) clean.put("supplierCode","");else clean.put("internalName","");
            if (complete) {
                filled(mode,"供应商或内部制作方式");filled(role,"供方职责");filled(clean.get("scope"),"供货／制作范围");filled(clean.get("basis"),"供方采用依据");
                filled(clean.get("supplier".equals(mode)?"supplierCode":"internalName"),"supplier".equals(mode)?"供应商":"内部制作方");
                require(seen.add(mode+":"+clean.get("supplierCode")+":"+clean.get("internalName")+":"+role+":"+clean.get("scope")),"同一供方职责和范围不能重复登记");
            }
            result.add(clean);
        }
        result.sort(Comparator.comparing(r->text(r.get("role"))+"|"+r.get("scope")+"|"+r.get("mode")+"|"+r.get("supplierCode")+"|"+r.get("internalName")));
        return result;
    }

    private void validateTechnical(long productId,Map<String,Object> plan,List<Map<String,Object>> references,boolean formal) {
        var technical=technical(references);var boms=technical.stream().filter(r->"bom".equals(r.get("type"))).toList();var processes=technical.stream().filter(r->"process".equals(r.get("type"))).toList();
        require(boms.size()<=1&&processes.size()<=1,"BOM与工艺各只能采用一个具体版本，请在资料区核对");
        if (direct(plan)&&technical.isEmpty()) return;
        require(boms.size()==1&&processes.size()==1,"请在供应商与工程方案区域选择本产品采用的BOM及工艺具体版本");
        long bomId=number(boms.get(0).get("id")),processId=number(processes.get(0).get("id"));
        var matches=jdbc.queryForList("""
            select b.status as "bomStatus",b.bom_type as "bomType",p.status as "processStatus",
              exists(select 1 from plm_product_bom_route_formal_selection s
                where s.product_id=? and s.product_bom_id=b.product_bom_id and s.product_bom_route_id=r.product_bom_route_id
                  and s.process_id=p.process_id and s.status='active' and s.deleted_flag=0) as "currentFormal"
            from plm_product_bom b join plm_product_bom_route r on r.product_bom_id=b.product_bom_id and r.status='active' and r.deleted_flag=0
              join plm_process p on p.process_id=r.process_id and p.product_id=b.product_id and p.process_type='routing' and p.deleted_flag=0
            where b.product_id=? and b.product_bom_id=? and p.process_id=? and b.deleted_flag=0
            """,productId,productId,bomId,processId);
        require(!matches.isEmpty(),"所选BOM与工艺未关联同一产品方案，请在BOM维护中关联对应工艺路线");
        require(matches.stream().anyMatch(r->!"archived".equals(r.get("bomStatus"))&&!"archived".equals(r.get("processStatus"))),"采用的BOM或工艺已归档，请重新选择有效版本");
        if (formal) require(matches.stream().anyMatch(r->"released".equals(r.get("bomStatus"))&&!"test".equals(r.get("bomType"))&&"locked".equals(r.get("processStatus"))&&Boolean.TRUE.equals(r.get("currentFormal"))),"上市与正式定型须采用当前工程确认的正式BOM及配套锁定工艺，请先在BOM维护中确认发布");
    }

    private boolean direct(Map<String,Object> plan) {return "direct".equals(plan.get("method"))&&Boolean.FALSE.equals(plan.get("needsMold"));}
    private void validatePlan(Map<String,Object> plan){
        require(Set.of("own_mold","supplier","direct").contains(text(plan.get("method"))),"请先明确本产品开发方式");
        require(plan.get("needsMold") instanceof Boolean,"请先明确是否需要开模");
        if("own_mold".equals(plan.get("method")))require(Boolean.TRUE.equals(plan.get("needsMold")),"自主开模需要登记模具开发");
    }
    private List<Map<String,Object>> technical(List<Map<String,Object>> references) {
        return references.stream().filter(r->Set.of("bom","process").contains(text(r.get("type")))).sorted(Comparator.comparing(r->text(r.get("type")))).toList();
    }
    private boolean same(Object left,Object right) {return Objects.equals(technicalContent(left),technicalContent(right));}
    private List<Map<String,Object>> records(Object value) {return value instanceof List<?> list?list.stream().map(DevelopmentWorkflowStore::map).toList():List.of();}

    /** Shared with design/test provenance: lifecycle stamps and price updates are not technical scheme changes. */
    static Object technicalContent(Object value){
        if(value instanceof Map<?,?> fields&&(fields.containsKey("engineeringConfirmed")||fields.containsKey("bom_scope"))){var technical=new LinkedHashMap<>(fields);technical.remove("engineeringConfirmed");technical.remove("bom_scope");return technicalContent(technical);}
        if(value instanceof Map<?,?> map){var clean=new TreeMap<String,Object>();map.forEach((k,v)->{if(!k.toString().toLowerCase(Locale.ROOT).matches(".*(cost|price|amount).*")&&!Set.of("currency","currency_code","created_at","updated_at","created_by","updated_by","status","frozen_flag","frozen_at","frozen_by","locked_at","locked_by","attachment","name","checksum","confirmed_at","confirmed_by","released_at","released_by").contains(k.toString()))clean.put(k.toString(),technicalContent(v));});return clean;}
        if(value instanceof List<?> list)return list.stream().map(EngineeringBasisService::technicalContent).toList();if(value instanceof Number n)return new java.math.BigDecimal(n.toString()).stripTrailingZeros();return value;
    }

    /** Supply-plan evidence follows supplier object permissions even when included in a project or release snapshot. */
    public Object redact(Object value) {
        return redact(value,new HashMap<>());
    }
    private Object redact(Object value,Map<String,Boolean> authorized) {
        if (value instanceof List<?> list) return list.stream().map(item->redact(item,authorized)).toList();
        if (!(value instanceof Map<?,?> source)) return value;
        var result=new LinkedHashMap<String,Object>();
        for (var entry:source.entrySet()) {
            String key=entry.getKey().toString();
            if (!"supplierAssignments".equals(key)) {result.put(key,redact(entry.getValue(),authorized));continue;}
            var visible=new ArrayList<Map<String,Object>>();boolean restricted=false;
            for (var assignment:records(entry.getValue())) {
                String code=text(assignment.get("supplierCode"));
                if ("internal".equals(assignment.get("mode"))||code.isBlank()) {visible.add(assignment);continue;}
                boolean allowed=authorized.computeIfAbsent(code,c->{try {access.requirePermission("supplier:view");suppliers.requireSupplier(c);return true;}catch (BusinessException denied) {return false;}});
                if(allowed)visible.add(assignment);else restricted=true;
            }
            result.put(key,visible);if (restricted) result.put("suppliersRestricted",true);
        }
        return result;
    }
}

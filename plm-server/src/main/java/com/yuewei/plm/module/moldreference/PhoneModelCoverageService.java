package com.yuewei.plm.module.moldreference;

import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Projection only: approval, production intent and verified physical compatibility stay distinct. */
@Service @RequiredArgsConstructor
public class PhoneModelCoverageService {
    private final JdbcTemplate jdbc;
    private final PilotAccessService access;
    public static final List<String> STATES=List.of("uncovered","catalogued","draft","applying","planned","making","partial","confirmed","reusable","paused","terminated","needs_review");
    private static final List<String> PRIORITY=List.of("reusable","making","partial","confirmed","planned","applying","draft","catalogued","needs_review","paused","terminated","uncovered");
    @SuppressWarnings("unchecked")
    public Map<Long,List<Map<String,Object>>> batch(List<Long> ids) {
        access.requireDomesticView();access.requirePermission("collaboration:view");if(ids.isEmpty())return Map.of();
        var safe=ids.stream().distinct().sorted().toList();String placeholders=String.join(",",Collections.nCopies(safe.size(),"?"));
        var grouped=new LinkedHashMap<Long,Map<Long,Map<String,Object>>>();
        if(access.hasPermission("product:view"))for(var product:PhoneProductEvidence.rows(jdbc,safe,access.visibleSqlClause("product","p.product_id"))) {
            var group=group(grouped,id(product.get("phoneModelId")),nullableId(product.get("productLineId")),Objects.toString(product.get("productLineName")));
            ((List<Map<String,Object>>)group.get("products")).add(product);addState(group,"catalogued");
        }
        var actor=access.access();String appScope=actor.allObjects()?"true":"(a.applicant_id="+actor.actor().userId()+" or (a.item_id is not null and "+access.visibleSqlClause("item","a.item_id")+"))";
        var applications=jdbc.queryForList("""
            select a.phone_model_id as "phoneModelId",a.application_id as "applicationId",a.product_line_id as "productLineId",
              coalesce(p.product_name,a.tipo,'产品待完善') as "productLineName",a.tipo,a.model,a.status,%s as "businessStatus",a.external_instance_id as "instanceId",a.approval_granted as "approvalGranted",
              a.remote_status as "remoteStatus",a.remote_result as "remoteResult",a.execution_intent as "executionIntent",
              a.remote_status='RUNNING' as "approvalRunning",coalesce(a.execution_resolution_json->'historicalAssociation'->>'associationOnly','false')='true' as "historicalAssociationOnly",
              a.execution_status as "executionStatus",a.provisioning_status as "provisioningStatus",a.provisioning_error as "provisioningError",
              a.form_data->>'phonePrice' as "historicalPrice",a.form_data->>'priceCurrency' as "historicalPriceCurrency",a.form_data->>'website' as "referenceUrl",a.source_created_at as "sourceCreatedAt",a.project_id as "projectId",a.item_id as "itemId",a.mold_set_id as "moldSetId",a.updated_at as "updatedAt"
            from plm_mold_application a left join plm_product p on p.product_id=a.product_line_id and p.deleted_flag=0
            where a.deleted_flag=0 and a.phone_model_id in (%s) and (%s)
            order by a.updated_at desc,a.application_id desc
            """.formatted(com.yuewei.plm.module.moldapplication.MoldApplicationBusinessStatus.SQL,placeholders,appScope),safe.toArray());
        for(var app:applications) {
            long phone=id(app.get("phoneModelId"));Long line=nullableId(app.get("productLineId"));
            String state=applicationState(app);app.put("coverageState",state);app.remove("remoteStatus");app.remove("remoteResult");
            app.put("reviewReasons","blocked".equals(app.get("provisioningStatus"))?List.of(MoldCompatibilityPolicy.reason("provisioning_blocked",Objects.toString(app.get("provisioningError"),"自动建档目标或资料冲突，需核对后重试"),"correct_provisioning","provisioningStatus")):List.of());
            var group=group(grouped,phone,line,Objects.toString(app.get("productLineName")));((List<Map<String,Object>>)group.get("applications")).add(app);addState(group,state);
        }
        // Legacy project links identify an archive, but do not assert which physical set fits a phone.
        String sql="""
            with links as (
              select cm.phone_model_id,cm.item_id,cm.mold_set_id,true verified,cm.pc_confirmed,cm.tpu_confirmed,concat_ws('；',cm.pc_evidence,cm.tpu_evidence) evidence,cm.pc_evidence,cm.tpu_evidence,null::bigint project_id
                from plm_collaboration_phone_set_compatibility cm where cm.phone_model_id in (%s)
              union all
              select a.phone_model_id,a.item_id,a.mold_set_id,false,false,false,null,null,null,a.project_id
                from plm_mold_application a where a.deleted_flag=0 and a.phone_model_id in (%s) and a.item_id is not null and a.mold_set_id is not null and (%s)
              union all
              select p.phone_model_member_id,ip.item_id,null::bigint,false,false,false,null,null,null,p.product_id
                from plm_product p join plm_collaboration_item_project ip on ip.project_id=p.product_id
                where p.deleted_flag=0 and p.phone_model_member_id in (%s) and (%s)
              union all
              select source_phone.phone_model_id,ip.item_id,null::bigint,false,false,false,null,null,null,p.product_id
                from plm_product p join plm_product_phone_model source_phone on source_phone.product_id=p.product_id
                  join plm_collaboration_item_project ip on ip.project_id=p.product_id
                where p.deleted_flag=0 and source_phone.phone_model_id in (%s) and (%s) and __RELATION__
              union all
              select pm.id,i.item_id,null::bigint,false,false,false,null,null,null,null::bigint
                from plm_collaboration_item i join plm_phone_model_member pm on pm.code_item_id=i.phone_model_code_item_id
                where i.deleted_flag=0 and pm.id in (%s) and not exists(select 1 from plm_phone_model_member other where other.code_item_id=pm.code_item_id and other.id<>pm.id)
              union all
              select cm.phone_model_id,cm.item_id,null::bigint,false,false,false,null,null,null,null::bigint
                from plm_collaboration_phone_member cm where cm.phone_model_id in (%s)
            )
            select l.phone_model_id as "phoneModelId",i.item_id as "itemId",i.product_line_id as "productLineId",
              (select count(*) from plm_collaboration_mold_set registered where registered.item_id=i.item_id and registered.deleted_flag=0 and (%s)) as "registeredSets",
              coalesce(line.product_name,i.style_name,'历史产品') as "productLineName",i.mold_name as "moldName",i.style_name as "styleName",i.reference_url as "referenceUrl",i.engraved_code as "engravedCode",
              s.mold_set_id as "moldSetId",s.set_no as "setNo",case when s.mold_set_id is null then null else 'MS-'||lpad(s.mold_set_id::text,greatest(6,length(s.mold_set_id::text)),'0') end as "physicalCode",
              l.verified as "compatibilityConfirmed",l.pc_confirmed as "pcConfirmed",l.tpu_confirmed as "tpuConfirmed",l.evidence,l.pc_evidence as "pcEvidence",l.tpu_evidence as "tpuEvidence",l.project_id as "projectId",
              coalesce(pc.original_mold_code,pcCode.mold_code) as "pcCode",coalesce(tpu.original_mold_code,tpuCode.mold_code) as "tpuCode",
              pc.current_location as "pcLocation",tpu.current_location as "tpuLocation",pc.location_detail as "pcLocationDetail",tpu.location_detail as "tpuLocationDetail",
              pc.physical_status as "pcPhysicalStatus",tpu.physical_status as "tpuPhysicalStatus",pc.status as "pcStatus",tpu.status as "tpuStatus",
              pc.factory_arrival_date as "pcArrival",tpu.factory_arrival_date as "tpuArrival",
              pc.acceptance_status as "pcAcceptanceStatus",tpu.acceptance_status as "tpuAcceptanceStatus",
              pc.acceptance_date as "pcAcceptanceDate",tpu.acceptance_date as "tpuAcceptanceDate",
              coalesce(s.forecast_delivery_date,s.planned_mold_delivery_date) as "plannedDeliveryDate",s.opened_date as "openedDate",s.actual_ship_date as "actualShipDate",s.actual_mexico_arrival_date as "actualMexicoArrivalDate",
              pm.status as "phoneStatus",b.status as "brandStatus",c.status as "codeStatus",c.deleted_flag as "codeDeletedFlag",pm.code_item_id as "codeItemId"
            from links l join plm_collaboration_item i on i.item_id=l.item_id and i.deleted_flag=0
              left join plm_product line on line.product_id=i.product_line_id and line.deleted_flag=0
              left join plm_collaboration_mold_set s on s.mold_set_id=l.mold_set_id and s.item_id=i.item_id and s.deleted_flag=0
              left join plm_collaboration_mold_part pc on pc.mold_set_id=s.mold_set_id and pc.part_type='PC'
              left join plm_collaboration_mold_part tpu on tpu.mold_set_id=s.mold_set_id and tpu.part_type='TPU'
              left join plm_product_mold_code pcCode on pcCode.product_mold_code_id=pc.product_mold_code_id and pcCode.deleted_flag=0
              left join plm_product_mold_code tpuCode on tpuCode.product_mold_code_id=tpu.product_mold_code_id and tpuCode.deleted_flag=0
              join plm_phone_model_member pm on pm.id=l.phone_model_id join plm_phone_brand b on b.id=pm.brand_id
              left join plm_code_item c on c.code_item_id=pm.code_item_id
            where (%s) and (l.mold_set_id is null or (s.mold_set_id is not null and (%s)))
            order by i.item_id,s.set_no nulls last,l.verified desc
            """.formatted(placeholders,placeholders,appScope,placeholders,access.visibleSqlClause("product","p.product_id"),placeholders,access.visibleSqlClause("product","p.product_id"),placeholders,placeholders,access.visibleSqlClause("mold","registered.mold_set_id"),access.visibleSqlClause("item","i.item_id"),access.visibleSqlClause("mold","s.mold_set_id"));
        sql=sql.replace("__RELATION__",PhoneProductEvidence.memberRelationSql("p","source_phone.phone_model_id"));
        var args=new ArrayList<Object>();for(int i=0;i<6;i++)args.addAll(safe);
        var molds=jdbc.queryForList(sql,args.toArray());var seen=new HashSet<String>();
        // Prefer verified links over production-intent links to the same real set.
        molds.sort(Comparator.<Map<String,Object>,Boolean>comparing(m->Boolean.TRUE.equals(m.get("compatibilityConfirmed"))).reversed());
        for(var mold:molds) {
            long phone=id(mold.get("phoneModelId"));Long line=nullableId(mold.get("productLineId"));
            String key=phone+":"+mold.get("itemId")+":"+mold.get("moldSetId");if(!seen.add(key))continue;
            if(mold.get("moldSetId")==null&&molds.stream().anyMatch(m->id(m.get("phoneModelId"))==phone&&Objects.equals(m.get("itemId"),mold.get("itemId"))&&m.get("moldSetId")!=null))continue;
            String state=moldState(mold);mold.put("state",state);mold.put("reusable","reusable".equals(state));mold.put("pcState",partState(mold,"pc"));mold.put("tpuState",partState(mold,"tpu"));
            mold.put("hasRegisteredSets",((Number)mold.get("registeredSets")).longValue()>0);mold.put("reviewReasons",MoldCompatibilityPolicy.reviewReasons(mold));
            for(String prefix:List.of("pc","tpu"))for(String suffix:List.of("PhysicalStatus","Status","Arrival","AcceptanceStatus","AcceptanceDate"))mold.remove(prefix+suffix);
            for(String field:List.of("phoneStatus","brandStatus","codeStatus","codeDeletedFlag","codeItemId"))mold.remove(field);
            var group=group(grouped,phone,line,Objects.toString(mold.get("productLineName")));((List<Map<String,Object>>)group.get("molds")).add(mold);addState(group,state);
        }
        var result=new LinkedHashMap<Long,List<Map<String,Object>>>();
        grouped.forEach((phone,lines)->{var groups=new ArrayList<>(lines.values());for(var group:groups){var states=(List<String>)group.get("states");group.put("state",primaryState(states));
            var archives=new HashMap<Long,Long>();for(var mold:(List<Map<String,Object>>)group.get("molds"))archives.put(id(mold.get("itemId")),((Number)mold.get("registeredSets")).longValue());long count=archives.values().stream().mapToLong(Long::longValue).sum();group.put("registeredSets",count);group.put("hasRegisteredSets",count>0);
            var reasons=new LinkedHashSet<Map<String,Object>>();for(String kind:List.of("molds","applications"))for(var record:(List<Map<String,Object>>)group.get(kind))reasons.addAll((List<Map<String,Object>>)record.get("reviewReasons"));group.put("reviewReasons",List.copyOf(reasons));
        }groups.sort(Comparator.comparing(g->Objects.toString(g.get("productLineName"))));result.put(phone,groups);});return result;
    }
    private static Map<String,Object> group(Map<Long,Map<Long,Map<String,Object>>> map,long phone,Long line,String name) {
        return map.computeIfAbsent(phone,k->new LinkedHashMap<>()).computeIfAbsent(line,k->{var group=new LinkedHashMap<String,Object>();group.put("productLineId",line);group.put("productLineName",name);group.put("states",new ArrayList<String>());group.put("products",new ArrayList<Map<String,Object>>());group.put("applications",new ArrayList<Map<String,Object>>());group.put("molds",new ArrayList<Map<String,Object>>());return group;});
    }
    @SuppressWarnings("unchecked") private static void addState(Map<String,Object> group,String state) {var values=(List<String>)group.get("states");if(!values.contains(state))values.add(state);}
    public static String primaryState(List<String> states) {return PRIORITY.stream().filter(states::contains).findFirst().orElse("uncovered");}
    public static String applicationState(Map<String,Object> app) {
        if(Set.of("rejected","withdrawn","terminated").contains(Objects.toString(app.get("businessStatus"),"")))return "terminated";
        if("paused".equals(app.get("executionStatus")))return "paused";
        if("terminated".equals(app.get("executionStatus"))||"TERMINATED".equals(app.get("remoteStatus"))||"terminated".equals(app.get("status"))||"refuse".equalsIgnoreCase(Objects.toString(app.get("remoteResult"),"")))return "terminated";
        if("draft".equals(app.get("status")))return "draft";
        if("blocked".equals(app.get("provisioningStatus")))return "needs_review";
        if(Boolean.TRUE.equals(app.get("approvalGranted")))return "planned";
        return "applying";
    }
    public static String partState(Map<String,Object> row,String prefix) {return MoldCompatibilityPolicy.partState(row,prefix);}
    public static String moldState(Map<String,Object> mold) {
        if(mold.get("moldSetId")==null)return "needs_review";
        if(MoldCompatibilityPolicy.reusable(mold))return "reusable";
        // Partial means engineering fit, not that only one physical part has arrived.
        if(Boolean.TRUE.equals(mold.get("pcConfirmed"))!=Boolean.TRUE.equals(mold.get("tpuConfirmed")))return "partial";
        if("confirmed".equals(partState(mold,"pc"))||"confirmed".equals(partState(mold,"tpu")))return "confirmed";
        if("making".equals(partState(mold,"pc"))||"making".equals(partState(mold,"tpu"))||mold.get("openedDate")!=null)return "making";
        return "planned";
    }
    private static long id(Object value) {return ((Number)value).longValue();}
    private static Long nullableId(Object value) {return value==null?null:id(value);}
}

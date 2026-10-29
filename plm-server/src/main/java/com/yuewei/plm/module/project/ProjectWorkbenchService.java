package com.yuewei.plm.module.project;

import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.attachment.service.DocumentService;
import com.yuewei.plm.module.collaboration.*;
import com.yuewei.plm.module.development.*;
import com.yuewei.plm.module.moldapplication.MoldApplicationService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.project.service.TimelineDefinitionProvider;
import com.yuewei.plm.module.project.constant.TimelineNodeConstants.TimelineNodeDefinition;
import com.yuewei.plm.repository.entity.Product;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Permission-scoped read model of existing business records; all writes stay in their modules. */
@Service @RequiredArgsConstructor
public class ProjectWorkbenchService {
    private final JdbcTemplate jdbc;
    private final PilotAccessService access;
    private final MoldApplicationService applications;
    private final MoldScheduleService schedules;
    private final TimelineDefinitionProvider definitions;
    private final DocumentService documents;
    private final DevelopmentFinanceService finance;

    @Data public static class Query {
        @Min(1) private int page=1;
        @Min(1) @Max(100) private int size=50;
        @Size(max=128) private String keyword;
        @Pattern(regexp="all|product_research|model_extension") private String kind="all";
        @Pattern(regexp="active|completed|stopped|all") private String view="active";
        @Positive private Long ownerUserId;
    }
    private static final String SKU_COMPLETE="exists(select 1 from plm_product sku where sku.deleted_flag=0 and "+com.yuewei.plm.module.moldreference.PhoneProductEvidence.skuForProjectSql("sku","p")+" and (%s))";
    private static final String STATE="case when p.abandoned_at is not null then 'stopped' when p.status in ('released','archived') or (p.project_kind='model_extension' and "+SKU_COMPLETE+") then 'completed' else 'active' end";
    private String state(){return STATE.formatted(access.hasPermission("product:view")?access.visibleSqlClause("product","sku.product_id"):"false");}
    private String from(){return """
      from plm_product p left join plm_product parent on parent.product_id=p.parent_product_id and parent.deleted_flag=0
      left join plm_phone_model_member phone on phone.id=p.phone_model_member_id
      left join plm_phone_brand brand on brand.id=phone.brand_id
      left join plm_code_item code on code.code_item_id=phone.code_item_id and code.deleted_flag=0
      left join sys_user owner on owner.user_id=p.owner_user_id and owner.deleted_flag=0
      """.replace("parent.deleted_flag=0", "parent.deleted_flag=0 and ("+access.visibleSqlClause("product","parent.product_id")+")");}
    private static final String SELECT="""
      select p.product_id as "projectId",p.product_code as "projectCode",p.product_name as "projectName",
       p.project_kind as "projectKind",p.project_registered_at as "registeredAt",p.product_type as "productType",p.mold_development_variant as "moldDevelopmentVariant",
       p.development_mode as "developmentMode",
       p.parent_product_id as "parentProductId",p.phone_model_member_id as "phoneModelId",p.model as "originalModel",
       case when p.product_type='product_line' then p.product_id else parent.product_id end as "productLineId",
       case when p.product_type='product_line' then p.product_name else parent.product_name end as "productLineName",
       phone.model_name as "phoneName",brand.name as "brandName",code.code_value as "phoneModelCode",
       p.owner_user_id as "ownerUserId",owner.display_name as "ownerName",p.expected_delivery_date as "expectedDeliveryDate",
       p.status,p.abandon_reason as "stopReason",p.current_step_no as "currentStepNo",p.workflow_template_id as "workflowTemplateId",
       p.workflow_template_version_no as "workflowTemplateVersionNo",p.updated_at as "updatedAt",
       exists(select 1 from plm_project_requirement_form rf where rf.project_id=p.product_id and rf.deleted_flag=0 and rf.status='confirmed') as "informationConfirmed",
      """;
    private String select(){return SELECT+state()+" as \"projectState\" ";}
    private void read(){access.requireDomesticView();access.requirePermission("project:view");}
    public Map<String,Boolean> capabilities(){return Map.of("applications",access.hasPermission("collaboration:view")&&applications.enabled(),"schedules",access.hasPermission("schedule:read"),"molds",access.hasPermission("collaboration:view"),"documents",access.hasPermission("file:read"),"bom",access.hasPermission("product:view"),"process",access.hasPermission("process:view"),"finance",access.hasPermission("finance:read"));}
    public Map<String,Object> page(Query q){
        read();if(q.getPage()<1||q.getSize()<1||q.getSize()>100)throw new BusinessException(40001,"分页参数无效");
        if(!Set.of("all","product_research","model_extension").contains(q.getKind())||!Set.of("active","completed","stopped","all").contains(q.getView()))throw new BusinessException(40001,"项目视图无效");
        var args=new ArrayList<Object>();String where=" where p.deleted_flag=0 and p.project_kind is not null and ("+access.visibleSqlClause("product","p.product_id")+")";
        if(!"all".equals(q.getKind())){where+=" and p.project_kind=?";args.add(q.getKind());}
        if(q.getOwnerUserId()!=null){where+=" and p.owner_user_id=?";args.add(q.getOwnerUserId());}
        if(q.getKeyword()!=null&&!q.getKeyword().isBlank()){where+=" and concat_ws(' ',p.product_code,p.product_name,parent.product_name,phone.model_name,brand.name,code.code_value,owner.display_name) ilike ?";args.add("%"+q.getKeyword().strip().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");}
        var stats=new LinkedHashMap<String,Long>();for(String state:List.of("active","completed","stopped","all"))stats.put(state,0L);
        for(var count:jdbc.queryForList("select "+state()+" state,count(*) count "+from()+where+" group by 1",args.toArray()))stats.put(count.get("state").toString(),number(count.get("count")));
        stats.put("all",stats.get("active")+stats.get("completed")+stats.get("stopped"));
        long total=stats.get(q.getView());if(!q.getView().equals("all")){where+=" and ("+state()+")=?";args.add(q.getView());}
        args.add(q.getSize());args.add((long)(q.getPage()-1)*q.getSize());
        var rows=jdbc.queryForList(select()+from()+where+" order by p.project_registered_at desc,p.product_id desc limit ? offset ?",args.toArray());enrich(rows,false);
        var result=new LinkedHashMap<String,Object>();result.put("content",rows);result.put("page",q.getPage());result.put("size",q.getSize());result.put("totalElements",total);result.put("totalPages",(total+q.getSize()-1)/q.getSize());result.put("stats",stats);result.put("capabilities",capabilities());
        if(capabilities().get("applications"))result.put("unlinkedApplications",applications.unlinkedSummary());return result;
    }
    public Map<String,Object> overview(long id){
        read();access.requireProduct(id);var rows=jdbc.queryForList(select()+from()+" where p.product_id=? and p.deleted_flag=0",id);if(rows.isEmpty())throw new BusinessException(40401,"项目不存在");
        enrich(rows,true);var result=rows.get(0);var capabilities=capabilities();result.put("capabilities",capabilities);
        if(capabilities.get("documents")){var q=new DocumentService.Query();q.setProjectId(id);q.setSize(1);result.put("documentCount",documents.page(q).get("totalElements"));}
        if(capabilities.get("bom"))result.put("bomCount",jdbc.queryForObject("select count(*) from plm_product_bom where product_id=? and deleted_flag=0",Long.class,id));
        if(capabilities.get("finance")){var direct=new DevelopmentTypes.Query();direct.setProductId(id);result.put("directFinance",finance.summary("expense",direct));var related=new DevelopmentTypes.Query();related.setProjectId(id);related.setSize(1);result.put("relatedExpenseCount",finance.page("expense",related).getTotalElements());}
        var children=jdbc.queryForList("select p.product_id as \"projectId\",p.product_name as \"projectName\",p.project_kind as \"projectKind\",p.status from plm_product p where p.parent_product_id=? and p.project_kind='model_extension' and p.deleted_flag=0 and ("+access.visibleSqlClause("product","p.product_id")+") order by p.project_registered_at desc",id);result.put("childProjects",children);return result;
    }
    @SuppressWarnings("unchecked") private void enrich(List<Map<String,Object>> rows,boolean detail){
        if(rows.isEmpty())return;var caps=capabilities();var ids=rows.stream().map(r->number(r.get("projectId"))).toList();String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        Map<Long,List<Map<String,Object>>> apps=new HashMap<>(),items=new HashMap<>(),sets=new HashMap<>();
        var productEvidence=access.hasPermission("product:view")?com.yuewei.plm.module.moldreference.PhoneProductEvidence.rows(jdbc,rows.stream().map(r->(Long)r.get("phoneModelId")).filter(Objects::nonNull).distinct().toList(),access.visibleSqlClause("product","p.product_id")):List.<Map<String,Object>>of();
        if(caps.get("applications"))for(var app:applications.projectApplications(ids))apps.computeIfAbsent(number(app.get("projectId")),k->new ArrayList<>()).add(app);
        if(caps.get("molds"))for(var item:jdbc.queryForList("select distinct links.project_id as \"projectId\",i.item_id as \"itemId\",coalesce(i.mold_name,i.style_name) as \"moldName\",coalesce(nullif(i.engraved_code,''),i.phone_model_code) as \"engravedCode\" from ("+ProjectBusinessLinks.ITEMS+") links join plm_collaboration_item i on i.item_id=links.item_id and i.deleted_flag=0 where links.project_id in ("+placeholders+") and ("+access.visibleSqlClause("item","i.item_id")+")",ids.toArray()))items.computeIfAbsent(number(item.get("projectId")),k->new ArrayList<>()).add(item);
        if(caps.get("schedules")){
            var links=jdbc.queryForList("select distinct links.project_id,links.mold_set_id from ("+ProjectBusinessLinks.SETS+") links join plm_collaboration_mold_set s on s.mold_set_id=links.mold_set_id where links.project_id in ("+placeholders+") and ("+access.visibleSqlClause("mold","s.mold_set_id")+")",ids.toArray());
            var allSets=schedules.projectRows(links.stream().map(r->number(r.get("mold_set_id"))).distinct().toList());Map<Long,Map<String,Object>> byId=new HashMap<>();allSets.forEach(s->byId.put(number(s.get("moldSetId")),s));
            for(var link:links){var set=byId.get(number(link.get("mold_set_id")));if(set!=null)sets.computeIfAbsent(number(link.get("project_id")),k->new ArrayList<>()).add(set);}
        }
        Map<String,List<TimelineNodeDefinition>> cachedDefinitions=new HashMap<>();
        for(var row:rows){long id=number(row.get("projectId"));var projectApps=apps.getOrDefault(id,List.of());var projectItems=items.getOrDefault(id,List.of());var projectSets=sets.getOrDefault(id,List.of());
            Product p=new Product();p.setProductId(id);p.setStatus(text(row,"status"));p.setProductType(text(row,"productType"));p.setDevelopmentMode(text(row,"developmentMode"));p.setWorkflowTemplateId((Long)row.get("workflowTemplateId"));p.setWorkflowTemplateVersionNo(text(row,"workflowTemplateVersionNo"));
            var nodes=cachedDefinitions.computeIfAbsent(p.getProductType()+":"+p.getDevelopmentMode()+":"+p.getStatus()+":"+p.getWorkflowTemplateId()+":"+p.getWorkflowTemplateVersionNo(),key->definitions.getDefinitions(p));
            boolean flowStarted=p.getProductType().equals("product_line")||Boolean.TRUE.equals(row.get("informationConfirmed"));
            int step=Math.max(1,Math.min(row.get("currentStepNo") instanceof Number n?n.intValue():1,nodes.size()));String flowNode=flowStarted&&!nodes.isEmpty()?nodes.get(step-1).nodeName():"待完善立项资料";
            row.put("flowNode",flowNode);row.put("flowStarted",flowStarted);row.put("applicationCount",caps.get("applications")?projectApps.size():null);row.put("archiveCount",caps.get("molds")?projectItems.size():null);row.put("moldSetCount",caps.get("schedules")?projectSets.size():null);
            var progress=ProjectWorkbenchProgress.summarize(text(row,"projectKind"),flowNode,projectApps,projectSets,caps.get("applications"),caps.get("schedules"));row.putAll(progress);
            if(access.hasPermission("product:view")){var sku=productEvidence.stream().filter(e->Objects.equals(e.get("phoneModelId"),row.get("phoneModelId"))&&Objects.equals(e.get("productLineId"),row.get("productLineId"))&&Boolean.TRUE.equals(e.get("historicalSkuEvidence"))&&(Objects.toString(row.get("moldDevelopmentVariant"),"").isBlank()||("sku".equals(e.get("productType"))&&Objects.equals(e.get("parentProductId"),row.get("projectId"))))).toList();row.put("historicalSkuCount",sku.size());if("model_extension".equals(row.get("projectKind"))&&projectSets.isEmpty()&&!sku.isEmpty()){row.put("phase","对应产品已有历史 SKU 开模依据");row.put("nextStep","查看历史产品与模具；可复用须核对具体套及 PC／TPU");}if(detail)row.put("historicalSkus",sku);}
            if(detail){row.put("applications",projectApps);row.put("archives",projectItems);row.put("moldSets",projectSets);row.put("historicalRecord",row.get("projectKind")==null);}
        }
    }
    private static long number(Object value){return ((Number)value).longValue();}
    private static String text(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
}

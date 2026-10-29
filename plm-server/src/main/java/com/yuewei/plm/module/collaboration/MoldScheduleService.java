package com.yuewei.plm.module.collaboration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.vo.PageVO;
import com.yuewei.plm.module.pilot.PilotAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yuewei.plm.module.collaboration.CollaborationFields.*;
import static com.yuewei.plm.module.collaboration.CollaborationTypes.*;
import static com.yuewei.plm.module.collaboration.MoldScheduleTypes.Task;

/** Thin facade over the shared collaboration ledger, mutation, audit and import engine. */
@Service @RequiredArgsConstructor
public class MoldScheduleService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CollaborationService collaboration;
    private final CollaborationRepository repository;
    private final PilotAccessService access;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private void read() {access.requireDomesticView();access.requirePermission("schedule:read");}
    public PageVO<Map<String,Object>> page(MoldScheduleTypes.Query query) {
        read();if(query.getProjectId()!=null)access.requireProduct(query.getProjectId());Query q=base(query);
        PageVO<Map<String,Object>> page=repository.page(q,collaboration.groups(collaboration.actor()),access.visibleSqlClause("mold","s.mold_set_id"));
        enrichPlans(page.getContent());collaboration.protectQuoteResponse(page.getContent());return page;
    }
    private static Query base(MoldScheduleTypes.Query query) {
        Query q=new Query();q.setView("delivery");q.setMoldLedger(true);q.setSchedule(true);q.setProjectId(query.getProjectId());q.setPage(query.getPage());q.setSize(query.getSize());q.setKeyword(query.getKeyword());q.setProductLineId(query.getProductLineId());q.setItemId(query.getItemId());q.setMoldSetId(query.getMoldSetId());q.setStatus(query.getStatus());q.setMineOnly(query.isMineOnly());q.setOverdueOnly(query.isOverdueOnly());return q;
    }
    public List<Map<String,Object>> projectRows(List<Long> ids) {read();var rows=repository.moldRows(ids,access.visibleSqlClause("mold","s.mold_set_id"));enrichPlans(rows);collaboration.protectQuoteResponse(rows);return rows;}
    public Map<String,Object> detail(long id) {read();access.requireMold(id);Map<String,Object> row=repository.row("mold_set",id);enrichPlans(List.of(row));collaboration.protectQuoteResponse(row);return row;}
    /** Internal confirmation snapshot: preserve raw facts; callers redact their API projections. */
    public Map<String,Object> snapshotForDevelopment(long id) {
        read();access.requireMold(id);var row=repository.row("mold_set",id);enrichPlans(List.of(row));
        row.put("development",decode(jdbc.queryForObject("select development_data::text from plm_collaboration_mold_set where mold_set_id=?",String.class,id)));
        return row;
    }
    public Map<String,Object> options() {
        read();Map<String,Object> result=new LinkedHashMap<>(collaboration.options());
        result.put("scheduleFields",MoldScheduleTypes.FIELDS);result.put("styleDays",MoldScheduleTypes.STYLE_DAYS);
        result.put("taskDefinitions",MoldSchedulePolicy.definitions());
        result.put("transportDays",7);result.put("acceptanceDays",1);result.put("productionDays",3);
        result.put("pausedWritableKeys",MoldScheduleTypes.ACTUAL_DATE_KEYS);result.put("remindersExternalEnabled",false);return result;
    }
    public List<RowResult> save(BatchSave dto,HttpServletRequest request) {
        access.requireDomesticView();access.requirePermission("schedule:write");
        for(RowPatch row:dto.rows()) {if(!row.entityType().equals("mold_set")) throw invalid("排程只保存模具套字段");access.requireMold(row.id());}
        List<RowResult> results=collaboration.save(dto,request);
        List<Map<String,Object>> rows=results.stream().map(RowResult::row).filter(Objects::nonNull).toList();enrichPlans(rows);
        return results;
    }
    /** The only mobile action field allowlist, reused by the mobile task facade. */
    /** Compact node form uses this same mold object, status writer and collaboration history. */
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> saveDevelopmentNode(long id,Map<String,Object> input,Map<String,Object> basis,boolean restart) {
        read();access.requirePermission("schedule:write");access.requireMold(id);var actor=collaboration.actor();collaboration.requireGroup(actor,"engineering");
        var authority=new MoldWorkflowRepository(jdbc);authority.lockExecution(id);
        jdbc.queryForList("select mold_set_id from plm_collaboration_mold_set where mold_set_id=? for update",id);
        if(authority.isPaused(id))throw invalid("原申请已暂停或终止，不能办理模具节点");
        var state=com.yuewei.plm.module.project.development.DevelopmentWorkflowStore.map(decode(jdbc.queryForObject("select development_data::text from plm_collaboration_mold_set where mold_set_id=?",String.class,id)));
        var before=new LinkedHashMap<>(state);String current=Objects.toString(state.get("node"),"making");long version=com.yuewei.plm.module.project.development.DevelopmentWorkflowStore.number(state.get("version"));
        if(com.yuewei.plm.module.project.development.DevelopmentWorkflowStore.number(input.get("nodeVersion"))!=version)throw invalid("模具节点已被其他操作更新，请重新载入");
        if(restart){var existing=repository.row("mold_set",id);if(!current.equals("ready")&&!(state.isEmpty()&&"ready".equals(existing.get("pcStatus"))&&"ready".equals(existing.get("tpuStatus"))))throw invalid("未验模通过的模具请继续办理当前节点");if(Objects.toString(input.get("reason"),"").isBlank())throw invalid("请填写重新验证原因");state.put("node","testing");state.put("cycle",com.yuewei.plm.module.project.development.DevelopmentWorkflowStore.number(state.get("cycle"))+1);state.remove("draft");}
        else {
            if(!Objects.toString(input.get("node"),"").equals(current)||current.equals("ready"))throw invalid("只能办理当前模具节点，已确认节点只读");
            var record=new LinkedHashMap<>(input);record.put("basis",basis);record.put("actorName",actor.displayName());record.put("recordedAt",LocalDateTime.now().toString());
            if(!Boolean.TRUE.equals(input.get("confirm"))) state.put("draft",record);
            else {
                LocalDate actual;try{actual=LocalDate.parse(Objects.toString(input.get("actualDate"),""));}catch(Exception e){throw invalid("请填写实际日期");}
                if(actual.isAfter(MoldSchedulePolicy.today()))throw invalid("实际日期不能晚于今天");
                var previous=com.yuewei.plm.module.project.development.DevelopmentWorkflowStore.map(state.get("last"));
                if(previous.get("actualDate")!=null&&actual.isBefore(LocalDate.parse(previous.get("actualDate").toString())))throw invalid("实际日期不能早于前一节点");
                String result=Objects.toString(input.get("result"),""),note=Objects.toString(input.get("note"),"").trim();
                if(!(current.equals("testing")?Set.of("passed","failed"):Set.of("complete","blocked")).contains(result))throw invalid("请确认本次实际结果");
                if((!current.equals("making")||result.equals("blocked"))&&note.isBlank())throw invalid("请填写实际验证、改动或遇阻处理说明");
                Map<String,Object> row=repository.row("mold_set",id);List<CellChange> changes=new ArrayList<>();
                if((current.equals("making")||current.equals("rework"))&&result.equals("complete"))for(String key:List.of("pcStatus","tpuStatus"))if(!Objects.equals(row.get(key),"ready"))changes.add(new CellChange(key,json.valueToTree(row.get(key)),json.valueToTree("ready"),"新品研发制作节点确认"));
                if(current.equals("testing"))changes.add(new CellChange("actualSupplierTrialDate",json.valueToTree(row.get("actualSupplierTrialDate")),json.valueToTree(actual.toString()),"新品研发实际验模"));
                if(!changes.isEmpty()){var resultRow=save(new BatchSave(List.of(new RowPatch("mold_set",id,changes))),null).get(0);if(!"saved".equals(resultRow.status()))throw invalid(resultRow.message());}
                if((current.equals("making")||current.equals("rework"))&&result.equals("complete"))state.put("cycle",com.yuewei.plm.module.project.development.DevelopmentWorkflowStore.number(state.get("cycle"))+1);
                state.put("last",record);state.remove("draft");state.put("node",result.equals("blocked")?current:current.equals("testing")?(result.equals("passed")?"ready":"rework"):"testing");
            }
        }
        state.put("version",version+1);
        jdbc.update("update plm_collaboration_mold_set set development_data=?::jsonb,updated_at=now(),updated_by=? where mold_set_id=?",encode(state),actor.username(),id);
        jdbc.update("insert into plm_collaboration_history(item_id,entity_type,entity_id,field_name,before_value,after_value,reason,actor_id,actor_name) values((select item_id from plm_collaboration_mold_set where mold_set_id=?),'mold_set',?,'developmentNode',?::jsonb,?::jsonb,?,?,?)",id,id,encode(before),encode(state),restart?Objects.toString(input.get("reason")):Boolean.TRUE.equals(input.get("confirm"))?"确认实际开发节点":"保存开发节点草稿",actor.userId(),actor.username());
        return detail(id);
    }
    private static final Map<String,Set<String>> MOBILE_FIELDS=Map.of(
        "processing",Set.of("pcStatus","tpuStatus"),
        "testing",Set.of("pcStatus","tpuStatus","actualSupplierTrialDate"),
        "ship",Set.of("actualShipDate","pcCurrentLocation","tpuCurrentLocation"),
        "arrival",Set.of("pcSignedDate","tpuSignedDate","pcFactoryArrivalDate","tpuFactoryArrivalDate"),
        "acceptance",Set.of("pcFactoryArrivalDate","tpuFactoryArrivalDate","pcAcceptanceStatus","tpuAcceptanceStatus","pcAcceptanceDate","tpuAcceptanceDate","pcAcceptanceIssues","tpuAcceptanceIssues"));
    public RowResult saveScopedTask(String action,RowPatch patch,HttpServletRequest request) {
        if(!MOBILE_FIELDS.containsKey(action)) throw invalid("手机任务操作无效");
        access.requirePermission("task:"+action);access.requireMold(patch.id());
        if(!patch.entityType().equals("mold_set")||patch.changes().stream().anyMatch(c->!MOBILE_FIELDS.get(action).contains(c.field()))) throw new BusinessException(40103,"手机任务包含未授权字段");
        return collaboration.save(new BatchSave(List.of(patch)),request).get(0);
    }
    public Map<String,Object> confirmPlan(long id,MoldScheduleTypes.ConfirmPlan dto) {
        access.requireDomesticView();access.requirePermission("schedule:write");access.requireMold(id);
        var actor=collaboration.actor();collaboration.requireGroup(actor,"engineering");
        return new TransactionTemplate(transactionManager).execute(tx->{
            MoldWorkflowRepository workflow=new MoldWorkflowRepository(jdbc);workflow.lockExecution(id);
            jdbc.queryForList("select mold_set_id from plm_collaboration_mold_set where mold_set_id=? and deleted_flag=0 for update",id);
            if(workflow.isPaused(id))throw invalid("申请执行已暂停，不能确认新的计划基准；原有基准与实际记录保留");
            Map<String,Object> row=repository.row("mold_set",id),snapshot=new LinkedHashMap<>();
            for(String key:MoldScheduleTypes.PLAN_KEYS) {
                if(!dto.before().containsKey(key)||!same(normalise(field("mold_set",key),dto.before().get(key)),row.get(key))) throw invalid("计划已变化，请刷新核对当前计划再留存基准");
                snapshot.put(key,row.get(key));
            }
            snapshot.put("styleName",row.get("styleName"));snapshot.put("compatibleModels",row.get("compatibleModels"));snapshot.put("defaultManufacturingDays",MoldScheduleTypes.defaultDays(Objects.toString(row.get("styleName"),"")));snapshot.put("transportDays",7);snapshot.put("acceptanceDays",1);snapshot.put("productionDays",3);
            if(row.get("plannedMoldDeliveryDate")==null||row.get("plannedFactoryArrivalDate")==null||row.get("plannedAcceptanceDate")==null||row.get("plannedProductionDate")==null) throw invalid("请先由各负责人确认完成、到厂、验收和量产四个计划日期，再留存基准版本");
            int version=Objects.requireNonNull(jdbc.queryForObject("select coalesce(max(version_no),0)+1 from plm_mold_schedule_plan where mold_set_id=?",Integer.class,id));
            jdbc.update("insert into plm_mold_schedule_plan(mold_set_id,version_no,plan_snapshot,reason,actor_id,actor_name) values(?,?,?::jsonb,?,?,?)",id,version,encode(snapshot),dto.reason(),actor.userId(),actor.username());
            jdbc.update("insert into plm_collaboration_history(item_id,entity_type,entity_id,field_name,before_value,after_value,reason,actor_id,actor_name) values(?,'mold_set',?,'planBaseline',null,?::jsonb,?,?,?)",row.get("itemId"),id,encode(Map.of("version",version,"snapshot",snapshot)),dto.reason(),actor.userId(),actor.username());
            enrichPlans(List.of(row));return row;
        });
    }
    public PageVO<Map<String,Object>> history(long id,int page,int size) {read();access.requireMold(id);return collaboration.protectHistoryResponse(repository.moldHistory(id,page,size));}
    private void enrichPlans(List<Map<String,Object>> rows) {
        if(rows.isEmpty()) return;
        new MoldWorkflowRepository(jdbc).enrich(rows);
        enrichPhoneMembers(rows);
        String placeholders=String.join(",",Collections.nCopies(rows.size(),"?"));Object[] ids=rows.stream().map(r->r.get("moldSetId")).toArray();
        var developments=new HashMap<Long,Object>();
        jdbc.queryForList("select mold_set_id,development_data::text as development_data from plm_collaboration_mold_set where mold_set_id in ("+placeholders+")",ids).forEach(value->developments.put(((Number)value.get("mold_set_id")).longValue(),value.get("development_data")));
        var projectAccess=new HashMap<Long,Boolean>();boolean money=access.hasPermission("finance:read");
        for(var row:rows){Object dev=com.yuewei.plm.module.project.development.FinancialSnapshotRedaction.scopedMold(decode(developments.getOrDefault(((Number)row.get("moldSetId")).longValue(),"{}")),project->projectAccess.computeIfAbsent(project,id->{try{access.requireProduct(id);return true;}catch(com.yuewei.plm.common.exception.BusinessException denied){return false;}}));row.put("development",money?dev:com.yuewei.plm.module.project.development.FinancialSnapshotRedaction.withoutMoney(dev));}
        var plans=jdbc.queryForList("select distinct on(mold_set_id) mold_set_id,version_no,plan_snapshot::text snapshot,reason,actor_name,confirmed_at from plm_mold_schedule_plan where mold_set_id in ("+placeholders+") order by mold_set_id,version_no desc",ids);
        Map<Long,Map<String,Object>> byId=new HashMap<>();plans.forEach(p->byId.put(((Number)p.get("mold_set_id")).longValue(),p));
        LocalDate today=MoldSchedulePolicy.today();
        for(Map<String,Object> row:rows) {
            MoldScheduleTypes.enrich(row);var p=byId.get(((Number)row.get("moldSetId")).longValue());
            row.put("planVersion",p==null?0:p.get("version_no"));row.put("baseline",p==null?null:decode(p.get("snapshot")));
            row.put("baselineConfirmedAt",p==null?null:Objects.toString(p.get("confirmed_at"),null));row.put("baselineActor",p==null?null:p.get("actor_name"));
            row.put("baselineNeedsReview",p!=null&&java.util.stream.Stream.concat(MoldScheduleTypes.PLAN_KEYS.stream(),java.util.stream.Stream.of("styleName","compatibleModels")).anyMatch(k->!same(((Map<?,?>)row.get("baseline")).get(k),row.get(k))));
            MoldSchedulePolicy.enrichTasks(row,today);
        }
    }
    private void enrichPhoneMembers(List<Map<String,Object>> rows) {
        List<Long> ids=rows.stream().map(row->row.get("itemId")).filter(Number.class::isInstance).map(id->((Number)id).longValue()).distinct().toList();
        if(ids.isEmpty())return;
        Map<Long,List<Map<String,Object>>> members=new HashMap<>();
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        jdbc.query("select cm.item_id,p.id,p.model_name,b.name brand_name,c.code_value from plm_collaboration_phone_member cm join plm_phone_model_member p on p.id=cm.phone_model_id left join plm_phone_brand b on b.id=p.brand_id left join plm_code_item c on c.code_item_id=p.code_item_id where cm.item_id in ("+placeholders+") order by p.id",rs->{
            Map<String,Object> member=new LinkedHashMap<>();member.put("phoneModelId",rs.getLong("id"));member.put("modelName",rs.getString("model_name"));member.put("brandName",rs.getString("brand_name"));member.put("phoneModelCode",rs.getString("code_value"));members.computeIfAbsent(rs.getLong("item_id"),ignored->new ArrayList<>()).add(member);
        },ids.toArray());
        rows.forEach(row->{if(row.get("itemId") instanceof Number id)row.put("members",members.getOrDefault(id.longValue(),List.of()));});
    }
    static String missingSql(Set<String> groups) {
        List<String> clauses=new ArrayList<>();
        if(groups.contains("engineering")) clauses.add("s.opened_date is null or s.planned_mold_delivery_date is null or s.owner_user_id is null");
        if(groups.contains("logistics")) clauses.add("s.planned_factory_arrival_date is null or s.logistics_owner_user_id is null or (pc.status='ready' and tpu.status='ready' and s.actual_ship_date is null)");
        if(groups.contains("mexico")) clauses.add("s.planned_acceptance_date is null or s.planned_production_date is null or (s.actual_ship_date is not null and (pc.acceptance_status<>'passed' or tpu.acceptance_status<>'passed'))");
        return clauses.isEmpty()?"false":String.join(" or ",clauses.stream().map(c->"("+c+")").toList());
    }
    public PageVO<Task> tasks(MoldScheduleTypes.Query query) {
        read();var actor=collaboration.actor();Set<String> groups=collaboration.groups(actor);
        return taskPage(query,access.visibleSqlClause("mold","s.mold_set_id"),groups,actor.userId());
    }
    /** Canonical task query shared by login-scoped pages and the trusted scheduler. */
    private PageVO<Task> taskPage(MoldScheduleTypes.Query query,String scope,Set<String> groups,Long actorId) {
        String category=MoldSchedulePolicy.category(query.getCategory());
        String from=" from plm_collaboration_mold_set s join plm_collaboration_item i on i.item_id=s.item_id left join plm_collaboration_mold_part pc on pc.mold_set_id=s.mold_set_id and pc.part_type='PC' left join plm_collaboration_mold_part tpu on tpu.mold_set_id=s.mold_set_id and tpu.part_type='TPU' left join plm_product task_platform on task_platform.product_id=i.product_line_id and task_platform.product_type='product_line' and task_platform.deleted_flag=0 left join plm_code_item task_phone on task_phone.code_item_id=i.phone_model_code_item_id and task_phone.code_type='phone_model' and task_phone.deleted_flag=0 "+CollaborationRepository.SET_PHONE_NAMES_JOIN+" cross join lateral (values "+MoldSchedulePolicy.taskValuesSql()+") t(task_key,title,task_group,category,due_field,due_date,owner_id,activated,completed)";
        StringBuilder where=new StringBuilder(" where i.deleted_flag=0 and s.deleted_flag=0 and coalesce(t.activated,false) and not coalesce(t.completed,false) and (").append(scope).append(") and ").append(MoldWorkflowRepository.activeSql("s"));List<Object> args=new ArrayList<>();
        if(!category.equals("all")) {where.append(" and t.category=?");args.add(category);}
        if(query.getProjectId()!=null) {access.requireProduct(query.getProjectId());where.append(" and "+com.yuewei.plm.module.project.ProjectBusinessLinks.setExists("s.mold_set_id"));args.add(query.getProjectId());}
        if(query.getItemId()!=null) {where.append(" and i.item_id=?");args.add(query.getItemId());}
        if(query.getMoldSetId()!=null) {where.append(" and s.mold_set_id=?");args.add(query.getMoldSetId());}
        if(query.getProductLineId()!=null) {where.append(" and i.product_line_id=?");args.add(query.getProductLineId());}
        if(query.getKeyword()!=null&&!query.getKeyword().isBlank()) {where.append(" and concat_ws(' ',i.style_name,i.compatible_models,task_platform.product_name,task_phone.code_name,current_phones.current_models,t.title,s.set_name,"+CollaborationRepository.assetSql()+") ilike ?");args.add("%"+query.getKeyword().trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");}
        if(query.getStatus()!=null&&!query.getStatus().isBlank()) {where.append(" and (case when s.market_ready_date is not null then 'market_ready' else "+CollaborationRepository.moldStatusSql()+" end)=?");args.add(query.getStatus());}
        if(query.isMineOnly()) {where.append(" and (t.owner_id=? or (t.owner_id is null and t.task_group in (").append(groups.isEmpty()?"''":String.join(",",groups.stream().map(g->"'"+g+"'").toList())).append(")))");args.add(actorId);}
        String late="t.due_date < (now() at time zone 'Asia/Shanghai')::date";if(query.isOverdueOnly()) where.append(" and ").append(late);
        Long count=jdbc.queryForObject("select count(*)"+from+where,Long.class,args.toArray());long total=count==null?0:count;args.add(query.getSize());args.add((long)(query.getPage()-1)*query.getSize());
        List<Task> rows=jdbc.query("select s.mold_set_id,i.item_id,"+CollaborationRepository.assetSql()+" asset_number,coalesce(task_platform.product_name,i.style_name) style_name,coalesce(current_phones.current_models,case when exists(select 1 from plm_collaboration_phone_set_compatibility scoped where scoped.item_id=i.item_id) then '本套尚未关联适用手机' else '待核对：'||coalesce(task_phone.code_name,i.compatible_models,'本套适用手机待核对') end) compatible_models,t.task_key,t.title,t.task_group,t.owner_id,t.category,t.due_field,t.due_date,coalesce("+late+",false) overdue, greatest((now() at time zone 'Asia/Shanghai')::date-t.due_date,0) overdue_days"+from+where+" order by overdue desc,t.due_date asc nulls last,s.mold_set_id,t.task_key limit ? offset ?",(rs,n)->{
            var definition=MoldSchedulePolicy.definition(rs.getString("task_key"));
            return new Task(rs.getLong("mold_set_id"),rs.getString("asset_number"),rs.getString("style_name"),rs.getString("compatible_models"),definition.taskKey(),rs.getString("title"),rs.getString("task_group"),(Long)rs.getObject("owner_id"),rs.getDate("due_date")==null?null:rs.getDate("due_date").toLocalDate().toString(),rs.getBoolean("overdue"),rs.getString("due_field"),rs.getLong("overdue_days"),rs.getLong("item_id"),definition.category(),definition.inputFields(),definition.supportFields());
        },args.toArray());
        return new PageVO<>(rows,query.getPage(),query.getSize(),total,(total+query.getSize()-1)/query.getSize());
    }
    /** Persist station reminders only; no external DingTalk delivery is claimed. */
    public Map<String,Object> enqueueReminders() {
        access.requireAdministrator();access.requirePermission("schedule:remind");
        return enqueueTasks(access.actor().username(),access.visibleSqlClause("mold","s.mold_set_id"));
    }
    /** Trusted internal entry point: system actor, not a fabricated interactive account. */
    public Map<String,Object> enqueueAutomaticReminders() {return enqueueTasks("system","true");}
    @Scheduled(cron="${app.pilot.schedule-reminder-cron:0 0 * * * *}",zone="Asia/Shanghai")
    public void generateScheduledReminders() {enqueueAutomaticReminders();}
    private Map<String,Object> enqueueTasks(String createdBy,String scope) {
        int created=0,unassigned=0;
        MoldScheduleTypes.Query q=new MoldScheduleTypes.Query();q.setSize(100);q.setOverdueOnly(true);
        PageVO<Task> page;
        do {page=taskPage(q,scope,Set.of(),null);for(Task task:page.getContent()) {
            if(task.ownerUserId()==null) {unassigned++;continue;}
            created+=jdbc.update("insert into plm_mold_schedule_reminder(mold_set_id,task_key,due_date,recipient_id,payload,created_by) select ?,?,?,?,?::jsonb,? where exists(select 1 from sys_user where user_id=? and deleted_flag=0 and status='active') on conflict(mold_set_id,task_key,due_date,recipient_id) do nothing",task.moldSetId(),task.taskKey(),java.sql.Date.valueOf(task.dueDate()),task.ownerUserId(),encode(task),createdBy,task.ownerUserId());
        }q.setPage(q.getPage()+1);}while(q.getPage()<=page.getTotalPages());
        return Map.of("created",created,"unassigned",unassigned,"externalSent",0,"externalEnabled",false);
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private Object decode(Object value) {try{return json.readValue(value.toString(),new TypeReference<Object>(){});}catch(Exception e){throw new IllegalStateException(e);}}
}

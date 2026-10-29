package com.yuewei.plm.module.collaboration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuewei.plm.common.security.*;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceRepository;
import com.yuewei.plm.module.operationlog.service.*;
import com.yuewei.plm.module.pilot.PilotAccessService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static com.yuewei.plm.module.collaboration.CollaborationTypes.*;
import static com.yuewei.plm.module.collaboration.CollaborationFields.*;

/** Source metadata and scheduling around the original permission/validation/history writer. */
@Service @RequiredArgsConstructor
public class MoldScheduleSheetSync {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CollaborationRepository repository;
    private final CollaborationService collaboration;
    private final PilotAccessService access;
    private final MoldScheduleSheetClient source;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final OperationLogService audit;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    public record Settings(@jakarta.validation.constraints.Positive long version,boolean automatic,@jakarta.validation.constraints.Min(15) @jakarta.validation.constraints.Max(1440) int intervalMinutes) {}
    public record Resume(@jakarta.validation.constraints.Positive long version,@jakarta.validation.constraints.NotEmpty @jakarta.validation.constraints.Size(max=7) List<String> fields,@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=2000) String reason) {}
    public record Binding(@jakarta.validation.constraints.Pattern(regexp="[0-9a-f]{64}") @jakarta.validation.constraints.NotNull String sourceKey,@jakarta.validation.constraints.Positive long moldSetId,@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=2000) String reason) {}
    private void read(){access.requireDomesticView();access.requirePermission("schedule:read");access.requireAdministrator();}
    private void write(){read();access.requirePermission("schedule:write");}
    private TransactionTemplate tx(){return new TransactionTemplate(transactionManager);}
    private Map<String,Object> config(boolean lock){return jdbc.queryForMap("select *,running_id is not null and running_since>now()-interval '5 minutes' as lease_live from plm_mold_sheet_config where config_id=1"+(lock?" for update":""));}
    public Map<String,Object> status() {
        read();var c=config(false);var out=new LinkedHashMap<String,Object>();
        out.put("sourceUrl",MoldScheduleSheetClient.SOURCE_URL);out.put("automatic",c.get("automatic_enabled"));out.put("intervalMinutes",c.get("interval_minutes"));out.put("version",c.get("version_no"));
        out.put("initializedAt",c.get("initialized_at"));out.put("lastSuccessAt",c.get("last_success_at"));out.put("lastError",c.get("running_id")!=null&&!Boolean.TRUE.equals(c.get("lease_live"))?"上次更新已中断，可以重试；原排程保留":c.get("last_error"));out.put("running",Boolean.TRUE.equals(c.get("lease_live")));
        var runs=jdbc.queryForList("select run_id,status,started_at,finished_at,source_hash,result::text as result from plm_mold_sheet_run order by started_at desc limit 1");
        if(!runs.isEmpty()){var run=runs.get(0);run.put("result",decode(run.get("result")));out.put("job",run);}
        return out;
    }
    public Map<String,Object> settings(Settings input) {
        write();if(input.intervalMinutes()<15||input.intervalMinutes()>1440)throw invalid("自动更新间隔须为15至1440分钟");
        var actor=access.actor();tx().executeWithoutResult(t->{var c=config(true);if(((Number)c.get("version_no")).longValue()!=input.version())throw conflict();
            if(input.automatic()&&c.get("initialized_at")==null)throw invalid("请先完成一次在线表更新，再开启自动更新");
            jdbc.update("update plm_mold_sheet_config set automatic_enabled=?,interval_minutes=?,operator_user_id=?,version_no=version_no+1,updated_at=now() where config_id=1",input.automatic(),input.intervalMinutes(),actor.userId());
            log(actor,"MOLD_SHEET_SETTINGS",Map.of("automatic",input.automatic(),"intervalMinutes",input.intervalMinutes()));
        });return status();
    }
    public Map<String,Object> start(){write();return start(access.actor(),"manual");}
    private Map<String,Object> start(CurrentUser actor,String mode) {
        UUID id=UUID.randomUUID();boolean claimed=Boolean.TRUE.equals(tx().execute(t->{
            int n=jdbc.update("update plm_mold_sheet_config set running_id=?,running_since=now(),last_attempt_at=now(),operator_user_id=coalesce(operator_user_id,?),version_no=version_no+1 where config_id=1 and (running_id is null or running_since<now()-interval '5 minutes')",id,actor.userId());
            if(n==0)return false;
            jdbc.update("insert into plm_mold_sheet_run(run_id,actor_id,mode,status) values(?,?,?,'running')",id,actor.userId(),mode);return true;
        }));
        if(claimed)executor.submit(()->{CurrentUserContext.set(actor);try{perform(id);}catch(Exception failure){fail(id,failure);}finally{CurrentUserContext.clear();}});
        return status();
    }
    @PreDestroy public void close(){executor.shutdownNow();}
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void automatic() {
        var ids=jdbc.queryForList("select c.operator_user_id from plm_mold_sheet_config c where automatic_enabled and operator_user_id is not null and (last_attempt_at is null or last_attempt_at<=now()-interval_minutes*interval '1 minute') and (running_id is null or running_since<now()-interval '5 minutes')");
        if(ids.isEmpty())return;
        try {
            var users=jdbc.queryForList("select user_id,username,display_name,all_permissions from sys_user where user_id=? and deleted_flag=0 and status='active'",ids.get(0).get("operator_user_id"));
            if(users.size()!=1||!Boolean.TRUE.equals(users.get(0).get("all_permissions")))throw invalid("自动更新账号已停用或没有管理权限，请重新设置");
            var u=users.get(0);var actor=new CurrentUser(((Number)u.get("user_id")).longValue(),u.get("username").toString(),Objects.toString(u.get("display_name"),u.get("username").toString()),true);
            CurrentUserContext.set(actor);write();start(actor,"automatic");
        }catch(Exception failure){jdbc.update("update plm_mold_sheet_config set last_error=? where config_id=1",safeFailure(failure));}finally{CurrentUserContext.clear();}
    }
    private void perform(UUID id) {
        write();var actor=access.actor();var before=repository.previewRows("mold_set",access.visibleSqlClause("mold","s.mold_set_id"));
        var identities=jdbc.queryForList("select dingtalk_user_id from plm_pilot_account where user_id=?",actor.userId());
        if(identities.size()!=1||identities.get(0).get("dingtalk_user_id")==null)throw invalid("更新账号尚未绑定真实钉钉身份");
        JsonNode snapshot=source.read(identities.get(0).get("dingtalk_user_id").toString());
        var rows=MoldScheduleSheetParser.parse(snapshot);
        var brands=new PhoneModelReferenceRepository(jdbc).allBrands();
        var codes=new HashMap<Long,String>();jdbc.queryForList("select product_id,product_specific_code from plm_product where deleted_flag=0 and product_type='product_line'").forEach(r->{if(r.get("product_specific_code")!=null)codes.put(((Number)r.get("product_id")).longValue(),r.get("product_specific_code").toString());});
        before.forEach(r->{if(r.get("productLineId") instanceof Number line)r.put("styleCode",codes.get(line.longValue()));});
        tx().executeWithoutResult(t->{
            var c=config(true);if(!id.equals(c.get("running_id")))throw conflict();
            Map<String,String> aliases=new LinkedHashMap<>();decode(c.get("style_aliases")).fields().forEachRemaining(e->aliases.put(e.getKey(),e.getValue().asText()));
            List<Map<String,Object>> results=new ArrayList<>();
            var resolved=new ArrayList<Map.Entry<MoldScheduleSheetParser.SourceRow,MoldScheduleSheetMatcher.Match>>();
            for(var row:rows){var existing=jdbc.queryForList("select mold_set_id from plm_mold_sheet_binding where source_key=?",row.key());
                var match=existing.isEmpty()?MoldScheduleSheetMatcher.match(row,before,brands,aliases):new MoldScheduleSheetMatcher.Match(((Number)existing.get(0).get("mold_set_id")).longValue(),List.of(),"沿用已核对的来源关联");
                resolved.add(Map.entry(row,match));}
            resolved.sort(Comparator.comparing(e->e.getValue().moldSetId()==null?Long.MAX_VALUE:e.getValue().moldSetId()));
            Map<Long,Long> targetCounts=new HashMap<>();resolved.forEach(e->{if(e.getValue().moldSetId()!=null)targetCounts.merge(e.getValue().moldSetId(),1L,Long::sum);});
            for(var entry:resolved){var row=entry.getKey();var match=entry.getValue();
                var result=new LinkedHashMap<String,Object>();result.put("sourceKey",row.key());result.put("rowNo",row.rowNo());result.put("style",row.style());result.put("model",row.model());result.put("candidates",match.candidates());result.put("sourceDates",row.dates());
                if(!row.issues().isEmpty()||match.moldSetId()==null||targetCounts.get(match.moldSetId())>1) {result.put("state","review");result.put("message",!row.issues().isEmpty()?String.join("；",row.issues()):match.moldSetId()==null?match.reason():"多条来源指向同一实物套，各行均未写入，请先核对");results.add(result);continue;}
                long set=match.moldSetId();result.put("moldSetId",set);access.requireMold(set);
                new MoldWorkflowRepository(jdbc).lockExecution(set);
                jdbc.queryForList("select mold_set_id from plm_collaboration_mold_set where mold_set_id=? for update",set);
                Map<String,Object> old=before.stream().filter(r->same(r.get("moldSetId"),set)).findFirst().orElseThrow(()->invalid("来源关联的实物套不可见或已移除"));
                // Check even a no-op source row; source reads must not apply a stale snapshot.
                var locked=repository.row("mold_set",set);
                for(String key:MoldScheduleSheetPolicy.KEYS)if(!same(old.get(key),locked.get(key)))throw conflict();
                for(String key:List.of("itemId","productLineId","compatibleModels","sourceMoldSetId"))if(!same(old.get(key),locked.get(key)))throw conflict();
                var bindings=jdbc.queryForList("select source_key,source_row_no,field_state::text as fields from plm_mold_sheet_binding where mold_set_id=? for update",set);
                ObjectNode state=preservedState(bindings,old,c.get("initialized_at")!=null);
                if(!bindings.isEmpty()&&!row.key().equals(bindings.get(0).get("source_key")))jdbc.update("update plm_mold_sheet_binding set source_key=?,source_identity=?::jsonb where mold_set_id=?",row.key(),encode(Map.of("style",row.style(),"brand",row.brand(),"model",row.model())),set);
                if(bindings.isEmpty())jdbc.update("insert into plm_mold_sheet_binding(source_key,mold_set_id,source_identity,source_row_no,field_state) values(?,?,?::jsonb,?,?::jsonb)",row.key(),set,encode(Map.of("style",row.style(),"brand",row.brand(),"model",row.model())),row.rowNo(),encode(state));
                var decision=MoldScheduleSheetPolicy.decide(old,row,state);result.put("protectedFields",decision.protectedFields());
                List<CellChange> changes=decision.values().entrySet().stream().map(e->new CellChange(e.getKey(),json.valueToTree(old.get(e.getKey())),json.valueToTree(e.getValue()),"钉钉在线表更新；来源第"+row.rowNo()+"行；"+(c.get("initialized_at")==null?"首次采用当前在线表基准":"保留系统人工维护字段"))).toList();
                if(!changes.isEmpty()) {
                    String marker=jdbc.queryForObject("select current_setting('plm.mold_sheet_sync_write',true)",String.class);
                    try {jdbc.queryForObject("select set_config('plm.mold_sheet_sync_write','on',true)",String.class);
                        Map<String,JsonNode> expected=new LinkedHashMap<>();MoldScheduleSheetPolicy.KEYS.forEach(k->expected.put(k,json.valueToTree(old.get(k))));
                        RowResult saved=collaboration.save(new BatchSave(List.of(new RowPatch("mold_set",set,changes,expected))),null).get(0);
                        if(!"saved".equals(saved.status()))throw invalid(saved.message());
                    }finally{jdbc.queryForObject("select set_config('plm.mold_sheet_sync_write',?,true)",String.class,marker==null?"":marker);}
                }
                for(String key:MoldScheduleSheetPolicy.KEYS){var field=state.withObject("/"+key);if(!field.has("manual"))field.put("manual",false);if(row.dates().containsKey(key))field.put("sourceValue",row.dates().get(key));if(key.equals("forecastDeliveryDate")&&row.dates().containsKey("plannedMoldDeliveryDate"))field.put("sourceValue",row.dates().get("plannedMoldDeliveryDate"));if(decision.values().containsKey(key))field.set("appliedValue",json.valueToTree(decision.values().get(key)));}
                jdbc.update("update plm_mold_sheet_binding set field_state=?::jsonb,source_row_no=?,source_notes=?,version_no=version_no+1,updated_at=now() where mold_set_id=?",encode(state),row.rowNo(),row.notes(),set);
                result.put("state","updated");result.put("changedFields",decision.values().keySet());results.add(result);
            }
            long updated=results.stream().filter(r->"updated".equals(r.get("state"))).count();
            var summary=Map.of("sourceRows",rows.size(),"matched",updated,"needsReview",results.size()-updated,"rows",results,"manualDatesProtected",true);
            jdbc.update("update plm_mold_sheet_run set status='completed',finished_at=now(),source_hash=?,source_snapshot=?::jsonb,result=?::jsonb where run_id=?",MoldScheduleSheetParser.hash(encode(snapshot)),encode(MoldScheduleSheetParser.retainedSnapshot(snapshot)),encode(summary),id);
            jdbc.update("update plm_mold_sheet_config set initialized_at=case when ?>0 then coalesce(initialized_at,now()) else initialized_at end,last_success_at=now(),last_error=null,running_id=null,running_since=null,version_no=version_no+1,updated_at=now() where config_id=1 and running_id=?",updated,id);
            log(actor,"MOLD_SHEET_UPDATE",Map.of("runId",id,"sourceRows",rows.size(),"matched",updated,"needsReview",results.size()-updated));
        });
    }
    public Map<String,Object> fields(long set) {
        read();access.requireMold(set);var rows=jdbc.queryForList("select source_key,source_identity::text as identity,source_row_no,source_notes,field_state::text as fields,version_no from plm_mold_sheet_binding where mold_set_id=?",set);
        if(rows.isEmpty())return Map.of("linked",false);var row=rows.get(0);var current=repository.row("mold_set",set);var fields=(ObjectNode)decode(row.get("fields"));MoldScheduleSheetPolicy.KEYS.forEach(key->fields.withObject("/"+key).set("currentValue",json.valueToTree(current.get(key))));row.put("linked",((Number)row.get("source_row_no")).intValue()>0);row.put("identity",decode(row.get("identity")));row.put("fields",fields);return row;
    }
    public Map<String,Object> bind(Binding input) {
        write();access.requireMold(input.moldSetId());if(input.reason()==null||input.reason().isBlank())throw invalid("请填写关联核对原因");
        var actor=access.actor();tx().executeWithoutResult(t->{var runs=jdbc.queryForList("select source_snapshot::text as source,result::text as result from plm_mold_sheet_run where status='completed' order by started_at desc limit 1");
            if(runs.isEmpty())throw invalid("请先读取在线表并核对对应行");
            var reviewed=decode(runs.get(0).get("result")).path("rows");JsonNode selection=null;
            for(JsonNode row:reviewed)if(input.sourceKey().equals(row.path("sourceKey").asText()))selection=row;
            if(selection==null||!"review".equals(selection.path("state").asText()))throw invalid("该来源行已变化或不需要关联，请先更新");
            boolean candidate=false;for(JsonNode id:selection.path("candidates"))if(id.asLong()==input.moldSetId())candidate=true;
            if(!candidate)throw invalid("所选实物套不是当前核对候选，请先维护正确型号或款式依据");
            var row=MoldScheduleSheetParser.parse(decode(runs.get(0).get("source"))).stream().filter(r->r.key().equals(input.sourceKey())).findFirst().orElseThrow();
            new MoldWorkflowRepository(jdbc).lockExecution(input.moldSetId());jdbc.queryForList("select mold_set_id from plm_collaboration_mold_set where mold_set_id=? for update",input.moldSetId());
            var bindings=jdbc.queryForList("select source_key,source_row_no,field_state::text as fields from plm_mold_sheet_binding where mold_set_id=? for update",input.moldSetId());
            if(!bindings.isEmpty()&&((Number)bindings.get(0).get("source_row_no")).intValue()>0)throw conflict();
            var state=preservedState(bindings,repository.row("mold_set",input.moldSetId()),config(false).get("initialized_at")!=null);
            String identity=encode(Map.of("style",row.style(),"brand",row.brand(),"model",row.model()));
            if(bindings.isEmpty())jdbc.update("insert into plm_mold_sheet_binding(source_key,mold_set_id,source_identity,source_row_no,field_state) values(?,?,?::jsonb,?,?::jsonb)",row.key(),input.moldSetId(),identity,row.rowNo(),encode(state));
            else jdbc.update("update plm_mold_sheet_binding set source_key=?,source_identity=?::jsonb,source_row_no=?,field_state=?::jsonb,version_no=version_no+1,updated_at=now() where mold_set_id=?",row.key(),identity,row.rowNo(),encode(state),input.moldSetId());
            log(actor,"MOLD_SHEET_BIND",Map.of("sourceKey",row.key(),"moldSetId",input.moldSetId(),"reason",input.reason()));
        });return start();
    }
    private ObjectNode preservedState(List<Map<String,Object>> bindings,Map<String,Object> current,boolean initialized) {
        ObjectNode state=bindings.isEmpty()?json.createObjectNode():(ObjectNode)decode(bindings.get(0).get("fields"));
        boolean unlinked=bindings.isEmpty()||((Number)bindings.get(0).get("source_row_no")).intValue()==0;
        if(initialized&&unlinked)for(String key:MoldScheduleSheetPolicy.KEYS)if(current.get(key)!=null&&!state.path(key).has("manual"))state.withObject("/"+key).put("manual",true).set("manualValue",json.valueToTree(current.get(key)));
        return state;
    }
    public Map<String,Object> resume(long set,Resume input) {
        write();access.requireMold(set);if(input.fields()==null||input.fields().isEmpty()||!MoldScheduleSheetPolicy.KEYS.containsAll(input.fields())||input.reason()==null||input.reason().isBlank())throw invalid("请选择恢复跟随的日期并填写原因");
        var actor=access.actor();tx().executeWithoutResult(t->{jdbc.queryForList("select mold_set_id from plm_collaboration_mold_set where mold_set_id=? for update",set);
            var rows=jdbc.queryForList("select field_state::text as fields,version_no from plm_mold_sheet_binding where mold_set_id=? for update",set);if(rows.size()!=1||((Number)rows.get(0).get("version_no")).longValue()!=input.version())throw conflict();
            var state=(ObjectNode)decode(rows.get(0).get("fields"));for(String key:input.fields())state.withObject("/"+key).put("manual",false);
            jdbc.update("update plm_mold_sheet_binding set field_state=?::jsonb,version_no=version_no+1,updated_at=now() where mold_set_id=?",encode(state),set);
            log(actor,"MOLD_SHEET_RESUME",Map.of("moldSetId",set,"fields",input.fields(),"reason",input.reason()));
        });return fields(set);
    }
    private void fail(UUID id,Exception failure) {String message=safeFailure(failure);tx().executeWithoutResult(t->{jdbc.update("update plm_mold_sheet_run set status='failed',finished_at=now(),result=?::jsonb where run_id=? and status='running'",encode(Map.of("message",message,"originalDatesPreserved",true)),id);jdbc.update("update plm_mold_sheet_config set last_error=?,running_id=null,running_since=null,version_no=version_no+1 where config_id=1 and running_id=?",message,id);});}
    private static String safeFailure(Exception failure){return failure instanceof BusinessException?Objects.toString(failure.getMessage(),"更新失败").substring(0,Math.min(900,Objects.toString(failure.getMessage(),"更新失败").length())):"在线表读取或保存未完成，原排程保留，请核对应用读表权限及来源结构后重试";}
    private void log(CurrentUser actor,String action,Object detail){audit.logRequired(OperationLogCreateCommand.builder().action(action).businessType("MOLD_SCHEDULE").businessId("1").businessName("开模在线表同步").detailJson(encode(detail)).build(),actor);}
    private JsonNode decode(Object raw){try{return raw==null?json.createObjectNode():json.readTree(raw.toString());}catch(Exception e){throw invalid("来源同步记录格式错误");}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode schedule source",e);}}
    private static BusinessException conflict(){return new BusinessException(40901,"排程或同步设置已更新，请刷新后核对");}
}

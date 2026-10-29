package com.yuewei.plm.module.moldapplication;

import com.yuewei.plm.module.project.ProjectRegistration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import com.yuewei.plm.common.vo.PageVO;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.operationlog.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import static com.yuewei.plm.module.moldapplication.MoldApplicationForms.*;

@Service @RequiredArgsConstructor
public class MoldApplicationService implements ApplicationRunner {
    private final MoldApplicationRepository repo;private final MoldApplicationProperties props;
    private final MoldDingTalkClient client;private final MoldApplicationMedia media;private final MoldApplicationCodes codes;
    private final PilotAccessService access;private final ObjectMapper json;private final PlatformTransactionManager transactions;
    private final OperationLogService logs;private final MoldApplicationDingTalkFiles dingFiles;
    private final MoldApplicationBackupImport backupImport;
    private final AtomicBoolean syncing=new AtomicBoolean();
    private static final String VALID_CODES="a.generated_codes~*'^[[:space:]]*"+com.yuewei.plm.common.util.ProductBusinessCodeGenerator.COMPLETE_MOLD_CODE_REGEX+"([[:space:]/，,、;；]+"+com.yuewei.plm.common.util.ProductBusinessCodeGenerator.COMPLETE_MOLD_CODE_REGEX+")*[[:space:]]*$'";
    private static final String KEYWORD_SQL="""
        and (concat_ws(' ',a.model,a.tipo,a.approval_no,a.generated_codes,a.originator_name) ilike ?
          or exists(select 1 from plm_phone_model_member phone
            join plm_phone_brand brand on brand.id=phone.brand_id
            left join plm_code_item code on code.code_item_id=phone.code_item_id and code.deleted_flag=0
            where phone.id=a.phone_model_id
              and concat_ws(' ',brand.name,phone.model_name,code.code_value,
                array_to_string(brand.aliases,' '),array_to_string(phone.aliases,' ')) ilike ?))
        """;
    private static String keywordClause(String keyword,List<Object> args){
        if(text(keyword)==null)return "";String pattern="%"+keyword.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";args.add(pattern);args.add(pattern);return " "+KEYWORD_SQL;
    }
    @Autowired @Lazy private MoldApplicationConversion conversion;
    @Autowired @Lazy private com.yuewei.plm.module.project.development.ProductDevelopmentService productDevelopment;
    @Autowired @Lazy private com.yuewei.plm.module.marketwatch.MarketWatchWorkflowService marketWorkflow;
    public void setConversion(MoldApplicationConversion value){conversion=value;}
    public record Draft(JsonNode form,Long productLineId,Long phoneCodeItemId,Long version,Long phoneModelId,String executionIntent,Long targetItemId,Long targetMoldSetId,Long targetProjectId,String executionReason,String draftRequestId,Boolean newApplication) {
        public Draft(JsonNode form,Long productLineId,Long phoneCodeItemId,Long version){this(form,productLineId,phoneCodeItemId,version,null,null,null,null,null,null,null,false);}
        public Draft(JsonNode form,Long productLineId,Long phoneCodeItemId,Long version,Long phoneModelId,String executionIntent,Long targetItemId,Long targetMoldSetId,Long targetProjectId){this(form,productLineId,phoneCodeItemId,version,phoneModelId,executionIntent,targetItemId,targetMoldSetId,targetProjectId,null,null,false);}
    }
    public record SourceDraft(Long phoneModelId,String sourceModelKey,String sourceKind,Long sourceId,Long productLineId,String executionIntent,Long targetItemId,Long targetMoldSetId,Long targetProjectId,String executionReason,String requestId,Boolean newApplication) {
        public SourceDraft(Long phoneModelId,String sourceModelKey,String sourceKind,Long sourceId,Long productLineId,String executionIntent,Long targetItemId,Long targetMoldSetId,Long targetProjectId){this(phoneModelId,sourceModelKey,sourceKind,sourceId,productLineId,executionIntent,targetItemId,targetMoldSetId,targetProjectId,null,null,false);}
    }
    public record ResearchDraft(long projectId,Long phoneModelId,String requestId,Long version,Draft draft) {
        public ResearchDraft(long projectId,Long phoneModelId,String requestId,Long version){this(projectId,phoneModelId,requestId,version,null);}
    }
    public record Resolution(long version,String action,String reason) {}
    public record TargetCorrection(long version,String executionIntent,Long targetItemId,Long targetProjectId,String reason) {}

    public record Submit(long version,Long departmentId,boolean confirmFormal) {}
    public boolean enabled(){return props.isEnabled();}
    void feature(){if(!props.isEnabled())throw new BusinessException(40401,"开模申请暂未启用");}
    void read(){feature();access.requirePermission("collaboration:view");}
    void createPermission(){read();if(access.isExternal())throw new BusinessException(40103,"国内账号才可发起开模申请");access.requirePermission("mold-application:create");}
    public void convertPermission(){read();access.requirePermission("mold-application:convert");if(!access.actor().allPermissions()&&repo.jdbc().queryForObject("select count(*) from plm_collaboration_assignment where user_id=? and field_group='engineering'",Integer.class,access.actorId())==0)throw new BusinessException(40103,"仅工程职责组和管理员可转入项目");}
    private void admin(){read();access.requireAdministrator();}
    private TransactionTemplate tx(){return new TransactionTemplate(transactions);}
    String visible(){var a=access.access();String original=a.allObjects()?"true":"(a.applicant_id="+a.actor().userId()+" or (a.item_id is not null and "+access.visibleSqlClause("item","a.item_id")+"))";
        String research=access.hasPermission("project:view")?"exists(select 1 from plm_product rp where rp.product_id=(a.source_snapshot_json->'research'->>'projectId')::bigint and rp.deleted_flag=0 and "+access.visibleSqlClause("product","rp.product_id")+")":"false";
        return "("+original+") and (a.source_snapshot_json->'research' is null or "+research+")"; }
    public Map<String,Object> require(long id,boolean lock){read();if(repo.jdbc().queryForObject("select count(*) from plm_mold_application a where application_id=? and deleted_flag=0 and "+visible(),Integer.class,id)==0)throw new BusinessException(40103,"申请不存在或未获授权");return repo.row(id,lock);}
    private void ownDraft(Map<String,Object> row){if(!"draft".equals(row.get("status")))throw invalid("已提交申请只读，请撤回后修订");if(!ownsDraft(row))throw new BusinessException(40103,"只能编辑本人申请");if(!editableDraft(row))throw new BusinessException(40103,"新品项目只读或来源资料未获授权，请回原项目由工程人员核对，不能覆盖受限草稿");}
    private boolean editableDraft(Map<String,Object> row){
        if(!"draft".equals(row.get("status"))||!ownsDraft(row))return false;
        if(productDevelopment!=null&&!productDevelopment.canEditMoldApplicationProductScope((Long)row.get("productLineId")))return false;
        if(productDevelopment!=null&&row.get("sourceSnapshot") instanceof JsonNode snapshot&&snapshot.path("research").isObject()){
            var source=snapshot.path("research");if(!productDevelopment.canEditResearchMoldDraft(source.path("projectId").asLong()))return false;
            var view=productDevelopment.redactMoldApplicationSource(json.convertValue(source,new TypeReference<Map<String,Object>>(){}));
            return !Boolean.TRUE.equals(view.get("restricted"))&&!Boolean.TRUE.equals(view.get("suppliersRestricted"))&&!Boolean.TRUE.equals(view.get("referencesRestricted"));
        }return true;
    }
    private void lockResearchProject(Map<String,Object> row){
        if(productDevelopment==null||row==null)return;
        if(row.get("sourceSnapshot") instanceof JsonNode source&&source.path("research").isObject())productDevelopment.lockResearchMoldDraft(source.path("research").path("projectId").asLong());
        else productDevelopment.assertMoldApplicationProductScope((Long)row.get("productLineId"));
    }
    private Map<String,Object> lockEditableDraft(long id){var original=require(id,false);lockPhones((Long)original.get("phoneModelId"));lockResearchProject(original);var row=require(id,true);ownDraft(row);return row;}
    private boolean deletableDraft(Map<String,Object> row){
        return "plm".equals(row.get("sourceSystem"))&&"draft".equals(row.get("status"))
            &&row.get("instanceId")==null&&row.get("approvalNo")==null&&row.get("remoteStatus")==null&&row.get("remoteResult")==null
            &&!Boolean.TRUE.equals(row.get("approvalGranted"))&&row.get("projectId")==null&&row.get("itemId")==null&&row.get("moldSetId")==null;
    }
    private boolean ownsDraft(Map<String,Object> row){return access.actor().allPermissions()||row.get("applicantId") instanceof Number owner&&owner.longValue()==access.actorId();}
    public Map<String,Object> deleteDraft(long id,long version,HttpServletRequest request){
        createPermission();var actor=access.actor();return tx().execute(tx->{
            var initial=require(id,false);lockPhones((Long)initial.get("phoneModelId"));lockResearchProject(initial);var row=require(id,true);ownDraft(row);
            if(!deletableDraft(row))throw invalid("只能删除尚未提交且未转入项目的本地草稿；审批和已建档记录须保留");
            if(((Number)row.get("version")).longValue()!=version)throw new BusinessException(40901,"草稿已被更新，请刷新核对后再删除");
            String application=Long.toString(id);
            boolean referenced=Boolean.TRUE.equals(repo.jdbc().queryForObject("select exists(select 1 from plm_product_development_state s join plm_product p on p.product_id=s.product_id and p.deleted_flag=0 where s.data @> jsonb_build_object('moldLinks',jsonb_build_array(jsonb_build_object('applicationId',?::bigint)))) or exists(select 1 from plm_product_development_round r join plm_product p on p.product_id=r.product_id and p.deleted_flag=0 where r.details_json->>'applicationId'=? or r.details_json->'moldApplication'->>'application_id'=?)",Boolean.class,id,application,application));
            if(referenced)throw invalid("草稿已被新品研发使用，请先在原项目中移除或更换申请关联；已确认的历史依据须保留");
            repo.jdbc().update("update plm_mold_application set deleted_flag=1,version_no=version_no+1,updated_at=now(),updated_by=? where application_id=?",actor.username(),id);
            audit("MOLD_APPLICATION_DRAFT_DELETE",id,row,request,actor);
            return Map.of("applicationId",id,"deleted",true);
        });
    }
    void audit(String action,long id,Object data,HttpServletRequest request,CurrentUser actor){logs.logRequired(OperationLogCreateCommand.builder().action(action).businessType("MOLD_APPLICATION").businessId(String.valueOf(id)).detailJson(repo.json(data)).request(request).build(),actor);}
    public Map<String,Object> featureConfig(){access.requirePermission("collaboration:view");return Map.of("enabled",props.isEnabled());}
    public Map<String,Object> options(){
        read();var a=access.actor();var result=new LinkedHashMap<String,Object>();result.put("controls",controls(repo.schema(props.getProcessCode()),json));
        var configurations=codes.configs();var templateTipos=officialTipos(repo.schema(props.getProcessCode()));result.put("configs",configurations);
        var productLines=repo.jdbc().queryForList("select p.product_id as \"productId\",p.product_name as \"productName\",p.product_specific_code as \"productSpecificCode\",p.sales_status as \"salesStatus\",p.product_name,p.sales_status,p.development_mode,p.product_type,p.product_specific_code,p.status,p.lock_status,p.current_step_no,case when (w.data->'revision'->>'stage')~'^[1-8]$' then (w.data->'revision'->>'stage')::integer else coalesce(p.current_step_no,1) end as \"researchStepNo\" from plm_product p left join plm_product_development_state w on w.product_id=p.product_id where p.deleted_flag=0 and p.product_type='product_line' and "+access.visibleSqlClause("product","p.product_id")+" order by p.product_name");
        for(var line:productLines){line.put("canMaintainProductIdentity","launch_v1".equals(line.get("development_mode"))&&com.yuewei.plm.module.project.development.ResearchLaunchService.canMaintainProductIdentity(text(line.get("productSpecificCode")),text(line.get("status")),text(line.get("lock_status")),access));line.put("moldApplicationEligibility",com.yuewei.plm.module.productionmetadata.ProductSalesPolicy.moldApplicationEligibility(line));line.put("submissionPolicy",MoldApplicationSubmissionPolicy.of(((Number)line.get("productId")).longValue(),line,true,null,null,null,null,templateTipos,configurations));for(String key:List.of("product_name","sales_status","development_mode","product_type","product_specific_code","status","lock_status","current_step_no"))line.remove(key);}
        result.put("productLines",productLines);
        result.put("canCreate",!access.isExternal()&&access.hasPermission("mold-application:create"));
        result.put("canConvert",access.hasPermission("mold-application:convert")&&(a.allPermissions()||repo.jdbc().queryForObject("select count(*) from plm_collaboration_assignment where field_group='engineering' and user_id=?",Integer.class,a.userId())>0));
        result.put("administrator",a.allPermissions());result.put("writeEnabled",props.isWriteEnabled());result.put("centralCodeEnabled",props.isCentralCodeEnabled());
        boolean allowed=props.isWriteEnabled()&&props.getAllowedSubmitters().contains(a.userId());
        String reason=!props.isWriteEnabled()?"钉钉写入开关关闭":!allowed?"此账号不在允许发起白名单中":null;
        var mapping=repo.jdbc().queryForList("select dingtalk_user_id from plm_pilot_account where user_id=?",a.userId());
        String uid=mapping.size()==1?text(mapping.get(0).get("dingtalk_user_id")):null;
        var departments=new ArrayList<Map<String,Object>>();String name=a.displayName();
        if(allowed&&uid==null){allowed=false;reason="请管理员先绑定真实钉钉身份";}
        if(allowed)try{var person=client.user(uid);name=Objects.requireNonNullElse(text(person.path("name")),name);for(var d:person.path("dept_id_list")){String departmentName=null;try{departmentName=text(client.department(d.asLong()).path("name"));}catch(Exception ignored){}departments.add(Map.of("id",d.asLong(),"name",Objects.requireNonNullElse(departmentName,"部门 "+d.asLong())));}if(departments.isEmpty()){allowed=false;reason="钉钉发起人没有可用部门，请管理员核对";}}catch(Exception e){allowed=false;reason="钉钉身份或通讯录权限暂不可用，请管理员核对";}
        var submitter=new LinkedHashMap<String,Object>();submitter.put("name",name);submitter.put("dingtalkUserId",uid);submitter.put("departments",departments);
        result.put("canSubmit",allowed);result.put("submitter",submitter);result.put("submissionBlockReason",reason);
        result.put("executionApprover",Map.of("name","周汉琴 / Tiffany","userId",EXECUTION_APPROVER_USER_ID));result.put("processCode",props.getProcessCode());result.put("syncRunning",syncing.get());
        result.put("originators",repo.jdbc().queryForList("select distinct originator_name as name,originator_user_id as id from plm_mold_application a where deleted_flag=0 and originator_user_id is not null and "+visible()+" order by name"));
        return result;
    }
    public PageVO<Map<String,Object>> page(int page,int size,String keyword,String tipo,String status,String originator,String from,String until){return page(page,size,keyword,tipo,status,originator,from,until,null,null);}
    public PageVO<Map<String,Object>> page(int page,int size,String keyword,String tipo,String status,String originator,String from,String until,Long projectId,Boolean unlinked){
        read();if(page<1||size<1||size>100)throw invalid("分页参数无效");StringBuilder where=new StringBuilder(" where a.deleted_flag=0 and "+visible());List<Object> args=new ArrayList<>();
        if(projectId!=null){access.requireProduct(projectId);where.append(" and a.project_id=?");args.add(projectId);}
        if(Boolean.TRUE.equals(unlinked))where.append(" and a.project_id is null");
        where.append(keywordClause(keyword,args));
        if(text(tipo)!=null){where.append(" and a.tipo=?");args.add(tipo);}
        if(text(originator)!=null){where.append(" and a.originator_user_id=?");args.add(originator);}
        if("ready".equals(status))where.append(" and a.approval_granted=true and a.project_id is null and a.remote_status<>'TERMINATED' and "+VALID_CODES);
        else if(MoldApplicationBusinessStatus.VALUES.contains(Objects.toString(status,""))){where.append(" and ("+MoldApplicationBusinessStatus.SQL+")=?");args.add(status);}
        else if(text(status)!=null){where.append(" and (a.status=? or a.remote_status=?)");args.add(status);args.add(status);}
        if(text(from)!=null){where.append(" and coalesce(a.source_created_at,a.created_at)>=?");try{args.add(LocalDate.parse(from).atStartOfDay(ZONE).toOffsetDateTime());}catch(Exception e){throw invalid("起始日期格式无效");}}
        if(text(until)!=null){where.append(" and coalesce(a.source_created_at,a.created_at)<?");try{args.add(LocalDate.parse(until).plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());}catch(Exception e){throw invalid("截止日期格式无效");}}
        Long total=repo.jdbc().queryForObject("select count(*) from plm_mold_application a"+where,Long.class,args.toArray());
        args.add(size);args.add((long)(page-1)*size);
        var rows=repo.jdbc().queryForList(MoldApplicationRepository.SELECT+where+" order by coalesce(a.source_created_at,a.created_at) desc,a.application_id desc limit ? offset ?",args.toArray());rows.forEach(repo::view);enrichProgress(rows);
        return new PageVO<>(rows,page,size,total,(total+size-1)/size);
    }
    /** Exact project list for research: the same application visibility and draft-write decision as its own module. */
    public List<Map<String,Object>> researchApplications(long projectId) {
        if(!enabled())return List.of();read();access.requireProduct(projectId);
        var rows=repo.jdbc().queryForList(MoldApplicationRepository.SELECT+" where a.deleted_flag=0 and (a.project_id=? or a.product_line_id=?) and "+visible()+" order by a.application_id desc",projectId,projectId);
        var result=new ArrayList<Map<String,Object>>();
        for(var raw:rows){var row=repo.view(raw);var value=new LinkedHashMap<String,Object>();for(String key:List.of("applicationId","approvalNo","title","status","businessStatus","moldSetId","phoneModelId","model"))value.put(key,row.get(key));value.put("canEditDraft",editableDraft(row));result.add(value);}
        return result;
    }
    public Map<String,Object> unlinkedSummary(){read();var rows=repo.jdbc().queryForList("select ("+MoldApplicationBusinessStatus.SQL+") as status,a.source_system,count(*) as count from plm_mold_application a where a.deleted_flag=0 and a.project_id is null and "+visible()+" group by 1,2");return Map.of("groups",rows,"total",rows.stream().mapToLong(r->((Number)r.get("count")).longValue()).sum());}
    public List<Map<String,Object>> projectApplications(List<Long> ids){read();if(ids.isEmpty())return List.of();var rows=repo.jdbc().queryForList(MoldApplicationRepository.SELECT+" where a.deleted_flag=0 and a.project_id in ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") and "+visible()+" order by a.updated_at desc,a.application_id desc",ids.toArray());rows.forEach(repo::view);enrichProgress(rows);return rows;}
    public Map<String,Object> summary(){return summary(null,null);}
    public Map<String,Object> summary(Long projectId,Boolean unlinked){
        read();String scope=" from plm_mold_application a where a.deleted_flag=0 and "+visible();
        if(projectId!=null){access.requireProduct(projectId);scope+=" and a.project_id="+projectId;}
        if(Boolean.TRUE.equals(unlinked))scope+=" and a.project_id is null";
        var counts=repo.jdbc().queryForList("select remote_status as \"remoteStatus\",status,count(*) as count"+scope+" group by remote_status,status");
        var stages=repo.jdbc().queryForList("select "+MoldApplicationBusinessStatus.SQL+" as \"businessStatus\",count(*) as count"+scope+" group by 1");
        return Map.of("counts",counts,"businessCounts",stages,"ready",repo.jdbc().queryForObject("select count(*)"+scope+" and approval_granted=true and project_id is null and remote_status<>'TERMINATED' and "+VALID_CODES,Long.class));
    }
    public List<Map<String,Object>> phoneCodes(String keyword){read();if(text(keyword)==null||keyword.length()<2)return List.of();return repo.jdbc().queryForList("select code_item_id as \"codeItemId\",code_value as \"codeValue\",code_name as \"codeName\",production_status as \"productionStatus\" from plm_code_item where code_type='phone_model' and status='enabled' and deleted_flag=0 and concat_ws(' ',code_value,code_name) ilike ? order by code_value desc,code_item_id desc limit 100","%"+keyword.replace("%","\\%").replace("_","\\_")+"%");}
    public PageVO<Map<String,Object>> progressPage(int page,int size,String keyword,String status,Long phoneModelId,Long productLineId){return progressPage(page,size,keyword,status,phoneModelId,productLineId,null);}
    public PageVO<Map<String,Object>> progressPage(int page,int size,String keyword,String status,Long phoneModelId,Long productLineId,Long projectId){
        read();if(page<1||size<1||size>100)throw invalid("分页参数无效");var args=new ArrayList<Object>();String where=" where a.deleted_flag=0 and "+visible()+" and a.status<>'draft' and a.external_instance_id is not null";
        where+=keywordClause(keyword,args);
        if(projectId!=null){access.requireProduct(projectId);where+=" and a.project_id=?";args.add(projectId);}
        if(phoneModelId!=null){where+=" and a.phone_model_id=?";args.add(phoneModelId);}if(productLineId!=null){where+=" and a.product_line_id=?";args.add(productLineId);}
        if(text(status)!=null){if(!MoldApplicationBusinessStatus.VALUES.contains(status))throw invalid("申请进度筛选无效");where+=" and ("+MoldApplicationBusinessStatus.SQL+")=?";args.add(status);}
        long total=Objects.requireNonNull(repo.jdbc().queryForObject("select count(*) from plm_mold_application a"+where,Long.class,args.toArray()));args.add(size);args.add((long)(page-1)*size);
        var rows=repo.jdbc().queryForList(MoldApplicationRepository.SELECT+where+" order by a.updated_at desc,a.application_id desc limit ? offset ?",args.toArray());rows.forEach(repo::view);enrichExecutionStages(rows);enrichProgress(rows);return new PageVO<>(rows,page,size,total,(total+size-1)/size);
    }
    private void enrichExecutionStages(List<Map<String,Object>> rows){
        List<Long> ids=rows.stream().map(row->row.get("moldSetId")).filter(Number.class::isInstance).map(value->((Number)value).longValue()).distinct().toList();if(ids.isEmpty())return;String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        var stages=repo.jdbc().queryForList("select s.mold_set_id as id,"+com.yuewei.plm.module.collaboration.CollaborationRepository.assetSql()+" as asset,case when s.market_ready_date is not null then 'market_ready' else "+com.yuewei.plm.module.collaboration.CollaborationRepository.moldStatusSql()+" end as status from plm_collaboration_mold_set s join plm_collaboration_item i on i.item_id=s.item_id and i.deleted_flag=0 left join plm_collaboration_mold_part pc on pc.mold_set_id=s.mold_set_id and pc.part_type='PC' left join plm_collaboration_mold_part tpu on tpu.mold_set_id=s.mold_set_id and tpu.part_type='TPU' where s.deleted_flag=0 and s.mold_set_id in ("+placeholders+") and "+access.visibleSqlClause("mold","s.mold_set_id"),ids.toArray());
        Map<Long,Map<String,Object>> byId=new HashMap<>();stages.forEach(row->byId.put(((Number)row.get("id")).longValue(),row));for(var row:rows){var stage=row.get("moldSetId") instanceof Number id?byId.get(id.longValue()):null;row.put("scheduleStatus",stage==null?null:stage.get("status"));row.put("assetNumber",stage==null?null:stage.get("asset"));}
    }
    private void enrichProgress(List<Map<String,Object>> rows){
        if(rows.isEmpty())return;var ids=rows.stream().map(r->r.get("applicationId")).toList();String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        var people=repo.jdbc().queryForList("select a.application_id,u.display_name from plm_mold_application a left join sys_user u on u.user_id=a.applicant_id where a.application_id in ("+placeholders+")",ids.toArray());Map<Object,Object> applicants=new HashMap<>();people.forEach(p->applicants.put(p.get("application_id"),p.get("display_name")));
        Map<Object,List<Map<String,Object>>> handlers=new HashMap<>();
        String handlerSql="""
            with historical_names as (
              select originator_user_id,min(btrim(originator_name)) as name
              from plm_mold_application where deleted_flag=0 and originator_user_id is not null
                and nullif(btrim(originator_name),'') is not null and originator_name<>originator_user_id
                and btrim(originator_name)!~'^[0-9[:space:]-]+$'
              group by originator_user_id having count(distinct btrim(originator_name))=1
            )
            select t.application_id,t.actor_id,t.task_id,
              coalesce(nullif(btrim(u.display_name),''),h.name,
                case when nullif(btrim(t.actor_name),'') is not null and t.actor_name<>t.actor_id and btrim(t.actor_name)!~'^[0-9[:space:]-]+$' then t.actor_name end,
                case when nullif(btrim(t.payload->>'name'),'') is not null and t.payload->>'name'<>t.actor_id and btrim(t.payload->>'name')!~'^[0-9[:space:]-]+$' then t.payload->>'name' end,
                case when nullif(btrim(t.payload->>'user_name'),'') is not null and t.payload->>'user_name'<>t.actor_id and btrim(t.payload->>'user_name')!~'^[0-9[:space:]-]+$' then t.payload->>'user_name' end) as name
            from plm_mold_application_activity t
              left join plm_pilot_account account on account.dingtalk_user_id=t.actor_id
              left join sys_user u on u.user_id=account.user_id and u.deleted_flag=0
              left join historical_names h on h.originator_user_id=t.actor_id
            where t.application_id in (%s) and t.activity_type='task' and t.payload->>'task_status'='RUNNING'
            order by t.activity_id
            """.formatted(placeholders);
        for(var person:repo.jdbc().queryForList(handlerSql,ids.toArray())){
            var handler=new LinkedHashMap<String,Object>();handler.put("userId",person.get("actor_id"));handler.put("taskId",person.get("task_id"));handler.put("name",Objects.requireNonNullElse(text(person.get("name")),"处理人待核对"));handlers.computeIfAbsent(person.get("application_id"),unused->new ArrayList<>()).add(handler);
        }
        Map<Object,Map<String,Object>> phones=new HashMap<>();var phoneIds=rows.stream().map(r->r.get("phoneModelId")).filter(Objects::nonNull).distinct().toList();
        if(!phoneIds.isEmpty())for(var phone:repo.jdbc().queryForList("select m.id as \"phoneModelId\",b.name as \"brandName\",m.model_name as \"modelName\",c.code_value as \"phoneModelCode\" from plm_phone_model_member m join plm_phone_brand b on b.id=m.brand_id left join plm_code_item c on c.code_item_id=m.code_item_id and c.deleted_flag=0 where m.id in ("+String.join(",",Collections.nCopies(phoneIds.size(),"?"))+")",phoneIds.toArray()))phones.put(phone.get("phoneModelId"),phone);
        for(var row:rows){var phone=phones.get(row.get("phoneModelId"));row.put("standardPhone",phone);if(phone!=null){row.put("originalPhoneModelCode",row.get("phoneModelCode"));row.put("phoneModelCode",phone.get("phoneModelCode"));}
            if(row.get("executionResolution") instanceof JsonNode resolution&&resolution.path("historicalAssociation").isObject())row.put("historicalAssociation",resolution.path("historicalAssociation"));
            row.put("applicantName",Objects.requireNonNullElse(text(row.get("originatorName")),Objects.toString(applicants.get(row.get("applicationId")),"申请人待核对")));row.put("currentHandlers",handlers.getOrDefault(row.get("applicationId"),List.of()));
            row.put("canEditDraft",editableDraft(row));
            row.put("canDeleteDraft",deletableDraft(row)&&!access.isExternal()&&access.hasPermission("mold-application:create")&&editableDraft(row));
            row.put("expectedDeliveryDate",text(((JsonNode)row.get("form")).path("expectedDeliveryDate")));row.put("nextStep",nextStep(row));redactResearchSource(row);
        }
    }
    private static String nextStep(Map<String,Object> row){
        if(row.get("historicalAssociation") instanceof JsonNode association&&association.path("associationOnly").asBoolean())return row.get("projectId")==null?"已补明确手机／产品关系；项目依据见集中补链":row.get("itemId")==null?"项目已关联；模具主档依据待补":row.get("moldSetId")==null?"项目／主档已关联；具体套依据待补":"历史关系已关联，适配待确认";
        if("paused".equals(row.get("executionStatus")))return "工程处理暂停：明确恢复或终止";
        if("terminated".equals(row.get("executionStatus")))return "执行已终止；仅补录已发生事实";
        if(researchSubmission(row)){
            if(Set.of("rejected","withdrawn","terminated").contains(Objects.toString(row.get("businessStatus"),"")))return "新品审批已结束；保留原项目和申请记录，确需重开请在新品项目办理修订";
            if(Boolean.TRUE.equals(row.get("approvalGranted")))return "新品审批已同意；回新品项目补齐工程建档资料，不自动建立型号项目或实物套";
        }
        if("blocked".equals(row.get("provisioningStatus")))return "工程核对建档："+Objects.toString(row.get("provisioningError"),"目标资料待核对");
        if(row.get("moldSetId")!=null)return "pending".equals(row.get("scheduleStatus"))?"确认逐套计划并登记开工":"按逐套排程更新实际进度";
        if(Boolean.TRUE.equals(row.get("approvalGranted")))return "plm".equals(row.get("sourceSystem"))?"自动建档待处理":"工程核对历史项目及模具关联";
        if(row.get("authorization") instanceof JsonNode proof&&"operation".equals(proof.path("evidenceSource").asText()))return "已有历史同意依据；补拉完整任务证据后建档";
        return switch(Objects.toString(row.get("businessStatus"),"")){case "draft"->"完善申请后提交";case "rejected","withdrawn","terminated"->"审批已结束；保留记录，确需重开请明确新增套与原因";case "awaiting_approval"->"等待周汉琴同意；可查看当前处理任务";default->"同步核对审批状态";};
    }
    public Map<String,Object> detail(long id){var r=require(id,false);enrichExecutionStages(List.of(r));enrichProgress(List.of(r));r.put("activities",repo.activities(id));r.put("files",media.list(id));r.put("reservedCompatibility",codes.reservedCompatibility(id,text(r.get("generatedCodes"))));
        r.put("submissionPolicy",submissionPolicy(r,repo.schema(props.getProcessCode())));redactResearchSource(r);return r;}
    private void redactResearchSource(Map<String,Object> row) {
        if(productDevelopment==null||!(row.get("sourceSnapshot") instanceof JsonNode source)||!source.path("research").isObject())return;
        var visible=productDevelopment.redactMoldApplicationSource(json.convertValue(source.path("research"),new TypeReference<Map<String,Object>>(){}));
        var snapshot=source.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)snapshot).set("research",json.valueToTree(visible));row.put("sourceSnapshot",snapshot);
        if(Boolean.TRUE.equals(visible.get("restricted"))||Boolean.TRUE.equals(visible.get("suppliersRestricted"))){
            if(row.get("form") instanceof com.fasterxml.jackson.databind.node.ObjectNode form){var safe=form.deepCopy();safe.remove("remark");row.put("form",safe);}
            if(Boolean.TRUE.equals(visible.get("restricted")))row.put("files",List.of());
        }
        if(!Boolean.TRUE.equals(visible.get("restricted"))&&row.get("files") instanceof List<?> list){
            row.put("files",list.stream().filter(raw->{var file=(Map<?,?>)raw;Object sourceId=file.get("sourceAttachmentId");if(sourceId==null)return true;try{access.requireFile(((Number)sourceId).longValue());return true;}catch(BusinessException denied){return false;}}).toList());
        }
    }
    private Map<String,Object> responseRow(long id){var row=repo.row(id,false);row.put("canEditDraft",editableDraft(row));row.put("submissionPolicy",submissionPolicy(row,repo.schema(props.getProcessCode())));redactResearchSource(row);return row;}
    private List<String> officialTipos(JsonNode schema) {
        return controls(schema,json).stream().filter(control->"tipo".equals(control.get("key")))
            .flatMap(control->((List<?>)control.get("options")).stream()).map(Object::toString).toList();
    }
    /** This mode is derived from the persisted product and server-captured source, never from a client flag. */
    private MoldApplicationSubmissionPolicy submissionPolicy(Map<String,Object> row,JsonNode schema) {
        Long line=(Long)row.get("productLineId");Map<String,Object> product=Map.of();
        if(line!=null){var products=repo.jdbc().queryForList("select product_type,development_mode,product_specific_code from plm_product where product_id=? and deleted_flag=0",line);if(products.size()==1)product=products.get(0);}
        JsonNode source=json.valueToTree(row.get("sourceSnapshot"));
        boolean trusted=source.path("research").isObject()&&Objects.equals(line,source.path("research").path("projectId").asLong());
        return MoldApplicationSubmissionPolicy.of(line,product,trusted,(Long)row.get("phoneModelId"),(Long)row.get("phoneCodeItemId"),text(row.get("generatedCodes")),text(row.get("tipo")),officialTipos(schema),codes.configs());
    }
    /** A new research approval is not an authorization to create a model-development project or inventory. */
    private static boolean researchSubmission(Map<String,Object> row) {
        return row.get("sourceSnapshot") instanceof JsonNode source&&"research".equals(source.path("researchSubmission").path("mode").asText())
            &&Objects.equals(row.get("productLineId"),source.path("researchSubmission").path("projectId").asLong());
    }
    void requireModelExecution(Map<String,Object> row) {
        if(researchSubmission(row)) {
            var policy=submissionPolicy(row,repo.schema(props.getProcessCode()));
            String missing=policy.filingMissing().isEmpty()?"请在新品项目中核对工程及模具执行依据":"请先在新品项目补齐："+String.join("、",policy.filingMissing());
            throw invalid("新品研发审批已关联原新品项目；"+missing+"。不自动建立型号开发项目或实物套");
        }
    }
    private Map<String,Object> clean(JsonNode form){
        if(form==null||!form.isObject())throw invalid("申请表单不能为空");Map<String,Object> result=new LinkedHashMap<>();
        var it=form.fields();while(it.hasNext()){var e=it.next();if(!COMPONENTS.containsKey(e.getKey()))throw invalid("未知申请字段");
            if(Set.of("generatedCodes","informationComplete","keyPhotos","samplePhotos","attachments").contains(e.getKey()))continue;
            String value=text(e.getValue());if(value!=null&&value.length()>4000)throw invalid("申请字段过长");result.put(e.getKey(),value);
        }
        for(String key:List.of("model","tipo")){String v=text(result.get(key));if(v!=null&&v.length()>255)throw invalid("型号或 Tipo 过长");}
        if(text(result.get("expectedDeliveryDate"))!=null)try{LocalDate.parse(result.get("expectedDeliveryDate").toString());}catch(Exception e){throw invalid("期望交付日期格式无效");}
        if(text(result.get("phonePrice"))!=null)try{if(new java.math.BigDecimal(result.get("phonePrice").toString()).signum()<0)throw invalid("价格不可为负数");}catch(NumberFormatException e){throw invalid("价格必须为数字，币种不明确请在备注保留原值");}
        if(text(result.get("website"))!=null)try{var uri=java.net.URI.create(result.get("website").toString());if(!Set.of("http","https").contains(uri.getScheme())||uri.getUserInfo()!=null)throw invalid("网站须为有效链接");}catch(IllegalArgumentException e){throw invalid("网站须为有效链接");}
        return result;
    }
    public Map<String,Object> targetOptions(long phoneModelId,long productLineId){
        read();codes.references().phone(phoneModelId);access.requireProduct(productLineId);
        var projects=repo.jdbc().queryForList("select product_id as \"projectId\",product_name as \"productName\" from plm_product p where deleted_flag=0 and "+ProjectRegistration.modelCandidateSql("p")+" and parent_product_id=? and phone_model_member_id=? and "+access.visibleSqlClause("product","p.product_id"),productLineId,phoneModelId);
        var items=repo.jdbc().queryForList("select item_id as \"itemId\",coalesce(mold_name,style_name) as \"moldName\",compatible_models as \"compatibleModels\" from plm_collaboration_item i where deleted_flag=0 and product_line_id=? and "+access.visibleSqlClause("item","i.item_id")+" order by item_id",productLineId);
        var applications=existingApplications(phoneModelId,productLineId,null);applications.forEach(row->{repo.view(row);redactResearchSource(row);});
        return Map.of("projects",projects,"items",items,"existingApplications",applications);
    }
    /** Lock only the stable phone identity: product selection must not open a second lock domain. */
    void lockPhones(Long... ids){
        Arrays.stream(ids).filter(Objects::nonNull).distinct().sorted().forEach(id->repo.jdbc().queryForObject("select pg_advisory_xact_lock(hashtextextended(?,610141710))",(rs,n)->true,"phone:"+id));
    }
    private Set<Long> lockResearchProducts(Long... ids){
        var locked=new HashSet<Long>();Arrays.stream(ids).filter(Objects::nonNull).distinct().sorted().forEach(id->{
            if(!repo.jdbc().queryForList("select product_id from plm_product where product_id=? and development_mode='launch_v1' and deleted_flag=0 for update",id).isEmpty())locked.add(id);
        });return locked;
    }
    private void validateExecutionRequest(String intent,String reason,boolean explicit,String requestId,boolean creating){
        if(!Set.of("new","new_set","reuse").contains(intent))throw invalid("开模方式无效");
        if(reason!=null&&reason.length()>4000)throw invalid("新增或重新开模原因过长");
        if((explicit||"new_set".equals(intent))&&reason==null)throw invalid("新增实物套或重新开模须填写原因");
        if(requestId!=null&&!requestId.matches("[A-Za-z0-9:_-]{8,128}"))throw invalid("草稿请求ID无效");
        if(creating&&explicit&&requestId==null)throw invalid("明确新增申请须提供稳定草稿请求ID");
    }
    private Map<String,Object> requestDraft(String key,Long phone,Long line){
        if(text(key)==null)return null;
        if(!key.matches("[A-Za-z0-9:_-]{8,128}"))throw invalid("草稿请求ID无效");
        var rows=repo.jdbc().queryForList(MoldApplicationRepository.SELECT+" where a.applicant_id=? and a.draft_request_id=? and a.deleted_flag=0",access.actorId(),key);
        if(rows.isEmpty())return null;var prior=repo.view(rows.get(0));
        if(!Objects.equals(phone,prior.get("phoneModelId"))||(line!=null&&prior.get("productLineId")!=null&&!Objects.equals(line,prior.get("productLineId"))))throw new BusinessException(40901,"草稿请求ID已对应其他手机或产品，请重新发起操作");
        return require(((Number)prior.get("applicationId")).longValue(),false);
    }
    private List<Map<String,Object>> existingApplications(long phone,Long line,Long exclude){
        var args=new ArrayList<Object>();args.add(phone);
        String where=" where a.deleted_flag=0 and a.phone_model_id=? and ("+MoldApplicationBusinessStatus.SQL+") not in ('rejected','withdrawn','terminated')";
        if(line!=null){where+=" and (a.product_line_id=? or a.product_line_id is null)";args.add(line);}
        if(exclude!=null){where+=" and a.application_id<>?";args.add(exclude);}
        if(repo.jdbc().queryForObject("select count(*) from plm_mold_application a"+where+" and not ("+visible()+")",Long.class,args.toArray())>0)throw new BusinessException(40901,"已有相关申请未获授权，请联系工程或管理员核对，不能重复创建");
        return repo.jdbc().queryForList(MoldApplicationRepository.SELECT+where+" order by case when a.status='draft' and a.applicant_id="+access.actorId()+" then 0 else 1 end,a.updated_at desc,a.application_id desc",args.toArray());
    }
    private boolean existingMolds(long phone,Long line){
        var args=new ArrayList<Object>();args.add(phone);args.add(phone);
        String where=" where i.deleted_flag=0 and (exists(select 1 from plm_collaboration_phone_member m where m.item_id=i.item_id and m.phone_model_id=?) or exists(select 1 from plm_mold_application a where a.item_id=i.item_id and a.phone_model_id=? and a.deleted_flag=0))";
        if(line!=null){where+=" and i.product_line_id=?";args.add(line);}
        if(repo.jdbc().queryForObject("select count(*) from plm_collaboration_item i"+where+" and not ("+access.visibleSqlClause("item","i.item_id")+")",Long.class,args.toArray())>0)throw new BusinessException(40901,"已有相关模具资料未获授权，请联系工程或管理员核对，不能重复创建");
        return repo.jdbc().queryForObject("select count(*) from plm_collaboration_item i"+where,Long.class,args.toArray())>0;
    }
    private void checkExistingExecution(Long phone,Long line,long current,String intent,boolean explicit){
        if(phone==null)return;var applications=existingApplications(phone,line,current);boolean molds=existingMolds(phone,line);
        if(!explicit&&!"reuse".equals(intent)&&(!applications.isEmpty()||molds))throw new BusinessException(40901,"已有同手机的申请或模具，请继续已有申请或确认共模；新增实物须明确选择并填写原因");
    }
    private Map<String,Object> sourceResult(Map<String,Object> row,Map<String,Object> snapshot,boolean existing,HttpServletRequest request){
        if(snapshot!=null){
            long id=((Number)row.get("applicationId")).longValue();row=repo.row(id,true);
            JsonNode merged=MoldApplicationSourceSnapshots.merge((JsonNode)row.get("sourceSnapshot"),json.valueToTree(snapshot),json);
            if(!Objects.equals(row.get("sourceSnapshot"),merged)){
                var actor=access.actor();repo.jdbc().update("update plm_mold_application set source_snapshot_json=?::jsonb,version_no=version_no+1,updated_at=now(),updated_by=? where application_id=?",repo.json(merged),actor.username(),id);
                var evidence=new LinkedHashMap<String,Object>();evidence.put("phoneModelId",row.get("phoneModelId"));evidence.put("sourceSnapshot",merged);
                audit("MOLD_APPLICATION_SOURCE_APPEND",id,evidence,request,actor);
            }
            row=repo.row(id,false);
        }
        row.put("alreadyExists",existing);row.put("requiresExplicitIntent",existing);row.put("canEditDraft",editableDraft(row));row.put("submissionPolicy",submissionPolicy(row,repo.schema(props.getProcessCode())));redactResearchSource(row);return row;
    }
    public Map<String,Object> startFromPhone(SourceDraft input,HttpServletRequest request){
        createPermission();if(input.phoneModelId()==null)throw invalid("请先匹配标准手机型号");
        var phone=codes.references().phone(input.phoneModelId());if(!"enabled".equals(phone.get("status")))throw invalid("标准手机已停用");
        Map<String,Object> source=null;
        if(text(input.sourceModelKey())!=null)source=marketWorkflow.draftContext(input.sourceModelKey());
        else if(input.sourceKind()!=null&&input.sourceId()!=null)source=marketWorkflow.sourceContext(input.sourceKind(),input.sourceId(),input.phoneModelId());
        if(source!=null&&!Objects.equals(((Number)source.get("phoneModelId")).longValue(),input.phoneModelId()))throw invalid("监控来源与标准手机不一致");
        final Map<String,Object> snapshot=source;
        return tx().execute(tx->{
            lockPhones(input.phoneModelId());
            if(input.productLineId()!=null){access.requireProduct(input.productLineId());codes.requireOnSaleProduct(input.productLineId(),null);}
            var retried=requestDraft(input.requestId(),input.phoneModelId(),input.productLineId());
            if(retried!=null)return sourceResult(retried,snapshot,true,request);
            String intent=Objects.requireNonNullElse(text(input.executionIntent()),"new");
            boolean explicit=Boolean.TRUE.equals(input.newApplication())||"new_set".equals(intent);
            validateExecutionRequest(intent,input.executionReason(),explicit,input.requestId(),true);
            var existing=existingApplications(input.phoneModelId(),input.productLineId(),null);
            if(!explicit&&!existing.isEmpty())return sourceResult(repo.view(existing.get(0)),snapshot,true,request);
            boolean molds=existingMolds(input.phoneModelId(),input.productLineId());
            if(!explicit&&molds)return Map.of("alreadyExists",false,"requiresExplicitIntent",true,"existingMold",true,"phoneModelId",input.phoneModelId());
            var form=new LinkedHashMap<String,Object>();form.put("model",phone.get("modelName"));if(snapshot!=null){var sources=json.valueToTree(snapshot).path("sources");if(sources.isArray()&&!sources.isEmpty()){var first=sources.get(0);String url=text(first.path("source_url"));if(url==null)url=text(first.path("sourceUrl"));if(url!=null)form.put("website",url);form.put("remark","来源：手机新品监控；市场信息以来源快照为准。");}}
            Long code=phone.get("codeItemId")==null?null:((Number)phone.get("codeItemId")).longValue();
            var row=save(null,new Draft(json.valueToTree(form),input.productLineId(),code,null,input.phoneModelId(),intent,input.targetItemId(),input.targetMoldSetId(),input.targetProjectId(),input.executionReason(),input.requestId(),explicit),request);
            return sourceResult(row,snapshot,Boolean.TRUE.equals(row.get("alreadyExists")),request);
        });
    }
    private List<Map<String,Object>> researchCandidates(long projectId,Long phone) {
        var args=new ArrayList<Object>();args.add(projectId);
        String where=" where a.deleted_flag=0 and a.product_line_id=?";
        if(phone!=null){where+=" and (a.phone_model_id=? or a.phone_model_id is null)";args.add(phone);}
        return repo.jdbc().queryForList(MoldApplicationRepository.SELECT+where+" order by case when a.phone_model_id is null then 1 else 0 end,case when a.status='draft' then 0 else 1 end,a.updated_at desc,a.application_id desc",args.toArray());
    }
    private Map<String,Object> researchForm(Map<String,Object> context) {
        var form=new LinkedHashMap<String,Object>();form.put("remark",researchRemark(context));
        if(context.get("phoneModelId")!=null&&context.get("phoneModelName")!=null)form.put("model",context.get("phoneModelName"));
        if(context.get("expectedDeliveryDate")!=null)form.put("expectedDeliveryDate",context.get("expectedDeliveryDate"));
        var configs=codes.configs().stream().filter(c->Objects.equals(c.get("productLineId"),context.get("projectId"))).toList();
        if(configs.size()==1)form.put("tipo",configs.get(0).get("tipo"));return form;
    }
    /** Read-only preview: opening or cancelling the editor creates no application, files or workflow events. */
    public Map<String,Object> researchContext(long projectId,Long phoneModelId,Long version) {
        createPermission();if(projectId<1||phoneModelId!=null&&phoneModelId<1)throw invalid("请选择有效的新品项目和标准手机型号");
        if(productDevelopment==null)throw invalid("新品研发服务暂不可用，请稍后重试");
        var context=productDevelopment.moldApplicationPreviewContext(projectId,phoneModelId,version);
        Long phone=(Long)context.get("phoneModelId");var standardPhone=phone==null?null:codes.references().phone(phone);
        var form=researchForm(context);var missing=new ArrayList<String>((List<String>)context.get("missingFields"));
        if(text(form.get("tipo"))==null)missing.add("LEMOS 类型");missing.add("关键图片");context.put("missingFields",missing);
        var result=new LinkedHashMap<String,Object>();result.put("applicationId",null);result.put("status","draft");result.put("canEditDraft",true);result.put("productLineId",projectId);result.put("phoneModelId",phone);result.put("phoneCodeItemId",standardPhone==null?null:standardPhone.get("codeItemId"));result.put("form",form);result.put("files",List.of());result.put("executionIntent","new");result.put("sourceSnapshot",Map.of("research",context));
        var existing=new ArrayList<Map<String,Object>>();
        for(var raw:researchCandidates(projectId,phone))try{existing.add(detail(((Number)raw.get("applicationId")).longValue()));}
        catch(BusinessException denied){if(denied.getCode()!=40103)throw denied;result.put("existingApplicationNotice","相关申请可能受权限限制；保存时仍会核对重复申请，请联系项目工程人员核对。");}
        result.put("existingApplications",existing);if(existing.size()==1)result.put("existingApplication",existing.get(0));result.put("submissionPolicy",submissionPolicy(result,repo.schema(props.getProcessCode())));return result;
    }
    /** The existing manual writer saves supplied editor fields and research evidence in one transaction. */
    public Map<String,Object> startFromResearch(ResearchDraft input,HttpServletRequest request) {
        createPermission();if(input==null||input.projectId()<1||input.phoneModelId()!=null&&input.phoneModelId()<1)throw invalid("请选择有效的新品项目和标准手机型号");
        validateExecutionRequest("new",null,false,input.requestId(),true);if(text(input.requestId())==null)throw invalid("请提供稳定的草稿请求ID");
        if(productDevelopment==null)throw invalid("新品研发服务暂不可用，请稍后重试");
        productDevelopment.moldApplicationPreviewContext(input.projectId(),input.phoneModelId(),input.version());
        return tx().execute(tx->{
            var candidates=researchCandidates(input.projectId(),input.phoneModelId());candidates.forEach(repo::view);
            Map<String,Object> candidate=candidates.isEmpty()?null:candidates.get(0);
            if(input.phoneModelId()==null){candidate=candidates.stream().filter(r->r.get("phoneModelId")==null).findFirst().orElse(candidate);if(candidates.size()>1&&candidate.get("phoneModelId")!=null)throw invalid("该新品已有多个申请，请选择标准手机型号或继续已有申请");}
            Long candidatePhone=candidate==null?null:(Long)candidate.get("phoneModelId");
            lockPhones(input.phoneModelId(),candidatePhone);
            var context=productDevelopment.moldApplicationDraftContext(input.projectId(),input.phoneModelId(),input.version());
            if(Boolean.TRUE.equals(context.get("suppliersRestricted")))throw new BusinessException(40103,"新品供应商资料未获授权，请由项目工程人员发起申请");
            var latest=researchCandidates(input.projectId(),input.phoneModelId());
            if(candidate==null&&!latest.isEmpty()){
                candidate=repo.view(latest.get(0));candidatePhone=(Long)candidate.get("phoneModelId");
                if(candidatePhone!=null&&!Objects.equals(candidatePhone,input.phoneModelId()))throw new BusinessException(40901,"已有申请手机身份刚被更新，请刷新后继续原申请");
            }
            Map<String,Object> row;boolean existing=candidate!=null;
            if(existing){row=require(((Number)candidate.get("applicationId")).longValue(),true);if(!Objects.equals(candidatePhone,row.get("phoneModelId")))throw new BusinessException(40901,"申请手机身份已更新，请刷新后继续");}
            else {
                var supplied=input.draft();
                if(supplied!=null&&((supplied.productLineId()!=null&&!Objects.equals(supplied.productLineId(),input.projectId()))||!Objects.equals(supplied.phoneModelId(),input.phoneModelId())))throw invalid("新品来源产品或手机选择不一致，请刷新核对");
                var form=researchForm(context);if(supplied!=null)form.putAll(clean(supplied.form()));
                Long code=supplied==null?null:supplied.phoneCodeItemId();
                row=save(null,new Draft(json.valueToTree(form),input.projectId(),code,null,input.phoneModelId(),supplied==null?"new":supplied.executionIntent(),supplied==null?null:supplied.targetItemId(),supplied==null?null:supplied.targetMoldSetId(),supplied==null?null:supplied.targetProjectId(),supplied==null?null:supplied.executionReason(),input.requestId(),supplied!=null&&Boolean.TRUE.equals(supplied.newApplication())),request);
                existing=Boolean.TRUE.equals(row.get("alreadyExists"));
            }
            if(row.get("applicationId")==null)return row;
            long id=((Number)row.get("applicationId")).longValue();
            if(row.get("productLineId")!=null&&!Objects.equals(row.get("productLineId"),input.projectId()))throw new BusinessException(40901,"已有申请的产品已变化，请刷新后核对原申请");
            if(!"draft".equals(row.get("status"))||!ownsDraft(row))return sourceResult(row,null,true,request);
            var original=(JsonNode)row.get("sourceSnapshot");
            if(original!=null&&original.path("research").isObject()){
                if(original.path("research").path("projectId").asLong()!=input.projectId())throw new BusinessException(40901,"已有草稿属于其他新品项目，请核对原申请");
                return sourceResult(row,null,true,request);
            }
            if(row.get("productLineId")==null)row=save(id,new Draft((JsonNode)row.get("form"),input.projectId(),(Long)row.get("phoneCodeItemId"),((Number)row.get("version")).longValue(),(Long)row.get("phoneModelId"),Objects.toString(row.get("executionIntent"),"new"),(Long)row.get("targetItemId"),(Long)row.get("targetMoldSetId"),(Long)row.get("targetProjectId"),text(row.get("executionReason")),text(row.get("draftRequestId")),Boolean.TRUE.equals(row.get("newApplication"))),request);
            @SuppressWarnings("unchecked") var references=(List<Map<String,Object>>)context.get("references");
            var inherited=media.inheritResearchFiles(id,input.projectId(),references,access.actor().username(),access);
            var snapshot=new LinkedHashMap<String,Object>();snapshot.put("phoneModelId",row.get("phoneModelId"));snapshot.put("research",context);snapshot.put("sources",List.of(Map.of("kind","product_research","id",input.projectId())));
            row=sourceResult(row,snapshot,existing,request);
            var evidence=new LinkedHashMap<String,Object>();evidence.put("projectId",input.projectId());evidence.put("phoneModelId",row.get("phoneModelId"));evidence.put("sourceAttachmentIds",inherited);
            audit("MOLD_APPLICATION_RESEARCH_DRAFT",id,evidence,request,access.actor());return row;
        });
    }
    private static String researchRemark(Map<String,Object> source) {
        String value="新品项目："+Objects.toString(source.get("productName"),"")+"（"+Objects.toString(source.get("productCode"),"")+"）\n项目负责人："+Objects.toString(source.get("ownerName"),"待补充")+"\n开发要求：\n"+Objects.toString(source.get("requirements"),"");
        return value.length()<=4000?value:value.substring(0,3900)+"\n完整开发要求及供应商、附件版本见本申请的新品项目来源。";
    }

    static String researchSuppliers(Map<String,Object> source) {
        var roles=Map.of("mold","模具","component","部件","processing","加工","finished_product","成品供货");
        var lines=new ArrayList<String>();
        if(source.get("suppliers") instanceof List<?> suppliers)for(Object value:suppliers){
            @SuppressWarnings("unchecked") var supplier=(Map<String,Object>)value;
            String name="internal".equals(supplier.get("mode"))?"内部制作 "+Objects.toString(supplier.get("internalName"),""):Objects.toString(supplier.get("supplierName"),Objects.toString(supplier.get("supplierCode"),"待确定"));
            String scope=Objects.toString(supplier.get("scope"),"").trim();lines.add(roles.getOrDefault(Objects.toString(supplier.get("role"),""),"分工")+"："+name+(scope.isBlank()?"":"（"+scope+"）"));
        }
        return lines.isEmpty()?"":"供应商分工"+(Boolean.TRUE.equals(source.get("supplierPlanConfirmed"))?"（已确认方案）":"（已保存，待工程确认）")+"："+String.join("；",lines);
    }
    public Map<String,Object> save(Long id,Draft draft,HttpServletRequest request){
        createPermission();var actor=access.actor();return tx().execute(status->{
            var form=clean(draft.form());Map<String,Object> before=null;
            if(id!=null)before=require(id,false);
            lockPhones(draft.phoneModelId(),before==null?null:(Long)before.get("phoneModelId"));
            if(draft.productLineId()!=null)access.requireProduct(draft.productLineId());
            // The product is the known identity until a phone is chosen. Manual and research writers share its row lock.
            var lockedProducts=draft.phoneModelId()==null||before!=null&&before.get("phoneModelId")==null?lockResearchProducts(draft.productLineId(),before==null?null:(Long)before.get("productLineId")):Set.<Long>of();
            boolean unknownResearchPhone=draft.phoneModelId()==null&&lockedProducts.contains(draft.productLineId());
            if(id!=null){lockResearchProject(before);before=require(id,true);ownDraft(before);if(draft.version()==null||((Number)before.get("version")).longValue()!=draft.version())throw new BusinessException(40901,"申请已被更新，请刷新核对");}
            if(before!=null&&before.get("sourceSnapshot") instanceof JsonNode source&&source.path("research").isObject()){
                var research=source.path("research");
                if(!Objects.equals(draft.productLineId(),research.path("projectId").asLong()))throw invalid("新品来源的产品已固定，不能将草稿改为其他产品；手机缺项可在本申请中补齐");
            }
            if(before==null||!Objects.equals(before.get("productLineId"),draft.productLineId())||!Objects.equals(text(before.get("tipo")),text(form.get("tipo"))))codes.requireOnSaleProduct(draft.productLineId(),text(form.get("tipo")));
            Long phoneId=draft.phoneModelId(),codeId=draft.phoneCodeItemId();
            if(phoneId!=null){var phone=codes.references().phone(phoneId);if(!"enabled".equals(phone.get("status")))throw invalid("标准手机已停用");form.put("model",phone.get("modelName"));Long actual=phone.get("codeItemId")==null?null:((Number)phone.get("codeItemId")).longValue();if(codeId!=null&&!Objects.equals(actual,codeId))throw invalid("手机身份与编码不一致");codeId=actual;}
            String intent=Objects.requireNonNullElse(text(draft.executionIntent()),"new");if(!Set.of("new","new_set","reuse").contains(intent))throw invalid("开模方式无效");
            boolean explicit=Boolean.TRUE.equals(draft.newApplication())||"new_set".equals(intent);
            String executionReason=text(draft.executionReason()),requestId=text(draft.draftRequestId());
            validateExecutionRequest(intent,executionReason,explicit,requestId,id==null);
            if(id==null){var retried=requestDraft(requestId,phoneId,draft.productLineId());if(retried!=null)return sourceResult(retried,null,true,request);}
            if(unknownResearchPhone){
                var pending=repo.jdbc().queryForList(MoldApplicationRepository.SELECT+" where a.deleted_flag=0 and a.product_line_id=? and a.phone_model_id is null and a.status='draft'"+(id==null?"":" and a.application_id<>"+id)+" order by a.application_id",draft.productLineId());
                if(!pending.isEmpty()){
                    var prior=require(((Number)pending.get(0).get("applicationId")).longValue(),false);
                    if(id==null)return sourceResult(prior,null,true,request);
                    throw new BusinessException(40901,"该新品已有待确定手机的申请，请继续原草稿并补齐实际机型，不能重复保存未确定机型的申请");
                }
            }
            if(phoneId!=null){var existing=existingApplications(phoneId,draft.productLineId(),id);boolean molds=existingMolds(phoneId,draft.productLineId());
                if(!explicit&&!"reuse".equals(intent)&&!existing.isEmpty()){
                    if(id==null)return sourceResult(repo.view(existing.get(0)),null,true,request);
                    throw new BusinessException(40901,"已有同手机的申请，请继续已有申请；确需新增须明确选择新增套或重新开模并填写原因");
                }
                if(!explicit&&!"reuse".equals(intent)&&molds)throw new BusinessException(40901,"已有模具资料，请确认共模；需要新增实物时请选择新增套并填写原因");
            }
            validateTargets(draft.productLineId(),phoneId,intent,draft.targetItemId(),draft.targetMoldSetId(),draft.targetProjectId(),false);
            Long applicationId=id;
            if(id==null)applicationId=repo.jdbc().queryForObject("insert into plm_mold_application(process_code,model,tipo,form_data,applicant_id,product_line_id,phone_code_item_id,phone_model_id,execution_intent,target_item_id,target_mold_set_id,target_project_id,execution_reason,new_application_requested,draft_request_id,created_by,updated_by) values(?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?,?,?,?) returning application_id",Long.class,props.getProcessCode(),form.get("model"),form.get("tipo"),repo.json(form),actor.userId(),draft.productLineId(),codeId,phoneId,intent,draft.targetItemId(),draft.targetMoldSetId(),draft.targetProjectId(),executionReason,explicit,requestId==null?UUID.randomUUID().toString():requestId,actor.username(),actor.username());
            else repo.jdbc().update("update plm_mold_application set model=?,tipo=?,form_data=?::jsonb,product_line_id=?,phone_code_item_id=?,phone_model_id=?,execution_intent=?,target_item_id=?,target_mold_set_id=?,target_project_id=?,execution_reason=?,new_application_requested=?,generated_codes=null,version_no=version_no+1,updated_at=now(),updated_by=? where application_id=?",form.get("model"),form.get("tipo"),repo.json(form),draft.productLineId(),codeId,phoneId,intent,draft.targetItemId(),draft.targetMoldSetId(),draft.targetProjectId(),executionReason,explicit,actor.username(),id);
            audit("MOLD_APPLICATION_DRAFT_SAVE",applicationId,Map.of("form",form,"executionIntent",intent),request,actor);return responseRow(applicationId);
        });
    }
    private void validateTargets(Long line,Long phone,String intent,Long item,Long set,Long project,boolean submitting){
        if(submitting&&phone==null)throw invalid("提交前请选择标准手机型号");
        if(item!=null){access.requireItem(item);var row=repo.jdbc().queryForMap("select product_line_id from plm_collaboration_item where item_id=?",item);if(line!=null&&!Objects.equals(row.get("product_line_id"),line))throw invalid("所选模具主档与产品线不一致");}
        if(set!=null){access.requireMold(set);if(item==null||repo.jdbc().queryForObject("select count(*) from plm_collaboration_mold_set where mold_set_id=? and item_id=? and deleted_flag=0",Long.class,set,item)!=1)throw invalid("原实物套不属于所选主档");}
        if(project!=null){access.requireProduct(project);var targets=repo.jdbc().queryForList("select parent_product_id,phone_model_member_id from plm_product p where product_id=? and deleted_flag=0 and "+ProjectRegistration.modelCandidateSql("p"),project);if(targets.size()!=1)throw invalid("请选择型号项目，不能用颜色 SKU 替代项目");var row=targets.get(0);if(!Objects.equals(row.get("parent_product_id"),line)||!Objects.equals(row.get("phone_model_member_id"),phone))throw invalid("目标项目的产品线或标准手机不一致");}
        if(submitting&&line!=null&&phone!=null&&project==null&&repo.jdbc().queryForObject("select count(*) from plm_product p where parent_product_id=? and phone_model_member_id=? and "+ProjectRegistration.modelCandidateSql("p")+" and deleted_flag=0",Long.class,line,phone)>0)throw invalid("已有同型号项目，请明确选择项目关联目标");
        if(submitting&&Set.of("new_set","reuse").contains(intent)&&item==null)throw invalid("提交前请选择已有模具主档");
        if(submitting&&"reuse".equals(intent)&&set==null)throw invalid("复用须选择原实物套");
        if("new".equals(intent)&&(item!=null||set!=null))throw invalid("新主档不能同时指定已有模具；请选择加开一套");
    }
    private void assertResearchScope(Map<String,Object> row) {
        if(productDevelopment!=null)productDevelopment.assertMoldApplicationProductScope((Long)row.get("productLineId"));
        if(productDevelopment!=null&&row.get("sourceSnapshot") instanceof JsonNode source&&source.path("research").isObject()){
            var research=source.path("research");
            if(!Objects.equals(row.get("productLineId"),research.path("projectId").asLong()))throw invalid("新品来源的产品身份不一致，请回原项目核对");
            if(row.get("phoneModelId")==null&&submissionPolicy(row,repo.schema(props.getProcessCode())).requiresStandardPhone())throw invalid("提交前请选择标准手机型号");
        }
    }
    public Map<String,Object> reserve(long id,long version,HttpServletRequest request){
        return reserve(id,version,null,null,null,null,request);
    }
    public Map<String,Object> reserve(long id,long version,Long phoneModelId,Long itemId,Long moldSetId,String compatibilityDigest,HttpServletRequest request){
        createPermission();var actor=access.actor();return tx().execute(tx->{var original=require(id,false);lockPhones(phoneModelId,(Long)original.get("phoneModelId"));lockResearchProject(original);var row=require(id,true);ownDraft(row);if(((Number)row.get("version")).longValue()!=version)throw new BusinessException(40901,"申请已更新，请刷新");
            assertResearchScope(row);
            if(phoneModelId==null&&row.get("phoneModelId")==null)throw invalid("预留正式模具编码前请选择标准手机型号");
            codes.requireOnSaleProduct((Long)row.get("productLineId"),text(row.get("tipo")));
            if(row.get("sourceSnapshot") instanceof JsonNode source&&source.path("research").isObject()&&!Objects.equals(codes.config(text(row.get("tipo"))).get("productLineId"),row.get("productLineId")))throw invalid("所选Tipo与新品产品不一致，请选择该产品的LEMOS类型配置，不能改变来源产品");
            Long chosen=phoneModelId==null?(Long)row.get("phoneModelId"):phoneModelId;
            checkExistingExecution(chosen,(Long)row.get("productLineId"),id,Objects.toString(row.get("executionIntent"),"new"),Boolean.TRUE.equals(row.get("newApplication"))||moldSetId!=null);
            Long selectedPhone=phoneModelId==null?(Long)row.get("phoneModelId"):phoneModelId;var r=codes.reserve(new MoldApplicationCodes.Request("application:"+id+":"+version,text(row.get("tipo")),text(row.get("model")),(Long)row.get("phoneCodeItemId"),id,selectedPhone,itemId,moldSetId,compatibilityDigest),actor.username(),"new_set".equals(row.get("executionIntent"))?(Long)row.get("targetItemId"):null);
            if(row.get("sourceSnapshot") instanceof JsonNode source&&source.path("research").isObject()&&!Objects.equals(r.get("productLineId"),row.get("productLineId")))throw invalid("Tipo配置的产品关联已变化，请刷新后核对原新品配置");
            repo.jdbc().update("update plm_mold_application set generated_codes=?,phone_code_item_id=?,product_line_id=?,phone_model_id=coalesce(?,phone_model_id),execution_intent=case when ? then 'reuse' else execution_intent end,target_item_id=coalesce(?,target_item_id),target_mold_set_id=coalesce(?,target_mold_set_id),version_no=version_no+1,updated_at=now() where application_id=?",r.get("generatedCodes"),r.get("phoneCodeItemId"),r.get("productLineId"),phoneModelId,moldSetId!=null,itemId,moldSetId,id);
            audit("MOLD_APPLICATION_CODES_RESERVE",id,r,request,actor);return responseRow(id);});
    }
    public Map<String,Object> upload(long id,String category,MultipartFile file,HttpServletRequest request){createPermission();var actor=access.actor();return tx().execute(tx->{var r=lockEditableDraft(id);long attachment=media.upload(id,category,file,actor.username());repo.jdbc().update("update plm_mold_application set version_no=version_no+1,updated_at=now() where application_id=?",id);audit("MOLD_APPLICATION_UPLOAD",id,Map.of("attachmentId",attachment),request,actor);return detail(id);});}
    public Map<String,Object> upload(long id,String category,MultipartFile file,String selection,HttpServletRequest request){createPermission();var actor=access.actor();return tx().execute(tx->{lockEditableDraft(id);long attachment=media.upload(id,category,file,actor.username(),selection);repo.jdbc().update("update plm_mold_application set version_no=version_no+1,updated_at=now() where application_id=?",id);audit("MOLD_APPLICATION_UPLOAD",id,Map.of("attachmentId",attachment),request,actor);return detail(id);});}
    public Map<String,Object> selectFiles(long id,List<MoldApplicationMedia.Selection> selections,HttpServletRequest request){createPermission();if(selections==null||selections.isEmpty()||selections.size()>50)throw invalid("每次请选择1至50个文件");var actor=access.actor();return tx().execute(tx->{lockEditableDraft(id);for(var s:selections)media.select(id,s,actor.username());repo.jdbc().update("update plm_mold_application set version_no=version_no+1 where application_id=?",id);audit("MOLD_APPLICATION_FILE_SELECTION",id,selections,request,actor);return detail(id);});}
    public Map<String,Object> discardSelection(long id,long attachment,HttpServletRequest request){createPermission();var actor=access.actor();return tx().execute(tx->{lockEditableDraft(id);media.discardSelection(id,attachment);repo.jdbc().update("update plm_mold_application set version_no=version_no+1 where application_id=?",id);audit("MOLD_APPLICATION_SELECTION_DISCARD",id,Map.of("attachmentId",attachment),request,actor);return detail(id);});}
    public byte[] download(long id,long attachment){require(id,false);media.requireResearchFileAccess(id,attachment,access);return media.bytes(id,attachment);}
    public void saveConfig(String tipo,long line,String prefix,List<Map<String,String>> materials,Long version,HttpServletRequest request){admin();if(controls(repo.schema(props.getProcessCode()),json).stream().filter(c->"tipo".equals(c.get("key"))).noneMatch(c->((List<?>)c.get("options")).contains(tipo)))throw invalid("Tipo 不在当前钉钉模板中");var actor=access.actor();tx().executeWithoutResult(tx->{codes.saveConfig(tipo,line,prefix,materials,version,actor.username());audit("MOLD_APPLICATION_CONFIG_SAVE",0,Map.of("tipo",tipo,"productLineId",line,"productSpecificCode",prefix,"materials",materials),request,actor);});}
    public Map<String,Object> submit(long id,Submit input,HttpServletRequest request){
        createPermission();var actor=access.actor();if(!props.isWriteEnabled()||!props.getAllowedSubmitters().contains(actor.userId()))throw new BusinessException(40103,"该账号尚未启用钉钉提交");
        if(!input.confirmFormal())throw invalid("请确认将提交到正式钉钉审批");
        var current=require(id,false);if(current.get("instanceId")!=null)return detail(id);
        assertResearchScope(current);
        codes.requireOnSaleProduct((Long)current.get("productLineId"),text(current.get("tipo")));
        var identity=repo.jdbc().queryForList("select dingtalk_user_id from plm_pilot_account where user_id=?",actor.userId());
        if(identity.size()!=1||text(identity.get(0).get("dingtalk_user_id"))==null)throw invalid("请管理员先绑定真实钉钉UserID");
        String uid=identity.get(0).get("dingtalk_user_id").toString();var user=client.user(uid);var departments=new ArrayList<Long>();user.path("dept_id_list").forEach(d->departments.add(d.asLong()));
        Long dept=input.departmentId();if(dept==null&&departments.size()==1)dept=departments.get(0);if(dept==null||!departments.contains(dept))throw invalid("请确认发起人在钉钉中的所属部门");
        final long departmentId=dept;var schema=client.schema(props.getProcessCode());repo.saveSchema(props.getProcessCode(),schema);
        var prepared=tx().execute(tx->{lockPhones((Long)current.get("phoneModelId"));lockResearchProject(current);var row=require(id,true);ownDraft(row);if(((Number)row.get("version")).longValue()!=input.version())throw new BusinessException(40901,"申请已更新，请刷新后提交");
            assertResearchScope(row);
            codes.requireOnSaleProduct((Long)row.get("productLineId"),text(row.get("tipo")));
            String intent=Objects.toString(row.get("executionIntent"),"new");boolean explicit=Boolean.TRUE.equals(row.get("newApplication"))||"new_set".equals(intent);
            validateExecutionRequest(intent,text(row.get("executionReason")),explicit,null,false);
            checkExistingExecution((Long)row.get("phoneModelId"),(Long)row.get("productLineId"),id,intent,explicit);
            var policy=submissionPolicy(row,schema);
            var form=json.convertValue(row.get("form"),new TypeReference<Map<String,Object>>(){});
            if(text(row.get("executionReason"))!=null)form.put("remark",Objects.toString(form.get("remark"),"")+"\n新增或重新开模原因："+row.get("executionReason"));
            for(String key:List.of("model","tipo","expectedDeliveryDate"))if(text(form.get(key))==null)throw invalid("请完善型号、Tipo和期望交期");
            if("research".equals(policy.mode())) {
                if(!"new".equals(intent)||explicit||row.get("targetItemId")!=null||row.get("targetMoldSetId")!=null||row.get("targetProjectId")!=null)
                    throw invalid("新品研发审批挂在原新品项目下；新增套、共模和型号项目目标请从型号开发办理");
                if(!policy.allowedTipos().contains(text(form.get("tipo"))))throw invalid("请选择当前钉钉模板中未映射其他产品的真实Tipo，不能用其他款式代替新品");
                // No fake code or generated-code acknowledgement is sent for a research approval.
                form.remove("generatedCodes");form.remove("informationComplete");
                var source=(com.fasterxml.jackson.databind.node.ObjectNode)((JsonNode)row.get("sourceSnapshot")).deepCopy();
                source.set("researchSubmission",json.valueToTree(Map.of("mode","research","projectId",row.get("productLineId"),"submittedAt",OffsetDateTime.now().toString())));
                repo.jdbc().update("update plm_mold_application set source_snapshot_json=?::jsonb where application_id=?",repo.json(source),id);
            } else {
            form.put("generatedCodes",row.get("generatedCodes"));form.put("informationComplete","点击生成编码");
            if(text(form.get("generatedCodes"))==null)throw invalid("请完善型号、Tipo、交期并生成编码");
            var config=codes.config(text(row.get("tipo")));if(!Objects.equals(config.get("productLineId"),row.get("productLineId")))throw invalid("产品线配置已改变，请重新生成编码");
            if(row.get("phoneCodeItemId")==null||!hasValidCodes(text(row.get("generatedCodes"))))throw invalid("请重新生成有效模具编码");
            var phone=repo.jdbc().queryForList("select code_value from plm_code_item where code_item_id=? and code_type='phone_model' and status='enabled' and deleted_flag=0",row.get("phoneCodeItemId"));if(phone.size()!=1)throw invalid("手机型号编码已改变或停用，请重新选择");
            var expected=new HashSet<String>();for(var material:(JsonNode)config.get("materials"))expected.add("M"+config.get("productSpecificCode")+material.path("code").asText()+phone.get(0).get("code_value"));
            var reserved=codes.reservedCompatibility(id,text(row.get("generatedCodes")));
            if(reserved!=null){var compatible=codes.references().requireCompatibility(((Number)row.get("productLineId")).longValue(),((Number)reserved.get("phoneModelId")).longValue(),((Number)reserved.get("itemId")).longValue(),((Number)reserved.get("moldSetId")).longValue(),reserved.get("compatibilityDigest").toString());var actual=new HashSet<String>();for(var part:(JsonNode)compatible.get("parts"))actual.add(part.path("code").asText());if(!actual.equals(new HashSet<>(MoldApplicationForms.codes(text(row.get("generatedCodes"))))))throw invalid("原部件刻码已变化，请重新核对");}
            else {var newSet=codes.reservedNewSet(id,text(row.get("generatedCodes")));if(newSet!=null){var currentMaster=repo.jdbc().queryForMap("select coalesce(nullif(engraved_code,''),phone_model_code) as code,product_line_id from plm_collaboration_item where item_id=? and deleted_flag=0",newSet.get("itemId"));if(!Objects.equals(((Number)newSet.get("itemId")).longValue(),row.get("targetItemId"))||!Objects.equals(newSet.get("engravedCode"),currentMaster.get("code"))||!Objects.equals(((Number)newSet.get("productLineId")).longValue(),currentMaster.get("product_line_id")))throw invalid("加开主档原刻码已变化，请重新预留核对");expected.clear();for(var material:(JsonNode)config.get("materials"))expected.add("M"+config.get("productSpecificCode")+material.path("code").asText()+currentMaster.get("code"));}if(!expected.equals(new HashSet<>(MoldApplicationForms.codes(text(row.get("generatedCodes"))))))throw invalid("产品线或材质配置已改变，请重新生成编码");}
            validateTargets((Long)row.get("productLineId"),(Long)row.get("phoneModelId"),Objects.toString(row.get("executionIntent"),"new"),(Long)row.get("targetItemId"),(Long)row.get("targetMoldSetId"),(Long)row.get("targetProjectId"),true);
            }
            media.requireResearchFileAccess(id,null,access);
            var files=media.internal(id);if(files.stream().noneMatch(f->"key_photo".equals(f.get("file_category"))))throw invalid("请上传关键图片");
            if(files.stream().anyMatch(f->!"downloaded".equals(f.get("download_status"))))throw invalid("所选图片或附件尚未归档完成");
            String key=UUID.randomUUID().toString();repo.jdbc().update("update plm_mold_application set status='submitting',provisioning_status='pending',submission_key=?,schema_version=?,schema_snapshot=?::jsonb,originator_user_id=?,originator_name=?,department_id=?,version_no=version_no+1,updated_at=now() where application_id=?",key,schema.path("gmtModified").asText(),repo.json(schema),uid,user.path("name").asText(actor.displayName()),departmentId,id);
            audit("MOLD_APPLICATION_SUBMIT_START",id,Map.of("requestId",key),request,actor);return Map.of("form",form,"key",key);
        });
        boolean createStarted=false;String confirmedInstance=null;
        try {
            @SuppressWarnings("unchecked") var form=(Map<String,Object>)prepared.get("form");
            var research=((JsonNode)current.get("sourceSnapshot"));
            if(research!=null&&research.path("research").isObject()){
                var source=productDevelopment.redactMoldApplicationSource(json.convertValue(research.path("research"),new TypeReference<Map<String,Object>>(){}));
                if(Boolean.TRUE.equals(source.get("restricted"))||Boolean.TRUE.equals(source.get("suppliersRestricted")))throw invalid("新品来源或供应商资料未获授权，请由原项目工程人员核对后提交");
                String suppliers=researchSuppliers(source);if(!suppliers.isBlank())form.put("remark",Objects.toString(form.get("remark"),"")+"\n"+suppliers);
            }
            String remark=Objects.toString(form.get("remark"),"").replaceAll("\\s*\\[PLM-ID:[a-f0-9-]+\\]","");
            form.put("remark",remark+"\n[PLM-ID:"+prepared.get("key")+"]");
            dingFiles.fillForm(id,uid,form);
            List<Map<String,String>> values=new ArrayList<>();for(var control:controls(schema,json)) {
                String key=control.get("key").toString();String value=text(form.get(key));
                if(Boolean.TRUE.equals(control.get("required"))&&value==null)throw invalid("钉钉模板必填字段未填写："+control.get("label")+"；请按真实业务完善，编码待定时须使用支持新品研发的原审批模板");
                if(value!=null){if(!((List<?>)control.get("options")).isEmpty()&&!((List<?>)control.get("options")).contains(value))throw invalid("下拉选项已变化，请刷新表单");values.add(Map.of("id",control.get("id").toString(),"name",control.get("label").toString(),"value",value));}
            }
            var payload=new LinkedHashMap<String,Object>();payload.put("originatorUserId",uid);payload.put("processCode",props.getProcessCode());payload.put("deptId",dept);payload.put("formComponentValues",values);
            repo.jdbc().update("update plm_mold_application set submission_snapshot=?::jsonb where application_id=?",repo.json(payload),id);
            createStarted=true;String instance=client.submit(payload);confirmedInstance=instance;
            tx().executeWithoutResult(tx->{repo.jdbc().update("update plm_mold_application set external_instance_id=?,status='submitted',remote_status='RUNNING',last_error=null,version_no=version_no+1,updated_at=now() where application_id=?",instance,id);audit("MOLD_APPLICATION_SUBMITTED",id,Map.of("instanceId",instance),request,actor);});
            syncInternal(instance);return detail(id);
        }catch(Exception e){if(confirmedInstance!=null){repo.jdbc().update("update plm_mold_application set external_instance_id=?,status='submitted',remote_status='RUNNING',last_error='已取得审批实例，详情补拉失败，请同步核对' where application_id=?",confirmedInstance,id);return detail(id);}boolean unknown=createStarted&&(!(e instanceof MoldDingTalkClient.RemoteFailure failure)||failure.uncertain);String message=e instanceof BusinessException b?b.getMessage():e instanceof MoldDingTalkClient.RemoteFailure f?f.getMessage():"钉钉提交失败，请同步核对";
            repo.jdbc().update("update plm_mold_application set status=?,last_error=?,version_no=version_no+1 where application_id=?",unknown?"submission_unknown":"draft",message,id);throw new BusinessException(50301,message);}
    }
    public Map<String,Object> withdraw(long id,long version,HttpServletRequest request){
        createPermission();if(!props.isWriteEnabled()||!props.getAllowedSubmitters().contains(access.actorId()))throw invalid("钉钉写入未启用");var actor=access.actor();
        var row=tx().execute(tx->{var r=require(id,true);
            if(!Set.of("submitted","terminated").contains(r.get("status"))||r.get("projectId")!=null||r.get("instanceId")==null)throw invalid("仅未转项目的运行中申请可撤回；待核对申请须先同步");
            var mapping=repo.jdbc().queryForList("select dingtalk_user_id from plm_pilot_account where user_id=?",actor.userId());
            if(mapping.size()!=1||!Objects.equals(mapping.get(0).get("dingtalk_user_id"),r.get("originatorUserId")))throw invalid("仅原钉钉发起人可撤回申请");
            if("terminated".equals(r.get("status"))){var copies=repo.jdbc().queryForList("select application_id from plm_mold_application where previous_application_id=? and deleted_flag=0",id);if(!copies.isEmpty())r.put("revisionId",copies.get(0).get("application_id"));}
            if(r.get("revisionId")==null&&((Number)r.get("version")).longValue()!=version)throw new BusinessException(40901,"申请已更新，请刷新");
            if(!"terminated".equals(r.get("status")))repo.jdbc().update("update plm_mold_application set status='withdrawing',version_no=version_no+1 where application_id=?",id);return r;});
        if(row.get("revisionId")!=null)return detail(((Number)row.get("revisionId")).longValue());
        boolean terminated="terminated".equals(row.get("status"));
        try{if(!terminated)client.withdraw(row.get("instanceId").toString(),row.get("originatorUserId").toString());var snapshot=client.instance(row.get("instanceId").toString());if(!"TERMINATED".equals(snapshot.path("status").asText()))throw new MoldDingTalkClient.RemoteFailure(true,"撤回状态尚未确认，请同步核对");terminated=true;
            syncInternal(row.get("instanceId").toString());
            return tx().execute(tx->{repo.row(id,true);var existing=repo.jdbc().queryForList("select application_id from plm_mold_application where previous_application_id=? and deleted_flag=0",id);if(!existing.isEmpty())return detail(((Number)existing.get(0).get("application_id")).longValue());Long next=repo.jdbc().queryForObject("""
                insert into plm_mold_application(process_code,model,tipo,form_data,applicant_id,product_line_id,phone_code_item_id,phone_model_id,source_snapshot_json,execution_intent,target_item_id,target_mold_set_id,target_project_id,execution_reason,new_application_requested,draft_request_id,previous_application_id,created_by,updated_by)
                select process_code,model,tipo,form_data,?,product_line_id,phone_code_item_id,phone_model_id,source_snapshot_json,execution_intent,target_item_id,target_mold_set_id,target_project_id,execution_reason,new_application_requested,?,application_id,?,? from plm_mold_application where application_id=? returning application_id
                """,Long.class,actor.userId(),UUID.randomUUID().toString(),actor.username(),actor.username(),id);
                repo.jdbc().update("""
                    insert into plm_attachment(owner_object_type,owner_object_id,file_category,file_name,original_file_name,file_ext,content_type,file_size,checksum,content_sha256,storage_type,storage_key,preview_type,preview_status,source_system,source_file_id,source_url,version_no,status,download_status,source_component_id,created_by,updated_by)
                    select 'MoldApplication',?,file_category,file_name,original_file_name,file_ext,content_type,file_size,checksum,content_sha256,storage_type,storage_key,preview_type,preview_status,source_system,source_file_id,source_url,'V1','draft',download_status,source_component_id,?,? from plm_attachment where owner_object_type='MoldApplication' and owner_object_id=? and deleted_flag=0
                    """,next,actor.username(),actor.username(),id);audit("MOLD_APPLICATION_WITHDRAW_COPY",id,Map.of("nextApplicationId",next),request,actor);return detail(next);});
        }catch(Exception e){boolean rejected=e instanceof MoldDingTalkClient.RemoteFailure f&&!f.uncertain;String state=terminated?"terminated":rejected?"submitted":"withdrawal_unknown";String message=terminated?"撤回已确认，复制失败；可重试创建修订草稿":rejected?"钉钉拒绝撤回，原申请仍只读":"撤回结果未确认，请先同步核对，不要重复发起";repo.jdbc().update("update plm_mold_application set status=?,last_error=? where application_id=?",state,message,id);throw new BusinessException(50301,message);}
    }
    public Map<String,Object> syncOne(long id){admin();var row=require(id,false);if(row.get("instanceId")==null)throw invalid("此申请尚未取得钉钉实例ID");syncInternal(row.get("instanceId").toString());return detail(id);}
    public Map<String,Object> syncRange(LocalDate from,LocalDate until){admin();return sync(from.atStartOfDay(ZONE).toInstant(),until.plusDays(1).atStartOfDay(ZONE).toInstant());}
    private Map<String,Object> sync(Instant from,Instant until){
        feature();if(!props.isReadSyncEnabled())throw invalid("钉钉读取同步未启用");if(!syncing.compareAndSet(false,true))throw invalid("已有同步任务正在运行");
        try{client.warmup();repo.saveSchema(props.getProcessCode(),client.schema(props.getProcessCode()));var ids=new LinkedHashSet<>(client.ids(props.getProcessCode(),from,until.isAfter(Instant.now())?Instant.now():until));
            ids.addAll(repo.jdbc().queryForList("select external_instance_id from plm_mold_application where process_code=? and remote_status='RUNNING' and external_instance_id is not null",String.class,props.getProcessCode()));int done=0;List<String> failed=new ArrayList<>();
            for(String id:ids)try{syncInternal(id);done++;}catch(Exception e){failed.add(id);repo.jdbc().update("update plm_mold_application set last_error='钉钉同步失败，请重试' where external_instance_id=? and process_code=?",id,props.getProcessCode());}
            return Map.of("synced",done,"failedInstanceIds",failed);
        }finally{syncing.set(false);}
    }
    public long syncInternal(String id){
        feature();if(!props.isReadSyncEnabled())throw invalid("钉钉读取同步未启用");
        var d=client.instance(id);var created=date(text(d.path("create_time")));
        boolean known=repo.jdbc().queryForObject("select exists(select 1 from plm_mold_application where process_code=? and external_instance_id=?)",Boolean.class,props.getProcessCode(),id);
        if(!known){if(created==null||!client.ids(props.getProcessCode(),created.toInstant().minus(Duration.ofHours(12)),created.toInstant().plus(Duration.ofHours(12))).contains(id))throw invalid("此实例不属于开模正式流程，未导入");}
        var normalized=normalize(d);String remark=Objects.toString(normalized.get("remark"),"");
        // DingTalk create-instance has no documented request-id deduplication. Reconcile our marker;
        // never send the create call a second time while the outcome is unknown.
        var marker=java.util.regex.Pattern.compile("\\[PLM-ID:([a-f0-9-]{36})\\]").matcher(remark);String key=null;while(marker.find())key=marker.group(1);
        if(key!=null)repo.jdbc().update("update plm_mold_application set external_instance_id=? where submission_key=? and process_code=? and originator_user_id=? and external_instance_id is null and status in ('submitting','submission_unknown')",id,key,props.getProcessCode(),text(d.path("originator_userid")));
        var empty=json.createObjectNode();long result=repo.ingest(props.getProcessCode(),id,d,repo.schema(props.getProcessCode()),empty,empty);media.archiveRemote(result,d);autoProvision(result);return result;
    }
    public Map<String,Object> callback(String instance,String processCode,boolean approved){feature();if(!props.getProcessCode().equals(processCode))throw invalid("非开模正式流程");if(text(instance)==null)throw invalid("缺少实例ID");long id=syncInternal(instance);return Map.of("applicationId",id,"processingStatus",repo.row(id,false).get("provisioningStatus"));}
    public Map<String,Object> correctTargets(long id,TargetCorrection input,HttpServletRequest request){
        convertPermission();if(text(input.reason())==null)throw invalid("请填写工程核对原因");if(!Set.of("new","new_set").contains(input.executionIntent()))throw invalid("建档方式无效");var actor=access.actor();
        tx().executeWithoutResult(tx->{var row=require(id,true);requireModelExecution(row);if(((Number)row.get("version")).longValue()!=input.version())throw new BusinessException(40901,"申请已更新，请刷新");if(row.get("moldSetId")!=null||!Boolean.TRUE.equals(row.get("approvalGranted"))||!"blocked".equals(row.get("provisioningStatus")))throw invalid("只可核对已获同意但自动建档待处理的申请");validateTargets((Long)row.get("productLineId"),(Long)row.get("phoneModelId"),input.executionIntent(),input.targetItemId(),null,input.targetProjectId(),true);if(codes.reservedNewSet(id,text(row.get("generatedCodes")))!=null&&!Objects.equals(row.get("targetItemId"),input.targetItemId()))throw invalid("加开原刻码已在正式审批冻结，不能改为另一主档；请核对原目标");if("new_set".equals(input.executionIntent())){String segment=repo.jdbc().queryForObject("select coalesce(nullif(engraved_code,''),phone_model_code) from plm_collaboration_item where item_id=?",String.class,input.targetItemId());if(segment==null||MoldApplicationForms.codes(text(row.get("generatedCodes"))).stream().anyMatch(code->!code.substring(5).equals(segment)))throw invalid("正式审批原刻码与所选主档不一致，请核对原申请，不能改写已提交的刻码");}repo.jdbc().update("update plm_mold_application set execution_intent=?,target_item_id=?,target_project_id=?,provisioning_status='pending',provisioning_error=null,version_no=version_no+1,updated_at=now() where application_id=?",input.executionIntent(),input.targetItemId(),input.targetProjectId(),id);audit("MOLD_APPLICATION_TARGETS_CORRECT",id,input,request,actor);});autoProvision(id,false);return detail(id);
    }
    public Map<String,Object> retryProvision(long id,long version,HttpServletRequest request){convertPermission();var row=require(id,false);requireModelExecution(row);if(((Number)row.get("version")).longValue()!=version)throw new BusinessException(40901,"申请已更新，请刷新");if(!Boolean.TRUE.equals(row.get("approvalGranted")))throw invalid("尚未取得周汉琴同意");if(!"plm".equals(row.get("sourceSystem")))throw invalid("历史申请请先确认目标后转入，不能自动批量创建");autoProvision(id,false);return detail(id);}
    private void autoProvision(long id){autoProvision(id,true);}
    private void autoProvision(long id,boolean applicantContext){
        var rows=repo.jdbc().queryForList("select applicant_id from plm_mold_application where application_id=? and source_system='plm' and approval_granted and authorization_json->>'evidenceSource'='task' and nullif(authorization_json->>'taskId','') is not null and mold_set_id is null and provisioning_status in ('pending','blocked') and execution_status not in ('paused','terminated')",id);if(rows.isEmpty()||conversion==null)return;
        var previous=CurrentUserContext.get();try{
            if(applicantContext){var actorRows=repo.jdbc().queryForList("select user_id,username,display_name,all_permissions from sys_user where user_id=? and status='active' and deleted_flag=0",rows.get(0).get("applicant_id"));if(actorRows.size()!=1)throw invalid("申请人的真实账号已停用，请工程核对");var who=actorRows.get(0);CurrentUserContext.set(new CurrentUser(((Number)who.get("user_id")).longValue(),who.get("username").toString(),who.get("display_name").toString(),Boolean.TRUE.equals(who.get("all_permissions"))));}
            conversion.convertAutomatically(id);
        }catch(Exception failure){String message=failure instanceof BusinessException?failure.getMessage():"自动建档失败，请工程核对目标后重试";repo.jdbc().update("update plm_mold_application set provisioning_status='blocked',provisioning_error=?,updated_at=now() where application_id=? and mold_set_id is null",message.length()>512?message.substring(0,512):message,id);}
        finally{if(previous.isPresent())CurrentUserContext.set(previous.get());else CurrentUserContext.clear();}
    }
    public Map<String,Object> resolve(long id,Resolution input,HttpServletRequest request){
        convertPermission();if(text(input.reason())==null)throw invalid("请填写工程处理原因");if(!Set.of("resume","terminate").contains(input.action()))throw invalid("处理方式无效");var actor=access.actor();return tx().execute(tx->{var row=require(id,true);if(((Number)row.get("version")).longValue()!=input.version())throw new BusinessException(40901,"申请已更新，请刷新");if(row.get("executionResolution") instanceof JsonNode resolution&&resolution.path("historicalAssociation").path("associationOnly").asBoolean())throw invalid("此申请仅补历史关联，不授予执行；新增执行须通过PLM申请");if(!"paused".equals(row.get("executionStatus")))throw invalid("只可处理暂停的申请");var proof=Map.of("action",input.action(),"reason",input.reason(),"actorId",actor.userId(),"actorName",actor.displayName(),"resolvedAt",OffsetDateTime.now().toString(),"approvalState",row.get("executionException") instanceof JsonNode exception?exception.path("key").asText():"");repo.jdbc().update("update plm_mold_application set execution_status=?,execution_resolution_json=?::jsonb,version_no=version_no+1,updated_at=now() where application_id=?","resume".equals(input.action())?"active":"terminated",repo.json(proof),id);audit("MOLD_APPLICATION_EXECUTION_RESOLVE",id,proof,request,actor);return detail(id);});
    }
    @Scheduled(fixedDelayString="${app.mold-application.sync-interval-ms:900000}",initialDelayString="${app.mold-application.sync-initial-delay-ms:900000}")
    public void scheduled(){if(props.isEnabled()&&props.isReadSyncEnabled())try{sync(Instant.now().minus(Duration.ofDays(3)),Instant.now());}catch(Exception ignored){}}
    @Override public void run(ApplicationArguments args)throws Exception{
        if(!props.isEnabled()||props.getImportDirectory().isBlank())return;
        backupImport.run(Path.of(props.getImportDirectory()),props.getProcessCode());
    }
}

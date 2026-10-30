package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.moldapplication.MoldApplicationForms.*;

@Repository
public class MoldApplicationRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public MoldApplicationRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public JdbcTemplate jdbc(){return jdbc;}
    public String json(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw invalid("申请数据无法序列化");}}
    public JsonNode node(Object value){try{return json.readTree(Objects.toString(value,"{}"));}catch(Exception e){throw invalid("申请快照无法解析");}}
    public static final String SELECT="""
        select a.application_id as "applicationId",a.source_system as "sourceSystem",a.process_code as "processCode",
          a.external_instance_id as "instanceId",a.approval_no as "approvalNo",a.title,a.model,a.tipo,a.generated_codes as "generatedCodes",
          a.form_data::text as "formJson",a.remote_status as "remoteStatus",a.remote_result as "remoteResult",a.status,
          a.approval_granted as "approvalGranted",a.schema_version as "schemaVersion",a.originator_user_id as "originatorUserId",
          a.originator_name as "originatorName",a.department_id as "departmentId",a.department_name as "departmentName",
          a.source_created_at as "sourceCreatedAt",a.source_finished_at as "sourceFinishedAt",a.last_synced_at as "lastSyncedAt",
          a.applicant_id as "applicantId",a.product_line_id as "productLineId",a.phone_code_item_id as "phoneCodeItemId",
          a.project_id as "projectId",a.item_id as "itemId",a.mold_set_id as "moldSetId",a.previous_application_id as "previousApplicationId",
          a.phone_model_id as "phoneModelId",(select c.code_value from plm_code_item c where c.code_item_id=a.phone_code_item_id) as "phoneModelCode",a.source_snapshot_json::text as "sourceSnapshotJson",
          a.execution_intent as "executionIntent",a.target_item_id as "targetItemId",a.target_mold_set_id as "targetMoldSetId",a.target_project_id as "targetProjectId",
          a.execution_reason as "executionReason",a.new_application_requested as "newApplication",a.draft_request_id as "draftRequestId",
          a.provisioning_status as "provisioningStatus",a.provisioning_error as "provisioningError",a.execution_status as "executionStatus",
          a.execution_exception_json::text as "executionExceptionJson",a.authorization_json::text as "authorizationJson",a.execution_resolution_json::text as "executionResolutionJson",a.updated_at as "updatedAt",
          a.version_no as version,a.last_error as "lastError",a.created_at as "createdAt",a.submission_key as "submissionKey",
          %s as "businessStatus"
        from plm_mold_application a
        """.formatted(MoldApplicationBusinessStatus.SQL);
    public Map<String,Object> row(long id,boolean lock){var rows=jdbc.queryForList(SELECT+" where a.application_id=? and a.deleted_flag=0"+(lock?" for update":""),id);if(rows.size()!=1)throw invalid("开模申请不存在");return view(rows.get(0));}
    public Map<String,Object> view(Map<String,Object> row){row.put("form",node(row.remove("formJson")));for(String key:List.of("sourceSnapshot","authorization","executionResolution","executionException")){Object raw=row.remove(key+"Json");row.put(key,raw==null?null:node(raw));}row.put("progressStatus",row.get("businessStatus"));var links=new LinkedHashMap<String,Object>();for(String key:List.of("applicationId","projectId","itemId","moldSetId","phoneModelId"))links.put(key,row.get(key));row.put("applicationLinks",links);return row;}
    public JsonNode schema(String code){var rows=jdbc.queryForList("select schema_snapshot::text snapshot from plm_mold_application_schema where process_code=?",code);return rows.isEmpty()?json.createObjectNode():node(rows.get(0).get("snapshot"));}
    public void saveSchema(String code,JsonNode schema){jdbc.update("insert into plm_mold_application_schema(process_code,schema_version,schema_snapshot) values(?,?,?::jsonb) on conflict(process_code) do update set schema_version=excluded.schema_version,schema_snapshot=excluded.schema_snapshot,fetched_at=now()",code,schema.path("gmtModified").asText(),json(schema));}
    @Transactional
    public long ingest(String code,String id,JsonNode detail,JsonNode schema,JsonNode user,JsonNode department) {
        var form=normalize(detail);String state=detail.path("status").asText();var evidence=approvalEvidence(detail);boolean approved=evidence.granted();
        var existing=jdbc.queryForList("select application_id,remote_status,source_system from plm_mold_application where process_code=? and external_instance_id=? for update",code,id);
        if(!existing.isEmpty()&&"plm".equals(existing.get(0).get("source_system")))approved=evidence.taskAuthorized();
        if(!existing.isEmpty()&&Set.of("TERMINATED","COMPLETED").contains(Objects.toString(existing.get(0).get("remote_status"),""))&&"RUNNING".equals(state))return ((Number)existing.get(0).get("application_id")).longValue();
        String name=text(user.path("name"));if(name==null)name=detail.path("title").asText().split("提交的")[0];
        Long appId=jdbc.queryForObject("""
            insert into plm_mold_application(source_system,process_code,external_instance_id,approval_no,title,model,tipo,generated_codes,
              form_data,remote_status,remote_result,status,approval_granted,schema_version,schema_snapshot,raw_snapshot,
              originator_user_id,originator_name,department_id,department_name,source_created_at,source_finished_at,last_synced_at,created_by,updated_by)
            values('dingtalk',?,?,?,?,?,?,?,?::jsonb,?,?,?, ?,?,?::jsonb,?::jsonb,?,?,?,?,?,?,now(),'dingtalk-sync','dingtalk-sync')
            on conflict(process_code,external_instance_id) where external_instance_id is not null do update set
              approval_no=excluded.approval_no,title=excluded.title,model=excluded.model,tipo=excluded.tipo,generated_codes=case when plm_mold_application.source_snapshot_json->'researchSubmission'->>'mode'='research' and plm_mold_application.mold_set_id is not null and plm_mold_application.execution_resolution_json->'researchCoding'->'m'->'codes' is not null then plm_mold_application.generated_codes else excluded.generated_codes end,
              form_data=excluded.form_data,remote_status=excluded.remote_status,remote_result=excluded.remote_result,
              status=case when plm_mold_application.status='converted' then 'converted' else excluded.status end,
              approval_granted=excluded.approval_granted,raw_snapshot=excluded.raw_snapshot,originator_user_id=excluded.originator_user_id,
              originator_name=excluded.originator_name,department_id=excluded.department_id,department_name=coalesce(excluded.department_name,plm_mold_application.department_name),
              source_created_at=excluded.source_created_at,source_finished_at=excluded.source_finished_at,last_synced_at=now(),
              last_error=null,updated_at=now(),version_no=plm_mold_application.version_no+1
            returning application_id
            """,Long.class,code,id,text(detail.path("business_id")),text(detail.path("title")),form.get("model"),form.get("tipo"),form.get("generatedCodes"),json(form),
            state,text(detail.path("result")),state.equals("TERMINATED")?"terminated":state.equals("COMPLETED")?"completed":"submitted",approved,
            schema.path("gmtModified").asText(),json(schema),json(detail),text(detail.path("originator_userid")),name,detail.path("originator_dept_id").isNumber()?detail.path("originator_dept_id").asLong():null,
            text(department.path("name")),date(text(detail.path("create_time"))),date(text(detail.path("finish_time"))));
        var proof=evidence.authorization();if(proof!=null)jdbc.update("update plm_mold_application set authorization_json=case when authorization_json is null or (coalesce(authorization_json->>'evidenceSource','')<>'task' and ?='task') then ?::jsonb else authorization_json end where application_id=?",proof.get("evidenceSource"),json(proof),appId);
        var exception=evidence.exception();jdbc.update("update plm_mold_application set execution_exception_json=?::jsonb where application_id=?",exception==null?null:json(exception),appId);
        if(exception!=null)jdbc.update("update plm_mold_application set execution_status='paused' where application_id=? and mold_set_id is not null and execution_status<>'terminated' and coalesce(execution_resolution_json->>'approvalState','')<>?",appId,exception.get("key"));
        for(String key:List.of("operation_records","tasks"))for(var record:detail.path(key)) {
            String sourceKey=MoldApplicationMedia.hash((key+":"+record.toString()).getBytes(StandardCharsets.UTF_8));
            // Task updates replace the earlier task snapshot; operations remain an append-only history.
            if(key.equals("tasks"))sourceKey=MoldApplicationMedia.hash(("task:"+record.path("taskid").asText()).getBytes(StandardCharsets.UTF_8));
            jdbc.update("""
                insert into plm_mold_application_activity(application_id,source_key,activity_type,occurred_at,actor_id,task_id,payload)
                values(?,?,?,?,?,?,?::jsonb) on conflict(application_id,source_key) do update set
                  payload=excluded.payload,occurred_at=excluded.occurred_at,task_id=excluded.task_id,
                  actor_name=case when plm_mold_application_activity.actor_id is distinct from excluded.actor_id then null else plm_mold_application_activity.actor_name end,
                  actor_id=excluded.actor_id
                """,appId,sourceKey,key.equals("tasks")?"task":record.path("operation_type").asText(),date(text(record.path(key.equals("tasks")?"finish_time":"date"))),text(record.path("userid")),text(record.path("taskid")),json(record));
        }
        // A verified historical code is an authoritative alias, not a fuzzy brand-name guess.
        // Conflicting aliases are retained so reservation can require manual resolution.
        var historicCodes=MoldApplicationForms.codes(text(form.get("generatedCodes")));
        var phoneCodes=new HashSet<String>();for(String mold:historicCodes)phoneCodes.add(mold.substring(5));
        String model=text(form.get("model"));
        if(model!=null&&model.length()<=255&&hasValidCodes(text(form.get("generatedCodes")))&&phoneCodes.size()==1) {
            String phone=phoneCodes.iterator().next();
            jdbc.update("insert into plm_mold_model_alias(source_database,source_id,phone_code,normalized_name,source_name,payload) values('dingtalk-formal',?,?,?,?,?::jsonb) on conflict(source_database,source_id) do update set phone_code=excluded.phone_code,normalized_name=excluded.normalized_name,source_name=excluded.source_name,payload=excluded.payload",id,phone,model.trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT),model,json(form));
            jdbc.update("update plm_mold_code_sequence set last_value=greatest(last_value,?) where sequence_key='phone_model'",Integer.parseInt(phone));
        }
        return appId;
    }
    public List<Map<String,Object>> activities(long id){return jdbc.queryForList("select activity_id as \"activityId\",activity_type as type,occurred_at as \"occurredAt\",actor_id as \"actorId\",actor_name as \"actorName\",task_id as \"taskId\",payload::text as \"payloadJson\" from plm_mold_application_activity where application_id=? order by occurred_at nulls last,activity_id",id).stream().peek(r->r.put("payload",node(r.remove("payloadJson")))).toList();}
}

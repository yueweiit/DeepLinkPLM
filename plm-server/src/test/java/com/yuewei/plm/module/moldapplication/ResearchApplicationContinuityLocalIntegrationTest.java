package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SQL producers, original application writer and object scopes; every new identity rolls back. */
@EnabledIfEnvironmentVariable(named="PLM_MOLD_TEST_DB_URL",matches=".+")
class ResearchApplicationContinuityLocalIntegrationTest {
    private final MoldApplicationLocalIntegrationTest fixture=new MoldApplicationLocalIntegrationTest();
    private long project,phoneA,phoneB;
    private Map<String,Long> executionBefore;
    private Path temporaryMedia;

    @BeforeEach void begin() throws Exception {
        fixture.begin();temporaryMedia=fixture.media.root();
        phoneA=fixture.phoneModel;phoneB=newPhone();
        project=fixture.researchFixture(false);
        fixture.jdbc.update("update plm_product set current_step_no=5 where product_id=?",project);
        ReflectionTestUtils.setField(fixture.researchDevelopment,"moldApplications",fixture.service);
        executionBefore=executionCounts();
    }

    @AfterEach void rollback() throws Exception {
        fixture.rollback();
        if(temporaryMedia!=null&&temporaryMedia.getFileName().toString().startsWith("plm-mold-verification-"))
            try(var files=Files.walk(temporaryMedia)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}
    }

    private long newPhone(){
        long brand=fixture.jdbc.queryForObject("insert into plm_phone_brand(name,created_by,updated_by) values(?,'qa','qa') returning id",Long.class,"QA-CONTINUITY-"+UUID.randomUUID());
        return fixture.jdbc.queryForObject("insert into plm_phone_model_member(brand_id,model_name,code_item_id,created_by,updated_by) values(?,'QA 新标准手机 B',?,'qa','qa') returning id",Long.class,brand,fixture.phone);
    }
    private long version(){return ((Number)fixture.researchStore.load(project).get("version")).longValue();}
    private String request(){return "qa-continuity-"+UUID.randomUUID();}
    private Map<String,Object> start(Long phone){return fixture.service.startFromResearch(new MoldApplicationService.ResearchDraft(project,phone,request(),version()),null);}
    private MoldApplicationService.ResearchDraft reapplication(long phone,String key,String reason){
        long code=fixture.jdbc.queryForObject("select code_item_id from plm_phone_model_member where id=?",Long.class,phone);
        var draft=new MoldApplicationService.Draft(fixture.json.valueToTree(Map.of("remark","QA 重新评估后的申请")),project,code,null,phone,"new",null,null,null,reason,key,true);
        return new MoldApplicationService.ResearchDraft(project,phone,key,version(),draft);
    }
    private long rejectedHistory(Long phone,String reason){
        var remote=fixture.json.createObjectNode().put("status","COMPLETED").put("result","refuse").put("business_id","QA-REFUSED-"+UUID.randomUUID()).put("title","QA 历史开模审批").put("originator_userid","qa-history-originator").put("create_time","2026-10-20 08:00:00").put("finish_time","2026-10-20 09:00:00");
        var fields=remote.putArray("form_component_values");
        fields.addObject().put("id",MoldApplicationForms.COMPONENTS.get("model")).put("value","历史未核对手机原文");
        fields.addObject().put("id",MoldApplicationForms.COMPONENTS.get("tipo")).put("value",fixture.tipo);
        fields.addObject().put("id",MoldApplicationForms.COMPONENTS.get("remark")).put("value","申请备注不能冒充审批拒绝原因");
        remote.putArray("operation_records").addObject().put("operation_type","EXECUTE_TASK_NORMAL").put("operation_result","REFUSE").put("userid","qa-history-reviewer").put("date","2026-10-20 09:00:00").put("remark",reason);
        long id=fixture.repo.ingest(fixture.props.getProcessCode(),"qa-history-"+UUID.randomUUID(),remote,fixture.schema(),fixture.json.createObjectNode().put("name","QA 历史申请人"),fixture.json.createObjectNode());
        fixture.jdbc.update("update plm_mold_application set product_line_id=?,project_id=?,phone_model_id=?,source_snapshot_json=?::jsonb,execution_resolution_json=?::jsonb where application_id=?",project,project,phone,fixture.repo.json(Map.of("sources",List.of(Map.of("kind","historical_association","id",project)))),fixture.repo.json(Map.of("historicalAssociation",Map.of("associationOnly",true,"reason","QA 已有项目关系，仅补历史关联"))),id);
        fixture.jdbc.update("update plm_mold_application_activity set actor_name='QA 评审人' where application_id=?",id);
        byte[] evidence="%PDF QA original refusal attachment".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        fixture.media.store(id,MoldApplicationForms.COMPONENTS.get("attachments"),"attachment","QA 原审批附件.pdf","qa-history-file-"+id,null,evidence,"application/pdf","qa","downloaded");
        return id;
    }
    private Map<String,String> historicalFacts(long id){
        var result=new LinkedHashMap<String,String>();
        result.put("application",fixture.jdbc.queryForObject("select to_jsonb(a)::text from plm_mold_application a where application_id=?",String.class,id));
        result.put("activities",fixture.jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(a) order by activity_id),'[]'::jsonb)::text from plm_mold_application_activity a where application_id=?",String.class,id));
        result.put("attachments",fixture.jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(a) order by attachment_id),'[]'::jsonb)::text from plm_attachment a where owner_object_type='MoldApplication' and owner_object_id=?",String.class,id));
        return result;
    }
    private Map<String,Object> pageRow(){
        String code=fixture.jdbc.queryForObject("select product_code from plm_product where product_id=?",String.class,project);
        var page=fixture.researchDevelopment.page(code,"all",1,50,null);
        return ((List<Map<String,Object>>)page.get("content")).stream().filter(row->Objects.equals(row.get("productId"),project)).findFirst().orElseThrow();
    }
    private Map<String,Object> summary(){return (Map<String,Object>)pageRow().get("moldApplicationSummary");}
    private List<Map<String,Object>> applications(Map<String,Object> summary,String key){return (List<Map<String,Object>>)summary.get(key);}
    private Map<String,Long> executionCounts(){
        var result=new LinkedHashMap<String,Long>();
        for(String table:List.of("plm_collaboration_item","plm_collaboration_mold_set","plm_collaboration_mold_part","plm_inventory","plm_product_mold_code","plm_mold_code_reservation"))result.put(table,fixture.jdbc.queryForObject("select count(*) from "+table,Long.class));
        return result;
    }
    private void noExecution(){
        assertThat(executionCounts()).isEqualTo(executionBefore);
        assertThat(fixture.jdbc.queryForObject("select count(*) from plm_product where parent_product_id=?",Long.class,project)).isZero();
        assertThat(fixture.jdbc.queryForObject("select product_specific_code from plm_product where product_id=?",String.class,project)).isNull();
        verify(fixture.client,never()).submit(anyMap());
    }

    @Test void anUncodedRejectedHistoryRemainsVisibleAndCannotSwallowANewPhonesDraft(){
        long historical=rejectedHistory(null,"手机不好卖，本次不开发");var before=historicalFacts(historical);
        var initial=summary();
        assertThat(((Number)initial.get("historyCount")).intValue()).isEqualTo(1);assertThat(((Number)initial.get("activeCount")).intValue()).isZero();
        var history=applications(initial,"historyApplications").get(0);
        assertThat(history).containsEntry("applicationId",historical).containsEntry("businessStatus","rejected").containsEntry("phoneModelId",null);
        assertThat((Map<String,Object>)history.get("refusal")).containsEntry("reason","手机不好卖，本次不开发").containsEntry("actorName","QA 评审人");
        assertThat(fixture.repo.json(history)).doesNotContain("申请备注不能冒充","raw_snapshot","rawJson","lastError");
        var created=start(phoneB);assertThat(fixture.id(created)).isNotEqualTo(historical);assertThat(created).containsEntry("status","draft").containsEntry("phoneModelId",phoneB).containsEntry("productLineId",project);
        var after=summary();assertThat(((Number)after.get("activeCount")).intValue()).isEqualTo(1);assertThat(((Number)after.get("historyCount")).intValue()).isEqualTo(1);
        assertThat(applications(after,"activeApplications")).singleElement().satisfies(row->assertThat(row).containsEntry("applicationId",fixture.id(created)));
        assertThat(historicalFacts(historical)).isEqualTo(before);noExecution();
    }

    @Test void aRejectedPhoneAAndAnActivePhoneBStaySeparateInTheRealProjectPage(){
        long historical=rejectedHistory(phoneA,"QA A 型号销售不足");var before=historicalFacts(historical);
        var created=start(phoneB);var page=summary();
        assertThat(((Number)page.get("totalCount")).intValue()).isEqualTo(2);assertThat(((Number)page.get("activeCount")).intValue()).isEqualTo(1);assertThat(((Number)page.get("historyCount")).intValue()).isEqualTo(1);
        assertThat(applications(page,"historyApplications")).singleElement().satisfies(row->assertThat(row).containsEntry("phoneModelId",phoneA).containsEntry("businessStatus","rejected"));
        assertThat(applications(page,"activeApplications")).singleElement().satisfies(row->assertThat(row).containsEntry("phoneModelId",phoneB).containsEntry("status","draft"));
        var context=fixture.service.researchContext(project,phoneB,version());
        assertThat((Map<String,Object>)context.get("existingApplication")).containsEntry("applicationId",fixture.id(created));
        assertThat(historicalFacts(historical)).isEqualTo(before);noExecution();
    }

    @Test void anActiveSamePhoneApplicationIsContinuedAcrossRepeatedResearchRequests(){
        var created=start(phoneA);long id=fixture.id(created);long total=fixture.jdbc.queryForObject("select count(*) from plm_mold_application",Long.class);
        var continued=start(phoneA);assertThat(fixture.id(continued)).isEqualTo(id);assertThat(fixture.version(continued)).isEqualTo(fixture.version(created));
        fixture.jdbc.update("update plm_mold_application set status='submitted',remote_status='RUNNING',external_instance_id=? where application_id=?","qa-running-"+UUID.randomUUID(),id);
        var running=start(phoneA);assertThat(fixture.id(running)).isEqualTo(id);assertThat(running).containsEntry("canEditDraft",false);
        assertThat(fixture.jdbc.queryForObject("select count(*) from plm_mold_application",Long.class)).isEqualTo(total);noExecution();
    }

    @Test void aSamePhoneReapplicationNeedsAnExplicitReasonAndKeepsTheRejectedEvidence(){
        long historical=rejectedHistory(phoneA,"QA 同型号此前被拒");var before=historicalFacts(historical);
        assertThatThrownBy(()->start(phoneA)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->fixture.service.startFromResearch(reapplication(phoneA,request(),null),null)).hasMessageContaining("原因");
        String key=request();var input=reapplication(phoneA,key,"QA 市场与方案重新评估，重新申请");
        var created=fixture.service.startFromResearch(input,null);long id=fixture.id(created);
        assertThat(id).isNotEqualTo(historical);assertThat(created).containsEntry("status","draft").containsEntry("newApplication",true).containsEntry("executionReason","QA 市场与方案重新评估，重新申请");
        var repeated=fixture.service.startFromResearch(input,null);assertThat(fixture.id(repeated)).isEqualTo(id);
        assertThat(fixture.jdbc.queryForObject("select count(*) from plm_mold_application where product_line_id=?",Long.class,project)).isEqualTo(2);
        assertThat(historicalFacts(historical)).isEqualTo(before);assertThat(fixture.service.detail(historical)).containsEntry("canEditDraft",false).containsEntry("businessStatus","rejected");noExecution();
    }

    private long restrictedUser(){
        String username="qa-continuity-scope-"+UUID.randomUUID();
        long user=fixture.jdbc.queryForObject("insert into sys_user(user_code,username,login_name,user_name,display_name,all_permissions,status) values(?,?,?,'QA 受限工程','QA 受限工程',false,'active') returning user_id",Long.class,username,username,username);
        fixture.jdbc.update("insert into plm_pilot_account(user_id,role_code,permissions,all_objects,created_by,updated_by) values(?,'domestic',?::jsonb,false,'qa','qa')",user,fixture.repo.json(PilotAccessService.ROLE_PERMISSIONS.get("domestic")));
        fixture.jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) values(?,'product',?)",user,project);
        CurrentUserContext.set(new CurrentUser(user,username,"QA 受限工程",false));return user;
    }

    @Test void aHiddenHistoricalRefusalIsNotDisclosedAndDoesNotBlockAnotherPhone(){
        long historical=rejectedHistory(phoneA,"QA 秘密拒因-不得外泄");var before=historicalFacts(historical);restrictedUser();
        assertThat(fixture.repo.json(pageRow())).doesNotContain("QA 秘密拒因-不得外泄","qa-history-reviewer");
        assertThat(applications(summary(),"applications")).isEmpty();
        assertThatThrownBy(()->fixture.service.detail(historical)).hasMessageContaining("未获授权");
        var created=start(phoneB);assertThat(created).containsEntry("phoneModelId",phoneB).containsEntry("status","draft");
        var visible=summary();assertThat(((Number)visible.get("activeCount")).intValue()).isEqualTo(1);assertThat(((Number)visible.get("historyCount")).intValue()).isZero();
        assertThat(applications(visible,"activeApplications")).singleElement().satisfies(row->assertThat(row).containsEntry("phoneModelId",phoneB));
        assertThat(historicalFacts(historical)).isEqualTo(before);noExecution();
    }

    @Test void aHiddenActiveSamePhoneStillBlocksDuplicatesWithoutDisclosingItsData(){
        var hidden=start(phoneA);long hiddenId=fixture.id(hidden);
        fixture.jdbc.update("update plm_mold_application set title='QA 隐藏审批标题' where application_id=?",hiddenId);restrictedUser();
        assertThat(fixture.repo.json(pageRow())).doesNotContain("QA 隐藏审批标题");assertThat(applications(summary(),"applications")).isEmpty();
        long before=fixture.jdbc.queryForObject("select count(*) from plm_mold_application",Long.class);
        assertThatThrownBy(()->start(phoneA)).isInstanceOf(BusinessException.class).satisfies(error->assertThat(error.getMessage()).doesNotContain(Long.toString(hiddenId)));
        assertThat(fixture.jdbc.queryForObject("select count(*) from plm_mold_application",Long.class)).isEqualTo(before);
        var other=start(phoneB);assertThat(other).containsEntry("phoneModelId",phoneB).containsEntry("status","draft");
        assertThat(applications(summary(),"activeApplications")).singleElement().satisfies(row->assertThat(row).containsEntry("phoneModelId",phoneB));noExecution();
    }

    @Test void oneProjectPageCanResolveMoreThanOneHundredPhonesAcrossItsProjects() throws Exception {
        String marker="QA-PAGE-PHONES-"+UUID.randomUUID();
        long brand=fixture.jdbc.queryForObject("insert into plm_phone_brand(name,created_by,updated_by) values(?,'qa','qa') returning id",Long.class,marker);
        var batch=new ArrayList<Object[]>();for(int index=0;index<120;index++)batch.add(new Object[]{brand,"QA 聚合手机 "+index,fixture.phone});
        fixture.jdbc.batchUpdate("insert into plm_phone_model_member(brand_id,model_name,code_item_id,created_by,updated_by) values(?,?,?,'qa','qa')",batch);
        var phones=fixture.jdbc.queryForList("select id from plm_phone_model_member where brand_id=? order by id",Long.class,brand);
        long second=fixture.researchFixture(false);ReflectionTestUtils.setField(fixture.researchDevelopment,"moldApplications",fixture.service);
        var scopes=new LinkedHashMap<Long,List<Long>>();scopes.put(project,phones.subList(0,60));scopes.put(second,phones.subList(60,120));
        for(var scope:scopes.entrySet()){
            fixture.jdbc.update("update plm_product set product_name=? where product_id=?",marker+" "+scope.getKey(),scope.getKey());
            var state=fixture.researchStore.load(scope.getKey());var plan=new LinkedHashMap<>((Map<String,Object>)state.get("plan"));plan.put("phoneModelIds",scope.getValue());
            state.put("plan",plan);state.put("planDraft",plan);fixture.researchStore.save(scope.getKey(),state);
        }
        var page=fixture.researchDevelopment.page(marker,"all",1,50,null);
        assertThat(((Number)page.get("totalElements")).intValue()).isEqualTo(2);
        var rows=(List<Map<String,Object>>)page.get("content");assertThat(rows).hasSize(2);
        for(var row:rows){
            long id=((Number)row.get("productId")).longValue();var models=(List<Map<String,Object>>)row.get("phoneModels");
            assertThat(models).hasSize(60);assertThat(models.stream().map(model->((Number)model.get("phoneModelId")).longValue()).toList()).containsExactlyInAnyOrderElementsOf(scopes.get(id));
        }
        noExecution();
    }

    @Test void researchDraftsKeepExistingStageRulesAndCannotBypassProjectOrCreatePermission(){
        for(int step:List.of(1,2,5)){
            fixture.jdbc.update("update plm_product set current_step_no=? where product_id=?",step,project);
            var draft=start(phoneA);assertThat(draft).containsEntry("status","draft");
            assertThat(fixture.jdbc.queryForObject("select current_step_no from plm_product where product_id=?",Integer.class,project)).isEqualTo(step);
        }
        fixture.jdbc.update("update plm_product set lock_status='frozen' where product_id=?",project);
        assertThatThrownBy(()->start(phoneB)).isInstanceOf(BusinessException.class);
        fixture.jdbc.update("update plm_product set lock_status='unlocked' where product_id=?",project);
        long user=restrictedUser();fixture.jdbc.update("update plm_pilot_account set permissions='[\"project:view\",\"collaboration:view\",\"documents:write\"]'::jsonb where user_id=?",user);
        assertThatThrownBy(()->start(phoneB)).hasMessageContaining("mold-application:create");
        assertThat(fixture.jdbc.queryForObject("select count(*) from plm_mold_application where product_line_id=?",Long.class,project)).isEqualTo(1);noExecution();
    }
}

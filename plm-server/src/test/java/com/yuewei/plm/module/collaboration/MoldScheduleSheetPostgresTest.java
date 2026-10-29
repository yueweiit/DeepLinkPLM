package com.yuewei.plm.module.collaboration;

import com.fasterxml.jackson.databind.*;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.*;
import com.yuewei.plm.module.operationlog.service.*;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in real PostgreSQL; only the named local QA database, never production. */
@EnabledIfEnvironmentVariable(named="PLM_SHEET_QA_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mold_sheet_sync_qa")
class MoldScheduleSheetPostgresTest {
    ObjectMapper json=new com.yuewei.plm.common.config.JacksonConfig().objectMapper();JdbcTemplate jdbc,admin;String schema;
    MoldScheduleSheetSync sync;CollaborationService shared;CollaborationRepository repository;
    PilotAccessService access;OperationLogService audit;MoldScheduleSheetClient source;
    MockedConstruction<MoldWorkflowRepository> workflow;
    final CurrentUser actor=new CurrentUser(1L,"qa","QA",true);
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("PLM_SHEET_QA_URL"),password=System.getenv("PLM_SHEET_QA_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password));
        schema="sheet_"+UUID.randomUUID().toString().replace("-","");admin.execute("create schema "+schema);
        var ds=new DriverManagerDataSource(url+"?currentSchema="+schema,"postgres",password);jdbc=new JdbcTemplate(ds);
        jdbc.execute("create table sys_user(user_id bigint primary key,username text,display_name text,all_permissions boolean,status text,deleted_flag int)");
        jdbc.update("insert into sys_user values(1,'qa','QA',true,'active',0)");
        jdbc.execute("create table plm_collaboration_mold_set(mold_set_id bigint primary key,deleted_flag int default 0,opened_date date,planned_mold_delivery_date date,forecast_delivery_date date,forecast_factory_date date,planned_factory_arrival_date date,actual_ship_date date,actual_mexico_arrival_date date,master_data_version bigint default 0,updated_at timestamptz,updated_by text)");
        jdbc.execute("create table plm_collaboration_history(item_id bigint,entity_type text,entity_id bigint,field_name text,before_value jsonb,after_value jsonb,reason text,actor_id bigint,actor_name text)");
        jdbc.execute("create table plm_pilot_account(user_id bigint,dingtalk_user_id text)");jdbc.update("insert into plm_pilot_account values(1,'fixture-user')");
        jdbc.execute("create table plm_product(product_id bigint,product_specific_code text,deleted_flag int,product_type text)");jdbc.update("insert into plm_product values(1,'BA',0,'product_line')");
        jdbc.execute("create table plm_phone_brand(id bigint,name text,aliases text[],prefixes jsonb,status text,source_system text,source_record_id text,source_snapshot jsonb,created_at timestamptz,updated_at timestamptz)");jdbc.update("insert into plm_phone_brand(id,name,aliases,prefixes,status) values(1,'Apple',array['Apple','iPhone','IP'],'[]','enabled')");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V20261029_0930__mold_schedule_sheet_sync.sql")));
        jdbc.update("insert into plm_collaboration_mold_set(mold_set_id,opened_date,planned_mold_delivery_date,forecast_delivery_date) values(1,'2026-07-01','2026-07-20','2026-07-20')");
        access=mock(PilotAccessService.class);when(access.actor()).thenReturn(actor);when(access.visibleSqlClause(anyString(),anyString())).thenReturn("true");when(access.hasPermission(anyString())).thenReturn(true);
        audit=mock(OperationLogService.class);source=mock(MoldScheduleSheetClient.class);when(source.read(anyString())).thenAnswer(i->snapshot("2026-07-15"));
        repository=mock(CollaborationRepository.class);when(repository.previewRows(anyString(),anyString())).thenAnswer(i->jdbc.queryForList("select mold_set_id from plm_collaboration_mold_set order by mold_set_id").stream().map(r->row(((Number)r.get("mold_set_id")).longValue())).toList());when(repository.row(eq("mold_set"),anyLong())).thenAnswer(i->row(i.getArgument(1)));
        workflow=mockConstruction(MoldWorkflowRepository.class);
        var tm=new DataSourceTransactionManager(ds);shared=new CollaborationService(jdbc,json,repository,tm,audit,access);sync=new MoldScheduleSheetSync(jdbc,json,repository,shared,access,source,tm,audit);
        CurrentUserContext.set(actor);
    }
    @AfterEach void cleanup(){CurrentUserContext.clear();if(sync!=null)sync.close();if(workflow!=null)workflow.close();if(admin!=null&&schema!=null)admin.execute("drop schema "+schema+" cascade");}
    Map<String,Object> row(long id){
        var db=jdbc.queryForMap("select * from plm_collaboration_mold_set where mold_set_id=?",id);var r=new LinkedHashMap<String,Object>();
        CollaborationFields.MOLD.forEach(f->r.put(f.key(),null));r.put("itemId",1L);r.put("moldSetId",id);r.put("productLineId",1L);r.put("styleName","亮甲2.0");r.put("compatibleModels",id==1?"Apple iPhone 18 Pro":"Apple iPhone 18 Pro Max");r.put("holidayDays",0);r.put("extraDays",0);r.put("pcAcceptanceStatus","pending");r.put("tpuAcceptanceStatus","pending");
        for(String key:MoldScheduleSheetPolicy.KEYS){String col=key.replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);r.put(key,db.get(col)==null?null:db.get(col).toString());}return r;
    }
    JsonNode snapshot(String plan)throws Exception{return json.readTree("{\"displayValues\":[[\"款式\",\"品牌\",\"型号\",\"开模日期\",\"计划模具交付日期\",\"计划到达工厂日期\",\"实际发模日期\",\"抵达墨西哥\"],[\"Rainbow 2.0亮甲\",\"Apple\",\"IP 18 PRO\",\"2026-07-01\",\""+plan+"\",\"2026-07-22\",\"2026-07-16\",\"暂无数据\"]],\"formulas\":[[],[\"\",\"\",\"\",\"\",\"\",\"=E2+7\"]]}");}
    UUID perform(){UUID id=UUID.randomUUID();jdbc.update("update plm_mold_sheet_config set running_id=? where config_id=1",id);jdbc.update("insert into plm_mold_sheet_run(run_id,actor_id,mode,status) values(?,1,'manual','running')",id);ReflectionTestUtils.invokeMethod(sync,"perform",id);return id;}
    String date(){return jdbc.queryForObject("select planned_mold_delivery_date::text from plm_collaboration_mold_set where mold_set_id=1",String.class);}
    JsonNode fields()throws Exception{return json.readTree(jdbc.queryForObject("select field_state::text from plm_mold_sheet_binding where mold_set_id=1",String.class));}
    @Test void first_adoption_uses_source_and_shared_history_then_repeated_source_adds_no_fact_history()throws Exception{
        perform();assertThat(date()).isEqualTo("2026-07-15");assertThat(fields().path("plannedMoldDeliveryDate").path("manual").asBoolean()).isFalse();assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_history",Integer.class)).isPositive();
        int count=jdbc.queryForObject("select count(*) from plm_collaboration_history",Integer.class);perform();assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_history",Integer.class)).isEqualTo(count);
        assertThat(jdbc.queryForObject("select current_setting('plm.mold_sheet_sync_write',true)",String.class)).isNotEqualTo("on");
    }
    @Test void every_manual_writer_and_manual_clear_are_protected_and_explicit_resume_is_versioned()throws Exception{
        perform();jdbc.update("update plm_collaboration_mold_set set planned_mold_delivery_date='2026-07-19' where mold_set_id=1");perform();assertThat(date()).isEqualTo("2026-07-19");assertThat(fields().path("plannedMoldDeliveryDate").path("manual").asBoolean()).isTrue();
        jdbc.update("update plm_collaboration_mold_set set planned_mold_delivery_date=null where mold_set_id=1");perform();assertThat(date()).isNull();
        long version=((Number)sync.fields(1).get("version_no")).longValue();assertThatThrownBy(()->sync.resume(1,new MoldScheduleSheetSync.Resume(version-1,List.of("plannedMoldDeliveryDate"),"核对来源"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->sync.resume(1,new MoldScheduleSheetSync.Resume(version,List.of("plannedMoldDeliveryDate"),""))).isInstanceOf(BusinessException.class);
        sync.resume(1,new MoldScheduleSheetSync.Resume(version,List.of("plannedMoldDeliveryDate"),"确认恢复来源计划"));perform();assertThat(date()).isEqualTo("2026-07-15");
    }
    @Test void manual_edit_between_fetch_and_apply_rejects_stale_sync_without_overwriting_edit()throws Exception{
        perform();when(source.read(anyString())).thenAnswer(i->{jdbc.update("update plm_collaboration_mold_set set planned_mold_delivery_date='2026-07-25' where mold_set_id=1");return snapshot("2026-07-17");});
        // Trigger protects the raced field; other stale dependent values cause the shared CAS to reject.
        assertThatThrownBy(this::perform).isInstanceOf(BusinessException.class);assertThat(date()).isEqualTo("2026-07-25");assertThat(fields().path("plannedMoldDeliveryDate").path("manual").asBoolean()).isTrue();
    }
    @Test void mandatory_audit_failure_rolls_back_dates_binding_and_history(){
        doThrow(new IllegalStateException("audit unavailable")).when(audit).logRequired(any(),any());assertThatThrownBy(this::perform).isInstanceOf(IllegalStateException.class);
        assertThat(date()).isEqualTo("2026-07-20");assertThat(jdbc.queryForObject("select count(*) from plm_mold_sheet_binding",Integer.class)).isZero();assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_history",Integer.class)).isZero();
    }
    @Test void source_failure_preserves_last_success_and_original_facts()throws Exception{
        perform();Object success=sync.status().get("lastSuccessAt");when(source.read(anyString())).thenThrow(new IllegalStateException("source unavailable"));UUID id=UUID.randomUUID();jdbc.update("update plm_mold_sheet_config set running_id=? where config_id=1",id);jdbc.update("insert into plm_mold_sheet_run(run_id,actor_id,mode,status) values(?,1,'manual','running')",id);
        try{ReflectionTestUtils.invokeMethod(sync,"perform",id);fail("must fail");}catch(Exception failure){ReflectionTestUtils.invokeMethod(sync,"fail",id,failure);}
        assertThat(date()).isEqualTo("2026-07-15");assertThat(sync.status()).containsEntry("lastSuccessAt",success).containsEntry("running",false);assertThat(sync.status().get("lastError")).isNotNull();
    }
    @Test void unauthorized_refresh_does_not_claim_job_or_call_source(){doThrow(new BusinessException(40103,"denied")).when(access).requireAdministrator();assertThatThrownBy(sync::start).isInstanceOf(BusinessException.class);verifyNoInteractions(source);assertThat(jdbc.queryForObject("select count(*) from plm_mold_sheet_run",Integer.class)).isZero();}
    @Test void a_later_row_failure_rolls_back_the_earlier_success_and_metadata()throws Exception{
        jdbc.update("insert into plm_collaboration_mold_set(mold_set_id) values(2)");
        var data=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot("2026-07-15");var rows=(com.fasterxml.jackson.databind.node.ArrayNode)data.get("displayValues");var second=rows.get(1).deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)second).set(2,json.valueToTree("IP 18 PRO MAX"));rows.add(second);when(source.read(anyString())).thenReturn(data);
        var spy=spy(shared);doAnswer(i->{var batch=(CollaborationTypes.BatchSave)i.getArgument(0);if(batch.rows().get(0).id()==2)throw new BusinessException(40001,"second row rejected");return i.callRealMethod();}).when(spy).save(any(),any());ReflectionTestUtils.setField(sync,"collaboration",spy);
        assertThatThrownBy(this::perform).isInstanceOf(BusinessException.class);assertThat(date()).isEqualTo("2026-07-20");assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_history",Integer.class)).isZero();assertThat(jdbc.queryForObject("select count(*) from plm_mold_sheet_binding",Integer.class)).isZero();
    }
    @Test void initial_noop_row_still_rejects_a_manual_edit_during_fetch()throws Exception{
        when(source.read(anyString())).thenAnswer(i->{jdbc.update("update plm_collaboration_mold_set set planned_mold_delivery_date='2026-07-25' where mold_set_id=1");return snapshot("2026-07-20");});assertThatThrownBy(this::perform).isInstanceOf(BusinessException.class);assertThat(date()).isEqualTo("2026-07-25");assertThat(sync.fields(1)).containsEntry("linked",false);assertThat(fields().path("plannedMoldDeliveryDate").path("manual").asBoolean()).isTrue();
    }
    void inlineWorker(){sync.close();ReflectionTestUtils.setField(sync,"executor",new java.util.concurrent.AbstractExecutorService(){public void shutdown(){}public List<Runnable> shutdownNow(){return List.of();}public boolean isShutdown(){return false;}public boolean isTerminated(){return false;}public boolean awaitTermination(long t,java.util.concurrent.TimeUnit u){return true;}public void execute(Runnable action){action.run();}});}
    @Test void scheduled_refresh_uses_identical_protection_and_rechecks_active_operator()throws Exception{
        perform();jdbc.update("update plm_collaboration_mold_set set planned_mold_delivery_date='2026-07-25' where mold_set_id=1");jdbc.update("update plm_mold_sheet_config set automatic_enabled=true,operator_user_id=1,last_attempt_at=now()-interval '2 hours'");inlineWorker();sync.automatic();
        assertThat(date()).isEqualTo("2026-07-25");assertThat(jdbc.queryForObject("select count(*) from plm_mold_sheet_run where mode='automatic' and status='completed'",Integer.class)).isEqualTo(1);
        jdbc.update("update sys_user set status='disabled' where user_id=1");jdbc.update("update plm_mold_sheet_config set last_attempt_at=now()-interval '2 hours'");sync.automatic();assertThat(jdbc.queryForObject("select count(*) from plm_mold_sheet_run where mode='automatic'",Integer.class)).isEqualTo(1);assertThat(jdbc.queryForObject("select last_error from plm_mold_sheet_config",String.class)).contains("停用");
    }
    @Test void repeated_refresh_while_running_joins_existing_job_instead_of_duplicating()throws Exception{
        inlineWorker();when(source.read(anyString())).thenAnswer(i->{sync.start();assertThat(jdbc.queryForObject("select count(*) from plm_mold_sheet_run",Integer.class)).isEqualTo(1);return snapshot("2026-07-15");});sync.start();assertThat(date()).isEqualTo("2026-07-15");verify(source,times(1)).read(anyString());assertThat(jdbc.queryForObject("select count(*) from plm_mold_sheet_run",Integer.class)).isEqualTo(1);
    }
    @Test void interrupted_job_expires_in_status_so_manual_retry_is_not_disabled_forever(){jdbc.update("update plm_mold_sheet_config set running_id=?,running_since=now()-interval '6 minutes'",UUID.randomUUID());assertThat(sync.status()).containsEntry("running",false);assertThat(sync.status().get("lastError")).asString().contains("可以重试");}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void explicit_binding_preserves_dates_maintained_before_the_source_link(boolean clear)throws Exception{
        jdbc.update("update plm_mold_sheet_config set initialized_at=now()");
        var review=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot("2026-07-15");
        ((com.fasterxml.jackson.databind.node.ArrayNode)review.path("formulas").get(1)).set(5,json.valueToTree("=E3+7"));
        when(source.read(anyString())).thenReturn(review);UUID prior=perform();
        JsonNode result=json.readTree(jdbc.queryForObject("select result::text from plm_mold_sheet_run where run_id=?",String.class,prior));
        assertThat(result.path("rows").get(0).path("state").asText()).isEqualTo("review");
        if(clear){jdbc.update("update plm_collaboration_mold_set set planned_mold_delivery_date=null where mold_set_id=1");assertThat(sync.fields(1)).containsEntry("linked",false);}
        when(source.read(anyString())).thenReturn(snapshot("2026-07-15"));inlineWorker();
        sync.bind(new MoldScheduleSheetSync.Binding(result.path("rows").get(0).path("sourceKey").asText(),1,"核对实物套关联，不恢复人工日期跟随"));
        assertThat(sync.status().get("lastError")).isNull();
        assertThat(date()).isEqualTo(clear?null:"2026-07-20");
        assertThat(fields().path("plannedMoldDeliveryDate").path("manual").asBoolean()).isTrue();
        assertThat(sync.fields(1)).containsEntry("linked",true);
        var otherReview=review.deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)otherReview.path("displayValues").get(1)).set(2,json.valueToTree("iPhone 18 Pro"));
        when(source.read(anyString())).thenReturn(otherReview);UUID other=perform();
        JsonNode candidate=json.readTree(jdbc.queryForObject("select result::text from plm_mold_sheet_run where run_id=?",String.class,other)).path("rows").get(0);
        assertThat(candidate.path("state").asText()).isEqualTo("review");assertThat(candidate.path("sourceKey").asText()).isNotEqualTo(result.path("rows").get(0).path("sourceKey").asText());
        var bound=jdbc.queryForMap("select source_key,field_state::text,version_no from plm_mold_sheet_binding where mold_set_id=1");
        assertThatThrownBy(()->sync.bind(new MoldScheduleSheetSync.Binding(candidate.path("sourceKey").asText(),1,"核对另一个来源"))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForMap("select source_key,field_state::text,version_no from plm_mold_sheet_binding where mold_set_id=1")).isEqualTo(bound);
    }
    @Test void a_manual_clear_before_any_binding_survives_the_first_later_automatic_match()throws Exception{
        perform();jdbc.update("insert into plm_collaboration_mold_set(mold_set_id,planned_mold_delivery_date) values(2,'2026-07-20')");
        jdbc.update("update plm_collaboration_mold_set set planned_mold_delivery_date=null where mold_set_id=2");
        assertThat(sync.fields(2)).containsEntry("linked",false);
        var data=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot("2026-07-15");
        var rows=(com.fasterxml.jackson.databind.node.ArrayNode)data.path("displayValues");var next=(com.fasterxml.jackson.databind.node.ArrayNode)rows.get(1).deepCopy();next.set(2,json.valueToTree("IP 18 PRO MAX"));rows.add(next);
        var formulas=(com.fasterxml.jackson.databind.node.ArrayNode)data.path("formulas");var formula=(com.fasterxml.jackson.databind.node.ArrayNode)formulas.get(1).deepCopy();formula.set(5,json.valueToTree("=E3+7"));formulas.add(formula);
        when(source.read(anyString())).thenReturn(data);
        jdbc.update("update plm_mold_sheet_config set automatic_enabled=true,operator_user_id=1,last_attempt_at=now()-interval '2 hours'");inlineWorker();sync.automatic();
        assertThat(jdbc.queryForObject("select status from plm_mold_sheet_run where mode='automatic'",String.class)).isEqualTo("completed");
        assertThat(jdbc.queryForObject("select planned_mold_delivery_date::text from plm_collaboration_mold_set where mold_set_id=2",String.class)).isNull();
        assertThat(jdbc.queryForObject("select opened_date::text from plm_collaboration_mold_set where mold_set_id=2",String.class)).isEqualTo("2026-07-01");
        var preserved=json.readTree(jdbc.queryForObject("select field_state::text from plm_mold_sheet_binding where mold_set_id=2",String.class));
        assertThat(preserved.path("plannedMoldDeliveryDate").path("manual").asBoolean()).isTrue();
        assertThat(preserved.path("plannedMoldDeliveryDate").path("manualValue").isNull()).isTrue();
        assertThat(sync.fields(2)).containsEntry("linked",true);
    }
}

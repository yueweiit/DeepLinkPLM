package com.yuewei.plm.module.integration.crm.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import com.yuewei.plm.module.collaboration.*;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Dedicated empty schema clone only; never accepts the running PLM database. */
@EnabledIfEnvironmentVariable(named="PLM_CRM_OPENAPI_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15519/crm_openapi_it\\?currentSchema=plm")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrmOpenApiPostgresTest {
    static JdbcTemplate jdbc;static DataSourceTransactionManager manager;static CrmOpenApiProperties properties;static CrmSyncStore store;static CrmQueryService query;static CrmChangeJobs jobs;static CrmScheduleDateService dates;static MoldScheduleService schedules;
    static final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    static long mold,phone,brand,item;static final long USER=99119001;
    static CurrentUser actor=new CurrentUser(USER,"crm-it","CRM IT",false);
    @BeforeAll static void setup(){
        var ds=new DriverManagerDataSource(System.getenv("PLM_CRM_OPENAPI_TEST_DB_URL"),"postgres","");jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
        jdbc.update("insert into sys_user(user_id,user_code,user_name,login_name,username,display_name,status) values(?,'CRM-IT','CRM IT','crm-it','crm-it','CRM IT','active')",USER);
        jdbc.update("insert into plm_pilot_account(user_id,role_code,permissions,all_objects,created_by,updated_by) values(?,'domestic','[\"schedule:read\",\"schedule:write\",\"collaboration:view\",\"project:view\",\"product:view\"]'::jsonb,true,'it','it')",USER);
        jdbc.update("insert into plm_collaboration_assignment(field_group,user_id) values('logistics',?),('mexico',?)",USER,USER);
        brand=jdbc.queryForObject("insert into plm_phone_brand(name,created_by,updated_by) values('CRM IT brand','it','it') returning id",Long.class);
        phone=jdbc.queryForObject("insert into plm_phone_model_member(brand_id,model_name,created_by,updated_by) values(?,'CRM IT phone','it','it') returning id",Long.class,brand);
        jdbc.update("insert into plm_product(product_id,product_code,product_name,product_type,version_no,created_by,updated_by) values(99119002,'MBA','CRM IT product','product_line',1,'it','it')");
        item=jdbc.queryForObject("insert into plm_collaboration_item(product_line_id,style_name,compatible_models,created_by,updated_by) values(99119002,'亮甲2.0','historical phone','it','it') returning item_id",Long.class);
        mold=jdbc.queryForObject("insert into plm_collaboration_mold_set(item_id,set_no,set_name,opened_date,planned_mold_delivery_date,actual_ship_date,planned_factory_arrival_date,owner_user_id,created_by,updated_by) values(?,1,'CRM IT mold','2026-09-29','2026-10-20','2026-10-23','2026-10-27',?,'it','it') returning mold_set_id",Long.class,item,USER);
        jdbc.update("insert into plm_collaboration_mold_part(mold_set_id,part_type,status) values(?,'PC','ready'),(?,'TPU','ready')",mold,mold);
        jdbc.update("insert into plm_collaboration_phone_member(item_id,phone_model_id,mold_set_ids,pc_confirmed,tpu_confirmed,evidence,created_by,updated_by) values(?,?,?::bigint[],true,true,'proof','it','it')",item,phone,"{"+mold+"}");
        jdbc.update("insert into plm_collaboration_phone_set_compatibility(item_id,phone_model_id,mold_set_id,pc_confirmed,tpu_confirmed,pc_evidence,tpu_evidence,created_by,updated_by) values(?,?,?,true,true,'proof','proof','it','it')",item,phone,mold);
        jdbc.queryForObject("select plm_crm_openapi_initialize()",UUID.class);
        properties=new CrmOpenApiProperties();properties.setEnabled(true);properties.setWriteEnabled(true);properties.setServiceUserId(USER);properties.setSigningSecret("test-signing-secret-32-characters-minimum");properties.setTokenDigests(List.of(CrmTokenCodec.hash("test-token")));properties.setScopes(List.of("crm:mold-schedule:read","crm:phone-model:read","crm:schedule-dates:write"));
        var codec=new CrmTokenCodec(properties,json);store=new CrmSyncStore(jdbc,properties,codec,manager);
        var repository=new CollaborationRepository(jdbc,json);var collaboration=new CollaborationService(jdbc,json,repository,manager,mock(OperationLogService.class));
        var access=new com.yuewei.plm.module.pilot.PilotAccessService(jdbc,json);
        schedules=new MoldScheduleService(jdbc,json,collaboration,repository,access,manager);
        query=new CrmQueryService(jdbc,schedules,store,codec,properties,manager);jobs=new CrmChangeJobs(jdbc,properties,manager);dates=new CrmScheduleDateService(jdbc,collaboration,store,query,properties,json,manager);
    }
    @BeforeEach void identity(){CurrentUserContext.set(actor);}
    @AfterEach void clear(){CurrentUserContext.clear();}
    MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.setAttribute(CrmServicePrincipal.ATTRIBUTE,new CrmServicePrincipal("crm",USER,Set.copyOf(properties.getScopes())));return r;}
    String body(String fields){return "{\"dates\":{"+fields+"},\"reason\":\"test change\",\"crmReference\":\"it\"}";}
    @Test @Order(1) void currentQueriesAndIndependentUncodedPhone(){
        var p=query.detail("PHONE_MODEL",phone);assertThat(p.get("phoneModelCode")).isNull();
        var m=query.detail("MOLD_SET",mold);assertThat(m.get("recordId")).isEqualTo(Long.toString(mold));assertThat(m.get("deliveryStatus")).isEqualTo("shipped");assertThat(m).doesNotContainKeys("quoteAmount","development","form");
        assertThat((List<?>)query.page("PHONE_MODEL",Map.of()).get("items")).hasSize(1);
    }
    @Test @Order(2) void patchClearNoopIdempotencyAndHumanConflict(){
        var before=query.detail("MOLD_SET",mold);String version="\""+before.get("recordVersion")+"\"";
        String body=body("\"actualMexicoArrivalDate\":\"2026-10-28\",\"plannedAcceptanceDate\":\"2026-11-11\",\"plannedProductionDate\":\"2026-11-14\"");
        var saved=dates.save(mold,version,"it-1",body,request());assertThat(saved.get("deliveryStatus")).isEqualTo("mexico_arrived");
        Long history=jdbc.queryForObject("select count(*) from plm_collaboration_history where entity_id=? and entity_type='mold_set'",Long.class,mold);
        assertThat(dates.save(mold,version,"it-1",body,request())).containsEntry("recordVersion",saved.get("recordVersion"));
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_history where entity_id=? and entity_type='mold_set'",Long.class,mold)).isEqualTo(history);
        var noop=dates.save(mold,"\""+saved.get("recordVersion")+"\"","it-noop",body("\"plannedAcceptanceDate\":\"2026-11-11\""),request());
        assertThat(noop.get("recordVersion")).isEqualTo(saved.get("recordVersion"));
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_history where entity_id=? and entity_type='mold_set'",Long.class,mold)).isEqualTo(history);
        assertThatThrownBy(()->dates.save(mold,version,"it-1",body("\"plannedAcceptanceDate\":null"),request())).isInstanceOfSatisfying(CrmApiException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        String current="\""+saved.get("recordVersion")+"\"";var cleared=dates.save(mold,current,"it-2",body("\"plannedAcceptanceDate\":null"),request());assertThat(((Map<?,?>)cleared.get("dates")).get("plannedAcceptanceDate")).isNull();
        var human=schedules.save(new CollaborationTypes.BatchSave(List.of(new CollaborationTypes.RowPatch("mold_set",mold,List.of(new CollaborationTypes.CellChange("plannedProductionDate",json.valueToTree("2026-11-14"),json.valueToTree("2026-11-20"),"PLM manual correction"))))),new MockHttpServletRequest());
        assertThat(human.get(0).status()).isEqualTo("saved");
        assertThatThrownBy(()->dates.save(mold,"\""+cleared.get("recordVersion")+"\"","it-3",body("\"plannedProductionDate\":null"),request())).isInstanceOfSatisfying(CrmApiException.class,e->assertThat(e.status()).isEqualTo(412));
        assertThat(jdbc.queryForObject("select planned_production_date::text from plm_collaboration_mold_set where mold_set_id=?",String.class,mold)).isEqualTo("2026-11-20");
    }
    @Test @Order(3) void codeLeadingZeroAndDependencyFanout(){
        String old=query.detail("MOLD_SET",mold).get("recordVersion").toString();
        long code=jdbc.queryForObject("insert into plm_code_item(code_type,code_value,code_name) values('phone_model','0185','CRM IT phone') returning code_item_id",Long.class);
        jdbc.update("update plm_phone_model_member set code_item_id=? where id=?",code,phone);
        assertThat(query.detail("PHONE_MODEL",phone)).containsEntry("phoneModelCode","0185");assertThat(query.detail("MOLD_SET",mold).get("recordVersion")).isNotEqualTo(old);
        jdbc.update("update plm_code_item set status='disabled' where code_item_id=?",code);assertThat(query.detail("PHONE_MODEL",phone).get("phoneModelCode")).isNull();
        jdbc.update("update plm_phone_model_member set status='disabled' where id=?",phone);assertThat(query.detail("PHONE_MODEL",phone)).containsEntry("lifecycleStatus","INACTIVE");
        while(jobs.publishBatch()>0){}assertThat(jdbc.queryForObject("select count(*) from plm_crm_openapi_change where entity_type='PHONE_MODEL' and entity_id=? and action='DISABLE'",Long.class,phone)).isGreaterThan(0);
        jdbc.update("update plm_phone_model_member set status='enabled' where id=?",phone);
    }
    @Test @Order(4) void lateCommitSmallCaptureIsNotLost()throws Exception {
        while(jobs.publishBatch()>0){}long head=((Number)store.control().get("published_head")).longValue();
        var ds=new DriverManagerDataSource(System.getenv("PLM_CRM_OPENAPI_TEST_DB_URL"),"postgres","");
        try(var a=ds.getConnection();var b=ds.getConnection()){
            a.setAutoCommit(false);b.setAutoCommit(false);
            try(var q=a.prepareStatement("update plm_phone_model_member set model_name='late-commit' where id=?")){q.setLong(1,phone);q.executeUpdate();}
            // Different entity and unrelated row: commit B while smaller capture in A is invisible.
            try(var q=b.prepareStatement("insert into plm_phone_brand(name,created_by,updated_by) values('unrelated','it','it') returning id")){try(var r=q.executeQuery()){r.next();long otherBrand=r.getLong(1);try(var p=b.prepareStatement("insert into plm_phone_model_member(brand_id,model_name,created_by,updated_by) values(?,'early-commit','it','it')")){p.setLong(1,otherBrand);p.executeUpdate();}}}
            b.commit();assertThat(jobs.publishBatch()).isGreaterThan(0);long mid=((Number)store.control().get("published_head")).longValue();assertThat(mid).isGreaterThan(head);
            a.commit();assertThat(jobs.publishBatch()).isGreaterThan(0);assertThat(((Number)store.control().get("published_head")).longValue()).isGreaterThan(mid);
            assertThat(jdbc.queryForObject("select count(*) from plm_crm_openapi_change where change_id>? and entity_type='PHONE_MODEL' and entity_id=?",Long.class,mid,phone)).isPositive();
        }
    }
    @Test @Order(5) void rollbackDeleteDisableRestoreAndDayChange(){
        long count=jdbc.queryForObject("select count(*) from plm_crm_openapi_capture",Long.class);
        new TransactionTemplate(manager).executeWithoutResult(s->{jdbc.update("update plm_collaboration_mold_set set planned_acceptance_date='2026-12-01' where mold_set_id=?",mold);s.setRollbackOnly();});
        assertThat(jdbc.queryForObject("select count(*) from plm_crm_openapi_capture",Long.class)).isEqualTo(count);
        jdbc.update("update plm_collaboration_mold_set set master_data_status='inactive' where mold_set_id=?",mold);assertThat(query.detail("MOLD_SET",mold)).containsEntry("lifecycleStatus","INACTIVE");
        jdbc.update("update plm_collaboration_mold_set set master_data_status='active' where mold_set_id=?",mold);
        jdbc.update("update plm_crm_openapi_resource set business_date='2000-01-01' where entity_type='MOLD_SET'");assertThat(jobs.advanceBusinessDate()).isPositive();assertThat(jobs.advanceBusinessDate()).isZero();
        jdbc.update("update plm_phone_model_member set deleted_flag=1 where model_name='early-commit'");while(jobs.publishBatch()>0){}
        assertThat(jdbc.queryForObject("select count(*) from plm_crm_openapi_change where action='DELETE'",Long.class)).isPositive();
    }
    @Test @Order(6) void concurrentSameKeyReplaysOneCommittedWrite()throws Exception {
        String version="\""+query.detail("MOLD_SET",mold).get("recordVersion")+"\"";
        String body=body("\"plannedAcceptanceDate\":\"2026-12-10\"");
        long before=jdbc.queryForObject("select count(*) from plm_collaboration_history where entity_type='mold_set' and entity_id=?",Long.class,mold);
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        Callable<Map<String,Object>> task=()->{CurrentUserContext.set(actor);try{start.await();return dates.save(mold,version,"it-concurrent",body,request());}finally{CurrentUserContext.clear();}};
        try {
            var first=pool.submit(task);var second=pool.submit(task);start.countDown();
            assertThat(first.get(20,TimeUnit.SECONDS).get("recordVersion")).isEqualTo(second.get(20,TimeUnit.SECONDS).get("recordVersion"));
        }finally{pool.shutdownNow();}
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_history where entity_type='mold_set' and entity_id=?",Long.class,mold)).isEqualTo(before+1);
    }
    @Test @Order(7) void pausedScheduleOnlyAcceptsActualDateAndNeverInfersCompletion(){
        long app=jdbc.queryForObject("insert into plm_mold_application(process_code,mold_set_id,phone_model_id,execution_status,created_by,updated_by) values('crm-it',?,?,'paused','it','it') returning application_id",Long.class,mold,phone);
        var row=query.detail("MOLD_SET",mold);assertThat(row.get("deliveryStatus")).isEqualTo("paused");assertThat(row.get("writableFields")).isEqualTo(List.of("actualMexicoArrivalDate"));
        assertThatThrownBy(()->dates.save(mold,"\""+row.get("recordVersion")+"\"","it-paused",body("\"plannedAcceptanceDate\":null"),request())).isInstanceOfSatisfying(CrmApiException.class,e->assertThat(e.code()).isEqualTo("EXECUTION_RESTRICTED"));
        jdbc.update("update plm_mold_application set deleted_flag=1 where application_id=?",app);
        assertThat(query.detail("MOLD_SET",mold).get("deliveryStatus")).isEqualTo("mexico_arrived");
    }
    @Test @Order(8) void parentDisableDeleteAndRestorePropagate(){
        jdbc.update("update plm_phone_brand set status='disabled' where id=?",brand);assertThat(query.detail("PHONE_MODEL",phone)).containsEntry("lifecycleStatus","INACTIVE");
        assertThat(query.detail("MOLD_SET",mold)).containsEntry("lifecycleStatus","ACTIVE");jdbc.update("update plm_phone_brand set status='enabled' where id=?",brand);
        jdbc.update("update plm_product set master_data_status='inactive' where product_id=99119002");assertThat(query.detail("MOLD_SET",mold)).containsEntry("lifecycleStatus","INACTIVE");
        jdbc.update("update plm_product set master_data_status='active' where product_id=99119002");
        jdbc.update("update plm_collaboration_item set deleted_flag=1 where item_id=?",item);
        assertThatThrownBy(()->query.detail("MOLD_SET",mold)).isInstanceOfSatisfying(CrmApiException.class,e->assertThat(e.status()).isEqualTo(404));
        jdbc.update("update plm_collaboration_item set deleted_flag=0 where item_id=?",item);assertThat(query.detail("MOLD_SET",mold)).containsEntry("lifecycleStatus","ACTIVE");
    }
    @Test @Order(9) void initialScanPagingSearchAndChangesRetention(){
        jdbc.queryForObject("insert into plm_phone_model_member(brand_id,model_name,created_by,updated_by) values(?,'paging phone','it','it') returning id",Long.class,brand);
        var first=query.page("PHONE_MODEL",Map.of("pageSize","1"));assertThat(first.get("hasMore")).isEqualTo(true);assertThat(first.get("syncCursor")).isNotNull();
        var next=query.page("PHONE_MODEL",Map.of("pageSize","1","pageToken",first.get("nextPageToken").toString()));
        assertThat(((List<?>)first.get("items")).get(0)).isNotEqualTo(((List<?>)next.get("items")).get(0));assertThat(first.get("syncCursor")).isEqualTo(next.get("syncCursor"));
        assertThatThrownBy(()->query.page("PHONE_MODEL",Map.of("pageSize","2","pageToken",first.get("nextPageToken").toString()))).isInstanceOf(CrmApiException.class);
        assertThat((List<?>)query.page("MOLD_SET",Map.of("keyword","late-commit")).get("items")).hasSize(1);
        assertThat((List<?>)query.page("MOLD_SET",Map.of("phoneModelId",Long.toString(phone))).get("items")).hasSize(1);
        String cursor=first.get("syncCursor").toString();jdbc.update("update plm_phone_model_member set model_name='incremental phone' where model_name='paging phone'");while(jobs.publishBatch()>0){}
        var changes=store.changes(cursor,1);assertThat((List<?>)changes.get("changes")).isNotEmpty();
        while(Boolean.TRUE.equals(changes.get("hasMore")))changes=store.changes(changes.get("nextCursor").toString(),1);
        assertThat((List<?>)store.changes(changes.get("nextCursor").toString(),1).get("changes")).isEmpty();
        jdbc.update("update plm_crm_openapi_change set published_at=clock_timestamp()-interval '31 days'");jobs.cleanup();
        assertThatThrownBy(()->store.changes(cursor,10)).isInstanceOfSatisfying(CrmApiException.class,e->assertThat(e.status()).isEqualTo(410));
    }
    @Test @Order(10) void physicalPhoneDeleteKeepsTombstoneAndRelationRemovalChangesMold(){
        long id=jdbc.queryForObject("insert into plm_phone_model_member(brand_id,model_name,created_by,updated_by) values(?,'physical delete','it','it') returning id",Long.class,brand);
        jdbc.update("delete from plm_phone_model_member where id=?",id);while(jobs.publishBatch()>0){}
        assertThat(jdbc.queryForObject("select count(*) from plm_crm_openapi_change where entity_type='PHONE_MODEL' and entity_id=? and action='DELETE'",Long.class,id)).isPositive();
        String before=query.detail("MOLD_SET",mold).get("recordVersion").toString();jdbc.update("delete from plm_collaboration_phone_set_compatibility where mold_set_id=? and phone_model_id=?",mold,phone);
        assertThat(query.detail("MOLD_SET",mold).get("recordVersion")).isNotEqualTo(before);assertThat((List<?>)query.detail("MOLD_SET",mold).get("compatiblePhones")).isEmpty();
    }
    @Test @Order(11) void serviceAccountChecksUseRealAccountScopeAndAssignments()throws Exception {
        var filter=new CrmOpenApiSecurityConfig.ServiceFilter(properties,jdbc,json);
        var r=new MockHttpServletRequest("GET",CrmOpenApiSecurityConfig.PREFIX+"/phone-models");r.addHeader("Authorization","Bearer test-token");var response=new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(r,response,(req,res)->assertThat(CrmServicePrincipal.from((jakarta.servlet.http.HttpServletRequest)req).userId()).isEqualTo(USER));assertThat(response.getStatus()).isEqualTo(200);
        jdbc.update("update plm_pilot_account set all_objects=false where user_id=?",USER);
        try {
            var denied=new MockHttpServletRequest("GET",CrmOpenApiSecurityConfig.PREFIX+"/phone-models");denied.addHeader("Authorization","Bearer test-token");var failure=new org.springframework.mock.web.MockHttpServletResponse();
            filter.doFilter(denied,failure,(req,res)->fail("Restricted account must not reach CRM data"));assertThat(failure.getStatus()).isEqualTo(401);
        }finally{jdbc.update("update plm_pilot_account set all_objects=true where user_id=?",USER);}
    }
    @Test @Order(12) void splitProductsAndLegacyOrExplicitSkusUsesOnlyExplicitItemRelations(){
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            try {
                jdbc.update("""
                    insert into plm_product(product_id,parent_product_id,product_type,product_code,product_name,
                      version_no,finished_product_code,color,color_code,phone_model_code,created_by,updated_by)
                    values(99119201,99119002,'model_variant','PRD-CRM-NEW','在研项目','1',null,null,null,null,'it','it'),
                      (99119202,99119002,'model_variant','NFA4020018501','历史SKU','1','NFA4020018501','黑色','01','0185','it','it'),
                      (99119203,99119201,'sku','LOCAL-EXPLICIT-SKU','显式SKU','1',null,null,null,null,'it','it'),
                      (99119204,99119002,'model_variant','NBA4030119901','无颜色的项目','1',null,null,null,null,'it','it'),
                      (99119205,99119002,'sku','UNLINKED-SKU','未关联SKU','1',null,null,null,null,'it','it')
                    """);
                for(long product:List.of(99119201L,99119202L,99119203L,99119204L))
                    jdbc.update("insert into plm_collaboration_item_project(item_id,project_id,linked_by) values(?,?,'it')",item,product);
                var detail=query.detail("MOLD_SET",mold);
                assertThat(detail).containsEntry("productSchemaVersion",2);
                var products=json.valueToTree(detail.get("products"));var skus=json.valueToTree(detail.get("skus"));
                assertThat(products.findValuesAsText("productId")).containsExactly("99119002","99119201","99119204");
                assertThat(skus.findValuesAsText("skuId")).containsExactly("99119202","99119203");
                assertThat(skus.get(0).path("skuCode").asText()).isEqualTo("NFA4020018501");
                assertThat(skus.get(0).path("phoneModelCode").asText()).isEqualTo("0185");
                assertThat(skus.get(1).path("skuCode").asText()).isEqualTo("LOCAL-EXPLICIT-SKU");
                var page=query.page("MOLD_SET",Map.of("moldSetId",Long.toString(mold)));
                assertThat(json.valueToTree(page.get("items")).get(0).get("skus")).isEqualTo(skus);
                var saved=dates.save(mold,"\""+detail.get("recordVersion")+"\"","it-split-noop",
                    body("\"plannedAcceptanceDate\":"+json.valueToTree(((Map<?,?>)detail.get("dates")).get("plannedAcceptanceDate"))),request());
                assertThat(saved.get("products")).isEqualTo(detail.get("products"));assertThat(saved.get("skus")).isEqualTo(detail.get("skus"));
                jdbc.update("update plm_product set deleted_flag=1 where product_id=99119203");
                assertThat(json.valueToTree(query.detail("MOLD_SET",mold).get("skus")).findValuesAsText("skuId")).containsExactly("99119202");
            }finally{tx.setRollbackOnly();}
        });
    }
    @Test @Order(13) void skuProjectionChangesInvalidateEtagAndPublishIncludingRelationRemoval(){
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            try {
                jdbc.update("""
                    insert into plm_product(product_id,parent_product_id,product_type,product_code,product_name,
                      version_no,finished_product_code,color,color_code,phone_model_code,created_by,updated_by)
                    values(99119210,99119002,'model_variant','CRM-SKU-CHANGE','历史SKU','1','NBA4030018501','黑色','01','0185','it','it')
                    """);
                jdbc.update("insert into plm_collaboration_item_project(item_id,project_id,linked_by) values(?,99119210,'it')",item);
                while(jobs.publishBatch()>0){}
                String version=query.detail("MOLD_SET",mold).get("recordVersion").toString();
                for(String sql:List.of("update plm_product set color_code='02'", "update plm_product set phone_model_code='0186'",
                    "update plm_product set finished_product_code='NBA4030018602'", "update plm_product set color='白色'",
                    "update plm_product set product_type='sku'", "update plm_product set master_data_status='inactive'")){
                    jdbc.update(sql+" where product_id=99119210");
                    String next=query.detail("MOLD_SET",mold).get("recordVersion").toString();assertThat(next).isNotEqualTo(version);version=next;
                }
                jdbc.update("update plm_product set product_type='model_variant',project_kind='model_extension',project_registered_at=now() where product_id=99119210");
                assertThat((List<?>)query.detail("MOLD_SET",mold).get("skus")).isEmpty();
                assertThat(json.valueToTree(query.detail("MOLD_SET",mold).get("products")).findValuesAsText("productId")).contains("99119210");
                jdbc.update("delete from plm_collaboration_item_project where item_id=? and project_id=99119210",item);
                assertThat(query.detail("MOLD_SET",mold).get("recordVersion")).isNotEqualTo(version);
                assertThat(jobs.publishBatch()).isPositive();
                assertThat(jdbc.queryForObject("select count(*) from plm_crm_openapi_change where entity_type='MOLD_SET' and entity_id=? and action='UPSERT'",Long.class,mold)).isPositive();
            }finally{tx.setRollbackOnly();}
        });
    }
    @Test @Order(14) void projectionUpgradeRefreshesCurrentMoldsWithoutResettingCursor() throws Exception {
        String migration;
        try(var input=new org.springframework.core.io.ClassPathResource("db/migration/V20261111_1000__crm_openapi_separate_products_skus.sql").getInputStream()){
            migration=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            try {
                var before=store.control();String epoch=before.get("stream_epoch").toString();
                String cursor=store.cursor(((Number)before.get("published_head")).longValue(),null,before);
                String version=query.detail("MOLD_SET",mold).get("recordVersion").toString();
                jdbc.execute(migration);
                assertThat(store.control().get("stream_epoch").toString()).isEqualTo(epoch);
                assertThat(query.detail("MOLD_SET",mold).get("recordVersion")).isNotEqualTo(version);
                while(jobs.publishBatch()>0){}
                assertThat((List<?>)store.changes(cursor,100).get("changes")).isNotEmpty();
            } finally {tx.setRollbackOnly();}
        });
    }

    @Test @Order(15) void moldScheduleDescendingCursorKeepsNumericOrderAndLegacyAscendingDefault(){
        assertThatThrownBy(()->query.page("MOLD_SET",Map.of("sortOrder","sideways"))).isInstanceOf(CrmApiException.class);
        jdbc.update("insert into plm_product(product_id,product_code,product_name,product_type,version_no,created_by,updated_by) values(99119998,'CRM-DESC-TEST','CRM descending test','product_line',1,'it','it')");
        long sortItem=jdbc.queryForObject("insert into plm_collaboration_item(product_line_id,style_name,compatible_models,created_by,updated_by) values(99119998,'CRM descending test','test','it','it') returning item_id",Long.class);
        for(int setNo=1;setNo<=3;setNo++)jdbc.update("insert into plm_collaboration_mold_set(item_id,set_no,set_name,created_by,updated_by) values(?,?,?,'it','it')",sortItem,setNo,"CRM descending test "+setNo);
        Map<String,String> descFilters=Map.of("sortOrder","desc","pageSize","1","productCode","CRM-DESC-TEST");
        List<Long> descending=new ArrayList<>();Map<String,Object> page=query.page("MOLD_SET",descFilters);
        while(true){
            List<?> rows=(List<?>)page.get("items");
            rows.stream().map(row->Long.parseLong(((Map<?,?>)row).get("recordId").toString())).forEach(descending::add);
            if(!Boolean.TRUE.equals(page.get("hasMore")))break;
            var nextFilters=new HashMap<>(descFilters);nextFilters.put("pageToken",page.get("nextPageToken").toString());page=query.page("MOLD_SET",nextFilters);
        }
        assertThat(descending).hasSize(3).doesNotHaveDuplicates().isSortedAccordingTo(Comparator.reverseOrder());

        Map<String,String> ascFilters=Map.of("pageSize","1","productCode","CRM-DESC-TEST");
        List<Long> ascending=new ArrayList<>();page=query.page("MOLD_SET",ascFilters);
        while(true){
            List<?> rows=(List<?>)page.get("items");
            rows.stream().map(row->Long.parseLong(((Map<?,?>)row).get("recordId").toString())).forEach(ascending::add);
            if(!Boolean.TRUE.equals(page.get("hasMore")))break;
            var nextFilters=new HashMap<>(ascFilters);nextFilters.put("pageToken",page.get("nextPageToken").toString());page=query.page("MOLD_SET",nextFilters);
        }
        var reversed=new ArrayList<>(descending);Collections.reverse(reversed);
        assertThat(ascending).containsExactlyElementsOf(reversed);
    }
}

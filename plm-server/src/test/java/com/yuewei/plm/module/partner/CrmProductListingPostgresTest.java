package com.yuewei.plm.module.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.integration.crm.productlisting.*;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Disposable PostgreSQL only, never an existing PLM database. */
@EnabledIfEnvironmentVariable(named="PLM_CRM_LISTING_TEST_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15531/plm_crm_listing_tests")
class CrmProductListingPostgresTest {
    JdbcTemplate admin,jdbc;String schema;TransactionTemplate tx;ObjectMapper json=new ObjectMapper();
    PilotAccessService access;PartnerStore store;PartnerListingService listings;OperationLogService audit;
    PartnerCrmListingSnapshotFactory factory;PartnerCrmListingApplicationService app;CrmProductListingWorker worker;
    CrmProductListingProperties config;CrmProductListingClient client;
    String version;
    @BeforeEach void setup() {
        String url=System.getenv("PLM_CRM_LISTING_TEST_URL"),password=System.getenv("PLM_CRM_LISTING_TEST_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password));
        schema="crm_listing_"+UUID.randomUUID().toString().replace("-","");admin.execute("create schema "+schema);
        var ds=new DriverManagerDataSource(url+"?currentSchema="+schema,"postgres",password);jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("""
          create table plm_partner_record(record_id bigint primary key,kind text,code text,name text,status text,version bigint default 1,data jsonb default '{}',parent_record_id bigint,supplier_code text,deleted_flag int default 0,site text,external_id text,customer_id bigint,source_project_id bigint,updated_at timestamptz default now());
          create table plm_product(product_id bigint primary key,parent_product_id bigint,product_type text default 'sku',product_code text,product_name text,phone_model_member_id bigint,phone_model_code text,color text,color_code text,master_data_status text default 'active',master_data_version bigint default 1,deleted_flag int default 0);
          create table plm_partner_spec(spec_id bigint primary key,record_id bigint,data jsonb,deleted_flag int default 0);
          create table plm_partner_event(record_id bigint,action text,data jsonb,request_id text,created_by text,actor_id bigint,unique(record_id,request_id));
          create table plm_attachment(attachment_id bigint primary key,version_no text,checksum text,content_type text,deleted_flag int default 0);
          create table plm_phone_brand(id bigint primary key,status text);
          create table plm_code_item(code_item_id bigint primary key,code_value text,deleted_flag int default 0);
          create table plm_phone_model_member(id bigint primary key,brand_id bigint,model_name text,code_item_id bigint,status text);
          insert into plm_attachment values(81,'V1','image-hash','image/png',0);
          insert into plm_phone_brand values(1,'enabled');
          insert into plm_code_item values(1,'0185',0);
          insert into plm_phone_model_member values(18,1,'真实手机',1,'enabled');
          insert into plm_product(product_id,product_code,product_name,phone_model_member_id,phone_model_code,color,color_code) values(101,'NBA4030018501','真实 SKU',18,'0185','黑色','01'),(102,'NBA4030018502','第二 SKU',18,'0185','蓝色','02');
          insert into plm_partner_record(record_id,kind,code,name,status) values(71,'product','PP-71','商品','draft'),(88,'listing','LS-88','商品','not_connected'),(89,'listing','LS-89','第二条','not_connected'),(99,'inspection','QC-99','质检','signed');
          update plm_partner_record set parent_record_id=71,supplier_code='S' where kind='listing';
          update plm_partner_record set data='{"basisHash":"qc-hash","referenceSample":{},"resultSnapshot":{"scope":[]}}' where record_id=99;
          """);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261112_1100__crm_partner_listing_push.sql")).execute(ds);
        var specs=List.of(Map.of("id",1L,"name","第一规格","internalSkuId",101L,"phoneModelIds",List.of(18)),Map.of("id",2L,"name","第二规格","internalSkuId",102L,"phoneModelIds",List.of(18)));
        jdbc.update("update plm_partner_record set data=?::jsonb where record_id=71",encode(json,Map.of("specs",specs)));
        for(var spec:specs)jdbc.update("insert into plm_partner_spec values(?,71,?::jsonb,0)",spec.get("id"),encode(json,spec));
        var evidence=List.of(Map.of("specId",1,"inspectionId",99,"inspectionHash","qc-hash"),Map.of("specId",2,"inspectionId",99,"inspectionHash","qc-hash"));
        var basis=Map.of("name","商品","introduction","真实介绍","photos",List.of(Map.of("id",81,"version","V1")),"specs",evidence);
        jdbc.update("update plm_partner_record set data=?::jsonb where kind='listing'",encode(json,Map.of("productId",71,"basis",basis,"basisHash",PartnerQualityRules.digest(json,basis),"supplierCode","S")));
        access=mock(PilotAccessService.class);when(access.actorId()).thenReturn(14L);when(access.actor()).thenReturn(new CurrentUser(14L,"qa","QA",false));when(access.hasPermission(anyString())).thenReturn(true);
        audit=mock(OperationLogService.class);store=new PartnerStore(jdbc,json,access,audit);listings=mock(PartnerListingService.class);
        factory=new PartnerCrmListingSnapshotFactory(store,listings,jdbc,json,access);
        config=new CrmProductListingProperties();config.setEnabled(true);client=mock(CrmProductListingClient.class);when(client.ready()).thenReturn(true);when(client.upsertProduct(anyMap(),anyString())).thenReturn("crm-product-1");when(client.upsertSku(anyString(),anyMap(),anyString())).thenAnswer(c->"crm-sku-"+map(c.getArgument(1)).get("internalSkuId"));
        app=new PartnerCrmListingApplicationService(factory,store,mock(PartnerOperationsAccess.class),access,jdbc,json,config,List.of(client));worker=new CrmProductListingWorker(jdbc,json,new DataSourceTransactionManager(ds),config,List.of(client));
        version=text(app.options(88).get("snapshotVersion"));
        clearInvocations(client);
    }
    @AfterEach void cleanup() { if(admin!=null&&schema!=null)admin.execute("drop schema "+schema+" cascade"); }
    Map<String,Object> item(long id,List<Long> ids) { return Map.of("listingId",id,"snapshotVersion",text(app.options(id).get("snapshotVersion")),"internalSkuIds",ids); }
    long submit(List<Long> ids) { return number(((List<?>)tx.execute(s->app.submit("request-123",List.of(item(88,ids)))).get("taskIds")).get(0)); }
    String state(long task) { return jdbc.queryForObject("select status from plm_crm_listing_task where task_id=?",String.class,task); }
    void due(long task) { jdbc.update("update plm_crm_listing_task set next_attempt_at=now()-interval '1 second' where task_id=?",task); }

    @Test void defaultOffAndMissingAdapterBothFailClosed() {
        config.setEnabled(false);assertThatThrownBy(()->tx.execute(s->app.submit("request-123",List.of(item(88,List.of(101L)))))).hasMessageContaining("尚未启用");
        config.setEnabled(true);var noClient=new PartnerCrmListingApplicationService(factory,store,mock(PartnerOperationsAccess.class),access,jdbc,json,config,List.of());assertThat(noClient.capabilities()).containsEntry("canSubmit",false);assertThat(jdbc.queryForObject("select count(*) from plm_crm_listing_task",Long.class)).isZero();
    }
    @Test void explicitSelectionAndRequestReplayDoNotCreateExtraJobs() {
        var item=item(88,List.of(101L));var first=tx.execute(s->app.submit("request-123",List.of(item)));var repeated=tx.execute(s->app.submit("request-123",List.of(item)));assertThat(repeated.get("taskIds")).isEqualTo(first.get("taskIds"));
        assertThat(jdbc.queryForList("select internal_sku_id from plm_crm_listing_sku",Long.class)).containsExactly(101L);
        assertThatThrownBy(()->tx.execute(s->app.submit("request-123",List.of(item(88,List.of(102L)))))).hasMessageContaining("其他内容");
        assertThat(jdbc.queryForObject("select count(*) from plm_crm_listing_task",Long.class)).isEqualTo(1);
    }
    @Test void staleVersionAndForeignSkuAreRejectedBeforeCreatingTasks() {
        var stale=Map.<String,Object>of("listingId",88,"snapshotVersion",version,"internalSkuIds",List.of(101L));jdbc.update("update plm_product set master_data_version=2 where product_id=101");
        assertThatThrownBy(()->tx.execute(s->app.submit("request-123",List.of(stale)))).hasMessageContaining("已变化");
        assertThatThrownBy(()->tx.execute(s->app.submit("request-123",List.of(item(88,List.of(999L)))))).hasMessageContaining("不属于");assertThat(jdbc.queryForObject("select count(*) from plm_crm_listing_task",Long.class)).isZero();
    }
    @Test void unmappedInactiveDeletedOrMismatchedSkuCannotBeSelected() {
        for(String sql:List.of("update plm_product set master_data_status='inactive' where product_id=101","update plm_product set deleted_flag=1 where product_id=101","update plm_product set phone_model_code='9999' where product_id=101")) {
            jdbc.execute(sql);var candidate=maps(app.options(88).get("skus")).get(0);assertThat(candidate).containsEntry("eligible",false);assertThatThrownBy(()->tx.execute(s->app.submit("request-123",List.of(item(88,List.of(101L)))))).isInstanceOf(RuntimeException.class);jdbc.execute("update plm_product set master_data_status='active',deleted_flag=0,phone_model_code='0185' where product_id=101");
        }
        jdbc.execute("update plm_partner_record set data=jsonb_set(data,'{specs,0,internalSkuId}','0') where record_id=71");assertThat(maps(app.options(88).get("skus")).get(0)).containsEntry("eligible",false);
    }
    @Test void missingImageAndQualityFailuresBlockSubmission() {
        doThrow(invalid("最新质检不通过")).when(listings).validateCrmPushEvidence(anyMap(),anyMap(),anyMap());assertThatThrownBy(()->tx.execute(s->app.submit("request-123",List.of(item(88,List.of(101L)))))).hasMessageContaining("质检不通过");
        reset(listings);jdbc.execute("update plm_attachment set deleted_flag=1");assertThatThrownBy(()->tx.execute(s->app.submit("request-123",List.of(item(88,List.of(101L)))))).hasMessageContaining("商品资料");
    }
    @Test void permissionChecksHappenAgainOnSubmitAndReplay() {
        var saved=item(88,List.of(101L));tx.execute(s->app.submit("request-123",List.of(saved)));
        doThrow(new IllegalStateException("permission denied")).when(access).requirePermission("partner:listing:crm-push");assertThatThrownBy(()->tx.execute(s->app.submit("request-123",List.of(saved)))).hasMessage("permission denied");verify(client,never()).upsertProduct(anyMap(),anyString());verify(client,never()).upsertSku(anyString(),anyMap(),anyString());
    }
    @Test void secondInvalidListingRollsBackEntireBatchAndAuditFailureRollsBackJobs() {
        var first=item(88,List.of(101L));var second=Map.<String,Object>of("listingId",89,"snapshotVersion","0".repeat(64),"internalSkuIds",List.of(101L));
        assertThatThrownBy(()->tx.execute(s->app.submit("request-123",List.of(first,second)))).hasMessageContaining("已变化");assertThat(jdbc.queryForObject("select count(*) from plm_crm_listing_task",Long.class)).isZero();
        doThrow(new IllegalStateException("audit unavailable")).when(audit).logRequired(any(),any());assertThatThrownBy(()->submit(List.of(101L))).hasMessage("audit unavailable");assertThat(jdbc.queryForObject("select count(*) from plm_crm_listing_sku",Long.class)).isZero();
    }
    @Test void concurrentDuplicateRequestsReturnExactlyOneTask() throws Exception {
        var chosen=item(88,List.of(101L));var pool=Executors.newFixedThreadPool(2);try{Callable<Object> call=()->tx.execute(s->app.submit("request-123",List.of(chosen))).get("taskIds");var one=pool.submit(call);var two=pool.submit(call);assertThat(one.get(10,TimeUnit.SECONDS)).isEqualTo(two.get(10,TimeUnit.SECONDS));assertThat(jdbc.queryForObject("select count(*) from plm_crm_listing_task",Long.class)).isEqualTo(1);}finally{pool.shutdownNow();}
    }
    @Test void workerSendsProductBeforeOnlySelectedSkuAndNeverMarksListed() {
        long task=submit(List.of(101L));clearInvocations(client);worker.tick();var order=inOrder(client);order.verify(client).ready();order.verify(client).upsertProduct(anyMap(),contains(":product"));order.verify(client).upsertSku(eq("crm-product-1"),argThat(s->number(s.get("internalSkuId"))==101L),contains(":sku:101"));assertThat(state(task)).isEqualTo("synced");assertThat(jdbc.queryForObject("select listing_status from plm_crm_listing_sku",String.class)).isEqualTo("not_listed");assertThat(jdbc.queryForObject("select status from plm_partner_record where record_id=88",String.class)).isEqualTo("not_connected");
    }
    @Test void partialFailureRetrySkipsSuccessfulSkusAndReusesSameKeys() {
        when(client.upsertSku(anyString(),argThat(s->s!=null&&number(s.get("internalSkuId"))==102L),anyString())).thenThrow(new CrmProductListingClient.DeliveryException(false));long task=submit(List.of(101L,102L));worker.tick();assertThat(state(task)).isEqualTo("partial");
        when(client.upsertSku(anyString(),argThat(s->s!=null&&number(s.get("internalSkuId"))==102L),anyString())).thenReturn("crm-sku-102");tx.execute(s->app.retry(88,task,"retry-request-1"));worker.tick();assertThat(state(task)).isEqualTo("synced");verify(client,times(1)).upsertProduct(anyMap(),anyString());verify(client,times(1)).upsertSku(anyString(),argThat(s->number(s.get("internalSkuId"))==101L),anyString());
    }
    @Test void productTimeoutRetriesSameKeyAndStopsAtLimit() {
        config.setMaxAttempts(2);when(client.upsertProduct(anyMap(),anyString())).thenThrow(new RuntimeException("raw remote body with secret"));long task=submit(List.of(101L));worker.tick();assertThat(state(task)).isEqualTo("pending");due(task);worker.tick();assertThat(state(task)).isEqualTo("failed");var keys=org.mockito.ArgumentCaptor.forClass(String.class);verify(client,times(2)).upsertProduct(anyMap(),keys.capture());assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));assertThat(app.sync(88).toString()).doesNotContain("secret");
    }
    @Test void sourceChangesAfterQueueingStopDispatch() {
        long task=submit(List.of(101L));jdbc.update("update plm_product set master_data_version=2 where product_id=101");clearInvocations(client);worker.tick();assertThat(state(task)).isEqualTo("failed");verify(client,never()).upsertProduct(anyMap(),anyString());assertThat(app.sync(88)).containsEntry("errorCode","SOURCE_CHANGED");
    }
    @Test void phoneOrSpecDisabledAfterQueueingStopsDispatch() {
        long task=submit(List.of(101L));jdbc.update("update plm_phone_model_member set status='disabled' where id=18");clearInvocations(client);worker.tick();assertThat(state(task)).isEqualTo("failed");verify(client,never()).upsertProduct(anyMap(),anyString());
    }
    @Test void sourceChangedBetweenProductAndSkuIsNotSent() {
        when(client.upsertProduct(anyMap(),anyString())).thenAnswer(c->{jdbc.update("update plm_partner_spec set deleted_flag=1 where spec_id=1");return "crm-product-1";});long task=submit(List.of(101L));worker.tick();assertThat(state(task)).isEqualTo("failed");verify(client,never()).upsertSku(anyString(),anyMap(),anyString());
    }
    @Test void crashedLeaseRecoversWithoutReplacingTaskOrKeys() {
        long task=submit(List.of(101L));jdbc.update("update plm_crm_listing_task set status='processing',attempts=1,lease_token=?::uuid,lease_until=now()-interval '1 minute' where task_id=?",UUID.randomUUID().toString(),task);worker.tick();assertThat(state(task)).isEqualTo("synced");assertThat(jdbc.queryForObject("select attempts from plm_crm_listing_task where task_id=?",Integer.class,task)).isEqualTo(2);
    }
}

package com.yuewei.plm.module.product.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.project.development.DevelopmentWorkflowStore;
import com.yuewei.plm.repository.entity.Product;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real migration + service transactions in a disposable schema; no application startup or sync. */
class ProductCodeReservationPostgresTest {
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static String schema;
    ProductCodeReservationService service;
    DevelopmentWorkflowStore store;
    PilotAccessService access;

    @BeforeAll static void database() throws Exception {
        String url=System.getenv("PLM_IDENTITY_TEST_DB_URL");
        Assumptions.assumeTrue(url!=null&&!url.isBlank(),"Set PLM_IDENTITY_TEST_DB_URL to run PostgreSQL identity tests");
        URI uri=URI.create(url.substring("jdbc:".length()));
        if(!Set.of("localhost","127.0.0.1").contains(uri.getHost())||!"/reservation_test".equals(uri.getPath())||uri.getPort()<1024)
            throw new IllegalArgumentException("Identity tests require a dedicated loopback reservation_test database");
        String password=Objects.toString(System.getenv("PLM_IDENTITY_TEST_DB_PASSWORD"),"");
        schema="identity_"+UUID.randomUUID().toString().replace("-","");
        new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password)).execute("create schema "+schema);
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",password);
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setTimeout(20);
        jdbc.execute("""
            create table plm_product(product_id bigint primary key,parent_product_id bigint,product_type text default 'product_line',
                product_specific_code text,product_code_prefix text,mold_code_prefix text,product_code text,finished_product_code text,
                deleted_flag int default 0,status text default 'draft',lock_status text,current_step_no int default 2,development_mode text default 'launch_v1',
                timeline_current_confirmed boolean default false,timeline_confirmed_node_key text);
            create table plm_product_mold_code(product_id bigint,product_specific_code text,mold_code text);
            create table plm_mold_code_source(product_specific_code text,mold_code text,approval_instance_id text);
            create table plm_mold_application(external_instance_id text,product_line_id bigint,project_id bigint,generated_codes text);
            create table plm_inventory(inventory_code text,product_id bigint);
            create table plm_product_development_event(event_id bigserial primary key,product_id bigint,kind text,data jsonb,
                request_id text,created_by text,created_at timestamptz default now());
            """);
        try(var in=new ClassPathResource("db/migration/V20261109_2000__research_product_code_reservations.sql").getInputStream()){
            jdbc.execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
        try(var in=new ClassPathResource("db/migration/V20261109_2010__research_product_code_legacy_entry.sql").getInputStream()){
            jdbc.execute(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    @AfterAll static void cleanup(){
        if(jdbc!=null&&schema!=null&&schema.matches("identity_[a-f0-9]{32}"))jdbc.execute("drop schema "+schema+" cascade");
    }
    @BeforeEach void setup(){
        jdbc.execute("truncate plm_product,plm_product_code_reservation,plm_product_mold_code,plm_mold_code_source,plm_mold_application,plm_inventory,plm_product_development_event cascade");
        access=mock(PilotAccessService.class);when(access.actor()).thenReturn(new CurrentUser(1L,"tester","Tester",false));
        store=new DevelopmentWorkflowStore(jdbc,new ObjectMapper());service=new ProductCodeReservationService(jdbc,access,store);
        project(1);project(2);
    }
    void project(long id){jdbc.update("insert into plm_product(product_id,product_identity_mode) values(?,'reserved_v1')",id);}
    <T>T write(Supplier<T> action){return tx.execute(status->action.get());}
    void run(Runnable action){write(()->{action.run();return null;});}
    void propose(long id,String code){run(()->service.propose(id,code,false,""));}
    String state(long id){return service.view(id).get("state").toString();}
    Product finalProduct(long id){var p=new Product();p.setProductId(id);p.setProductIdentityMode("reserved_v1");p.setTimelineCurrentConfirmed(true);p.setTimelineConfirmedNodeKey("LAUNCH_SHIP");return p;}
    void persist(Product p){jdbc.update("update plm_product set product_specific_code=?,product_code_prefix=?,mold_code_prefix=?,timeline_current_confirmed=?,timeline_confirmed_node_key=? where product_id=?",p.getProductSpecificCode(),p.getProductCodePrefix(),p.getMoldCodePrefix(),p.getTimelineCurrentConfirmed(),p.getTimelineConfirmedNodeKey(),p.getProductId());}

    @Test void validatesNormalizesAndDefersFormalIdentity(){
        assertThat(ProductCodeReservationService.normalize(" nab ")).isEqualTo("AB");
        assertThatThrownBy(()->ProductCodeReservationService.normalize("AB")).hasMessageContaining("N 加两位");
        propose(1,"nab");assertThat(service.view(1)).containsEntry("productCode","NAB").containsEntry("moldPrefix","MAB");
        assertThat(jdbc.queryForObject("select product_specific_code from plm_product where product_id=1",String.class)).isNull();
        assertThatThrownBy(()->run(()->jdbc.update("update plm_product set product_specific_code='AB' where product_id=1"))).hasStackTraceContaining("只能在完成最终发模");
        assertThatThrownBy(()->service.requireConfirmedFinalShipment(1)).hasMessageContaining("先完成本项目发模");
    }
    @Test void uncodedExistingProjectShowsEntryWithoutMutatingUntilFirstProposal(){
        org.springframework.test.util.ReflectionTestUtils.setField(service,"enabled",true);
        jdbc.update("insert into plm_product(product_id,product_code) values(3,'PRD-existing-0073')");
        jdbc.update("insert into plm_mold_application(project_id,product_line_id,generated_codes) values(3,3,'')");
        assertThat(service.proposalView(3)).containsEntry("mode","legacy").containsEntry("state","unassigned");
        assertThat(service.availability(3,"NAB")).containsEntry("available",true);
        assertThat(jdbc.queryForObject("select product_identity_mode from plm_product where product_id=3",String.class)).isEqualTo("legacy");
        assertThat(jdbc.queryForObject("select count(*) from plm_product_development_event where product_id=3",Integer.class)).isZero();
        propose(3,"NAB");
        assertThat(service.proposalView(3)).containsEntry("mode","reserved_v1").containsEntry("productCode","NAB");
        assertThat(jdbc.queryForObject("select product_specific_code from plm_product where product_id=3",String.class)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from plm_product_development_event where product_id=3 and kind='product_code_enrolled'",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("update plm_product set product_identity_mode='legacy' where product_id=3")).hasStackTraceContaining("不能切换");
    }
    @Test void failedFirstProposalRollsBackEnrollmentAndItsEvent(){
        org.springframework.test.util.ReflectionTestUtils.setField(service,"enabled",true);
        jdbc.update("insert into plm_product(product_id,product_code) values(3,'PRD-existing-0073')");
        propose(1,"NAB");
        assertThatThrownBy(()->propose(3,"NAB")).hasMessageContaining("已被占用");
        assertThat(jdbc.queryForObject("select product_identity_mode from plm_product where product_id=3",String.class)).isEqualTo("legacy");
        assertThat(jdbc.queryForObject("select count(*) from plm_product_development_event where product_id=3",Integer.class)).isZero();
    }
    @Test void existingFormalAndReferencedIdentitiesAndInactiveProjectsKeepTheirOriginalFlow(){
        org.springframework.test.util.ReflectionTestUtils.setField(service,"enabled",true);
        jdbc.update("insert into plm_product(product_id,product_code_prefix) values(3,'AB')");
        jdbc.update("insert into plm_product(product_id) values(4),(6),(7),(8),(9)");
        jdbc.update("insert into plm_product(product_id,parent_product_id,product_type,product_code) values(5,4,'sku','NAC4030018501')");
        jdbc.update("insert into plm_mold_application(project_id,product_line_id,generated_codes) values(6,6,'MAD100185')");
        jdbc.update("insert into plm_mold_application(external_instance_id,product_line_id) values('external-7',7)");
        jdbc.update("insert into plm_mold_code_source(approval_instance_id,mold_code) values('external-7','MAE100185')");
        jdbc.update("update plm_product set lock_status='abandoned' where product_id=8");
        jdbc.update("update plm_product set current_step_no=8,timeline_current_confirmed=true,timeline_confirmed_node_key='LAUNCH_SHIP' where product_id=9");
        for(long id:List.of(3L,4L,6L,7L,8L,9L)){
            assertThat(service.proposalView(id)).isEmpty();
            assertThatThrownBy(()->propose(id,"NAF")).hasMessageContaining("沿用原编码流程");
        }
        jdbc.update("insert into plm_product(product_id) values(10)");
        assertThatThrownBy(()->jdbc.update("update plm_product set product_identity_mode='reserved_v1' where product_id=10")).hasStackTraceContaining("不能切换");
        org.springframework.test.util.ReflectionTestUtils.setField(service,"enabled",false);
        assertThat(service.proposalView(10)).isEmpty();
    }
    @Test void onlyOneConcurrentProjectCanReserveSameCode() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try{
            List<Future<Boolean>> results=new ArrayList<>();
            for(long id:List.of(1L,2L)){results.add(pool.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("barrier timed out");try{propose(id,"NAB");return true;}catch(com.yuewei.plm.common.exception.BusinessException e){assertThat(e).hasMessageContaining("已被占用");return false;}}));}
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(results.get(0).get(25,TimeUnit.SECONDS),results.get(1).get(25,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(jdbc.queryForObject("select count(*) from plm_product_code_reservation where specific_code='AB' and state='proposed'",Integer.class)).isEqualTo(1);
        }finally{start.countDown();pool.shutdownNow();}
    }
    @Test void legacyWritersCannotStealThroughAnyRootIdentityField(){
        propose(1,"NAB");
        for(var entry:Map.of("product_specific_code","AB","product_code_prefix","NAB","mold_code_prefix","MAB","product_code","NAB4030018501","finished_product_code","NAB4030018501").entrySet()){
            assertThatThrownBy(()->jdbc.update("insert into plm_product(product_id,"+entry.getKey()+") values(3,?)",entry.getValue())).hasStackTraceContaining("已被拟定或正式身份占用");
        }
    }
    @Test void releaseWithoutReferencesAndHoldPinnedCodeUntilExplicitReconfirmation(){
        propose(1,"NAB");run(()->service.invalidate(1,"项目驳回"));assertThat(state(1)).isEqualTo("released");
        propose(2,"NAB");run(()->service.pinForResearch(2,"开模"));run(()->service.invalidate(2,"停止"));
        assertThat(state(2)).isEqualTo("invalid_held");assertThatThrownBy(()->service.researchCode(2)).hasMessageContaining("重新确认");
        assertThatThrownBy(()->write(()->service.propose(2,"NAC",true,"恢复"))).hasMessageContaining("不能换码");
        assertThatThrownBy(()->write(()->service.propose(2,"NAB",true,""))).hasMessageContaining("填写原因");
        run(()->service.propose(2,"NAB",true,"恢复原方案"));assertThat(state(2)).isEqualTo("proposed");
        assertThatThrownBy(()->write(()->service.propose(2,"NAC",false,""))).hasMessageContaining("不能换码");
    }
    @Test void finalConfirmationCommitsSameProductAndSurvivesRevisionAndStop(){
        propose(1,"NAB");Product p=finalProduct(1);
        run(()->{service.confirmForFinalShipment(p,"tester");assertThat(p.getProductSpecificCode()).isEqualTo("AB");persist(p);});
        assertThat(state(1)).isEqualTo("confirmed");assertThatCode(()->service.requireConfirmedFinalShipment(1)).doesNotThrowAnyException();
        jdbc.update("update plm_product set timeline_current_confirmed=false,timeline_confirmed_node_key=null,status='released' where product_id=1");
        run(()->service.pinForResearch(1,"修订SKU"));run(()->service.invalidate(1,"修订退回"));assertThat(state(1)).isEqualTo("confirmed");
        assertThatThrownBy(()->jdbc.update("update plm_product_code_reservation set state='released' where product_id=1")).hasStackTraceContaining("不可释放或改绑");
        assertThatThrownBy(()->jdbc.update("delete from plm_product_code_reservation where product_id=1")).hasStackTraceContaining("不可释放或改绑");
    }
    @Test void missingOuterUpdateAndLaterFailureBothRollbackConfirmationAndAudit(){
        propose(1,"NAB");
        assertThatThrownBy(()->run(()->service.confirmForFinalShipment(finalProduct(1),"tester"))).hasStackTraceContaining("正式产品身份");
        assertThat(state(1)).isEqualTo("proposed");
        assertThatThrownBy(()->run(()->{var p=finalProduct(1);service.confirmForFinalShipment(p,"tester");persist(p);throw new IllegalStateException("later failure");})).hasMessage("later failure");
        assertThat(state(1)).isEqualTo("proposed");
        assertThat(jdbc.queryForObject("select count(*) from plm_product_development_event where kind='product_code_confirmed'",Integer.class)).isZero();
        var early=finalProduct(1);early.setTimelineConfirmedNodeKey("LAUNCH_PILOT");
        assertThatThrownBy(()->run(()->service.confirmForFinalShipment(early,"tester"))).hasMessageContaining("最终发模");
    }
    @Test void historicalAndUnknownSourceEvidenceStayOccupied(){
        jdbc.update("insert into plm_product(product_id,deleted_flag,status,product_code_prefix) values(3,1,'archived','AB')");
        assertThatThrownBy(()->propose(1,"NAB")).hasMessageContaining("已被占用");
        jdbc.update("insert into plm_mold_code_source(mold_code) values('MAC100185')");
        assertThatThrownBy(()->propose(1,"NAC")).hasMessageContaining("已被占用");
        jdbc.update("insert into plm_product(product_id,parent_product_id,product_type,product_code) values(4,3,'sku','NAD4030018501')");
        assertThat(service.occupied("AD",1L)).isTrue();
        jdbc.update("insert into plm_inventory(inventory_code) values('NAE4030018501')");
        assertThat(service.occupied("AE",1L)).isTrue();
    }
    @Test void confirmedInsertAlsoRequiresFinalNode(){
        assertThatThrownBy(()->run(()->{
            jdbc.update("insert into plm_product_code_reservation(product_id,specific_code,state,created_by,updated_by) values(1,'AB','confirmed','tester','tester')");
            jdbc.update("update plm_product set product_specific_code='AB',product_code_prefix='AB',mold_code_prefix='MAB' where product_id=1");
            store.event(1,"product_code_confirmed",Map.of("nodeKey","LAUNCH_SHIP"),null,"tester");
        })).hasStackTraceContaining("尚未完成最终发模");
    }
    @Test void requestIdCannotBeReusedForDifferentPayloadAndScopeIsChecked(){
        var payload=Map.<String,Object>of("productCode","NAB","reason","");
        store.event(1,"propose_product_code",Map.of("requestPayload",payload),"request-123","tester");
        assertThat(store.repeatedIdentity(1,"request-123","propose_product_code",payload)).isTrue();
        assertThatThrownBy(()->store.repeatedIdentity(1,"request-123","propose_product_code",Map.of("productCode","NAC"))).hasMessageContaining("另一份编码申请");
        doThrow(new com.yuewei.plm.common.exception.BusinessException(40103,"无项目权限")).when(access).requireProduct(1L);
        assertThatThrownBy(()->propose(1,"NAB")).hasMessageContaining("无项目权限");
        assertThat(state(1)).isEqualTo("unassigned");
    }
}

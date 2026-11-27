package com.yuewei.plm.module.workbench.home;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.moldapplication.*;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.net.URI;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yuewei.plm.module.workbench.home.WorkbenchHomeModels.*;

/** Opt-in, isolated schema on an explicitly named loopback test database. No Spring startup/sync. */
class WorkbenchHomePostgresTest {
    static JdbcTemplate jdbc;
    static TransactionTemplate readOnly;
    static String schema;
    PilotAccessService access;
    MoldDingTalkClient remote;
    MoldApplicationService applications;
    ResearchHomeQuery research;
    ApprovalHomeQuery generic;

    @BeforeAll static void database() {
        String url=System.getenv("PLM_WORKBENCH_TEST_DB_URL");
        Assumptions.assumeTrue(url!=null&&!url.isBlank(),"Set PLM_WORKBENCH_TEST_DB_URL to run PostgreSQL projection tests");
        URI uri=URI.create(url.substring("jdbc:".length()));
        if(!Set.of("localhost","127.0.0.1").contains(uri.getHost()) || !"/plm_workbench_test".equals(uri.getPath()) || uri.getPort()<1024)
            throw new IllegalArgumentException("Workbench tests require a loopback plm_workbench_test database");
        schema="workbench_"+UUID.randomUUID().toString().replace("-","");
        var admin=new DriverManagerDataSource(url,"postgres",Objects.toString(System.getenv("PLM_WORKBENCH_TEST_DB_PASSWORD"),""));
        new JdbcTemplate(admin).execute("create schema "+schema);
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",Objects.toString(System.getenv("PLM_WORKBENCH_TEST_DB_PASSWORD"),""));
        jdbc=new JdbcTemplate(ds);
        var tx=new DataSourceTransactionManager(ds); tx.setEnforceReadOnly(true);
        readOnly=new TransactionTemplate(tx);readOnly.setReadOnly(true);
        jdbc.execute("create table sys_user(user_id bigint primary key,username text,display_name text)");
        jdbc.execute("""
            create table plm_product(product_id bigint primary key,product_code text,product_name text,
              owner_user_id bigint,product_type text default 'product_line',development_mode text default 'launch_v1',
              status text default 'draft',current_step_no int default 1,timeline_current_confirmed boolean default false,
              lock_status text,expected_delivery_date date,updated_at timestamptz default now(),deleted_flag int default 0)
            """);
        jdbc.execute("create table plm_product_development_state(product_id bigint primary key,data jsonb default '{}'::jsonb)");
        jdbc.execute("create table plm_approval_instance(approval_instance_id bigint primary key,object_type text,object_id bigint,status text,deleted_flag int default 0)");
        jdbc.execute("create table plm_approval_task(approval_task_id bigint primary key,approval_instance_id bigint,approver_user_id bigint,status text,node_code text,created_at timestamptz default now(),deleted_flag int default 0)");
        jdbc.execute("create table plm_pilot_account(user_id bigint primary key,dingtalk_user_id text)");
        jdbc.execute("""
            create table plm_mold_application(application_id bigint primary key,applicant_id bigint,item_id bigint,
              source_snapshot_json jsonb default '{}'::jsonb,remote_result text,execution_exception_json jsonb default '{}'::jsonb,
              remote_status text default 'RUNNING',status text default 'submitted',previous_application_id bigint,
              approval_granted boolean default false,external_instance_id text,provisioning_status text,execution_status text,
              approval_no text,title text,project_id bigint,product_line_id bigint,last_synced_at timestamptz default now(),
              version_no bigint default 1,updated_at timestamptz default now(),deleted_flag int default 0)
            """);
        jdbc.execute("create table plm_mold_application_activity(activity_id bigserial primary key,application_id bigint,activity_type text,task_id text,actor_id text,occurred_at timestamptz,payload jsonb)");
    }

    @AfterAll static void cleanup() {
        if(jdbc!=null && schema!=null && schema.matches("workbench_[a-f0-9]{32}")) jdbc.execute("drop schema "+schema+" cascade");
    }

    @BeforeEach void setup() {
        jdbc.execute("truncate sys_user,plm_product,plm_product_development_state,plm_approval_instance,plm_approval_task,plm_pilot_account,plm_mold_application,plm_mold_application_activity");
        access=mock(PilotAccessService.class);remote=mock(MoldDingTalkClient.class);
        when(access.actorId()).thenReturn(1L);when(access.hasPermission(anyString())).thenReturn(true);
        when(access.access()).thenReturn(new PilotAccessService.Access(new CurrentUser(1L,"tester","Tester",false),"domestic",List.of(),true,true));
        when(access.visibleSqlClause(eq("product"),anyString())).thenAnswer(i->i.getArgument(1)+"<>999");
        when(access.visibleSqlClause(eq("file"),anyString())).thenAnswer(i->i.getArgument(1)+"<>999");
        when(access.visibleSqlClause(eq("item"),anyString())).thenReturn("false");
        var props=new MoldApplicationProperties();props.setEnabled(true);
        var json=new ObjectMapper();
        applications=new MoldApplicationService(new MoldApplicationRepository(jdbc,json),props,remote,
            mock(MoldApplicationMedia.class),mock(MoldApplicationCodes.class),access,json,
            mock(org.springframework.transaction.PlatformTransactionManager.class),mock(OperationLogService.class),
            mock(MoldApplicationDingTalkFiles.class),mock(MoldApplicationBackupImport.class));
        research=new ResearchHomeQuery(jdbc,access);generic=new ApprovalHomeQuery(jdbc,access);
        jdbc.update("insert into sys_user values(1,'tester',null)");
    }

    <T> T read(Supplier<T> query) {return readOnly.execute(status->query.get());}
    Query query() {return new Query();}
    void project(long id,long owner) {jdbc.update("insert into plm_product(product_id,product_name,product_code,owner_user_id) values(?,'新品项目','P'||?,?)",id,id,owner);}
    void app(long id) {jdbc.update("insert into plm_mold_application(application_id,applicant_id,title,external_instance_id) values(?,1,'申请','instance-'||?)",id,id);}
    void task(long id,String task,String actor,String state,int minute) {
        jdbc.update("insert into plm_mold_application_activity(application_id,activity_type,task_id,actor_id,occurred_at,payload) values(?,'task',?,?,now()+(? * interval '1 minute'),jsonb_build_object('task_status',?::text,'private','secret'))",id,task,actor,minute,state);
    }

    @Test void projectsUseFullCountActorAclAndRealEightStages() {
        for(int i=1;i<=25;i++)project(i,1);
        project(998,2);project(999,1);
        jdbc.update("update plm_product set current_step_no=8 where product_id=25");
        var q=query();long start=System.nanoTime();
        var first=read(()->research.page(q,false));
        assertThat(first.totalElements()).isEqualTo(25);assertThat(first.content()).hasSize(20);
        assertThat(first.content().get(0)).containsEntry("stageKey","ship").containsEntry("ownerName","tester");
        q.setPage(2);var second=read(()->research.page(q,false));assertThat(second.content()).hasSize(5);
        assertThat(second.content()).extracting(r->r.get("key")).doesNotContainAnyElementsOf(first.content().stream().map(r->r.get("key")).toList());
        q.setScope("visible");assertThat(read(()->research.page(q,false)).totalElements()).isEqualTo(26);
        System.out.printf("Workbench PostgreSQL projects fixture=27, page/count/second/scope checks=%.1fms%n",(System.nanoTime()-start)/1e6);
    }

    @Test void finalConfirmationActiveRevisionFrozenStoppedAndOwnerDraftStayDistinct() {
        for(int i=1;i<=8;i++)project(i,1);
        jdbc.update("update plm_product set current_step_no=8,timeline_current_confirmed=true where product_id=1");
        jdbc.update("update plm_product set status='released' where product_id in (2,3)");
        jdbc.update("insert into plm_product_development_state values(2,'{\"revision\":{\"stage\":4}}'),(4,'{\"profileDraft\":{\"ownerUserId\":2}}'),(5,'{\"profileDraft\":{}}')");
        jdbc.update("update plm_product set lock_status='frozen' where product_id=6");
        jdbc.update("update plm_product set lock_status='abandoned' where product_id=7");
        jdbc.update("update plm_product set expected_delivery_date=(now() at time zone 'Asia/Shanghai')::date-1 where product_id=8");
        var active=read(()->research.page(query(),false));
        assertThat(active.content()).extracting(r->((Number)r.get("projectId")).longValue()).containsExactlyInAnyOrder(2L,4L,5L,8L);
        assertThat(active.content().stream().filter(r->r.get("projectId").equals(2L)).findFirst().orElseThrow()).containsEntry("stageKey","sample-confirm").containsEntry("revision",true);
        var errors=read(()->research.page(query(),true));
        assertThat(errors.content()).extracting(r->((Number)r.get("projectId")).longValue()).containsExactlyInAnyOrder(4L,5L,6L,8L);
        assertThat(errors.content().stream().filter(r->r.get("projectId").equals(5L)).findFirst().orElseThrow().get("blocker").toString()).contains("未分配确认负责人");
    }

    @Test void genericApprovalCountsBeyondLegacyHundredWithMatchingActorStatusAndAcl() {
        jdbc.update("insert into plm_approval_instance values(1,'Product',1,'in_progress',0),(2,'Product',999,'in_progress',0),(3,'Attachment',1,'in_progress',0),(4,'Product',1,'approved',0)");
        jdbc.update("insert into plm_approval_task(approval_task_id,approval_instance_id,approver_user_id,status,node_code) select n,1,1,'pending','review' from generate_series(1,125) n");
        jdbc.update("insert into plm_approval_task values(126,2,1,'pending','hidden',now(),0),(127,1,2,'pending','other',now(),0),(128,3,1,'pending','file',now(),0),(129,4,1,'pending','ended',now(),0),(130,1,1,'approved','done',now(),0)");
        var q=query();q.setSize(100);q.setPage(2);var page=read(()->generic.page(q));
        assertThat(page.totalElements()).isEqualTo(126);assertThat(page.content()).hasSize(26);
        assertThat(page.content()).extracting(r->r.get("businessId")).contains("128").doesNotContain("126","127","129","130");
    }

    @Test void approvalUsesBoundIdentityLatestSnapshotStableDedupAndNoProjectFirstPage() {
        jdbc.update("insert into plm_pilot_account values(1,'oa-1')");
        for(int i=1;i<=26;i++){app(i);task(i,"t"+i,"oa-1","RUNNING",0);}
        task(1,"t1","oa-1","RUNNING",1); // Duplicate history counts once.
        task(2,"t2","oa-1","COMPLETED",1); // Historical RUNNING is no longer a task.
        task(3,"t3","oa-2","RUNNING",1); // Reassigned task no longer belongs to actor.
        jdbc.update("update plm_mold_application set remote_status='TERMINATED' where application_id=4");
        jdbc.update("update plm_mold_application set remote_result='refuse' where application_id=5");
        jdbc.update("update plm_mold_application set approval_granted=true,product_line_id=777 where application_id=6");
        var first=read(()->applications.workbenchRead(1,20,false));
        assertThat(first.page().getTotalElements()).isEqualTo(22);assertThat(first.page().getContent()).hasSize(20);
        var second=read(()->applications.workbenchRead(2,20,false));assertThat(second.page().getContent()).hasSize(2);
        var all=new ArrayList<>(first.page().getContent());all.addAll(second.page().getContent());
        assertThat(all).extracting(r->r.get("key")).doesNotHaveDuplicates();
        assertThat(all.stream().filter(r->r.get("applicationId").equals(6L)).findFirst().orElseThrow())
            .containsEntry("approvalGranted",true).containsEntry("approvalFlowState","awaiting_approval").containsEntry("productLineId",777L)
            .doesNotContainKeys("payload","form_data","last_error","execution_exception_json");
        assertThat(all).extracting(r->r.get("businessId")).doesNotContain("2","3","4","5");
        verifyNoInteractions(remote);
    }

    @Test void missingAndAmbiguousIdentityStayUnknownAndExceptionsDoNotRequireBinding() {
        app(1);task(1,"task","oa-1","RUNNING",0);
        var source=new MoldApprovalHomeSource(applications);
        assertThat(read(()->source.page(query(),false)).sourceState()).isEqualTo("identity_unbound");
        jdbc.update("insert into plm_pilot_account values(1,'oa-1'),(2,' oa-1 ')");
        assertThat(read(()->source.page(query(),false)).totalElements()).isNull();
        jdbc.update("update plm_mold_application set provisioning_status='failed' where application_id=1");
        var exceptions=read(()->source.page(query(),true));assertThat(exceptions.totalElements()).isEqualTo(1);
        assertThat(exceptions.content().get(0).get("blocker").toString()).contains("开通失败");
    }

    @Test void applicationVisibilityIncludesResearchScopeAndApplicantScope() {
        when(access.access()).thenReturn(new PilotAccessService.Access(new CurrentUser(1L,"tester","Tester",false),"domestic",List.of(),false,true));
        jdbc.update("insert into plm_pilot_account values(1,'oa-1')");project(1,1);project(999,1);
        for(int i=1;i<=3;i++){app(i);task(i,"t"+i,"oa-1","RUNNING",0);}
        jdbc.update("update plm_mold_application set applicant_id=2 where application_id=2");
        jdbc.update("update plm_mold_application set source_snapshot_json='{\"research\":{\"projectId\":999}}' where application_id=3");
        var page=read(()->applications.workbenchRead(1,20,false));
        assertThat(page.page().getContent()).singleElement().satisfies(r->assertThat(r.get("applicationId")).isEqualTo(1L));
    }

    @Test void missingOldAndFreshSyncTimesRemainDifferentFromHttpReadTime() {
        jdbc.update("insert into plm_pilot_account values(1,'oa-1')");app(1);task(1,"task","oa-1","RUNNING",0);
        var source=new MoldApprovalHomeSource(applications);
        assertThat(read(()->source.page(query(),false)).sourceState()).isEqualTo("ok");
        jdbc.update("update plm_mold_application set last_synced_at=now()-interval '2 day'");
        var stale=read(()->source.page(query(),false));assertThat(stale.sourceState()).isEqualTo("stale");assertThat(stale.totalElements()).isEqualTo(1);
        jdbc.update("update plm_mold_application set last_synced_at=null");
        var unknown=read(()->source.page(query(),false));assertThat(unknown.sourceState()).isEqualTo("stale");assertThat(unknown.sourceUpdatedAt()).isNull();
        verifyNoInteractions(remote);
    }
}

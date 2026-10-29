package com.yuewei.plm.module.project.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.config.AppProperties;
import com.yuewei.plm.common.security.*;
import com.yuewei.plm.infrastructure.storage.StorageClient;
import com.yuewei.plm.module.attachment.repository.*;
import com.yuewei.plm.module.attachment.service.impl.AttachmentServiceImpl;
import com.yuewei.plm.module.inventory.service.InventorySupplierService;
import com.yuewei.plm.module.operationlog.dto.OperationLogQueryDTO;
import com.yuewei.plm.module.operationlog.repository.OperationLogRepository;
import com.yuewei.plm.module.operationlog.service.impl.OperationLogServiceImpl;
import com.yuewei.plm.module.partner.*;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.project.service.TimelineDefinitionProvider;
import com.yuewei.plm.module.project.variant.repository.RequirementFormRepository;
import com.yuewei.plm.repository.ProductRepository;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Dedicated task-owned clone only; every example rolls back all of its business fixtures. */
@EnabledIfEnvironmentVariable(named="PLM_SUPPLIER_FEEDBACK_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15478/plm_supplier_list_it\\?currentSchema=plm")
class SupplierFeedbackLocalIntegrationTest {
    static final String URL="jdbc:postgresql://127.0.0.1:15478/plm_supplier_list_it?currentSchema=plm";
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    JdbcTemplate jdbc;DataSourceTransactionManager transactions;TransactionStatus fixture;TransactionTemplate command;
    PilotAccessService access;DevelopmentWorkflowStore store;SupplierFeedbackWorkflow feedback;EngineeringBasisService engineering;
    ProductDevelopmentService service,target;OperationLogServiceImpl logs;AttachmentServiceImpl files;
    long product,other,file,foreignFile,actor;String codeA,codeB;boolean failAudit;

    @BeforeEach void setup() throws Exception {
        assertThat(System.getenv("PLM_SUPPLIER_FEEDBACK_TEST_DB_URL")).isEqualTo(URL);
        var source=new DriverManagerDataSource(URL,"postgres",System.getenv("PLM_SUPPLIER_FEEDBACK_TEST_DB_PASSWORD"));jdbc=spy(new JdbcTemplate(source));
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("plm_supplier_list_it");
        transactions=new DataSourceTransactionManager(source);fixture=transactions.getTransaction(new DefaultTransactionDefinition());command=new TransactionTemplate(transactions);command.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        var user=jdbc.queryForMap("select user_id,username,display_name from sys_user where all_permissions=true and status='active' and deleted_flag=0 order by user_id limit 1");
        actor=((Number)user.get("user_id")).longValue();CurrentUserContext.set(new CurrentUser(actor,user.get("username").toString(),Objects.toString(user.get("display_name")),true));
        access=new PilotAccessService(jdbc,json);store=new DevelopmentWorkflowStore(jdbc,json);
        var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ProductRepository.class);configuration.addMapper(AttachmentRepository.class);configuration.addMapper(OperationLogRepository.class);
        var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(configuration);
        var session=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());var products=session.getMapper(ProductRepository.class);var attachments=session.getMapper(AttachmentRepository.class);
        logs=spy(new OperationLogServiceImpl(session.getMapper(OperationLogRepository.class)));
        doAnswer(call->{if(failAudit)throw new IllegalStateException("隔离测试审计失败");return call.callRealMethod();}).when(logs).logRequired(any(),any());
        var suppliers=new SupplierProfileService(new PartnerStore(jdbc,json,access,logs));engineering=new EngineeringBasisService(jdbc,access,suppliers);feedback=new SupplierFeedbackWorkflow(access,suppliers);
        target=spy(new ProductDevelopmentService(jdbc,json,access,products,attachments,logs,store,mock(InventorySupplierService.class),mock(LaunchDevelopmentWorkflow.class),mock(LaunchReviewWorkflow.class),mock(DevelopmentUnitCostWorkflow.class)));
        ReflectionTestUtils.setField(target,"engineering",engineering);ReflectionTestUtils.setField(target,"supplierFeedback",feedback);
        // This test invokes the real command, reference resolver and logs; full page enrichment belongs to HTTP/UI QA.
        doAnswer(call->Map.of("productId",call.getArgument(0))).when(target).detail(anyLong());service=proxy(target);
        files=new AttachmentServiceImpl(products,attachments,mock(AttachmentDownloadLogRepository.class),mock(TimelineDefinitionProvider.class),mock(StorageClient.class),new AppProperties(),logs,mock(RequirementFormRepository.class));
        ReflectionTestUtils.setField(files,"productDevelopmentService",service);
        product=createProduct();other=createProduct();codeA=createSupplier("甲");codeB=createSupplier("乙");file=createFile(product);foreignFile=createFile(other);
        var plan=Map.<String,Object>of("method","supplier","needsMold",true,"supplierAssignments",List.of(assignment(codeA,"PC背板"),assignment(codeA,"TPU包边")));
        store.save(product,new LinkedHashMap<>(Map.of("plan",plan,"planDraft",plan,"stage5Basis",Map.of("plan",plan),"engineeringBasis",Map.of("identity",plan))));
    }
    @AfterEach void rollback(){try{if(fixture!=null&&!fixture.isCompleted())transactions.rollback(fixture);}finally{CurrentUserContext.clear();}}
    @SuppressWarnings("unchecked") <T>T proxy(T object){var p=new ProxyFactory(object);p.setProxyTargetClass(true);p.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));return (T)p.getProxy();}
    long createProduct(){return jdbc.queryForObject("insert into plm_product(product_code,product_name,product_type,version_no,status,development_mode,current_step_no,created_by,updated_by) values(?,?,'product_line','V1','draft','launch_v1',1,'feedback-it','feedback-it') returning product_id",Long.class,"IT-SUP-"+UUID.randomUUID(),"隔离供应商反馈");}
    String createSupplier(String name){String code="IT-SUP-"+UUID.randomUUID();jdbc.update("insert into plm_supplier_profile(supplier_code,supplier_name,supplier_type,status,version,created_by,updated_by) values(?,?,'company','active',3,'feedback-it','feedback-it')",code,name);return code;}
    long createFile(long owner){long id=jdbc.queryForObject("select coalesce(max(attachment_id),0)+1 from plm_attachment",Long.class);jdbc.update("insert into plm_attachment(attachment_id,owner_object_type,owner_object_id,file_category,file_name,original_file_name,file_ext,storage_key,version_no,created_by,updated_by) values(?,'Product',?,'sample_image','technical.png','技术问题.png','png',?,'V1','feedback-it','feedback-it')",id,owner,"feedback-it/"+UUID.randomUUID());return id;}
    Map<String,Object> assignment(String code,String scope){return Map.of("mode","supplier","supplierCode",code,"internalName","","role","mold","scope",scope,"basis","原采用依据");}
    Map<String,Object> data(String code){return new LinkedHashMap<>(Map.of("stage",1,"supplierCode",code,"result","cannot_accept","reason","透明薄壁技术无法实现","scope","PC背板","references",List.of(Map.of("type","file","id",file))));}
    long version(){return DevelopmentWorkflowStore.number(store.load(product).get("version"));}
    void save(String request,long version,Map<String,Object> data){command.execute(tx->service.workflowAction(product,new DevelopmentModels.WorkflowInput("supplier_feedback",version,request,data)));}
    long lastFeedback(){return store.events(product).stream().filter(e->"supplier_feedback".equals(e.get("kind"))).mapToLong(e->DevelopmentWorkflowStore.number(e.get("eventId"))).max().orElseThrow();}
    Map<String,Object> current(){return DevelopmentWorkflowStore.map(((List<?>)feedback.projection(store.events(product)).get("items")).get(0));}
    long count(String sql,Object... args){return jdbc.queryForObject(sql,Long.class,args);}
    Map<String,Object> persisted(){return Map.of("state",store.load(product),"events",store.events(product),"audit",count("select count(*) from plm_operation_log where business_id=?",Long.toString(product)),"rounds",count("select count(*) from plm_product_development_round where product_id=?",product),"molds",count("select count(*) from plm_mold_application where project_id=?",product));}
    void scopedReader(Set<String> supplierCodes,boolean supplierPermission){
        long uid=jdbc.queryForObject("select coalesce(max(user_id),0)+1 from sys_user",Long.class);String username="feedback-it-"+UUID.randomUUID();
        jdbc.update("insert into sys_user(user_id,user_code,user_name,login_name,username,display_name,status,all_permissions) values(?,?,?,?,?,?,'active',false)",uid,username,"隔离工程师",username,username,"隔离工程师");
        var permissions=new ArrayList<>(PilotAccessService.ROLE_PERMISSIONS.get("domestic"));if(!supplierPermission)permissions.remove("supplier:view");
        jdbc.update("insert into plm_pilot_account(user_id,role_code,permissions,all_objects,created_by,updated_by) values(?,'domestic',?::jsonb,false,'feedback-it','feedback-it')",uid,json.valueToTree(permissions).toString());
        jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) values(?,'product',?)",uid,product);
        for(String code:supplierCodes)jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) select ?,'supplier_profile',supplier_id from plm_supplier_profile where supplier_code=?",uid,code);
        CurrentUserContext.set(new CurrentUser(uid,username,"隔离工程师",false));
    }
    void standaloneChange(boolean pending){
        var input=new LinkedHashMap<String,Object>();input.put("stage",1);input.put("previousSupplierCode",codeA);input.put("supplierCode",pending?"":codeB);input.put("replacementPending",pending);
        input.put("role","mold");input.put("scope","PC背板");input.put("reason","独立更换：透明薄壁技术无法实现");
        command.execute(tx->service.workflowAction(product,new DevelopmentModels.WorkflowInput("supplier_change",version(),"standalone",input)));
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> page(int size){return (List<Map<String,Object>>)service.page("隔离供应商反馈","active",1,size,null).get("content");}
    Map<String,Object> summaryRow(List<Map<String,Object>> rows,long id){return DevelopmentWorkflowStore.map(rows.stream().filter(row->DevelopmentWorkflowStore.number(row.get("productId"))==id).findFirst().orElseThrow().get("supplierFeedbackSummary"));}
    List<org.mockito.invocation.Invocation> summaryQueries(){return mockingDetails(jdbc).getInvocations().stream().filter(call->call.getMethod().getName().equals("queryForList")&&call.getArgument(0,String.class).contains("as \"summaryJson\"")).toList();}

    @Test void pageUsesOnePageScopedBatchAndLatestLogicalRevisionsWithoutMergingSameNames(){
        jdbc.update("update plm_supplier_profile set supplier_name='同名供方' where supplier_code in (?,?)",codeA,codeB);
        save("page-a",version(),data(codeA));long first=lastFeedback();save("page-b",version(),data(codeB));save("page-a-again",version(),data(codeA));long repeated=lastFeedback();
        clearInvocations(jdbc,target);var firstPage=page(1);
        assertThat(firstPage).hasSize(1);assertThat(DevelopmentWorkflowStore.number(firstPage.get(0).get("productId"))).isEqualTo(other);
        assertThat(summaryRow(firstPage,other)).containsEntry("cannotAcceptSupplierCount",0L).containsEntry("hasFeedback",false);
        assertThat(summaryQueries()).hasSize(1);assertThat((Object[])summaryQueries().get(0).getRawArguments()[1]).containsExactly(other);
        clearInvocations(jdbc,target);var rows=page(50);assertThat(rows).hasSize(2);
        assertThat(summaryRow(rows,product)).containsEntry("cannotAcceptSupplierCount",2L).containsEntry("restricted",false);
        assertThat(summaryQueries()).hasSize(1);verify(target,never()).detail(anyLong());verify(target,never()).materialOptions(anyLong());
        var correction=data(codeA);correction.put("feedbackId",first);correction.put("result","can_accept");correction.put("changeReason","第一轮可以承接，另一轮仍无法承接");save("page-correct",version(),correction);
        assertThat(summaryRow(page(50),product)).containsEntry("cannotAcceptSupplierCount",2L);
        save("page-void",version(),Map.of("stage",1,"feedbackId",repeated,"voided",true,"changeReason","重复轮次作废"));
        var state=store.load(product);state.put("planDraft",Map.of());store.save(product,state);
        rows=page(50);assertThat(summaryRow(rows,product)).containsEntry("cannotAcceptSupplierCount",1L).containsEntry("nextSupplierPending",false);
        assertThat(rows.stream().filter(row->DevelopmentWorkflowStore.number(row.get("productId"))==product).findFirst().orElseThrow().get("supplierNames")).isEqualTo(List.of("同名供方"));
    }

    @Test void pagePermissionsHideTheWholeSummaryAndMissingSupplierPermissionSkipsTheEventRead(){
        save("page-visible",version(),data(codeA));save("page-hidden",version(),data(codeB));var original=store.events(product);
        jdbc.update("update plm_product set target_cost=99 where product_id=?",product);
        scopedReader(Set.of(codeA),true);clearInvocations(jdbc,target);var rows=page(50);
        var hidden=Map.of("cannotAcceptSupplierCount",0L,"nextSupplierPending",false,"hasFeedback",false,"restricted",true);
        assertThat(rows).hasSize(1);assertThat(summaryRow(rows,product)).isEqualTo(hidden);assertThat(rows.get(0).get("targetCost")).isNull();assertThat(summaryQueries()).hasSize(1);
        scopedReader(Set.of(codeA),false);clearInvocations(jdbc,target);rows=page(50);
        assertThat(summaryRow(rows,product)).isEqualTo(hidden);assertThat(summaryQueries()).isEmpty();verify(target,never()).detail(anyLong());
        assertThat(store.events(product)).isEqualTo(original);
    }

    @Test void listAndActualDetailShareMoldPendingWhileComponentChangesAndInternalWorkRemainDistinct(){
        var state=store.load(product);var plan=new LinkedHashMap<>(DevelopmentWorkflowStore.map(state.get("planDraft")));plan.put("supplierAssignments",List.of(assignment(codeA,"PC背板")));state.put("planDraft",plan);state.put("plan",plan);store.save(product,state);
        standaloneChange(true);
        state=store.load(product);plan=new LinkedHashMap<>(DevelopmentWorkflowStore.map(state.get("planDraft")));var component=new LinkedHashMap<>(assignment(codeB,"配件"));component.put("role","component");plan.put("supplierAssignments",List.of(component));state.put("planDraft",plan);store.save(product,state);
        command.execute(tx->service.workflowAction(product,new DevelopmentModels.WorkflowInput("supplier_change",version(),"component-change",Map.of("stage",1,"previousSupplierCode",codeB,"supplierCode",codeA,"role","component","scope","配件","reason","仅更换部件供方"))));
        doCallRealMethod().when(target).detail(product);
        var pending=summaryRow(page(50),product);assertThat(pending).containsEntry("nextSupplierPending",true).containsEntry("hasFeedback",false);
        assertThat(service.detail(product).get("supplierFeedbackSummary")).isEqualTo(pending);
        state=store.load(product);plan=new LinkedHashMap<>(DevelopmentWorkflowStore.map(state.get("planDraft")));plan.put("supplierAssignments",List.of(Map.of("mode","internal","role","mold","internalName","内部工程组")));state.put("planDraft",plan);store.save(product,state);
        var assigned=summaryRow(page(50),product);assertThat(assigned).containsEntry("nextSupplierPending",false);
        assertThat(service.detail(product).get("supplierFeedbackSummary")).isEqualTo(assigned);
    }

    Map<String,Object> placeholder(String code){var row=new LinkedHashMap<String,Object>(Map.of("mode","supplier","role","component","scope","","basis","","internalName",""));row.put("supplierCode",code);return row;}
    void currentAssignments(List<Map<String,Object>> rows){var state=store.load(product);var plan=new LinkedHashMap<>(DevelopmentWorkflowStore.map(state.get("planDraft")));plan.put("supplierAssignments",rows);state.put("planDraft",plan);store.save(product,state);}
    Map<String,Object> ownRows(){return Map.of("product",jdbc.queryForObject("select to_jsonb(p)::text from plm_product p where product_id=?",String.class,product),"state",jdbc.queryForObject("select to_jsonb(s)::text from plm_product_development_state s where product_id=?",String.class,product));}
    void sameListAndDetail(Map<String,Object> expected){var before=ownRows();var rows=page(50);assertThat(summaryRow(rows,product)).isEqualTo(expected);assertThat(service.detail(product).get("supplierFeedbackSummary")).isEqualTo(expected);assertThat(ownRows()).isEqualTo(before);}

    @Test void realLegacyEmptyNullAndWhitespaceRowsStayNeutralWithoutRewritingProjectOrState(){
        doCallRealMethod().when(target).detail(product);
        var neutral=Map.<String,Object>of("cannotAcceptSupplierCount",0L,"nextSupplierPending",false,"hasFeedback",false,"restricted",false);
        for(String code:Arrays.asList("",null,"   ")){
            currentAssignments(List.of(placeholder(code)));sameListAndDetail(neutral);
            var row=page(50).stream().filter(r->DevelopmentWorkflowStore.number(r.get("productId"))==product).findFirst().orElseThrow();
            assertThat(row.get("supplierNames")).isEqualTo(List.of());
        }
        assertThat(store.events(product)).isEmpty();
    }

    @Test void realBlankPlanRowsKeepTwoDeclinesAndPendingButNotAfterActualMoldUndertaking(){
        currentAssignments(List.of(assignment(codeA,"PC背板")));save("blank-a",version(),data(codeA));save("blank-b",version(),data(codeB));standaloneChange(true);
        currentAssignments(List.of(placeholder("")));doCallRealMethod().when(target).detail(product);
        var pending=Map.<String,Object>of("cannotAcceptSupplierCount",2L,"nextSupplierPending",true,"hasFeedback",true,"restricted",false);sameListAndDetail(pending);
        var expected=new LinkedHashMap<>(pending);expected.put("nextSupplierPending",false);
        for(var undertaker:List.of(assignment(codeB,"后续开模"),Map.<String,Object>of("mode","internal","role","mold","internalName","内部工程组"))){
            currentAssignments(List.of(placeholder(" "),undertaker));sameListAndDetail(expected);
            var row=page(50).stream().filter(r->DevelopmentWorkflowStore.number(r.get("productId"))==product).findFirst().orElseThrow();
            assertThat(row.get("supplierNames").toString()).doesNotContain("供应商受限");
        }
    }

    @Test void realRestrictedReadersStillCannotSeeNonblankAssignmentsFeedbackOrChangeEvidence(){
        var writer=CurrentUserContext.get().orElseThrow();doCallRealMethod().when(target).detail(product);
        var hidden=Map.<String,Object>of("cannotAcceptSupplierCount",0L,"nextSupplierPending",false,"hasFeedback",false,"restricted",true);
        var component=new LinkedHashMap<>(assignment(codeB,"配件"));component.put("role","component");currentAssignments(List.of(placeholder(""),component));
        scopedReader(Set.of(codeA),true);sameListAndDetail(hidden);
        CurrentUserContext.set(writer);currentAssignments(List.of(assignment(codeA,"PC背板")));save("blank-hidden-feedback",version(),data(codeB));standaloneChange(true);currentAssignments(List.of(placeholder("")));var events=store.events(product);
        scopedReader(Set.of(codeA),true);sameListAndDetail(hidden);
        scopedReader(Set.of(codeB),true);sameListAndDetail(hidden);
        scopedReader(Set.of(codeA,codeB),true);sameListAndDetail(Map.of("cannotAcceptSupplierCount",1L,"nextSupplierPending",true,"hasFeedback",true,"restricted",false));
        scopedReader(Set.of(codeA,codeB),false);clearInvocations(jdbc);sameListAndDetail(hidden);assertThat(summaryQueries()).isEmpty();assertThat(store.events(product)).isEqualTo(events);
    }

    @Test void twoDecliningSuppliersStaySeparateAndNoNextSupplierOrSampleIsRequired(){
        var before=store.load(product);save("a",version(),data(codeA));save("b",version(),data(codeB));
        assertThat((List<?>)feedback.projection(store.events(product)).get("items")).hasSize(2);
        assertThat(current()).containsEntry("feedbackDate",null).containsEntry("recordedBy",access.actor().displayName());
        assertThat(store.load(product).get("planDraft")).isEqualTo(before.get("planDraft"));assertThat(count("select count(*) from plm_product_development_round where product_id=?",product)).isZero();
        assertThat(count("select count(*) from plm_supplier_profile where supplier_code in (?,?) and status='active'",codeA,codeB)).isEqualTo(2);
    }

    @Test void explicitReleaseIsAtomicAndOnlyRemovesTheSelectedScope(){
        var input=data(codeA);input.put("releaseCurrent",true);input.put("previousSupplierCode",codeA);input.put("role","mold");input.put("assignmentScope","PC背板");save("release",version(),input);
        assertThat(DevelopmentWorkflowStore.map(store.load(product).get("planDraft")).get("supplierAssignments")).isEqualTo(List.of(assignment(codeA,"TPU包边")));
        assertThat(store.events(product)).hasSize(2);var changed=store.events(product).stream().filter(e->"supplier_change".equals(e.get("kind"))).findFirst().orElseThrow();
        var evidence=DevelopmentWorkflowStore.map(changed.get("data"));assertThat(DevelopmentWorkflowStore.number(evidence.get("supplierFeedbackEventId"))).isEqualTo(lastFeedback());assertThat(evidence).containsEntry("replacementPending",true);
        assertThat(store.load(product).get("stage5Basis").toString()).contains("PC背板");
    }

    @Test void correctingAndVoidingCannotDeleteOriginalEvidenceAndTheRealAttachmentDeleteGuardStillRejects(){
        save("original",version(),data(codeA));long root=lastFeedback();var original=jdbc.queryForObject("select data::text from plm_product_development_event where event_id=?",String.class,root);
        var correction=data(codeA);correction.put("feedbackId",root);correction.put("changeReason","重新核对技术范围");correction.put("references",List.of());save("correct",version(),correction);
        save("void",version(),Map.of("stage",1,"feedbackId",root,"voided",true,"changeReason","误录作废"));
        assertThat(current()).containsEntry("voided",true);assertThat(DevelopmentWorkflowStore.number(current().get("revision"))).isEqualTo(3L);assertThat(jdbc.queryForObject("select data::text from plm_product_development_event where event_id=?",String.class,root)).isEqualTo(original);
        assertThat(service.fileReferenced(file)).isTrue();assertThatThrownBy(()->files.delete(file,null)).hasMessageContaining("必须保留历史");
        assertThat(jdbc.queryForObject("select deleted_flag from plm_attachment where attachment_id=?",Integer.class,file)).isZero();
    }

    @Test void foreignProjectFilesAreRejectedByTheRealReferenceResolverWithoutAnyWrites(){
        var before=persisted();var input=data(codeA);input.put("references",List.of(Map.of("type","file","id",foreignFile)));
        assertThatThrownBy(()->save("foreign",version(),input)).hasMessageContaining("未关联本项目");assertThat(persisted()).isEqualTo(before);
    }

    @Test void repeatedRequestDoesNotAppendOrApplyReleaseAgainAndStaleVersionCannotCorrect(){
        var input=data(codeA);long version=version();save("repeat",version,input);var once=persisted();save("repeat",version,input);assertThat(persisted()).isEqualTo(once);
        input.put("feedbackId",lastFeedback());input.put("changeReason","更正");assertThatThrownBy(()->save("stale",version,input)).hasMessageContaining("其他操作更新");assertThat(persisted()).isEqualTo(once);
    }

    @Test void auditFailureRollsBackBothTheFeedbackEventAndCurrentSupplierRelease(){
        var before=persisted();var input=data(codeA);input.put("releaseCurrent",true);input.put("previousSupplierCode",codeA);input.put("role","mold");input.put("assignmentScope","PC背板");failAudit=true;
        assertThatThrownBy(()->save("audit-fail",version(),input)).hasMessageContaining("隔离测试审计失败");assertThat(persisted()).isEqualTo(before);
    }

    @Test void invalidReleaseTargetAndAnUnrelatedSupplierCannotChangeThePlanOrAppendFeedback(){
        var before=persisted();var input=data(codeB);input.put("releaseCurrent",true);input.put("previousSupplierCode",codeA);input.put("role","mold");input.put("assignmentScope","PC背板");
        assertThatThrownBy(()->save("wrong-vendor",version(),input)).hasMessageContaining("必须与本次");assertThat(persisted()).isEqualTo(before);
        input.put("supplierCode",codeA);input.put("assignmentScope","失效分工");assertThatThrownBy(()->save("wrong-scope",version(),input)).hasMessageContaining("分工已变化");assertThat(persisted()).isEqualTo(before);
    }

    @Test void realScopedEngineerCannotSeeFeedbackForUnassignedSupplierInDetailOrProjectLogs(){
        var input=data(codeA);input.put("releaseCurrent",true);input.put("previousSupplierCode",codeA);input.put("role","mold");input.put("assignmentScope","PC背板");save("scoped",version(),input);
        scopedReader(Set.of(),true);
        assertThat(feedback.projection(store.events(product))).containsEntry("items",List.of()).containsEntry("restricted",true);
        var query=new OperationLogQueryDTO();var logs=service.projectLogs(product,query);assertThat(logs.toString()).contains("supplierFeedbackRestricted=true").doesNotContain(codeA,"透明薄壁技术无法实现","技术问题.png");
        var before=store.events(product);assertThatThrownBy(()->save("unassigned-vendor",version(),data(codeA))).hasMessageContaining("工程职责");assertThat(store.events(product)).isEqualTo(before);
    }

    @Test void standalonePendingChangeReasonIsHiddenWithoutSupplierPermissionEvenWhenObjectIsAssigned(){
        standaloneChange(true);var original=store.events(product);assertThat(service.projectLogs(product,new OperationLogQueryDTO()).toString()).contains("透明薄壁技术无法实现");
        scopedReader(Set.of(codeA),false);
        assertThat(service.projectLogs(product,new OperationLogQueryDTO()).toString()).contains("suppliersRestricted=true").doesNotContain(codeA,"透明薄壁技术无法实现");
        assertThat(store.events(product)).isEqualTo(original);
    }

    @Test void ordinaryStandaloneReplacementRequiresBothSupplierObjectsToReadItsReason(){
        standaloneChange(false);var original=store.events(product);assertThat(service.projectLogs(product,new OperationLogQueryDTO()).toString()).contains(codeA,codeB,"透明薄壁技术无法实现");
        scopedReader(Set.of(codeA),true);
        assertThat(service.projectLogs(product,new OperationLogQueryDTO()).toString()).contains("suppliersRestricted=true").doesNotContain(codeA,codeB,"透明薄壁技术无法实现");
        scopedReader(Set.of(codeB),true);
        assertThat(service.projectLogs(product,new OperationLogQueryDTO()).toString()).contains("suppliersRestricted=true").doesNotContain(codeA,codeB,"透明薄壁技术无法实现");
        scopedReader(Set.of(codeA,codeB),true);assertThat(service.projectLogs(product,new OperationLogQueryDTO()).toString()).contains(codeA,codeB,"透明薄壁技术无法实现");
        assertThat(store.events(product)).isEqualTo(original);
    }
}

package com.yuewei.plm.module.project.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.*;
import com.yuewei.plm.module.attachment.repository.AttachmentRepository;
import com.yuewei.plm.module.inventory.service.InventorySupplierService;
import com.yuewei.plm.module.operationlog.entity.OperationLog;
import com.yuewei.plm.module.operationlog.repository.OperationLogRepository;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.operationlog.service.impl.OperationLogServiceImpl;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.productionmetadata.*;
import com.yuewei.plm.module.moldreference.*;
import com.yuewei.plm.repository.ProductRepository;
import com.yuewei.plm.repository.entity.Product;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Only the dedicated production-clone test database is accepted. All fixtures and commands roll back. */
@EnabledIfEnvironmentVariable(named="PLM_LAUNCH_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15461/plm_launch_unification_test\\?currentSchema=plm")
class ResearchLaunchLocalIntegrationTest {
    static final String URL="jdbc:postgresql://127.0.0.1:15461/plm_launch_unification_test?currentSchema=plm";
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    JdbcTemplate jdbc;DataSourceTransactionManager transactions;TransactionStatus fixture;TransactionTemplate command;
    PilotAccessService access;ProductionMetadataService catalog;ProductDevelopmentService service;
    EngineeringBasisService engineering;DevelopmentWorkflowStore store;OperationLogService logs;long product,release;String failAuditAction;

    @BeforeEach void setup(){
        assertThat(System.getenv("PLM_LAUNCH_TEST_DB_URL")).isEqualTo(URL);
        var source=new DriverManagerDataSource(URL,"postgres",System.getenv("PLM_LAUNCH_TEST_DB_PASSWORD"));jdbc=new JdbcTemplate(source);transactions=new DataSourceTransactionManager(source);
        fixture=transactions.getTransaction(new DefaultTransactionDefinition());command=new TransactionTemplate(transactions);command.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("plm_launch_unification_test");
        var actor=jdbc.queryForMap("select user_id,username,display_name from sys_user where all_permissions=true and deleted_flag=0 order by user_id limit 1");
        CurrentUserContext.set(new CurrentUser(((Number)actor.get("user_id")).longValue(),actor.get("username").toString(),Objects.toString(actor.get("display_name"),"工程师"),true));
        access=spy(new PilotAccessService(jdbc,json));store=new DevelopmentWorkflowStore(jdbc,json);
        var auditRepository=mock(OperationLogRepository.class);
        when(auditRepository.insert(any(OperationLog.class))).thenAnswer(invocation->{
            OperationLog log=invocation.getArgument(0);if(Objects.equals(failAuditAction,log.getAction()))throw new IllegalStateException("隔离测试：审计不可用");
            long id=jdbc.queryForObject("insert into plm_operation_log(request_id,operator_user_id,operator_user_name,action,business_type,business_id,business_name,detail_json,created_by,updated_by) values(?,?,?,?,?,?,?,?,?,?) returning log_id",Long.class,log.getRequestId(),log.getOperatorUserId(),log.getOperatorUserName(),log.getAction(),log.getBusinessType(),log.getBusinessId(),log.getBusinessName(),log.getDetailJson(),log.getCreatedBy(),log.getUpdatedBy());log.setLogId(id);return 1;
        });
        logs=proxy(new OperationLogServiceImpl(auditRepository));
        catalog=proxy(new ProductionMetadataService(jdbc,json,new PhoneCodeMetadataRepository(jdbc,json),new PhoneModelReferenceRepository(jdbc),access,logs));
        var launch=new LaunchDevelopmentWorkflow(jdbc,json,access,store,mock(com.yuewei.plm.module.collaboration.MoldScheduleService.class),mock(com.yuewei.plm.module.development.DevelopmentFinanceService.class),mock(LaunchReviewWorkflow.class),mock(PhoneModelReferenceService.class));
        ReflectionTestUtils.setField(launch,"salesLaunch",proxy(new ResearchLaunchService(jdbc,access,catalog)));
        var products=mock(ProductRepository.class);when(products.selectById(anyLong())).thenAnswer(invocation->{
            long id=invocation.getArgument(0);var rows=jdbc.queryForList("select product_type,development_mode,status,current_step_no,lock_status,deleted_flag from plm_product where product_id=?",id);if(rows.isEmpty())return null;var row=rows.get(0);var p=new Product();p.setProductId(id);p.setProductType((String)row.get("product_type"));p.setDevelopmentMode((String)row.get("development_mode"));p.setStatus((String)row.get("status"));p.setCurrentStepNo(((Number)row.get("current_step_no")).intValue());p.setLockStatus((String)row.get("lock_status"));p.setDeletedFlag(((Number)row.get("deleted_flag")).intValue());return p;
        });
        var target=spy(new ProductDevelopmentService(jdbc,json,access,products,mock(AttachmentRepository.class),logs,store,mock(InventorySupplierService.class),launch,mock(LaunchReviewWorkflow.class),mock(DevelopmentUnitCostWorkflow.class)));
        engineering=mock(EngineeringBasisService.class);ReflectionTestUtils.setField(target,"engineering",engineering);doAnswer(invocation->Map.of("productId",invocation.getArgument(0))).when(target).detail(anyLong());
        service=proxy(target);
        var used=new HashSet<>(jdbc.queryForList("select product_specific_code from plm_product where product_type='product_line' and deleted_flag=0 and product_specific_code is not null",String.class));String prefix=null;
        for(char a='Z';a>='A'&&prefix==null;a--)for(char b='Z';b>='A'&&prefix==null;b--){String candidate=""+a+b;if(!used.contains(candidate))prefix=candidate;}
        assertThat(prefix).isNotNull();
        product=jdbc.queryForObject("insert into plm_product(product_code,product_name,product_type,version_no,status,development_mode,current_step_no,sales_status,product_specific_code,created_by,updated_by) values(?,?,'product_line','V1','released','launch_v1',4,'unconfirmed',?,'launch-it','launch-it') returning product_id",Long.class,"IT-LAUNCH-"+UUID.randomUUID(),"隔离上市验证",prefix);
        release=jdbc.queryForObject("insert into plm_product_development_release(product_id,version_no,references_json,product_snapshot,rounds_snapshot,missing_items,published_by) values(?,'R1','[]','{}','[]','[]','launch-it') returning release_id",Long.class,product);
        store.save(product,new LinkedHashMap<>(Map.of("version",2L,"currentReleaseId",release,"releaseDraft",Map.of("references",List.of()))));
    }
    @SuppressWarnings("unchecked") <T>T proxy(T target){var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));return (T)factory.getProxy();}
    @AfterEach void rollback(){try{if(fixture!=null&&!fixture.isCompleted())transactions.rollback(fixture);}finally{CurrentUserContext.clear();}}
    DevelopmentModels.WorkflowInput input(String request,long version,Map<String,Object> extra){var data=new HashMap<String,Object>(Map.of("actualDate",LocalDate.now().toString(),"channels","墨西哥正式渠道","releaseId",release));data.putAll(extra);return new DevelopmentModels.WorkflowInput("launch",version,request,data);}
    Map<String,Object> launch(String request,long version,Map<String,Object> extra){return command.execute(tx->service.workflowAction(product,input(request,version,extra)));}
    long count(String sql,Object... args){return jdbc.queryForObject(sql,Long.class,args);}
    long onSale(){return count("select count(*) from plm_product where product_type='product_line' and deleted_flag=0 and sales_status='on_sale'");}
    Map<String,Object> snapshot(){return Map.of("sales",jdbc.queryForObject("select sales_status from plm_product where product_id=?",String.class,product),"state",jdbc.queryForMap("select version,data::text from plm_product_development_state where product_id=?",product),"events",count("select count(*) from plm_product_development_event where product_id=?",product),"audit",count("select count(*) from plm_operation_log where business_type='PRODUCT' and business_id=?",Long.toString(product)),"sku",count("select count(*) from plm_product where product_type in ('model_variant','sku')"),"masters",count("select count(*) from plm_collaboration_item"),"sets",count("select count(*) from plm_collaboration_mold_set"),"catalog",onSale());}
    void unchanged(Map<String,Object> before){assertThat(snapshot()).isEqualTo(before);}

    org.springframework.test.web.servlet.MockMvc identityApi() throws Exception {
        var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);configuration.addMapper(ProductRepository.class);
        var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(jdbc.getDataSource());factory.setConfiguration(configuration);
        var mapper=new org.mybatis.spring.SqlSessionTemplate(factory.getObject()).getMapper(ProductRepository.class);
        var target=new com.yuewei.plm.service.impl.ProductServiceImpl(mapper,mock(com.yuewei.plm.common.util.ProductCodeGenerator.class),logs,json,mock(com.yuewei.plm.service.ProductReleaseGateValidator.class),mock(com.yuewei.plm.module.bom.service.BomInheritanceService.class),mock(com.yuewei.plm.module.process.service.ProcessRouteInheritanceService.class),mock(com.yuewei.plm.module.bom.repository.ProductProductionColorDecisionRepository.class),mock(com.yuewei.plm.module.project.variant.repository.ProductVariantColorRepository.class));
        ReflectionTestUtils.setField(target,"pilotAccessService",access);ReflectionTestUtils.setField(target,"productDevelopmentService",service);
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.yuewei.plm.controller.ProductController(proxy(target))).setControllerAdvice(new com.yuewei.plm.common.exception.GlobalExceptionHandler()).setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json)).build();
    }
    org.springframework.test.web.servlet.MvcResult identityRequest(String method,boolean basic,Map<String,Object> body) throws Exception {
        var api=identityApi();return command.execute(tx->{try{var result=api.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),"/api/v1/products/"+product+(basic?"/basic-info":"")).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andReturn();if(result.getResponse().getStatus()>=400)tx.setRollbackOnly();return result;}catch(Exception failure){throw new IllegalStateException(failure);}});
    }
    String clearIdentity(){String code=jdbc.queryForObject("select product_specific_code from plm_product where product_id=?",String.class,product);jdbc.update("update plm_product set product_specific_code=null where product_id=?",product);return code;}
    void successfulIdentityRequest(String method,boolean basic) throws Exception {
        String code=clearIdentity();var before=snapshot();String productCode=jdbc.queryForObject("select product_code from plm_product where product_id=?",String.class,product);
        var result=identityRequest(method,basic,Map.of("updatedBy","client-claimed-updater","productSpecificCode",code));assertThat(result.getResponse().getStatus()).isEqualTo(200);assertThat(json.readTree(result.getResponse().getContentAsByteArray()).path("data").path("productSpecificCode").asText()).isEqualTo(code);
        assertThat(jdbc.queryForObject("select product_specific_code from plm_product where product_id=?",String.class,product)).isEqualTo(code);assertThat(jdbc.queryForObject("select product_code from plm_product where product_id=?",String.class,product)).isEqualTo(productCode);assertThat(jdbc.queryForObject("select status from plm_product where product_id=?",String.class,product)).isEqualTo("released");
        assertThat(count("select count(*) from plm_operation_log where business_id=? and action='PRODUCT_BUSINESS_IDENTITY_ASSIGN'",Long.toString(product))).isEqualTo(1);var after=snapshot();for(String key:List.of("sales","state","events","sku","masters","sets","catalog"))assertThat(after.get(key)).isEqualTo(before.get(key));
    }

    @Test void releasedEmptyIdentityCanBeAssignedThroughTheRealPutControllerAndMybatisRowLock() throws Exception {successfulIdentityRequest("PUT",false);}
    @Test void releasedEmptyIdentityCanBeAssignedThroughTheOriginalBasicInfoPatch() throws Exception {successfulIdentityRequest("PATCH",true);}
    @Test void releasedIdentityPutCannotSmuggleTechnicalEdits() throws Exception {
        String code=clearIdentity();var before=snapshot();var result=identityRequest("PUT",false,Map.of("updatedBy","fixture","productSpecificCode",code,"productName","禁止夹带的技术修改"));assertThat(result.getResponse().getStatus()).isEqualTo(400);assertThat(result.getResponse().getContentAsString()).contains("首次补填");assertThat(jdbc.queryForObject("select product_specific_code from plm_product where product_id=?",String.class,product)).isNull();unchanged(before);
    }
    @Test void releasedIdentityPutCannotReplaceAnExistingCode() throws Exception {
        String code=jdbc.queryForObject("select product_specific_code from plm_product where product_id=?",String.class,product);var before=snapshot();var result=identityRequest("PUT",false,Map.of("updatedBy","fixture","productSpecificCode","XX"));assertThat(result.getResponse().getStatus()).isEqualTo(400);assertThat(result.getResponse().getContentAsString()).contains("原编码");assertThat(jdbc.queryForObject("select product_specific_code from plm_product where product_id=?",String.class,product)).isEqualTo(code);unchanged(before);
    }
    @Test void releasedFrozenIdentityCannotBeFilled() throws Exception {
        String code=clearIdentity();jdbc.update("update plm_product set lock_status='frozen' where product_id=?",product);var before=snapshot();var result=identityRequest("PUT",false,Map.of("updatedBy","fixture","productSpecificCode",code));assertThat(result.getResponse().getStatus()).isEqualTo(400);assertThat(result.getResponse().getContentAsString()).contains("冻结或停止");unchanged(before);
    }
    @Test void releasedIdentityFillStillChecksRealPrefixUniqueness() throws Exception {
        clearIdentity();String occupied=jdbc.queryForObject("select product_specific_code from plm_product where product_type='product_line' and deleted_flag=0 and product_id<>? and product_specific_code is not null limit 1",String.class,product);var before=snapshot();var result=identityRequest("PUT",false,Map.of("updatedBy","fixture","productSpecificCode",occupied));assertThat(result.getResponse().getStatus()).isEqualTo(409);assertThat(jdbc.queryForObject("select product_specific_code from plm_product where product_id=?",String.class,product)).isNull();unchanged(before);
    }
    @Test void releasedIdentityFillRequiresDocumentPermission() throws Exception {
        String code=clearIdentity();var before=snapshot();doThrow(new BusinessException(40001,"缺少文档写入权限")).when(access).requirePermission("documents:write");var result=identityRequest("PUT",false,Map.of("updatedBy","fixture","productSpecificCode",code));assertThat(result.getResponse().getStatus()).isEqualTo(400);assertThat(result.getResponse().getContentAsString()).contains("文档写入权限");unchanged(before);
    }
    @Test void releasedIdentityAuditFailureRollsBackTheCodeWrittenByTheRealMapper() throws Exception {
        String code=clearIdentity();var before=snapshot();failAuditAction="PRODUCT_BUSINESS_IDENTITY_ASSIGN";var result=identityRequest("PUT",false,Map.of("updatedBy","fixture","productSpecificCode",code));assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400);assertThat(jdbc.queryForObject("select product_specific_code from plm_product where product_id=?",String.class,product)).isNull();unchanged(before);
    }

    @Test void firstLaunchAddsOneCatalogProductAndRepeatedRequestDoesNotWriteAgain(){
        var before=snapshot();launch("it-launch-first",3L,Map.of());var after=snapshot();assertThat(after.get("catalog")).isEqualTo(((Number)before.get("catalog")).longValue()+1);assertThat(after.get("sales")).isEqualTo("on_sale");assertThat(after.get("events")).isEqualTo(1L);assertThat(after.get("audit")).isEqualTo(2L);for(String key:List.of("sku","masters","sets"))assertThat(after.get(key)).isEqualTo(before.get(key));
        assertThat(DevelopmentWorkflowStore.map(store.load(product).get("launched"))).containsEntry("catalogAdmission",true);launch("it-launch-first",3L,Map.of());unchanged(after);verify(engineering,times(1)).requireFinal(eq(product),anyMap(),anyList());
    }
    @Test void anAlreadyLaunchedProductRemainsRetiredWhenItsChannelIsCorrected(){
        launch("it-launch-initial",3L,Map.of());command.execute(tx->catalog.product(product,new PhoneModelReferenceTypes.Patch(Map.of("salesStatus",json.valueToTree("off_sale")),Map.of("salesStatus",json.valueToTree("on_sale")),"隔离验证停售"),null));
        long metadataBefore=count("select count(*) from plm_operation_log where business_id=? and action='PRODUCTION_METADATA_UPDATE'",Long.toString(product));long catalogBefore=onSale();
        launch("it-launch-adjust",4L,Map.of("channels","更正后的实际渠道","reason","补正历史渠道"));assertThat(jdbc.queryForObject("select sales_status from plm_product where product_id=?",String.class,product)).isEqualTo("off_sale");assertThat(onSale()).isEqualTo(catalogBefore);assertThat(count("select count(*) from plm_operation_log where business_id=? and action='PRODUCTION_METADATA_UPDATE'",Long.toString(product))).isEqualTo(metadataBefore);assertThat(DevelopmentWorkflowStore.map(store.load(product).get("launched"))).containsEntry("catalogAdmission",false);verify(engineering,times(1)).requireFinal(eq(product),anyMap(),anyList());
    }
    @Test void invalidPrefixCannotChangeSalesStateOrWorkflowFacts(){jdbc.update("update plm_product set product_specific_code='123' where product_id=?",product);var before=snapshot();assertThatThrownBy(()->launch("it-launch-code",3L,Map.of())).hasMessageContaining("两位产品特定编码");unchanged(before);}
    @Test void anOccupiedPrefixIsRejectedByTheRealUniqueConstraintWithoutAnyLaunchFacts(){var before=snapshot();String occupied=jdbc.queryForObject("select product_specific_code from plm_product where product_type='product_line' and deleted_flag=0 and product_id<>? and product_specific_code is not null limit 1",String.class,product);assertThatThrownBy(()->command.execute(tx->{jdbc.update("update plm_product set product_specific_code=? where product_id=?",occupied,product);return service.workflowAction(product,input("it-launch-duplicate",3L,Map.of()));})).isInstanceOf(DataIntegrityViolationException.class);unchanged(before);}
    @Test void missingPublicationPermissionCannotPromoteTheCatalog(){var before=snapshot();doThrow(new BusinessException(40103,"没有发布权限")).when(access).requirePermission("documents:publish");assertThatThrownBy(()->launch("it-launch-permission",3L,Map.of())).hasMessageContaining("发布权限");unchanged(before);verifyNoInteractions(engineering);}
    @Test void staleVersionCannotOverwriteTheCurrentState(){var before=snapshot();assertThatThrownBy(()->launch("it-launch-stale",2L,Map.of())).hasMessageContaining("项目已被其他操作更新");unchanged(before);verifyNoInteractions(engineering);}
    @Test void failedEngineeringValidationCannotAdmitTheProduct(){var before=snapshot();doThrow(new BusinessException(40001,"当前工程版本未确认")).when(engineering).requireFinal(eq(product),anyMap(),anyList());assertThatThrownBy(()->launch("it-launch-engineering",3L,Map.of())).hasMessageContaining("工程版本未确认");unchanged(before);}
    @Test void requiredWorkflowAuditFailureRollsBackTheEarlierMetadataAuditStateAndEvent(){var before=snapshot();failAuditAction="PRODUCT_RESEARCH_LAUNCH";assertThatThrownBy(()->launch("it-launch-audit",3L,Map.of())).hasMessageContaining("审计不可用");unchanged(before);verify(engineering).requireFinal(eq(product),anyMap(),anyList());}
}

package com.yuewei.plm.module.moldapplication;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import com.yuewei.plm.common.util.ProductCodeGenerator;
import com.yuewei.plm.module.bom.repository.*;
import com.yuewei.plm.module.bom.service.BomInheritanceService;
import com.yuewei.plm.module.bom.service.ProductionConfirmationService;
import com.yuewei.plm.module.code.repository.CodeItemRepository;
import com.yuewei.plm.module.code.service.CodeItemService;
import com.yuewei.plm.module.operationlog.repository.OperationLogRepository;
import com.yuewei.plm.module.operationlog.service.impl.OperationLogServiceImpl;
import com.yuewei.plm.module.process.repository.ProcessRepository;
import com.yuewei.plm.module.process.service.ProcessRouteInheritanceService;
import com.yuewei.plm.module.product.service.ResearchProductCodingService;
import com.yuewei.plm.module.project.variant.repository.ProductVariantColorRepository;
import com.yuewei.plm.module.workflow.service.WorkflowTemplateService;
import com.yuewei.plm.repository.ProductRepository;
import com.yuewei.plm.service.ProductReleaseGateValidator;
import com.yuewei.plm.service.impl.ProductServiceImpl;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real repositories and coding/conversion services; only external OA and unused collaborators are replaced. */
@EnabledIfEnvironmentVariable(named="PLM_MOLD_TEST_DB_URL",matches=".+")
class ResearchAutomaticCodingLocalIntegrationTest {
    private final MoldApplicationLocalIntegrationTest fixture = new MoldApplicationLocalIntegrationTest();
    private ResearchProductCodingService productCoding;
    private com.yuewei.plm.module.collaboration.CollaborationService collaboration;
    private ProductRepository productRepository;
    private ProductionConfirmationService production;
    private long project;
    private long stockBefore;
    private long fixtureFinalOperation;

    @BeforeEach void begin() throws Exception {
        fixture.begin();
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (var mapper : List.of(ProductRepository.class, CodeItemRepository.class, OperationLogRepository.class,ProductProductionColorDecisionRepository.class))
            configuration.addMapper(mapper);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(fixture.jdbc.getDataSource());
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        var products = session.getMapper(ProductRepository.class);
        productRepository=products;
        var logs = new OperationLogServiceImpl(session.getMapper(OperationLogRepository.class));
        var colorService = new CodeItemService(session.getMapper(CodeItemRepository.class));
        ReflectionTestUtils.setField(colorService,"jdbc",fixture.jdbc);
        var productService = new ProductServiceImpl(products,new ProductCodeGenerator(fixture.jdbc),logs,fixture.json,
            mock(ProductReleaseGateValidator.class),mock(BomInheritanceService.class),mock(ProcessRouteInheritanceService.class),
            mock(ProductProductionColorDecisionRepository.class),mock(ProductVariantColorRepository.class));
        ReflectionTestUtils.setField(productService,"masterDataJdbc",fixture.jdbc);
        ReflectionTestUtils.setField(productService,"pilotAccessService",fixture.access);
        production = new ProductionConfirmationService(products,mock(ProductBomRepository.class),
            mock(ProductBomRouteRepository.class),mock(ProcessRepository.class),mock(ProductBomRouteColorRepository.class),
            mock(ProductBomCostSnapshotRepository.class),mock(ProductBomRouteFormalSelectionRepository.class),
            mock(ProcessProductionOperationSelectionRepository.class),session.getMapper(ProductProductionColorDecisionRepository.class),
            colorService,new ProductBusinessCodeGenerator());
        productCoding = new ResearchProductCodingService(fixture.jdbc,fixture.json,products,productService,colorService,production,new ProductCodeGenerator(fixture.jdbc),fixture.access);
        ReflectionTestUtils.setField(fixture.service,"productCoding",productCoding);
        var conversion = new MoldApplicationConversion(fixture.repo,fixture.service,fixture.codes,products,
            new ProductCodeGenerator(fixture.jdbc),mock(WorkflowTemplateService.class),fixture.access,fixture.tx);
        ReflectionTestUtils.setField(conversion,"productCoding",productCoding);
        collaboration=new com.yuewei.plm.module.collaboration.CollaborationService(fixture.jdbc,fixture.json,new com.yuewei.plm.module.collaboration.CollaborationRepository(fixture.jdbc,fixture.json),fixture.tx,logs,fixture.access,fixture.codes.references());
        ReflectionTestUtils.setField(conversion,"collaboration",collaboration);
        fixture.service.setConversion(conversion);
        fixture.phoneModel = phone("0052");
        fixture.phone = fixture.jdbc.queryForObject("select code_item_id from plm_phone_model_member where id=?",Long.class,fixture.phoneModel);
        project = fixture.researchFixture(false);
        installRealResearchReviews();
        fixture.jdbc.update("update plm_product set current_step_no=5,color='黑色',color_code='02' where product_id=?",project);
        fixture.tipo = "亮甲Rainbow 3.0";
        fixture.repo.saveSchema(fixture.props.getResearchProcessCode(),fixture.currentResearchSchema());
        fixture.jdbc.update("delete from plm_mold_application_config where tipo=?",fixture.tipo);
        fixtureFinalOperation=fixture.jdbc.queryForObject("insert into plm_process(process_id,process_code,process_name,process_type,operation_craft_code,finished_product_flag,status,created_by,updated_by) values(nextval('plm_process_process_id_seq'),?,'QA 最终组装','operation_master','4030',true,'confirmed','it','it') returning process_id",Long.class,
            "QA-CODING-"+UUID.randomUUID());
        stockBefore = count("select count(*) from plm_inventory");
    }

    private void installRealResearchReviews() {
        var reviews=new com.yuewei.plm.module.project.development.LaunchReviewWorkflow(fixture.jdbc,fixture.access,fixture.researchStore);
        ReflectionTestUtils.setField(fixture.researchDevelopment,"reviews",reviews);
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(fixture.researchDevelopment,"launch"),"reviews",reviews);
    }

    @AfterEach void rollback() { fixture.rollback(); }

    private long phone(String code) {
        var ids=fixture.jdbc.queryForList("select code_item_id from plm_code_item where code_type='phone_model' and code_value=? and deleted_flag=0",Long.class,code);
        long item=ids.isEmpty()?fixture.jdbc.queryForObject("insert into plm_code_item(code_type,code_value,code_name,status) values('phone_model',?,?,'enabled') returning code_item_id",Long.class,code,"QA 原码 "+code):ids.get(0);
        long brand=fixture.jdbc.queryForObject("insert into plm_phone_brand(name,created_by,updated_by) values(?,'it','it') returning id",Long.class,"QA-AUTO-"+UUID.randomUUID());
        return fixture.jdbc.queryForObject("insert into plm_phone_model_member(brand_id,model_name,code_item_id,created_by,updated_by) values(?,?,?,'it','it') returning id",Long.class,brand,"QA 标准手机 "+code,item);
    }

    private Map<String,Object> application(long phone,List<String> materials,String operation) {
        return application(phone,materials,operation,"new",null,null);
    }
    private Map<String,Object> application(long phone,List<String> materials,String operation,String intent,Long targetItem,Long targetSet) {
        return application(phone,materials,operation,intent,targetItem,targetSet,null);
    }
    private Map<String,Object> application(long phone,List<String> materials,String operation,String intent,Long targetItem,Long targetSet,JsonNode engineeringPlan) {
        var state=fixture.researchStore.load(project);
        var plan=new LinkedHashMap<>((Map<String,Object>)state.get("plan"));
        var phones=new ArrayList<Long>((List<Long>)plan.get("phoneModelIds"));
        if(!phones.contains(phone))phones.add(phone);
        for(long declared:MoldEngineeringPlan.phones(engineeringPlan))if(!phones.contains(declared))phones.add(declared);
        plan.put("phoneModelIds",phones);state.put("plan",plan);state.put("planDraft",plan);fixture.researchStore.save(project,state);
        long code=fixture.jdbc.queryForObject("select code_item_id from plm_phone_model_member where id=?",Long.class,phone);
        String model=fixture.jdbc.queryForObject("select model_name from plm_phone_model_member where id=?",String.class,phone);
        var input=new MoldApplicationService.Draft(fixture.json.valueToTree(Map.of("model",model,"holeType","大孔Gran agujero","expectedDeliveryDate","2026-12-01")),project,code,null,phone,intent,targetItem,targetSet,null,"new_set".equals(intent)?"隔离验证明确加开独立套":null,null,"new_set".equals(intent),engineeringPlan);
        var created=fixture.service.startFromResearch(new MoldApplicationService.ResearchDraft(project,phone,"qa-auto-"+UUID.randomUUID(),((Number)state.get("version")).longValue(),input),null);
        long id=fixture.id(created);
        String instance="qa-auto-instance-"+UUID.randomUUID();
        var coding=new LinkedHashMap<String,Object>();if(materials!=null)coding.put("materialCodes",materials);if(operation!=null)coding.put("operationCode",operation);
        fixture.jdbc.update("update plm_mold_application set status='submitted',remote_status='RUNNING',external_instance_id=?,provisioning_status='pending',source_snapshot_json=jsonb_set(source_snapshot_json,'{researchSubmission}',?::jsonb),execution_resolution_json=?::jsonb where application_id=?",instance,fixture.repo.json(Map.of("mode","research","projectId",project)),fixture.repo.json(Map.of("researchCoding",coding)),id);
        return fixture.repo.row(id,false);
    }

    private ObjectNode remote(Map<String,Object> row,String approver,String taskId,String finishedAt) {
        ObjectNode source=fixture.json.createObjectNode();
        source.put("status","RUNNING").put("business_id","QA-AUTO-APPROVAL").put("title","隔离自动编码验收").put("originator_userid","mold-it-user");
        var fields=source.putArray("form_component_values");
        JsonNode form=(JsonNode)row.get("form");
        for(String name:List.of("productName","model","holeType","expectedDeliveryDate"))fields.addObject().put("id",MoldApplicationForms.COMPONENTS.get(name)).put("value",form.path(name).asText());
        fields.addObject().put("id",MoldApplicationForms.COMPONENTS.get("generatedCodes")).put("value","");
        var task=source.putArray("tasks").addObject().put("userid",approver).put("task_status","COMPLETED").put("task_result","AGREE");
        if(taskId!=null)task.put("taskid",taskId);
        if(finishedAt!=null)task.put("finish_time",finishedAt);
        when(fixture.client.instance(row.get("instanceId").toString())).thenReturn(source);
        return source;
    }

    private ObjectNode agree(Map<String,Object> row) {
        return remote(row,MoldApplicationForms.EXECUTION_APPROVER_USER_ID,"qa-tiffany-"+row.get("applicationId"),"2026-10-29 12:00:00");
    }

    private Map<String,Object> sync(Map<String,Object> row) {
        fixture.service.callback(row.get("instanceId").toString(),row.get("processCode").toString(),true);
        var result=fixture.repo.row(fixture.id(row),false);
        assertThat(Objects.toString(result.get("provisioningError"),"")).doesNotContain("自动建档失败");
        return result;
    }

    private long count(String sql,Object... args) { return fixture.jdbc.queryForObject(sql,Long.class,args); }
    private List<Map<String,Object>> identities() {
        return fixture.jdbc.queryForList("select product_id,parent_product_id,product_type,product_code,phone_model_code,color_code,finished_product_code,status,project_kind,development_mode from plm_product where product_id=? or parent_product_id=? or parent_product_id in (select product_id from plm_product where parent_product_id=?) order by product_id",project,project,project);
    }
    private List<String> productCodes() {
        return fixture.jdbc.queryForList("select p.finished_product_code from plm_product p join plm_product v on v.product_id=p.parent_product_id where v.parent_product_id=? and p.product_type='sku' and p.deleted_flag=0 order by p.finished_product_code",String.class,project);
    }
    private void noProductionFacts() {
        assertThat(count("select count(*) from plm_inventory")).isEqualTo(stockBefore);
        assertThat(count("select count(*) from plm_product_production_color_decision where product_id=?",project)).isZero();
        assertThat(count("select count(*) from plm_product where (parent_product_id=? or parent_product_id in (select product_id from plm_product where parent_product_id=?)) and (status<>'draft' or released_at is not null or project_kind is not null or development_mode is not null)",project,project)).isZero();
        assertThat(fixture.jdbc.queryForMap("select status,current_step_no,sales_status from plm_product where product_id=?",project)).containsEntry("status","developing").containsEntry("current_step_no",5).containsEntry("sales_status","unconfirmed");
    }
    private void workflowLinked(Map<String,Object> row) {
        var links=(List<Map<String,Object>>)fixture.researchStore.load(project).get("moldLinks");
        assertThat(links).anySatisfy(link->{assertThat(((Number)link.get("applicationId")).longValue()).isEqualTo(((Number)row.get("applicationId")).longValue());assertThat(((Number)link.get("moldSetId")).longValue()).isEqualTo(((Number)row.get("moldSetId")).longValue());});
        var readiness=fixture.researchDevelopment.stageReadiness(project,new com.yuewei.plm.module.project.development.DevelopmentModels.StageReadinessInput(5,null,null));
        var checks=(List<com.yuewei.plm.module.project.development.DevelopmentRules.ReadinessCheck>)readiness.get("checks");
        assertThat(checks).filteredOn(check->"MOLD_AUTHORIZATION".equals(check.code())).singleElement().satisfies(check->assertThat(check.complete()).as(check.message()).isTrue());
    }

    @Test void onlyTheVerifiedTiffanyTaskCanAllocateCodes() {
        var row=application(fixture.phoneModel,List.of("10","20"),"4030");
        remote(row,"another-approver","other-task","2026-10-29 12:00:00").put("result","agree");
        var pending=sync(row);assertThat(pending).containsEntry("approvalGranted",false).containsEntry("moldSetId",null);
        assertThat(fixture.jdbc.queryForObject("select product_specific_code from plm_product where product_id=?",String.class,project)).isNull();
        remote(row,MoldApplicationForms.EXECUTION_APPROVER_USER_ID,null,"2026-10-29 12:00:00");
        sync(row);assertThat(productCodes()).isEmpty();assertThat(fixture.repo.row(fixture.id(row),false).get("moldSetId")).isNull();
        remote(row,MoldApplicationForms.EXECUTION_APPROVER_USER_ID,"missing-time-task",null);
        sync(row);assertThat(productCodes()).isEmpty();assertThat(fixture.repo.row(fixture.id(row),false).get("moldSetId")).isNull();
        agree(row);var built=sync(row);
        assertThat(built).containsEntry("approvalGranted",true).containsEntry("projectId",project).containsEntry("provisioningStatus","ready");
        assertThat(productCodes()).singleElement().asString().matches("N[A-Z]{2}4030005202");
        var product=((JsonNode)built.get("executionResolution")).path("researchCoding").path("products").get(0);
        assertThat(product.path("colorCode").asText()).isEqualTo("02");assertThat(product.path("colorName").asText()).isEqualTo("黑色");assertThat(product.path("modelName").asText()).isEqualTo("QA 标准手机 0052");
        workflowLinked(built);noProductionFacts();verify(fixture.client,never()).submit(anyMap());
    }

    @Test void aNewSharedMoldDeclaresEveryPhoneButDoesNotInventFitOrAnotherManufacturingIdentity() {
        long other=phone("0053");
        var plan=fixture.json.valueToTree(Map.of("handling","new","codePhoneModelId",fixture.phoneModel,"specifications",List.of(Map.of("key","large","holeType","large","structureDescription","大孔共模候选","phoneModelIds",List.of(fixture.phoneModel,other)))));
        var row=application(fixture.phoneModel,List.of("10","20"),"4030","new",null,null,plan);agree(row);var built=sync(row);
        assertThat(built).containsEntry("provisioningStatus","ready");long item=((Number)built.get("itemId")).longValue(),set=((Number)built.get("moldSetId")).longValue();
        var fits=fixture.jdbc.queryForList("select phone_model_id,pc_confirmed,tpu_confirmed,pc_evidence,tpu_evidence from plm_collaboration_phone_set_compatibility where item_id=? and mold_set_id=? order by phone_model_id",item,set);
        assertThat(fits).hasSize(2).extracting(fit->((Number)fit.get("phone_model_id")).longValue()).containsExactlyInAnyOrder(fixture.phoneModel,other);
        assertThat(fits).allSatisfy(fit->assertThat(fit).containsEntry("pc_confirmed",false).containsEntry("tpu_confirmed",false).containsEntry("pc_evidence",null).containsEntry("tpu_evidence",null));
        assertThat(count("select count(*) from plm_collaboration_phone_member where item_id=?",item)).isEqualTo(2);
        assertThat(fixture.codes.references().compatibleMolds(other,project)).isEmpty();
        assertThat(productCodes()).singleElement().asString().matches("N[A-Z]{2}4030005202");
        long memberVersion=count("select master_data_version from plm_collaboration_item where item_id=?",item);var products=identities();sync(row);
        assertThat(count("select master_data_version from plm_collaboration_item where item_id=?",item)).isEqualTo(memberVersion);
        assertThat(identities()).isEqualTo(products);assertThat(count("select count(*) from plm_collaboration_phone_set_compatibility where item_id=?",item)).isEqualTo(2);noProductionFacts();
    }

    @Test void anAdditionalSetKeepsTheOriginal1145ManufacturingSkusAndDeclaresTheNewSetAsUnverified() {
        long oldPhone=phone("1145"),customerPhone=phone("1218");
        var oldPlan=fixture.json.valueToTree(Map.of("handling","new","codePhoneModelId",oldPhone,"specifications",List.of(Map.of("key","original","holeType","large","structureDescription","","phoneModelIds",List.of(oldPhone)))));
        var old=application(oldPhone,List.of("10","20"),"4030","new",null,null,oldPlan);agree(old);old=sync(old);
        long item=((Number)old.get("itemId")).longValue(),sourceSet=((Number)old.get("moldSetId")).longValue();
        fixture.jdbc.update("update plm_collaboration_mold_part set physical_status='confirmed',status='ready' where mold_set_id=?",sourceSet);
        var master=collaboration.item(item);var before=fixture.json.valueToTree(master.get("compatiblePhones"));var after=(com.fasterxml.jackson.databind.node.ArrayNode)before.deepCopy();
        after.addObject().put("phoneModelId",customerPhone).putArray("setCompatibilities").addObject().put("moldSetId",sourceSet).put("pcConfirmed",true).put("tpuConfirmed",true).put("pcEvidence","隔离实测PC适配").put("tpuEvidence","隔离实测TPU适配");
        var saved=collaboration.saveMoldLedger(new com.yuewei.plm.module.collaboration.CollaborationTypes.BatchSave(List.of(new com.yuewei.plm.module.collaboration.CollaborationTypes.RowPatch("item",item,List.of(new com.yuewei.plm.module.collaboration.CollaborationTypes.CellChange("compatiblePhones",before,after,"隔离工程适配确认"))))),null);
        assertThat(saved.get(0).status()).as(saved.get(0).message()).isEqualTo("saved");
        var identitiesService=new com.yuewei.plm.module.manufacturing.ManufacturingIdentityService(fixture.jdbc,fixture.json,fixture.access,fixture.codes.references(),mock(com.yuewei.plm.module.operationlog.service.OperationLogService.class),new ProductBusinessCodeGenerator());
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(fixture.service,"conversion"),"manufacturing",identitiesService);ReflectionTestUtils.setField(productCoding,"manufacturingIdentities",identitiesService);
        String digest=fixture.codes.references().compatibleMolds(customerPhone,project).stream().filter(candidate->Objects.equals(candidate.get("moldSetId"),sourceSet)).findFirst().orElseThrow().get("compatibilityDigest").toString();
        long manufacturer=fixture.jdbc.queryForObject("select product_id from plm_product where parent_product_id=? and product_type='model_variant' and phone_model_code='1145' and deleted_flag=0",Long.class,project);
        var skus=fixture.jdbc.queryForList("select product_id from plm_product where parent_product_id=? and product_type='sku' and deleted_flag=0 order by product_id",Long.class,manufacturer);
        var relation=identitiesService.save(null,new com.yuewei.plm.module.manufacturing.ManufacturingIdentityService.WriteInput(null,project,item,sourceSet,customerPhone,"large","",true,manufacturer,skus,"隔离工程共模确认","隔离工程成品与BOM共用确认",digest,"隔离追加套依据","active"),null);
        var plan=fixture.json.valueToTree(Map.of("handling","new_set","codePhoneModelId",customerPhone,"specifications",List.of(Map.of("key","additional","holeType","large","structureDescription","","phoneModelIds",List.of(customerPhone),"manufacturingRelationId",relation.get("id"),"manufacturingRelationVersion",relation.get("version")))));
        var productBefore=identities();long sets=count("select count(*) from plm_collaboration_mold_set");
        var additional=application(customerPhone,List.of("10","20"),"4030","new_set",item,sourceSet,plan);agree(additional);var built=sync(additional);
        assertThat(built).containsEntry("itemId",item).containsEntry("provisioningStatus","ready");long newSet=((Number)built.get("moldSetId")).longValue();assertThat(newSet).isNotEqualTo(sourceSet);
        assertThat(built.get("generatedCodes").toString()).contains("1145").doesNotContain("1218");assertThat(productCodes()).singleElement().asString().matches("N[A-Z]{2}4030114502");assertThat(identities()).isEqualTo(productBefore);
        var coding=((JsonNode)built.get("executionResolution")).path("researchCoding");assertThat(coding.path("productCodePrefix").asText()).isEqualTo("N"+coding.path("productSpecificCode").asText()).isNotEqualTo("N");assertThat(coding.path("n").path("manufacturingSnapshots").get(0).path("businessPhone").path("phoneModelCode").asText()).isEqualTo("1218");
        assertThat(count("select source_mold_set_id from plm_collaboration_mold_set where mold_set_id=?",newSet)).isEqualTo(sourceSet);assertThat(count("select count(*) from plm_collaboration_mold_set")).isEqualTo(sets+1);
        assertThat(fixture.jdbc.queryForMap("select pc_confirmed,tpu_confirmed from plm_collaboration_phone_set_compatibility where item_id=? and mold_set_id=? and phone_model_id=?",item,newSet,customerPhone)).containsEntry("pc_confirmed",false).containsEntry("tpu_confirmed",false);
        assertThat(fixture.jdbc.queryForMap("select pc_confirmed,tpu_confirmed from plm_collaboration_phone_set_compatibility where item_id=? and mold_set_id=? and phone_model_id=?",item,sourceSet,customerPhone)).containsEntry("pc_confirmed",true).containsEntry("tpu_confirmed",true);
        sync(additional);assertThat(count("select count(*) from plm_collaboration_mold_set")).isEqualTo(sets+1);noProductionFacts();
    }

    @Test void explicitNewPrecisionManufacturingKeeps1218AndCannotCreateAnotherSkuForADifferentHole() {
        fixture.jdbc.execute("alter table plm_project_requirement_form add column if not exists manufacturing_structure_description varchar(1000)");
        long actualPhone=phone("1218");var row=application(actualPhone,List.of("10","20"),"4030");
        long applicationId=fixture.id(row);
        var result=productCoding.provision(project,actualPhone,applicationId,"it","precision","独立精孔方案");
        assertThat(result).containsEntry("status","ready");
        assertThat((List<String>)result.get("codes")).singleElement().asString().matches("N[A-Z]{2}4030121802");
        long variant=fixture.jdbc.queryForObject("select product_id from plm_product where parent_product_id=? and phone_model_code='1218' and product_type='model_variant' and deleted_flag=0",Long.class,project);
        assertThat(fixture.jdbc.queryForMap("select hole_type,manufacturing_structure_description from plm_project_requirement_form where project_id=? and deleted_flag=0",variant))
            .containsEntry("hole_type","precision").containsEntry("manufacturing_structure_description","独立精孔方案");
        var before=identities();var repeated=productCoding.provision(project,actualPhone,applicationId,"it","precision","独立精孔方案");
        assertThat(repeated.get("codes")).isEqualTo(result.get("codes"));assertThat(identities()).isEqualTo(before);
        var wrongHole=productCoding.provision(project,actualPhone,applicationId,"it","large","独立精孔方案");
        assertThat(wrongHole).containsEntry("status","pending");assertThat((List<?>)wrongHole.get("codes")).isEmpty();assertThat(identities()).isEqualTo(before);
        assertThat(fixture.jdbc.queryForObject("select hole_type from plm_project_requirement_form where project_id=? and deleted_flag=0",String.class,variant)).isEqualTo("precision");
        noProductionFacts();
    }

    @Test void aLegacyManufacturingIdentityWithoutAHoleIsNotSilentlyAdoptedByANewExplicitSpecification() {
        fixture.jdbc.execute("alter table plm_project_requirement_form add column if not exists manufacturing_structure_description varchar(1000)");
        var row=application(fixture.phoneModel,List.of("10","20"),"4030");long applicationId=fixture.id(row);
        var old=productCoding.provision(project,fixture.phoneModel,applicationId,"it","large","");assertThat(old).containsEntry("status","ready");
        fixture.jdbc.update("update plm_project_requirement_form set hole_type=null,manufacturing_structure_description=null where project_id in (select product_id from plm_product where parent_product_id=? and product_type='model_variant')",project);
        var before=identities();long requirements=count("select count(*) from plm_project_requirement_form");
        var pending=productCoding.provision(project,fixture.phoneModel,applicationId,"it","large","");
        assertThat(pending).containsEntry("status","pending");assertThat((List<?>)pending.get("codes")).isEmpty();assertThat(identities()).isEqualTo(before);
        assertThat(count("select count(*) from plm_project_requirement_form")).isEqualTo(requirements);noProductionFacts();
    }

    @Test void aLegacyApprovedResearchWithoutAHoleCannotAllocateMoldOrManufacturingCodes() {
        var row=application(fixture.phoneModel,List.of("10","20"),"4030");
        fixture.jdbc.update("update plm_mold_application set form_data=form_data-'holeType'-'structureDescription' where application_id=?",fixture.id(row));row=fixture.repo.row(fixture.id(row),false);
        long reservations=count("select count(*) from plm_mold_code_reservation"),sets=count("select count(*) from plm_collaboration_mold_set");var products=identities();
        agree(row);var blocked=sync(row);
        assertThat(blocked).containsEntry("moldSetId",null).containsEntry("provisioningStatus","blocked");assertThat(blocked.get("provisioningError").toString()).contains("孔型");
        assertThat(count("select count(*) from plm_mold_code_reservation")).isEqualTo(reservations);assertThat(count("select count(*) from plm_collaboration_mold_set")).isEqualTo(sets);assertThat(identities()).isEqualTo(products);assertThat(productCodes()).isEmpty();
        assertThat(fixture.jdbc.queryForObject("select product_specific_code from plm_product where product_id=?",String.class,project)).isNull();noProductionFacts();
    }

    @Test void duplicateCallbacksRetainLocalCodesAndTheOriginalResearchProject() {
        var row=application(fixture.phoneModel,List.of("10","20"),"4030");agree(row);var built=sync(row);
        var before=identities();String mold=built.get("generatedCodes").toString();Object set=built.get("moldSetId");
        Object workflowVersion=fixture.researchStore.load(project).get("version");long linkEvents=count("select count(*) from plm_product_development_event where product_id=? and kind='mold_link'",project);
        assertThat(mold).contains("100052","200052");assertThat(set).isNotNull();
        sync(row);var repeated=sync(row);
        assertThat(identities()).isEqualTo(before);assertThat(repeated).containsEntry("projectId",project).containsEntry("moldSetId",set).containsEntry("generatedCodes",mold);
        assertThat(fixture.researchStore.load(project).get("version")).isEqualTo(workflowVersion);assertThat(count("select count(*) from plm_product_development_event where product_id=? and kind='mold_link'",project)).isEqualTo(linkEvents);
        assertThat(count("select count(*) from plm_product_development_state where product_id in (select product_id from plm_product where parent_product_id=?)",project)).isZero();
        noProductionFacts();
    }

    @Test void savingMissingProcessContinuesAfterTheMoldSetHasAlreadyBeenCreated() {
        var row=application(fixture.phoneModel,List.of("10","20"),null);agree(row);var partial=sync(row);
        assertThat(partial.get("moldSetId")).isNotNull();assertThat(productCodes()).isEmpty();
        assertThat(partial.get("executionResolution").toString()).contains("operationCode");
        assertThat(new com.yuewei.plm.module.collaboration.MoldWorkflowRepository(fixture.jdbc).isAuthorized(fixture.id(row),project,((Number)partial.get("moldSetId")).longValue())).isTrue();
        workflowLinked(partial);
        var completed=fixture.service.saveResearchCoding(fixture.id(row),new MoldApplicationService.ResearchCoding(fixture.version(partial),null,"4030"),null);
        assertThat(completed).containsEntry("moldSetId",partial.get("moldSetId")).containsEntry("projectId",project).containsEntry("provisioningStatus","ready");
        assertThat(productCodes()).singleElement().asString().matches("N[A-Z]{2}4030005202");
        sync(row);assertThat(productCodes()).hasSize(1);noProductionFacts();verify(fixture.client,never()).submit(anyMap());
    }

    @Test void savingMissingMaterialsContinuesWithoutAnotherApproval() {
        var row=application(fixture.phoneModel,List.of(),"4030");
        fixture.jdbc.update("update plm_product set material=null,development_requirements=null where product_id=?",project);
        fixture.jdbc.update("update plm_mold_application set source_snapshot_json=jsonb_set(source_snapshot_json,'{research}',?::jsonb) where application_id=?",fixture.repo.json(Map.of("projectId",project)),fixture.id(row));
        agree(row);var partial=sync(row);assertThat(partial.get("moldSetId")).isNull();
        assertThatThrownBy(()->fixture.service.saveResearchCoding(fixture.id(row),new MoldApplicationService.ResearchCoding(fixture.version(partial),List.of("99"),null),null)).hasMessageContaining("材质");
        var unchanged=fixture.repo.row(fixture.id(row),false);assertThat(unchanged).containsEntry("version",partial.get("version")).containsEntry("executionResolution",partial.get("executionResolution"));
        var completed=fixture.service.saveResearchCoding(fixture.id(row),new MoldApplicationService.ResearchCoding(fixture.version(partial),List.of("10","20"),null),null);
        assertThat(completed.get("moldSetId")).isNotNull();assertThat(completed).containsEntry("projectId",project).containsEntry("provisioningStatus","ready");
        assertThat(completed.get("generatedCodes").toString()).contains("100052","200052");
        assertThat(productCodes()).hasSize(1);noProductionFacts();verify(fixture.client,never()).submit(anyMap());
    }

    @Test void twoPhonesWithTheSameStandardColorRemainSeparateDraftIdentities() {
        var first=application(fixture.phoneModel,List.of("10","20"),"4030");agree(first);sync(first);
        var second=application(phone("0053"),List.of("10","20"),"4030");agree(second);var built=sync(second);
        assertThat(built).containsEntry("projectId",project).containsEntry("provisioningStatus","ready");
        assertThat(productCodes()).hasSize(2).anyMatch(code->code.matches("N[A-Z]{2}4030005202")).anyMatch(code->code.matches("N[A-Z]{2}4030005302"));
        assertThat(count("select count(*) from plm_product where parent_product_id=? and product_type='model_variant' and project_kind is null and development_mode is null",project)).isEqualTo(2);
        sync(first);sync(second);assertThat(productCodes()).hasSize(2);noProductionFacts();
    }

    @Test void terminationPausesButKeepsCodesAndAlreadyRecordedFacts() {
        var row=application(fixture.phoneModel,List.of("10","20"),"4030");ObjectNode source=agree(row);var built=sync(row);
        Object set=built.get("moldSetId");String mold=built.get("generatedCodes").toString();var before=identities();
        fixture.jdbc.update("update plm_collaboration_mold_set set opened_date='2026-10-29' where mold_set_id=?",set);
        source.put("status","TERMINATED").put("finish_time","2026-10-29 14:00:00");var ended=sync(row);
        assertThat(ended).containsEntry("approvalGranted",false).containsEntry("executionStatus","paused").containsEntry("moldSetId",set).containsEntry("generatedCodes",mold);
        var pausedSummary=researchSummary(fixture.id(row));
        assertThat(pausedSummary).containsEntry("businessStatus","terminated").containsEntry("executionStatus","paused").containsEntry("approvalGranted",false);
        assertThat(pausedSummary.get("provisioningError")).isEqualTo(ended.get("provisioningError"));
        assertSafeTaskAuthorization(pausedSummary);
        var terminated=fixture.service.resolve(fixture.id(row),new MoldApplicationService.Resolution(fixture.version(ended),"terminate","QA 已发生记录保留，结束后续执行"),null);
        var terminatedSummary=researchSummary(fixture.id(row));
        assertThat(terminatedSummary).containsEntry("executionStatus","terminated").containsEntry("approvalGranted",false).containsEntry("moldSetId",set);
        assertThat(terminatedSummary.get("provisioningError")).isEqualTo(terminated.get("provisioningError"));
        assertSafeTaskAuthorization(terminatedSummary);
        assertThat(identities()).isEqualTo(before);assertThat(fixture.jdbc.queryForObject("select opened_date::text from plm_collaboration_mold_set where mold_set_id=?",String.class,set)).isEqualTo("2026-10-29");noProductionFacts();
    }

    @Test void confirmedCommonMoldRetainsItsOriginalEngravingInsteadOfTheNewPhonesCode() {
        String prefix=productCoding.ensurePrefix(project,"it").get("productSpecificCode").toString();
        long item=fixture.jdbc.queryForObject("insert into plm_collaboration_item(product_line_id,style_name,compatible_models,mold_name,engraved_code,created_by,updated_by) values(?,'QA shared','QA 标准手机 0052','QA 原刻码共模','1144','it','it') returning item_id",Long.class,project);
        long set=fixture.jdbc.queryForObject("insert into plm_collaboration_mold_set(item_id,set_no,set_name,created_by,updated_by) values(?,1,'QA 明确实物套','it','it') returning mold_set_id",Long.class,item);
        for(var part:List.of(Map.entry("PC","20"),Map.entry("TPU","10")))fixture.jdbc.update("insert into plm_collaboration_mold_part(mold_set_id,part_type,original_mold_code,physical_status) values(?,?,?,'confirmed')",set,part.getKey(),"M"+prefix+part.getValue()+"1144");
        fixture.jdbc.update("insert into plm_collaboration_phone_member(item_id,phone_model_id,mold_set_ids,pc_confirmed,tpu_confirmed,evidence,created_by,updated_by) values(?,?,?::bigint[],true,true,'明确PC/TPU适配','it','it')",item,fixture.phoneModel,"{"+set+"}");
        fixture.jdbc.update("insert into plm_collaboration_phone_set_compatibility(item_id,phone_model_id,mold_set_id,pc_confirmed,tpu_confirmed,pc_evidence,tpu_evidence,created_by,updated_by) values(?,?,?,true,true,'PC明确适配','TPU明确适配','it','it')",item,fixture.phoneModel,set);
        long sets=count("select count(*) from plm_collaboration_mold_set");
        var row=application(fixture.phoneModel,List.of("10","20"),"4030","reuse",item,set);agree(row);var built=sync(row);
        assertThat(built).containsEntry("itemId",item).containsEntry("moldSetId",set).containsEntry("projectId",project).containsEntry("provisioningStatus","ready");
        assertThat(built.get("generatedCodes").toString()).contains("M"+prefix+"101144","M"+prefix+"201144").doesNotContain("0052");
        assertThat(count("select count(*) from plm_collaboration_mold_set")).isEqualTo(sets);
        assertThat(productCodes()).containsExactly("N"+prefix+"4030005202");
        sync(row);assertThat(count("select count(*) from plm_collaboration_mold_set")).isEqualTo(sets);noProductionFacts();
    }

    @Test void anExisting4020ProductConfigurationIsNotReplacedByAnotherFinishedFlaggedOperation() {
        String prefix=productCoding.ensurePrefix(project,"it").get("productSpecificCode").toString();
        fixture.jdbc.update("update plm_product set product_code=? where product_id=?","N"+prefix+"4020",project);
        long route=fixture.jdbc.queryForObject("insert into plm_process(process_id,process_code,product_id,process_name,process_type,status,created_by,updated_by) values(nextval('plm_process_process_id_seq'),?,?,'QA 原有效路线','routing','confirmed','it','it') returning process_id",Long.class,"QA-ROUTE-"+UUID.randomUUID(),project);
        for(String code:List.of("4020","4030"))fixture.jdbc.update("insert into plm_process(process_id,process_code,product_id,parent_process_id,process_name,process_type,operation_craft_code,material_status_code,finished_product_flag,status,created_by,updated_by) values(nextval('plm_process_process_id_seq'),?,?,?,'QA 原工序','operation',?,?,?,'confirmed','it','it')","QA-OP-"+UUID.randomUUID(),project,route,code,code,"4030".equals(code));
        var row=application(fixture.phoneModel,List.of("10","20"),null);agree(row);var built=sync(row);
        assertThat(built).containsEntry("provisioningStatus","ready");
        assertThat(productCodes()).containsExactly("N"+prefix+"4020005202");
        assertThat(count("select count(*) from plm_product_bom where product_id=?",project)).isZero();
        noProductionFacts();
    }

    @Test void knownProjectMaterialsAreAppliedWithoutAnyAdditionalConfirmation() {
        fixture.jdbc.update("update plm_product set material='PC+TPU' where product_id=?",project);
        var row=application(fixture.phoneModel,null,"4030");agree(row);var built=sync(row);
        assertThat(built).containsEntry("provisioningStatus","ready").containsEntry("projectId",project);
        assertThat(built.get("generatedCodes").toString()).contains("100052","200052");
        assertThat(productCodes()).hasSize(1);workflowLinked(built);noProductionFacts();
    }

    @Test void conflictingMaterialSourcesWaitForTheExistingSaveEntry() {
        String prefix=productCoding.ensurePrefix(project,"it").get("productSpecificCode").toString();
        fixture.jdbc.update("update plm_product set material='PC+TPU' where product_id=?",project);
        fixture.codes.saveConfig(fixture.tipo,project,prefix,List.of(Map.of("code","10","partType","TPU")),null,"it");
        var row=application(fixture.phoneModel,null,"4030");agree(row);var partial=sync(row);
        assertThat(partial.get("moldSetId")).isNull();
        assertThat(partial.get("executionResolution").toString()).contains("materialCodes");
        var built=fixture.service.saveResearchCoding(fixture.id(row),new MoldApplicationService.ResearchCoding(fixture.version(partial),List.of("10","20"),null),null);
        assertThat(built.get("moldSetId")).as(built.toString()).isNotNull();assertThat(built).containsEntry("provisioningStatus","ready");
        workflowLinked(built);noProductionFacts();verify(fixture.client,never()).submit(anyMap());
    }

    private long confirmedColorForAllocatedVariant() {
        long variant=fixture.jdbc.queryForObject("select product_id from plm_product where parent_product_id=? and product_type='model_variant' and phone_model_member_id=?",Long.class,project,fixture.phoneModel);
        long color=fixture.jdbc.queryForObject("select code_item_id from plm_code_item where code_type='color' and code_value='02' and deleted_flag=0",Long.class);
        long bom=fixture.jdbc.queryForObject("insert into plm_product_bom(product_bom_id,product_id,bom_code,bom_type,version_no,status,created_by,updated_by) values(nextval('plm_product_bom_product_bom_id_seq'),?,?,'mbom','V1','draft','it','it') returning product_bom_id",Long.class,variant,"QA-LATER-BOM-"+UUID.randomUUID());
        long process=fixture.jdbc.queryForObject("insert into plm_process(process_id,process_code,product_id,process_name,process_type,status,created_by,updated_by) values(nextval('plm_process_process_id_seq'),?,?,'QA 后续真实采用工艺','routing','confirmed','it','it') returning process_id",Long.class,"QA-LATER-PROCESS-"+UUID.randomUUID(),variant);
        long route=fixture.jdbc.queryForObject("insert into plm_product_bom_route(product_bom_id,product_id,process_id,route_code,route_name,shared_bom_group_code,route_variant_no,status,created_by,updated_by) values(?,?,?,?,'QA 后续真实采用路线',?,'BASE','active','it','it') returning product_bom_route_id",Long.class,bom,variant,process,"QA-LATER-ROUTE-"+UUID.randomUUID(),"QA-GROUP-"+UUID.randomUUID());
        fixture.jdbc.update("insert into plm_product_production_color_decision(product_id,code_item_id,color_code,color_name,product_bom_id,product_bom_route_id,decision_batch_no,selected_flag,status,confirmed_at,confirmed_by,created_by,updated_by) values(?,?,'02','黑色',?,?,?,1,'confirmed',now(),'it','it','it')",variant,color,bom,route,"QA-COLOR-"+UUID.randomUUID());
        return variant;
    }

    @Test void theOriginalProductionColorWriterRetainsAnAlreadyAllocated4020Identity() {
        var row=application(fixture.phoneModel,List.of("10","20"),"4020");
        fixture.jdbc.update("insert into plm_process(process_id,process_code,process_name,process_type,operation_craft_code,finished_product_flag,status,created_by,updated_by) values(nextval('plm_process_process_id_seq'),?,'QA 原最终4020','operation_master','4020',true,'confirmed','it','it')","QA-FINAL-"+UUID.randomUUID());
        agree(row);sync(row);var codes=productCodes();assertThat(codes).singleElement().asString().matches("N[A-Z]{2}4020005202");
        long variant=confirmedColorForAllocatedVariant();
        assertThat(production.syncModelVariantConfirmedColorsAndSkus(productRepository.selectById(variant))).isZero();
        assertThat(productCodes()).isEqualTo(codes);assertThat(count("select count(*) from plm_inventory")).isEqualTo(stockBefore);
    }

    @Test void aLaterExplicitDifferentFinalOperationCannotRewriteAnAllocatedSku() {
        var row=application(fixture.phoneModel,List.of("10","20"),"4020");
        fixture.jdbc.update("insert into plm_process(process_id,process_code,process_name,process_type,operation_craft_code,finished_product_flag,status,created_by,updated_by) values(nextval('plm_process_process_id_seq'),?,'QA 原最终4020','operation_master','4020',true,'confirmed','it','it')","QA-FINAL-"+UUID.randomUUID());
        agree(row);sync(row);var codes=productCodes();long variant=confirmedColorForAllocatedVariant();
        String prefix=fixture.jdbc.queryForObject("select product_specific_code from plm_product where product_id=?",String.class,project);
        fixture.jdbc.update("update plm_product set product_code=? where product_id=?","N"+prefix+"4030",project);
        assertThatThrownBy(()->production.syncModelVariantConfirmedColorsAndSkus(productRepository.selectById(variant))).hasMessageContaining("原编码");
        assertThat(productCodes()).isEqualTo(codes);assertThat(count("select count(*) from plm_inventory")).isEqualTo(stockBefore);
    }

    private Map<String,Object> removePhoneBinding(Map<String,Object> row,String frozenModel) {
        var form=((JsonNode)row.get("form")).deepCopy();((ObjectNode)form).put("model",frozenModel);
        fixture.jdbc.update("update plm_mold_application set phone_model_id=null,phone_code_item_id=null,model=?,form_data=?::jsonb,source_snapshot_json=(source_snapshot_json-'phoneModelId')||jsonb_build_object('research',(source_snapshot_json->'research')-'phoneModelId'-'phoneModelName') where application_id=?",frozenModel,fixture.repo.json(form),fixture.id(row));
        return fixture.repo.row(fixture.id(row),false);
    }

    @Test void theUniqueMatchingAdoptedPhoneIsLinkedWithoutAnotherConfirmation() {
        var row=application(fixture.phoneModel,List.of("10","20"),"4030");
        row=removePhoneBinding(row,"QA 标准手机 0052");Object originalSource=row.get("sourceSnapshot");
        agree(row);var built=sync(row);
        assertThat(built).containsEntry("phoneModelId",fixture.phoneModel).containsEntry("phoneCodeItemId",fixture.phone).containsEntry("projectId",project).containsEntry("provisioningStatus","ready");
        assertThat(built.get("sourceSnapshot")).isEqualTo(originalSource);
        assertThat(((JsonNode)built.get("form")).path("model").asText()).isEqualTo("QA 标准手机 0052");
        assertThat(productCodes()).singleElement().asString().matches("N[A-Z]{2}4030005202");
        workflowLinked(built);noProductionFacts();
    }

    @Test void anUnrelatedFrozenModelCannotBeBoundToTheOnlyAdoptedPhone() {
        var row=application(fixture.phoneModel,List.of("10","20"),"4030");
        row=removePhoneBinding(row,"Honor 600 Lite");Object originalSource=row.get("sourceSnapshot");
        agree(row);var pending=sync(row);
        assertThat(pending).containsEntry("phoneModelId",null).containsEntry("phoneCodeItemId",null).containsEntry("moldSetId",null);
        assertThat(pending.get("sourceSnapshot")).isEqualTo(originalSource);
        assertThat(((JsonNode)pending.get("form")).path("model").asText()).isEqualTo("Honor 600 Lite");
        assertThat(productCodes()).isEmpty();noProductionFacts();
    }

    @Test void realArchivedActiveRoutesRemainSelectableWhileInactiveAncestorsDoNot() {
        fixture.jdbc.update("delete from plm_process where process_id=?",fixtureFinalOperation);
        var realOptions=productCoding.operationOptions().stream().map(option->option.get("code")).toList();
        assertThat(realOptions).contains("4020","4030");
        assertThat(count("select count(*) from plm_product p join plm_process r on r.product_id=p.product_id where p.status='archived' and p.master_data_status='active' and p.deleted_flag=0 and r.status in ('confirmed','locked') and r.deleted_flag=0")).isPositive();
        long child=fixture.jdbc.queryForObject("insert into plm_product(parent_product_id,product_code,product_name,product_type,version_no,status,master_data_status,created_by,updated_by) values(?,?,'QA 停用祖先下型号','model_variant','V1','draft','active','it','it') returning product_id",Long.class,project,"QA-INACTIVE-CHILD-"+UUID.randomUUID());
        long route=fixture.jdbc.queryForObject("insert into plm_process(process_id,process_code,product_id,process_name,process_type,status,created_by,updated_by) values(nextval('plm_process_process_id_seq'),?,?,'QA 仅停用来源工艺','routing','confirmed','it','it') returning process_id",Long.class,"QA-INACTIVE-ROUTE-"+UUID.randomUUID(),child);
        fixture.jdbc.update("insert into plm_process(process_id,process_code,product_id,parent_process_id,process_name,process_type,operation_craft_code,finished_product_flag,status,created_by,updated_by) values(nextval('plm_process_process_id_seq'),?,?,?,'QA 仅停用来源终态','operation','7842',true,'confirmed','it','it')","QA-INACTIVE-OP-"+UUID.randomUUID(),child,route);
        assertThat(productCoding.operationOptions().stream().map(option->option.get("code")).toList()).contains("7842");
        fixture.jdbc.update("update plm_product set master_data_status='inactive' where product_id=?",project);
        assertThat(productCoding.operationOptions().stream().map(option->option.get("code")).toList()).doesNotContain("7842").contains("4020","4030");
        fixture.jdbc.update("update plm_product set master_data_status='active' where product_id=?",project);
        fixture.jdbc.update("update plm_product set master_data_status='inactive' where product_id=?",child);
        assertThat(productCoding.operationOptions().stream().map(option->option.get("code")).toList()).doesNotContain("7842");
    }

    @Test void twoNewResearchProductsWithoutTipoKeepSeparateOwnedMoldIdentities() throws Exception {
        String firstName="QA 新品A-"+UUID.randomUUID();long firstProject=project;
        fixture.jdbc.update("update plm_product set product_name=? where product_id=?",firstName,project);
        var first=application(fixture.phoneModel,List.of("10","20"),"4030");Object firstSource=first.get("sourceSnapshot");agree(first);var builtFirst=sync(first);
        String secondName="QA 新品B-"+UUID.randomUUID();project=fixture.researchFixture(false);installRealResearchReviews();
        fixture.jdbc.update("update plm_product set product_name=?,current_step_no=5,color='黑色',color_code='02' where product_id=?",secondName,project);
        var second=application(fixture.phoneModel,List.of("10","20"),"4030");Object secondSource=second.get("sourceSnapshot");agree(second);var builtSecond=sync(second);
        assertThat(builtFirst).containsEntry("projectId",firstProject).containsEntry("tipo",null);
        assertThat(builtSecond).containsEntry("projectId",project).containsEntry("tipo",null).containsEntry("provisioningStatus","ready");
        assertThat(builtSecond.get("itemId")).isNotEqualTo(builtFirst.get("itemId"));assertThat(builtSecond.get("moldSetId")).isNotEqualTo(builtFirst.get("moldSetId"));
        assertThat(builtFirst.get("generatedCodes").toString().substring(1,3)).isNotEqualTo(builtSecond.get("generatedCodes").toString().substring(1,3));
        assertThat(fixture.jdbc.queryForObject("select style_name from plm_collaboration_item where item_id=?",String.class,builtFirst.get("itemId"))).isEqualTo(firstName);
        assertThat(fixture.jdbc.queryForObject("select style_name from plm_collaboration_item where item_id=?",String.class,builtSecond.get("itemId"))).isEqualTo(secondName);
        assertThat(builtFirst.get("sourceSnapshot")).isEqualTo(firstSource);assertThat(builtSecond.get("sourceSnapshot")).isEqualTo(secondSource);
        assertThat(((JsonNode)builtFirst.get("form")).path("productName").asText()).isEqualTo(firstName);assertThat(((JsonNode)builtSecond.get("form")).path("productName").asText()).isEqualTo(secondName);
        workflowLinked(builtSecond);
        project=fixture.researchFixture(false);installRealResearchReviews();
        fixture.jdbc.update("update plm_product set product_name=?,current_step_no=5,color='黑色',color_code='02' where product_id=?",firstName,project);
        var duplicate=application(fixture.phoneModel,List.of("10","20"),"4030");agree(duplicate);var conflict=sync(duplicate);
        assertThat(conflict).containsEntry("moldSetId",null).containsEntry("itemId",null).containsEntry("productLineId",project);
        assertThat(conflict.get("provisioningError").toString()).contains("同名同型号").contains("其他产品");
        assertThat(fixture.repo.row(fixture.id(first),false)).containsEntry("itemId",builtFirst.get("itemId")).containsEntry("projectId",firstProject);
        noProductionFacts();
    }

    @Test void anotherUnconfirmedPhoneWithTheSameCodeCannotInventAnotherPhysicalSet() {
        var first=application(fixture.phoneModel,List.of("10","20"),"4030");agree(first);var built=sync(first);
        long sets=count("select count(*) from plm_collaboration_mold_set");
        assertThat(count("select count(*) from plm_collaboration_phone_set_compatibility where mold_set_id=?",built.get("moldSetId"))).isZero();
        long sameCodePhone=phone("0052");fixture.jdbc.update("update plm_phone_model_member set model_name='QA 另一通用手机 0052' where id=?",sameCodePhone);
        var another=application(sameCodePhone,List.of("10","20"),"4030");agree(another);var blocked=sync(another);
        assertThat(blocked).containsEntry("productLineId",project).containsEntry("phoneModelId",sameCodePhone).containsEntry("moldSetId",null);
        assertThat(blocked.get("provisioningError").toString()).contains("模具编码已关联实物套");
        var failedSummary=researchSummary(fixture.id(another));
        assertThat(failedSummary).containsEntry("executionStatus",blocked.get("executionStatus")).containsEntry("provisioningStatus","blocked").containsEntry("approvalGranted",true).containsEntry("provisioningError",blocked.get("provisioningError"));
        assertSafeTaskAuthorization(failedSummary);
        assertThat(count("select count(*) from plm_collaboration_mold_set")).isEqualTo(sets);
        assertThat(fixture.repo.row(fixture.id(first),false)).containsEntry("moldSetId",built.get("moldSetId")).containsEntry("itemId",built.get("itemId"));
        noProductionFacts();
    }

    private Map<String,Object> researchSummary(long applicationId) {
        return fixture.service.researchApplications(project).stream().filter(row->((Number)row.get("applicationId")).longValue()==applicationId).findFirst().orElseThrow();
    }

    private void assertSafeTaskAuthorization(Map<String,Object> summary) {
        JsonNode proof=fixture.json.valueToTree(summary.get("authorization"));
        assertThat(proof.path("evidenceSource").asText()).isEqualTo("task");
        assertThat(proof.path("taskId").asText()).isNotBlank();
        assertThat(proof.path("approvedAt").asText()).isNotBlank();
        var keys=new ArrayList<String>();proof.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).isSubsetOf("approverUserId","approverName","taskId","activityId","approvedAt","evidenceSource");
        assertThat(summary).doesNotContainKeys("rawJson","sourceSnapshot","authorizationJson","lastError","token");
    }

    private long useScopedEngineer() {
        String username="qa-scoped-"+UUID.randomUUID();
        long engineer=fixture.jdbc.queryForObject("insert into sys_user(user_code,username,login_name,user_name,display_name,all_permissions,status) values(?,?,?,'受限工程师','受限工程师',false,'active') returning user_id",Long.class,username,username,username);
        fixture.jdbc.update("insert into plm_pilot_account(user_id,role_code,permissions,all_objects,created_by,updated_by) values(?,'domestic',?::jsonb,false,'it','it')",engineer,fixture.repo.json(com.yuewei.plm.module.pilot.PilotAccessService.ROLE_PERMISSIONS.get("domestic")));
        fixture.jdbc.update("insert into plm_collaboration_assignment(field_group,user_id) values('engineering',?)",engineer);
        fixture.jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) values(?,'product',?)",engineer,project);
        com.yuewei.plm.common.security.CurrentUserContext.set(new com.yuewei.plm.common.security.CurrentUser(engineer,username,"受限工程师",false));
        assertThat(fixture.access.access().allObjects()).isFalse();assertThat(fixture.access.canMaintainEngineeringData()).isTrue();
        return engineer;
    }

    @Test void anExistingVariantWithoutItsOwnScopeCannotBeGrantedByCoding() {
        var row=application(fixture.phoneModel,List.of("10","20"),"4030");agree(row);sync(row);
        long variant=fixture.jdbc.queryForObject("select product_id from plm_product where parent_product_id=? and product_type='model_variant'",Long.class,project);
        var before=identities();long engineer=useScopedEngineer();
        assertThatThrownBy(()->fixture.access.requireProduct(variant)).hasMessageContaining("未获授权");
        assertThatThrownBy(()->productCoding.provision(project,fixture.phoneModel,fixture.id(row),"QA 受限续办","large","")).hasMessageContaining("未获授权");
        assertThat(count("select count(*) from plm_pilot_scope where user_id=? and entity_type='product' and entity_id in (select product_id from plm_product where parent_product_id=? or parent_product_id in (select product_id from plm_product where parent_product_id=?))",engineer,project,project)).isZero();
        assertThat(identities()).isEqualTo(before);noProductionFacts();
    }

    @Test void anExistingSkuWithoutItsOwnScopeCannotBeGrantedByCoding() {
        var row=application(fixture.phoneModel,List.of("10","20"),"4030");agree(row);sync(row);
        long variant=fixture.jdbc.queryForObject("select product_id from plm_product where parent_product_id=? and product_type='model_variant'",Long.class,project);
        long sku=fixture.jdbc.queryForObject("select product_id from plm_product where parent_product_id=? and product_type='sku'",Long.class,variant);
        var before=identities();long engineer=useScopedEngineer();
        fixture.jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) values(?,'product',?)",engineer,variant);
        assertThatCode(()->fixture.access.requireProduct(variant)).doesNotThrowAnyException();
        assertThatThrownBy(()->fixture.access.requireProduct(sku)).hasMessageContaining("未获授权");
        assertThatThrownBy(()->productCoding.provision(project,fixture.phoneModel,fixture.id(row),"QA 受限续办","large","")).hasMessageContaining("未获授权");
        assertThat(count("select count(*) from plm_pilot_scope where user_id=? and entity_type='product' and entity_id=?",engineer,sku)).isZero();
        assertThat(identities()).isEqualTo(before);noProductionFacts();
    }

    @Test void anInactiveVariantOrItsInactiveAncestorCannotBeAdoptedAgain() {
        var row=application(fixture.phoneModel,List.of("10","20"),"4030");agree(row);var built=sync(row);
        long variant=fixture.jdbc.queryForObject("select product_id from plm_product where parent_product_id=? and product_type='model_variant'",Long.class,project);
        var before=identities();
        fixture.jdbc.update("update plm_product set master_data_status='inactive' where product_id=?",variant);
        assertThatThrownBy(()->productCoding.provision(project,fixture.phoneModel,fixture.id(row),"QA 停用资料","large","")).hasMessageContaining("已停用");
        assertThat(identities()).isEqualTo(before);
        fixture.jdbc.update("update plm_product set master_data_status='active' where product_id=?",variant);
        fixture.jdbc.update("update plm_product set master_data_status='inactive' where product_id=?",project);
        assertThatThrownBy(()->productCoding.provision(project,fixture.phoneModel,fixture.id(row),"QA 停用祖先","large","")).hasMessageContaining("已停用");
        assertThat(identities()).isEqualTo(before);
        assertThat(fixture.repo.row(fixture.id(row),false)).containsEntry("provisioningStatus","ready").containsEntry("moldSetId",built.get("moldSetId"));
        fixture.jdbc.update("update plm_product set master_data_status='active' where product_id=?",project);
        noProductionFacts();
    }

    @Test void anInactiveSkuIsBlockedButProductionDiscontinuationDoesNotRetireItsIdentity() {
        var row=application(fixture.phoneModel,List.of("10","20"),"4030");agree(row);var built=sync(row);
        long variant=fixture.jdbc.queryForObject("select product_id from plm_product where parent_product_id=? and product_type='model_variant'",Long.class,project);
        long sku=fixture.jdbc.queryForObject("select product_id from plm_product where parent_product_id=? and product_type='sku'",Long.class,variant);
        var before=identities();
        fixture.jdbc.update("update plm_product set master_data_status='inactive' where product_id=?",sku);
        assertThatThrownBy(()->productCoding.provision(project,fixture.phoneModel,fixture.id(row),"QA 停用SKU","large","")).hasMessageContaining("已停用");
        assertThat(identities()).isEqualTo(before);
        assertThat(fixture.repo.row(fixture.id(row),false)).containsEntry("provisioningStatus","ready").containsEntry("moldSetId",built.get("moldSetId"));
        fixture.jdbc.update("update plm_product set master_data_status='active',production_status='discontinued' where product_id in (?,?,?)",project,variant,sku);
        var reused=productCoding.provision(project,fixture.phoneModel,fixture.id(row),"QA 已停产资料","large","");
        assertThat(reused).containsEntry("status","ready");assertThat((List<Long>)reused.get("skuIds")).containsExactly(sku);
        assertThat(identities()).isEqualTo(before);noProductionFacts();
    }

    @Test void aScopedEngineerCanReadOnlyTheGeneratedIdentitiesForTheAuthorizedProject() {
        long engineer=useScopedEngineer();
        var row=application(fixture.phoneModel,List.of("10","20"),"4030");agree(row);var built=sync(row);
        assertThat(built).containsEntry("projectId",project).containsEntry("provisioningStatus","ready");
        var generated=fixture.jdbc.queryForList("select product_id from plm_product where parent_product_id=? or parent_product_id in (select product_id from plm_product where parent_product_id=?) order by product_id",Long.class,project,project);
        assertThat(generated).hasSize(2);
        for(long id:generated){assertThatCode(()->fixture.access.requireProduct(id)).doesNotThrowAnyException();assertThat(count("select count(*) from plm_pilot_scope where user_id=? and entity_type='product' and entity_id=?",engineer,id)).isEqualTo(1);}
        assertThatThrownBy(()->fixture.access.requireProduct(50L)).hasMessageContaining("未获授权");
        long otherModel=fixture.jdbc.queryForObject("select product_id from plm_product where product_type='model_variant' and parent_product_id<>? and deleted_flag=0 order by product_id limit 1",Long.class,project);
        assertThatThrownBy(()->fixture.access.requireProduct(otherModel)).hasMessageContaining("未获授权");
        long otherFile=fixture.jdbc.queryForObject("select attachment_id from plm_attachment where deleted_flag=0 order by attachment_id limit 1",Long.class);
        assertThatThrownBy(()->fixture.access.requireFile(otherFile)).hasMessageContaining("未获授权");
        assertThatThrownBy(()->productCoding.provision(50L,fixture.phoneModel,fixture.id(row),"QA 受限续办","large","")).hasMessageContaining("未获授权");
        workflowLinked(built);noProductionFacts();
    }
    @Test void aLegacySubmittedResearchStillHandlesItsOriginalCallbackWithoutChangingItsFlow() {
        var row=application(fixture.phoneModel,List.of("10","20"),"4030");long id=fixture.id(row);
        fixture.jdbc.update("update plm_mold_application set process_code=?,tipo=?,form_data=jsonb_set(form_data,'{tipo}',to_jsonb(?::text)) where application_id=?",fixture.props.getProcessCode(),fixture.tipo,fixture.tipo,id);
        row=fixture.repo.row(id,false);var source=agree(row);((com.fasterxml.jackson.databind.node.ArrayNode)source.path("form_component_values")).addObject().put("id",MoldApplicationForms.COMPONENTS.get("tipo")).put("value",fixture.tipo);
        var built=sync(row);assertThat(built).containsEntry("processCode",fixture.props.getProcessCode()).containsEntry("projectId",project).containsEntry("tipo",fixture.tipo).containsEntry("provisioningStatus","ready");assertThat(fixture.service.detail(id)).containsEntry("processMigrationRequired",false);workflowLinked(built);
    }

}

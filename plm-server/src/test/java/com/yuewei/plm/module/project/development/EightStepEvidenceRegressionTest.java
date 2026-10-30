package com.yuewei.plm.module.project.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.collaboration.MoldScheduleService;
import com.yuewei.plm.module.development.DevelopmentFinanceService;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import com.yuewei.plm.module.operationlog.dto.OperationLogQueryDTO;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.repository.ProductRepository;
import com.yuewei.plm.repository.entity.Product;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Business regressions for the new sequence; no database or external approval side effects. */
class EightStepEvidenceRegressionTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final PilotAccessService access=mock(PilotAccessService.class);
    final DevelopmentWorkflowStore store=mock(DevelopmentWorkflowStore.class);
    final ProductRepository products=mock(ProductRepository.class);
    final MoldScheduleService molds=mock(MoldScheduleService.class);
    final PhoneModelReferenceService phones=mock(PhoneModelReferenceService.class);
    final LaunchReviewWorkflow reviews=new LaunchReviewWorkflow(jdbc,access,store);
    final LaunchDevelopmentWorkflow flow=new LaunchDevelopmentWorkflow(jdbc,new ObjectMapper(),access,store,molds,mock(DevelopmentFinanceService.class),reviews,phones);
    final com.yuewei.plm.module.attachment.repository.AttachmentRepository attachments=mock(com.yuewei.plm.module.attachment.repository.AttachmentRepository.class);
    final ProductDevelopmentService service=spy(new ProductDevelopmentService(jdbc,new ObjectMapper(),access,products,
        attachments,
        mock(com.yuewei.plm.module.operationlog.service.OperationLogService.class),store,
        mock(com.yuewei.plm.module.inventory.service.InventorySupplierService.class),flow,reviews,mock(DevelopmentUnitCostWorkflow.class)));

    Map<String,Object> state(){
        var state=new LinkedHashMap<String,Object>();
        state.put("plan",Map.of("phoneModelIds",List.of(10L),"method","supplier","needsMold",true,"planVersion",1L));
        state.put("requirement",Map.of("ownerUserId",7L));state.put("requirementVersion",1L);
        return state;
    }
    void knownStandardPhone(){
        var member=Map.<String,Object>of("phoneModelId",10L,"modelName","验收标准手机","brandName","验收品牌","status","enabled","brandStatus","enabled");
        when(phones.members(List.of(10L),true)).thenReturn(Map.of(10L,member));
        when(phones.requireEnabledModels(List.of(10L))).thenReturn(Map.of(10L,member));
    }
    Map<String,Object> drawing(long id){return Map.of("type","file","id",id,"familyId",11L,"category","drawing","fileExt","step","versionNo","V"+id);}
    DevelopmentModels.RoundInput receipt(String result,List<DevelopmentModels.MaterialReference> refs){
        return new DevelopmentModels.RoundInput("sample",LocalDate.now(),"3D样到货",result,"","","",refs,true);
    }
    Product product(int stage){
        var product=new Product();product.setProductId(42L);product.setProductType("product_line");product.setDevelopmentMode("launch_v1");
        product.setCurrentStepNo(stage);product.setStatus("developing");product.setDeletedFlag(0);return product;
    }
    DevelopmentRules.ReadinessCheck check(List<DevelopmentRules.ReadinessCheck> rows,String code){return rows.stream().filter(r->code.equals(r.code())).findFirst().orElseThrow();}

    @Test void onlyActualResponsiblePersonMayConfirmInitiation(){
        var state=Map.<String,Object>of("profileDraft",Map.of("ownerUserId",7L));
        when(jdbc.queryForObject(startsWith("select count(*) from sys_user"),eq(Long.class),eq(7L))).thenReturn(1L);
        when(access.actorId()).thenReturn(8L);
        assertThatThrownBy(()->service.requireResponsibleConfirmation(state)).hasMessageContaining("负责人确认");
        when(access.actorId()).thenReturn(7L);assertThatCode(()->service.requireResponsibleConfirmation(state)).doesNotThrowAnyException();
        verifyNoInteractions(store);
    }

    @Test void receiptConfirmsPhysicalArrivalWhileItsConclusionStaysPending(){
        var facts=new HashMap<String,Object>(Map.of("samplePurpose","prototype_3d","phoneModelIds",List.of(10L),"makerName","外部打样商"));
        var refs=List.of(new DevelopmentModels.MaterialReference("file",21L));
        flow.validateSample(42L,receipt("pending",refs),facts,state());
        assertThat(facts).containsEntry("planVersion",1L).doesNotContainKeys("actualResult","designSummary","reviewerUserId");
        assertThatThrownBy(()->flow.validateSample(42L,receipt("passed",refs),facts,state())).hasMessageContaining("确认结论请在样品确认");
        assertThatThrownBy(()->flow.validateSample(42L,receipt("pending",List.of()),facts,state())).hasMessageContaining("样品图片或附件");
    }

    @Test void receivedPrototypeCannotStandInForAPassedTrialSample(){
        var state=state();state.put("prototypeRoundId",11L);state.put("selectedRoundId",11L);
        doReturn(Map.of("kind","sample","status","confirmed","result","pending","roundId",11L,"details",Map.of("samplePurpose","prototype_3d"))).when(service).rawRound(42L,11L);
        when(store.load(42L)).thenReturn(state);
        assertThatThrownBy(()->flow.currentSample(42L,state,service)).hasMessageContaining("来源验证尚未通过");
    }

    @Test void aVoidedSelectedPrototypeDoesNotSilentlyFallBackToAnotherReceipt(){
        var state=state();state.put("prototypeRoundId",11L);state.put("voidedRounds",Map.of("11",Map.of("reason","误录")));
        doReturn(List.of(prototype(11L),prototype(12L))).when(service).allRounds(42L);
        assertThatThrownBy(()->flow.currentPrototype(42L,state,service)).hasMessageContaining("实际收样");
    }
    Map<String,Object> prototype(long id){return Map.of("roundId",id,"kind","sample","status","confirmed","details",Map.of("samplePurpose","prototype_3d"));}

    @Test void prototypeConfirmationRequiresTheResponsibleActorAndCannotImpersonateAReviewer(){
        var state=state();var input=new LinkedHashMap<String,Object>();
        input.put("gate","design");input.put("confirm",true);input.put("reviewDate",LocalDate.now().toString());input.put("reviewerUserId",7L);input.put("result","passed");input.put("summary","外观、结构、装配确认");
        var mockService=mock(ProductDevelopmentService.class);when(mockService.requireDevelopmentEditable(42L)).thenReturn(product(4));
        when(jdbc.queryForList(startsWith("select coalesce(display_name,user_name,username) as name"),eq(7L))).thenReturn(List.of(Map.of("name","产品开发负责人")));
        when(access.actorId()).thenReturn(8L);
        assertThatThrownBy(()->reviews.action(42L,state,"review_save",input,mockService)).hasMessageContaining("产品开发负责人");
        when(access.actorId()).thenReturn(7L);input.put("reviewerUserId",8L);
        when(jdbc.queryForList(startsWith("select coalesce(display_name,user_name,username) as name"),eq(8L))).thenReturn(List.of(Map.of("name","其他工程师")));
        assertThatThrownBy(()->reviews.action(42L,state,"review_save",input,mockService)).hasMessageContaining("样品确认人必须");
        assertThat(state).doesNotContainKey("reviewRecords");
    }

    @Test void aLaterFailedConfirmationCannotReuseAnOlderPassingConclusion(){
        var state=state();var passed=review(state,1L,"passed");var failed=review(state,2L,"failed");state.put("reviewRecords",List.of(passed,failed));
        doReturn(true).when(service).compatibleRequirement(state,1L);
        assertThatThrownBy(()->reviews.requireDesign(42L,state,null,service)).hasMessageContaining("最新评审未通过");
    }
    Map<String,Object> review(Map<String,Object> state,long id,String result){
        var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("gate","design");row.put("status","confirmed");row.put("result",result);
        row.put("requirementVersion",1L);row.put("revisionNo",0L);row.put("planBasis",LaunchReviewWorkflow.planBasis(state));row.put("references",List.of(drawing(1L)));return row;
    }

    @Test void editedTechnicalContentInvalidatesThePreviousSampleConfirmation(){
        var state=state();state.put("reviewRecords",List.of(review(state,1L,"passed")));
        doReturn(true).when(service).compatibleRequirement(state,1L);
        doReturn(List.of(drawing(2L))).when(service).snapshotReferences(eq(42L),anyList());
        assertThatThrownBy(()->reviews.requireDesign(42L,state,null,service)).hasMessageContaining("补充对应验证");
    }

    @Test void linkedPhysicalSetDoesNotAuthorizeANewMoldBeforeTheOriginalApplicationDoes(){
        var state=state();state.put("moldLinks",List.of(Map.of("applicationId",99L,"moldSetId",5L)));
        when(jdbc.queryForObject(startsWith("select exists(select 1 from plm_mold_application a where a.mold_set_id"),eq(Boolean.class),eq(5L))).thenReturn(false);
        when(jdbc.queryForObject(startsWith("select exists(select 1 from plm_mold_application where application_id"),eq(Boolean.class),eq(99L),eq(42L),eq(42L),eq(5L))).thenReturn(false);
        var authorization=check(flow.stageChecks(42L,5,state,mock(ProductDevelopmentService.class)),"MOLD_AUTHORIZATION");
        assertThat(authorization.complete()).isFalse();assertThat(authorization.message()).contains("尚未达到已授权开工");
        when(jdbc.queryForObject(startsWith("select exists(select 1 from plm_mold_application where application_id"),eq(Boolean.class),eq(99L),eq(42L),eq(42L),eq(5L))).thenReturn(true);
        assertThat(check(flow.stageChecks(42L,5,state,mock(ProductDevelopmentService.class)),"MOLD_AUTHORIZATION").complete()).isTrue();
        when(jdbc.queryForObject(startsWith("select exists(select 1 from plm_mold_application a where a.mold_set_id"),eq(Boolean.class),eq(5L))).thenReturn(true);
        assertThat(check(flow.stageChecks(42L,5,state,mock(ProductDevelopmentService.class)),"MOLD_AUTHORIZATION").message()).contains("暂停或终止");
        verify(access,atLeastOnce()).requireMold(5L);
    }

    @Test void anExistingSetRequiresExplicitReuseAndVerifiedPcTpuCompatibility(){
        var state=state();state.put("moldLinks",List.of(Map.of("moldSetId",5L)));
        var authorization=check(flow.stageChecks(42L,5,state,mock(ProductDevelopmentService.class)),"MOLD_AUTHORIZATION");
        assertThat(authorization.complete()).isFalse();assertThat(authorization.message()).contains("明确确认复用");
        state.put("moldLinks",List.of(Map.of("moldSetId",5L,"reuseConfirmed",true)));
        when(jdbc.queryForList(eq("select master_data_status,deleted_flag from plm_product where product_id=? for share"),eq(42L)))
            .thenReturn(List.of(Map.of("master_data_status","active","deleted_flag",0)));
        when(jdbc.queryForList(eq("select master_data_status,deleted_flag from plm_collaboration_mold_set where mold_set_id=? for share"),eq(5L)))
            .thenReturn(List.of(Map.of("master_data_status","active","deleted_flag",0)));
        when(phones.compatibleMolds(10L,42L)).thenReturn(List.of());
        authorization=check(flow.stageChecks(42L,5,state,mock(ProductDevelopmentService.class)),"MOLD_AUTHORIZATION");
        assertThat(authorization.complete()).isFalse();assertThat(authorization.message()).contains("PC／TPU逐套适配");
    }

    @Test void projectLogsRespectProjectScopeAndRemoveFinancialAmounts(){
        when(products.selectById(42L)).thenReturn(product(1));when(jdbc.queryForObject(startsWith("select count(*) from plm_product_development_event"),eq(Long.class),any(Object[].class))).thenReturn(1L);
        var event=new LinkedHashMap<String,Object>();event.put("eventId",5L);event.put("kind","supplier_change");event.put("data","{\"reason\":\"技术无法实现\",\"amount\":120,\"before\":{\"quoteAmount\":88},\"actualResult\":\"需更换\"}");
        when(jdbc.queryForList(startsWith("select event_id as"),any(Object[].class))).thenReturn(List.of(event));
        var response=service.projectLogs(42L,new OperationLogQueryDTO());
        assertThat(response.toString()).contains("技术无法实现","需更换").doesNotContain("120","quoteAmount","88");
        verify(access).requireProduct(42L);verify(access).requirePermission("project:view");verify(access,never()).requirePermission("system:log");
    }

    @Test void aProjectLogFilterCannotBypassProjectAccess(){
        doThrow(DevelopmentWorkflowStore.invalid("无此项目权限")).when(access).requireProduct(42L);
        assertThatThrownBy(()->service.projectLogs(42L,new OperationLogQueryDTO())).hasMessageContaining("无此项目权限");
        verifyNoInteractions(jdbc,products);
    }

    Map<String,Object> shipmentState(List<Long> selected){
        var state=state();state.put("moldLinks",List.of(Map.of("moldSetId",5L),Map.of("moldSetId",6L)));
        state.put("stageDrafts",new LinkedHashMap<>(Map.of("8",shipmentDraft(selected))));
        when(access.actor()).thenReturn(new CurrentUser(7L,"developer","产品开发负责人",false));return state;
    }
    Map<String,Object> shipmentDraft(List<Long> selected){return new LinkedHashMap<>(Map.of("stage",8,"moldSetIds",selected,"recipient","墨西哥工厂模房","references",List.of(Map.of("type","file","id",91L))));}
    ProductDevelopmentService shipmentService(){
        var mockService=mock(ProductDevelopmentService.class);
        when(mockService.snapshotReferences(eq(42L),anyList())).thenReturn(List.of(Map.of("type","file","id",91L,"category","other","versionNo","V1")));
        return mockService;
    }

    @Test void aRealFirstShipmentBatchDoesNotClaimTheUnshippedSecondSetIsComplete(){
        var state=shipmentState(List.of(5L));var mockService=shipmentService();
        when(molds.snapshotForDevelopment(5L)).thenReturn(Map.of("moldSetId",5L,"actualShipDate",LocalDate.now().toString()));
        when(molds.snapshotForDevelopment(6L)).thenReturn(Map.of("moldSetId",6L));
        flow.action(42L,product(8),state,"shipment_confirm",new HashMap<>(),mockService);
        var batches=LaunchReviewWorkflow.records(state.get("shipmentBatches"));
        assertThat(batches).hasSize(1);assertThat(batches.get(0)).containsEntry("moldSetIds",List.of(5L));
        assertThat(LaunchReviewWorkflow.records(batches.get(0).get("molds"))).extracting(row->row.get("moldSetId")).containsExactly(5L);
        var checks=flow.stageChecks(42L,8,state,mockService);
        assertThat(check(checks,"SHIP_5").complete()).isTrue();assertThat(check(checks,"SHIP_6").complete()).isFalse();
        assertThatThrownBy(()->flow.confirmStage(42L,8,state,mockService)).hasMessageContaining("原排程");
        assertThat(state).doesNotContainKey("stage8Basis");
        DevelopmentWorkflowStore.map(state.get("stageDrafts")).put("8",shipmentDraft(List.of(6L)));
        when(molds.snapshotForDevelopment(6L)).thenReturn(Map.of("moldSetId",6L,"actualShipDate",LocalDate.now().toString()));
        flow.action(42L,product(8),state,"shipment_confirm",new HashMap<>(),mockService);
        assertThat(flow.stageChecks(42L,8,state,mockService)).allMatch(DevelopmentRules.ReadinessCheck::complete);
        flow.confirmStage(42L,8,state,mockService);
        assertThat(LaunchReviewWorkflow.records(DevelopmentWorkflowStore.map(state.get("stage8Basis")).get("shipments"))).hasSize(2);
    }

    @Test void aTypedDraftDateCannotReplaceTheCanonicalScheduleDateOrInventAShipment(){
        var state=shipmentState(List.of(5L));var draft=DevelopmentWorkflowStore.map(DevelopmentWorkflowStore.map(state.get("stageDrafts")).get("8"));
        draft.put("actualShipDate",LocalDate.now().toString());when(molds.snapshotForDevelopment(5L)).thenReturn(Map.of("moldSetId",5L));
        assertThatThrownBy(()->flow.action(42L,product(8),state,"shipment_confirm",new HashMap<>(),shipmentService())).hasMessageContaining("原排程");
        assertThat(state).doesNotContainKey("shipmentBatches");
    }

    @Test void anUnrelatedSetCannotBeAddedToAProjectShipmentBatch(){
        var state=shipmentState(List.of(99L));
        assertThatThrownBy(()->flow.action(42L,product(8),state,"shipment_confirm",new HashMap<>(),shipmentService())).hasMessageContaining("本项目关联");
        assertThat(state).doesNotContainKey("shipmentBatches");verify(molds,never()).snapshotForDevelopment(99L);
    }

    @Test void aBomVersionCannotReplaceTheActualLogisticsAttachmentForAShipmentBatch(){
        var state=shipmentState(List.of(5L));var mockService=shipmentService();
        DevelopmentWorkflowStore.map(DevelopmentWorkflowStore.map(state.get("stageDrafts")).get("8")).put("references",List.of(Map.of("type","bom","id",22L)));
        when(mockService.snapshotReferences(eq(42L),anyList())).thenReturn(List.of(Map.of("type","bom","id",22L,"versionNo","V1")));
        when(molds.snapshotForDevelopment(5L)).thenReturn(Map.of("moldSetId",5L,"actualShipDate",LocalDate.now().toString()));
        assertThatThrownBy(()->flow.action(42L,product(8),state,"shipment_confirm",new HashMap<>(),mockService)).hasMessageContaining("物流");
        assertThat(state).doesNotContainKey("shipmentBatches");
    }

    @Test void correctingAScheduleDateNeedsANewReasonedBatchAndPreservesTheOriginalEvidence()throws Exception{
        var state=shipmentState(List.of(5L));var mockService=shipmentService();String originalDate=LocalDate.now().minusDays(1).toString();
        when(molds.snapshotForDevelopment(5L)).thenReturn(Map.of("moldSetId",5L,"actualShipDate",originalDate));
        when(molds.snapshotForDevelopment(6L)).thenReturn(Map.of("moldSetId",6L));
        flow.action(42L,product(8),state,"shipment_confirm",new HashMap<>(),mockService);
        Object original=LaunchReviewWorkflow.records(state.get("shipmentBatches")).get(0);String before=new ObjectMapper().writeValueAsString(original);
        when(molds.snapshotForDevelopment(5L)).thenReturn(Map.of("moldSetId",5L,"actualShipDate",LocalDate.now().toString()));
        assertThat(check(flow.stageChecks(42L,8,state,mockService),"SHIP_5").message()).contains("日期已更正");
        assertThatThrownBy(()->flow.action(42L,product(8),state,"shipment_confirm",new HashMap<>(),mockService)).hasMessageContaining("更正原因");
        DevelopmentWorkflowStore.map(DevelopmentWorkflowStore.map(state.get("stageDrafts")).get("8")).put("reason","原排程发运日期更正，重新核对物流单");
        flow.action(42L,product(8),state,"shipment_confirm",new HashMap<>(),mockService);
        assertThat(LaunchReviewWorkflow.records(state.get("shipmentBatches"))).hasSize(2);
        assertThat(new ObjectMapper().writeValueAsString(original)).isEqualTo(before);
        assertThat(check(flow.stageChecks(42L,8,state,mockService),"SHIP_5").complete()).isTrue();
    }

    @Test void confirmedNoMoldPathCanPassInapplicableDesignAndShipmentWithoutCreatingPhysicalFacts(){
        knownStandardPhone();
        var state=state();state.put("plan",Map.of("phoneModelIds",List.of(10L),"method","direct","needsMold",false));
        state.put("reviewRecords",List.of(review(state,1L,"passed")));
        state.put("noMold",Map.of("reason","外部现有成品方案，无本次开模或发模","designReviewId",1L,"planBasis",LaunchReviewWorkflow.planBasis(state),"revisionNo",0L));
        state.put("planDraft",state.get("plan"));
        doReturn(true).when(service).compatibleRequirement(state,1L);doReturn(List.of(drawing(1L))).when(service).snapshotReferences(eq(42L),anyList());
        assertThat(flow.stageChecks(42L,5,state,service)).allMatch(DevelopmentRules.ReadinessCheck::complete);
        assertThat(flow.stageChecks(42L,8,state,service)).allMatch(DevelopmentRules.ReadinessCheck::complete);
        flow.confirmStage(42L,5,state,service);flow.confirmStage(42L,8,state,service);
        assertThat(state).containsKeys("stage5Basis","stage8Basis").doesNotContainKeys("moldLinks","shipmentBatches");
        verifyNoInteractions(molds);
    }

    @Test void selectingNoMoldDoesNotSkipItsExplicitEvidenceOrGrantANewMoldPath(){
        var state=state();state.put("plan",Map.of("phoneModelIds",List.of(10L),"method","direct","needsMold",false));
        doNothing().when(service).requireMoldSupplier(state);
        state.put("reviewRecords",List.of(review(state,1L,"passed")));
        doReturn(true).when(service).compatibleRequirement(state,1L);doReturn(List.of(drawing(1L))).when(service).snapshotReferences(eq(42L),anyList());
        assertThat(flow.stageChecks(42L,5,state,service)).anyMatch(c->!c.complete()&&c.message().contains("无需开模依据"));
        assertThat(flow.stageChecks(42L,8,state,service)).anyMatch(c->!c.complete()&&c.message().contains("无需开模依据"));
        state.put("noMold",Map.of("reason","现有成品","designReviewId",1L,"planBasis",LaunchReviewWorkflow.planBasis(state),"revisionNo",0L));
        state.put("plan",Map.of("phoneModelIds",List.of(10L),"method","supplier","needsMold",true));
        assertThat(flow.stageChecks(42L,5,state,service)).anyMatch(c->!c.complete()&&"MOLD_AUTHORIZATION".equals(c.code()));
        assertThat(flow.stageChecks(42L,8,state,service)).anyMatch(c->!c.complete()&&"SHIPMENT_SCOPE".equals(c.code()));
    }

    @Test void acceptingOnePhysicalSetNeedsItsOwnT0WhileProjectHandoverStillNeedsEverySet(){
        var state=state();state.put("moldLinks",List.of(Map.of("moldSetId",5L),Map.of("moldSetId",6L)));state.put("selectedRoundId",21L);
        var design=review(state,1L,"passed");var t0=review(state,2L,"passed");
        t0.put("gate","t0");t0.put("moldSetId",5L);t0.put("moldCycle",1L);t0.put("sampleRoundId",21L);
        state.put("reviewRecords",List.of(design,t0));
        var sample=Map.<String,Object>of("roundId",21L,"references",List.of(drawing(1L)),"details",Map.of("phoneModelIds",List.of(10L),"planVersion",1L,"requirementVersion",1L));
        doReturn(product(6)).when(service).requireDevelopmentEditable(42L);
        doReturn(true).when(service).compatibleRequirement(eq(state),any());
        doReturn(sample).when(service).confirmedSource(eq(42L),eq(21L),eq("sample"),anyBoolean());
        doReturn(List.of(drawing(1L))).when(service).snapshotReferences(eq(42L),anyList());
        when(jdbc.queryForList(startsWith("select development_data::text as data"),eq(5L))).thenReturn(List.of(Map.of("data","{\"cycle\":1}")));
        when(store.read("{\"cycle\":1}")).thenReturn(Map.of("cycle",1L));
        when(jdbc.queryForList(startsWith("select phone_model_id from plm_collaboration_phone_set_compatibility"),eq(Long.class),eq(5L))).thenReturn(List.of(10L));
        assertThatCode(()->flow.action(42L,product(6),state,"mold_node",new HashMap<>(Map.of("moldSetId",5L,"node","testing","confirm",true,"result","passed","references",List.of(drawing(1L)))),service)).doesNotThrowAnyException();
        verify(molds).saveDevelopmentNode(eq(5L),anyMap(),anyMap(),eq(false));
        assertThatThrownBy(()->reviews.requireT0(42L,state,sample,service)).hasMessageContaining("套 6");
    }

    @Test void moldDesignHandoverAdoptsItsSupplierSoItCanBeChangedLaterWithoutAPlanDraftSplit(){
        knownStandardPhone();
        var reviewPolicy=mock(LaunchReviewWorkflow.class);
        var policy=new LaunchDevelopmentWorkflow(jdbc,new ObjectMapper(),access,store,molds,mock(DevelopmentFinanceService.class),reviewPolicy,phones);
        var state=state();var assignments=List.of(Map.of("mode","supplier","role","mold","supplierCode","SUP-ACTIVE","scope","PC与TPU制作","basis","当前报价及技术能力已核对"));
        var draft=new LinkedHashMap<>(DevelopmentWorkflowStore.map(state.get("plan")));draft.put("supplierAssignments",assignments);state.put("planDraft",draft);
        state.put("stage2Basis",Map.of("references",List.of(drawing(1L))));
        state.put("moldLinks",List.of(Map.of("applicationId",99L,"moldSetId",5L)));
        state.put("stageDrafts",Map.of("5",Map.of("moldDesignReferences",List.of(Map.of("type","file","id",1L)),"references",List.of(Map.of("type","file","id",1L)))));
        var mockService=mock(ProductDevelopmentService.class);when(mockService.snapshotReferences(eq(42L),anyList())).thenReturn(List.of(drawing(1L)));
        when(reviewPolicy.requireDesign(eq(42L),same(state),isNull(),same(mockService))).thenReturn(Map.of("id",1L));
        when(reviewPolicy.resolveMold(eq(42L),anyMap())).thenReturn(5L);
        when(jdbc.queryForObject(startsWith("select exists(select 1 from plm_mold_application where application_id"),eq(Boolean.class),eq(99L),eq(42L),eq(42L),eq(5L))).thenReturn(true);
        when(access.actor()).thenReturn(new CurrentUser(7L,"developer","产品开发负责人",false));
        assertThat(draft).doesNotContainKey("reason");
        assertThat(policy.stageChecks(42L,5,state,mockService)).allMatch(DevelopmentRules.ReadinessCheck::complete);
        policy.confirmStage(42L,5,state,mockService);
        assertThat(DevelopmentWorkflowStore.map(state.get("plan"))).containsEntry("supplierAssignments",assignments);
        assertThat(DevelopmentWorkflowStore.map(state.get("plan")).get("planVersion")).isEqualTo(2L);
        verify(store).event(eq(42L),eq("plan_confirmed"),anyMap(),isNull(),eq("产品开发负责人"));
    }

    @Test void moldDesignNeedsAnEnabledSupplierWithoutDemandingProductionBomOrProcess(){
        var suppliers=mock(com.yuewei.plm.module.partner.SupplierProfileService.class);var policy=new EngineeringBasisService(jdbc,access,suppliers);
        var plan=Map.<String,Object>of("supplierAssignments",List.of(Map.of("mode","supplier","role","mold","supplierCode","SUP-A")));
        when(suppliers.requireSupplier("SUP-A")).thenReturn(Map.of("status","draft","needsReview",false));
        assertThatThrownBy(()->policy.requireMoldSupplier(plan)).hasMessageContaining("尚未启用");
        when(suppliers.requireSupplier("SUP-A")).thenReturn(Map.of("status","active","needsReview",true));
        assertThatThrownBy(()->policy.requireMoldSupplier(plan)).hasMessageContaining("身份待核对");
        when(suppliers.requireSupplier("SUP-A")).thenReturn(Map.of("status","active","needsReview",false));
        assertThatCode(()->policy.requireMoldSupplier(plan)).doesNotThrowAnyException();verifyNoInteractions(jdbc);
    }

    @Test void aPriorEmptyConfirmedSupplierPlanCanRecoverFromItsSavedDraftButCannotOverwriteAConflictingPlan()throws Exception{
        var suppliers=mock(com.yuewei.plm.module.partner.SupplierProfileService.class);var policy=new EngineeringBasisService(jdbc,access,suppliers);
        when(suppliers.requireSupplier("SUP-A")).thenReturn(Map.of("supplierId",1L,"supplierName","原供方","version",1,"status","active"));
        doCallRealMethod().when(suppliers).requireSupplierForNewUse(anyString());when(suppliers.requireSupplier("SUP-B")).thenReturn(Map.of("supplierId",2L,"supplierName","新供方","version",1,"status","active"));
        var before=Map.<String,Object>of("mode","supplier","role","mold","supplierCode","SUP-A","scope","PC与TPU制作","basis","原采用依据");
        var state=state();var confirmed=new LinkedHashMap<>(DevelopmentWorkflowStore.map(state.get("plan")));confirmed.put("supplierAssignments",List.of());state.put("plan",confirmed);
        var draft=new LinkedHashMap<>(confirmed);draft.put("supplierAssignments",List.of(before));state.put("planDraft",draft);
        var input=Map.<String,Object>of("previousSupplierCode","SUP-A","supplierCode","SUP-B","role","mold","scope","PC与TPU制作","reason","原供方技术无法实现","basis","新供方报价和技术能力已核对");
        var result=policy.changeSupplier(42L,state,7,input);
        assertThat(LaunchReviewWorkflow.records(DevelopmentWorkflowStore.map(state.get("plan")).get("supplierAssignments"))).allMatch(row->"SUP-B".equals(row.get("supplierCode")));
        assertThat(LaunchReviewWorkflow.records(DevelopmentWorkflowStore.map(result.get("before")).get("supplierAssignments"))).allMatch(row->"SUP-A".equals(row.get("supplierCode")));
        confirmed.put("supplierAssignments",List.of(Map.of("mode","supplier","role","mold","supplierCode","SUP-OTHER","scope","PC与TPU制作")));
        state.put("plan",confirmed);state.put("planDraft",draft);String unchanged=new ObjectMapper().writeValueAsString(state);
        assertThatThrownBy(()->policy.changeSupplier(42L,state,7,input)).hasMessageContaining("开发计划与供方草稿不一致");
        assertThat(new ObjectMapper().writeValueAsString(state)).isEqualTo(unchanged);
    }

    Map<String,Object> receivedPrototype(long id,List<Map<String,Object>> refs){
        return Map.of("roundId",id,"kind","sample","status","confirmed","result","pending","recordDate",LocalDate.now().toString(),"references",refs,
            "details",Map.of("samplePurpose","prototype_3d","phoneModelIds",List.of(10L),"requirementVersion",1L));
    }
    Map<String,Object> photo(){return Map.of("type","file","id",91L,"category","sample_image");}
    Map<String,Object> render(long id){return Map.of("type","file","id",100L+id,"familyId",101L,"category","render_3d","fileExt","png","versionNo","V"+id);}

    @Test void returningToPrototypeRequiresANewReceiptAndKeepsTheSupersededStepEvidenceInTheReturnEvent(){
        var state=state();var original=receivedPrototype(11L,List.of(drawing(1L),photo()));
        state.put("prototypeRoundId",11L);state.put("stage2Basis",Map.of("references",List.of(drawing(1L))));
        state.put("stage3Basis",Map.of("sample",original));state.put("stage4Basis",Map.of("confirmation","old outcome"));state.put("finalized",Map.of("id",1L));
        var review=review(state,1L,"failed");state.put("reviewRecords",List.of(review));
        doReturn(product(4)).when(service).requireDevelopmentEditable(42L);when(store.load(42L)).thenReturn(state);
        doReturn(original).when(service).rawRound(42L,11L);doReturn(List.of(original)).when(service).allRounds(42L);
        when(access.actor()).thenReturn(new CurrentUser(7L,"developer","产品开发负责人",false));
        service.recordReturn(42L,4,3,"打印件尺寸异常，重新打样",11L,9L);
        assertThat(state).containsEntry("prototypeAfterRoundId",11L).containsKey("stage2Basis").doesNotContainKeys("prototypeRoundId","stage3Basis","stage4Basis","finalized");
        verify(store).event(eq(42L),eq("returned"),argThat(value->{var data=DevelopmentWorkflowStore.map(value);return DevelopmentWorkflowStore.map(data.get("previousStepBasis")).containsKey("stage3Basis")&&data.get("sourceReview")==review;}),isNull(),eq("产品开发负责人"));
        assertThatThrownBy(()->flow.currentPrototype(42L,state,service)).hasMessageContaining("实际收样");
        var replacement=receivedPrototype(12L,List.of(drawing(1L),photo()));doReturn(List.of(replacement,original)).when(service).allRounds(42L);
        assertThat(flow.currentPrototype(42L,state,service)).isSameAs(replacement);
        assertThat(original).containsEntry("roundId",11L).containsEntry("result","pending");
    }

    @Test void aCadFileCannotReplaceTheActualPrototypeReceiptPhoto(){
        var state=state();state.put("prototypeRoundId",11L);state.put("stage2Basis",Map.of("references",List.of(drawing(1L))));
        doReturn(List.of(receivedPrototype(11L,List.of(drawing(1L))))).when(service).allRounds(42L);
        assertThatThrownBy(()->flow.currentPrototype(42L,state,service)).hasMessageContaining("实际收样照片");
        var received=receivedPrototype(11L,List.of(drawing(1L),photo()));doReturn(List.of(received)).when(service).allRounds(42L);
        assertThat(flow.currentPrototype(42L,state,service)).isSameAs(received);
    }

    @Test void missingHistoricalTechnicalVersionsProduceABusinessGapInsteadOfAServerNullPointer(){
        assertThatThrownBy(()->service.sameTechnical(null,List.of(drawing(1L))))
            .isInstanceOf(com.yuewei.plm.common.exception.BusinessException.class).hasMessageContaining("版本缺失");
        assertThatThrownBy(()->service.sameTechnical(List.of(drawing(1L)),null,true))
            .isInstanceOf(com.yuewei.plm.common.exception.BusinessException.class).hasMessageContaining("版本缺失");
    }

    @Test void aLater3dFileUsesTheActualSampleReferencesWithoutRewritingTheConfirmedDrawingStep(){
        var state=state();state.put("prototypeRoundId",11L);
        var actual=receivedPrototype(11L,List.of(drawing(1L),photo()));doReturn(List.of(actual)).when(service).allRounds(42L);
        var checks=flow.stageChecks(42L,3,state,service);
        assertThat(checks).extracting(DevelopmentRules.ReadinessCheck::code).containsExactly("PROTOTYPE_RECEIPT","PROTOTYPE_DRAWING");
        assertThat(checks).allMatch(DevelopmentRules.ReadinessCheck::complete);assertThat(check(checks,"PROTOTYPE_DRAWING").blocking()).isFalse();
        assertThat(flow.currentPrototype(42L,state,service)).isSameAs(actual);flow.confirmStage(42L,3,state,service);
        assertThat(DevelopmentWorkflowStore.map(state.get("stage3Basis"))).containsEntry("sample",actual);
        assertThat(state).doesNotContainKey("stage2Basis");verifyNoInteractions(molds);
    }

    @Test void anImageIsNotA3dDrawingAndARealReceiptCanProceedWithTheSameTechnicalImageStillAdopted(){
        var state=state();var image=Map.<String,Object>of("type","file","id",31L,"familyId",31L,"category","drawing","fileExt","png","versionNo","V1");
        var original=Map.<String,Object>of("references",List.of(image));state.put("stage2Basis",original);
        var actual=receivedPrototype(11L,List.of(image,photo()));doReturn(List.of(actual)).when(service).allRounds(42L);
        var checks=flow.stageChecks(42L,3,state,service);assertThat(check(checks,"PROTOTYPE_RECEIPT").complete()).isTrue();
        var reminder=check(checks,"PROTOTYPE_DRAWING");assertThat(reminder.complete()).isFalse();assertThat(reminder.satisfied()).isTrue();assertThat(reminder.message()).contains("可后补");
        flow.confirmStage(42L,3,state,service);assertThat(state.get("stage2Basis")).isSameAs(original);
    }

    @Test void first3dSupplementKeepsEveryExisting2dAndBomVersionExact(){
        var state=state();var image=Map.<String,Object>of("type","file","id",31L,"familyId",31L,"category","drawing","fileExt","pdf","versionNo","V1");
        var bom=Map.<String,Object>of("type","bom","id",41L,"versionNo","V1","content",Map.of("material","PC"));
        var original=Map.<String,Object>of("references",List.of(image,bom));state.put("stage2Basis",original);
        var actual=receivedPrototype(11L,List.of(image,bom,drawing(1L),photo()));doReturn(List.of(actual)).when(service).allRounds(42L);
        assertThat(flow.currentPrototype(42L,state,service)).isSameAs(actual);
        var changedImage=new LinkedHashMap<>(image);changedImage.put("versionNo","V2");
        doReturn(List.of(receivedPrototype(11L,List.of(changedImage,bom,drawing(1L),photo())))).when(service).allRounds(42L);
        assertThatThrownBy(()->flow.currentPrototype(42L,state,service)).hasMessageContaining("版本或内容与来源验证不同");
        var changedBom=new LinkedHashMap<>(bom);changedBom.put("content",Map.of("material","TPU"));
        doReturn(List.of(receivedPrototype(11L,List.of(image,changedBom,drawing(1L),photo())))).when(service).allRounds(42L);
        assertThatThrownBy(()->flow.currentPrototype(42L,state,service)).hasMessageContaining("版本或内容与来源验证不同");
        assertThat(state.get("stage2Basis")).isSameAs(original);
    }

    @Test void alreadyAdopted3dCannotBeReplacedAndMissingDrawingDoesNotAuthorizeANewBom(){
        var state=state();state.put("stage2Basis",Map.of("references",List.of(drawing(1L))));
        doReturn(List.of(receivedPrototype(11L,List.of(drawing(2L),photo())))).when(service).allRounds(42L);
        assertThatThrownBy(()->flow.currentPrototype(42L,state,service)).hasMessageContaining("版本或内容与来源验证不同");
        state.remove("stage2Basis");var bom=Map.<String,Object>of("type","bom","id",41L,"versionNo","V1");
        doReturn(List.of(receivedPrototype(11L,List.of(bom,drawing(1L),photo())))).when(service).allRounds(42L);
        assertThatThrownBy(()->flow.currentPrototype(42L,state,service)).hasMessageContaining("版本或内容与来源验证不同");
        assertThat(state).doesNotContainKeys("stage2Basis","stage3Basis");
    }

    @Test void missing3dDoesNotPermitAStaleRequirementOrAnUncoveredRepresentativePhone(){
        var state=state();state.put("requirementVersion",2L);
        doReturn(List.of(receivedPrototype(11L,List.of(photo())))).when(service).allRounds(42L);
        assertThatThrownBy(()->flow.currentPrototype(42L,state,service)).hasMessageContaining("需求已变化");
        state.put("requirementVersion",1L);state.put("plan",Map.of("phoneModelIds",List.of(10L,12L)));
        assertThatThrownBy(()->flow.currentPrototype(42L,state,service)).hasMessageContaining("未覆盖本次开发型号");
        assertThat(state).doesNotContainKeys("stage2Basis","stage3Basis");
    }

    @Test void sourceAndRenderCanEachBeFirstSupplementedButExistingVersionsOfEitherKindRemainExact(){
        var state=state();state.put("stage2Basis",Map.of("references",List.of(drawing(1L))));
        var firstRender=receivedPrototype(11L,List.of(drawing(1L),render(1L),photo()));doReturn(List.of(firstRender)).when(service).allRounds(42L);
        assertThat(flow.currentPrototype(42L,state,service)).isSameAs(firstRender);
        doReturn(List.of(receivedPrototype(11L,List.of(drawing(2L),render(1L),photo())))).when(service).allRounds(42L);
        assertThatThrownBy(()->flow.currentPrototype(42L,state,service)).hasMessageContaining("版本或内容与来源验证不同");
        state.put("stage2Basis",Map.of("references",List.of(render(1L))));
        var firstSource=receivedPrototype(11L,List.of(render(1L),drawing(1L),photo()));doReturn(List.of(firstSource)).when(service).allRounds(42L);
        assertThat(flow.currentPrototype(42L,state,service)).isSameAs(firstSource);
        doReturn(List.of(receivedPrototype(11L,List.of(render(2L),drawing(1L),photo())))).when(service).allRounds(42L);
        assertThatThrownBy(()->flow.currentPrototype(42L,state,service)).hasMessageContaining("版本或内容与来源验证不同");
    }

    @Test void aFirstExplicitRenderCanCompleteTheReminderButCannotReplaceTheActualSamplePhoto(){
        var state=state();var actual=receivedPrototype(11L,List.of(render(1L),photo()));
        doReturn(List.of(actual)).when(service).allRounds(42L);var checks=flow.stageChecks(42L,3,state,service);
        assertThat(checks).allMatch(DevelopmentRules.ReadinessCheck::complete);assertThat(check(checks,"PROTOTYPE_DRAWING").blocking()).isFalse();
        flow.confirmStage(42L,3,state,service);assertThat(state).doesNotContainKey("stage2Basis");
        doReturn(List.of(receivedPrototype(11L,List.of(render(1L))))).when(service).allRounds(42L);
        assertThatThrownBy(()->flow.currentPrototype(42L,state,service)).hasMessageContaining("实际收样照片");
    }

    @Test void aFirstRenderDoesNotPermitChangingOrAddingOtherTechnicalVersions(){
        var state=state();var bom=Map.<String,Object>of("type","bom","id",41L,"versionNo","V1","content",Map.of("material","PC"));
        var image=Map.<String,Object>of("type","file","id",51L,"category","drawing","fileExt","pdf","versionNo","V1");
        state.put("stage2Basis",Map.of("references",List.of(bom,image)));
        var actual=receivedPrototype(11L,List.of(bom,image,render(1L),photo()));doReturn(List.of(actual)).when(service).allRounds(42L);
        assertThat(flow.currentPrototype(42L,state,service)).isSameAs(actual);
        var changedBom=new LinkedHashMap<>(bom);changedBom.put("content",Map.of("material","TPU"));
        doReturn(List.of(receivedPrototype(11L,List.of(changedBom,image,render(1L),photo())))).when(service).allRounds(42L);
        assertThatThrownBy(()->flow.currentPrototype(42L,state,service)).hasMessageContaining("版本或内容与来源验证不同");
        state.remove("stage2Basis");doReturn(List.of(actual)).when(service).allRounds(42L);
        assertThatThrownBy(()->flow.currentPrototype(42L,state,service)).hasMessageContaining("版本或内容与来源验证不同");
    }

    @Test void late3dAdoptionStillUsesTheOriginalProjectScopeFilePermissionAndExactVersionIds(){
        var selected=drawing(1L);var next=drawing(2L);doReturn(List.of(selected,next)).when(service).materialOptions(42L);
        var file=new com.yuewei.plm.module.attachment.entity.Attachment();file.setFileExt("step");file.setChecksum("selected-version-checksum");file.setDeletedFlag(0);
        when(attachments.selectById(1L)).thenReturn(file);
        var refs=List.of(new DevelopmentModels.MaterialReference("file",1L));var snapshots=service.snapshotReferences(42L,refs);
        assertThat(snapshots).hasSize(1);assertThat(snapshots.get(0)).containsEntry("id",1L).containsEntry("versionNo","V1").containsEntry("checksum","selected-version-checksum");
        verify(access).requireFile(1L);verify(attachments,never()).selectById(2L);
        assertThatThrownBy(()->service.snapshotReferences(42L,List.of(new DevelopmentModels.MaterialReference("file",999L)))).hasMessageContaining("未关联本项目或没有访问权限");
        doThrow(DevelopmentWorkflowStore.invalid("没有此文件权限")).when(access).requireFile(1L);
        assertThatThrownBy(()->service.snapshotReferences(42L,refs)).hasMessageContaining("没有此文件权限");
    }
}

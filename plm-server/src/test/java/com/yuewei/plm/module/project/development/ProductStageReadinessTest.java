package com.yuewei.plm.module.project.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.collaboration.MoldScheduleService;
import com.yuewei.plm.repository.ProductRepository;
import com.yuewei.plm.repository.entity.Product;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductStageReadinessTest {
    final org.springframework.jdbc.core.JdbcTemplate jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
    final PilotAccessService access=mock(PilotAccessService.class);
    final DevelopmentWorkflowStore store=mock(DevelopmentWorkflowStore.class);
    final ProductRepository products=mock(ProductRepository.class);
    final MoldScheduleService molds=mock(MoldScheduleService.class);
    final LaunchReviewWorkflow reviews=mock(LaunchReviewWorkflow.class);
    final LaunchDevelopmentWorkflow launch=new LaunchDevelopmentWorkflow(jdbc,new ObjectMapper(),access,store,molds,mock(com.yuewei.plm.module.development.DevelopmentFinanceService.class),reviews,mock(com.yuewei.plm.module.moldreference.PhoneModelReferenceService.class));
    final ProductDevelopmentService service=spy(new ProductDevelopmentService(jdbc,new ObjectMapper(),access,products,mock(com.yuewei.plm.module.attachment.repository.AttachmentRepository.class),mock(com.yuewei.plm.module.operationlog.service.OperationLogService.class),store,mock(com.yuewei.plm.module.inventory.service.InventorySupplierService.class),launch,reviews,mock(DevelopmentUnitCostWorkflow.class)));
    { org.springframework.test.util.ReflectionTestUtils.setField(service,"engineering",mock(EngineeringBasisService.class)); }
    Map<String,Object> state(int stage,boolean needsMold){
        var product=new Product();product.setProductId(42L);product.setProductType("product_line");product.setDevelopmentMode("launch_v1");product.setCurrentStepNo(stage);product.setStatus("developing");product.setDeletedFlag(0);
        when(products.selectById(42L)).thenReturn(product);
        var state=new LinkedHashMap<String,Object>();state.put("version",3L);state.put("plan",Map.of("method",needsMold?"own_mold":"direct","needsMold",needsMold,"phoneModelIds",List.of(10L),"planVersion",1));
        when(store.load(42L)).thenReturn(state);
        when(reviews.requireDesign(eq(42L),anyMap(),any(),eq(service))).thenReturn(Map.of("id",1));
        when(reviews.requireNoMold(anyMap(),anyMap())).thenAnswer(invocation->{var basis=DevelopmentWorkflowStore.map(((Map<?,?>)invocation.getArgument(0)).get("noMold"));DevelopmentRules.require(!basis.isEmpty(),"请确认当前方案的无需开模依据");return basis;});
        when(reviews.requireReliability(eq(42L),anyMap(),eq(service))).thenReturn(Map.of("roundId",12L,"references",List.of()));
        when(reviews.requirePilot(eq(42L),anyMap(),eq(service))).thenReturn(Map.of("roundId",13L,"references",List.of()));
        doReturn(true).when(service).compatibleRequirement(anyMap(),any());
        doReturn(List.of()).when(service).snapshotReferences(eq(42L),anyList());
        return state;
    }
    Map<String,Object> preview(int stage){return service.stageReadiness(42L,new DevelopmentModels.StageReadinessInput(stage,null,null));}
    @SuppressWarnings("unchecked") List<DevelopmentRules.ReadinessCheck> checks(Map<String,Object> response){return (List<DevelopmentRules.ReadinessCheck>)response.get("checks");}
    void sample(Map<String,Object> state){
        state.put("selectedRoundId",11L);
        doReturn(Map.of("roundId",11L,"references",List.of(),"details",Map.of("phoneModelIds",List.of(10L),"planVersion",1))).when(service).confirmedSource(42L,11L,"sample",true);
    }
    void readonly(String before,Map<String,Object> state)throws Exception{
        assertThat(new ObjectMapper().writeValueAsString(state)).isEqualTo(before);
        verify(store,never()).save(anyLong(),anyMap());verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void noMoldBasisIsAnExplicitOriginalConfirmationAndPreviewCannotCreateIt()throws Exception{
        var state=state(6,false);sample(state);String before=new ObjectMapper().writeValueAsString(state);
        var result=preview(6);assertThat(checks(result).stream().filter(c->!c.complete())).extracting(DevelopmentRules.ReadinessCheck::code).containsExactly("NO_MOLD");
        assertThatThrownBy(()->launch.confirmStage(42,6,new LinkedHashMap<>(state),service)).hasMessageContaining("无需开模依据");readonly(before,state);
        state.put("noMold",Map.of("reason","隔离验收依据"));assertThat(preview(6).get("ready")).isEqualTo(true);launch.confirmStage(42,6,state,service);assertThat(state).containsKey("stage6Basis");
    }
    @Test void eachPendingPhysicalSetHasAnActionableIdentityAndUsesTheSameMakingGate()throws Exception{
        var state=state(6,true);sample(state);state.put("moldLinks",List.of(Map.of("moldSetId",7L),Map.of("applicationId",9L)));
        when(reviews.resolveMold(eq(42L),anyMap())).thenAnswer(i->DevelopmentWorkflowStore.number(((Map<?,?>)i.getArgument(1)).get("moldSetId")));
        when(molds.snapshotForDevelopment(7L)).thenReturn(Map.of("development",Map.of("node","making"),"pcStatus","making","tpuStatus","ready"));
        String before=new ObjectMapper().writeValueAsString(state);var result=preview(6);
        assertThat(checks(result).stream().filter(c->!c.complete())).extracting(DevelopmentRules.ReadinessCheck::target).containsExactly("mold:7","application:9");
        assertThatThrownBy(()->launch.confirmStage(42,6,new LinkedHashMap<>(state),service)).hasMessage(checks(result).stream().filter(c->!c.complete()).findFirst().orElseThrow().message());readonly(before,state);
    }
    @Test void sampleValidationReportsCurrentRequirementsAndReviewIndependentlyWithoutTrialFields()throws Exception{
        var state=state(6,false);sample(state);state.put("noMold",Map.of("reason","无需新模具"));
        when(reviews.requireReliability(eq(42L),anyMap(),eq(service))).thenThrow(DevelopmentWorkflowStore.invalid("可靠性评审尚未通过"));
        String before=new ObjectMapper().writeValueAsString(state);var result=preview(6);
        assertThat(checks(result).stream().filter(c->!c.complete())).extracting(DevelopmentRules.ReadinessCheck::code).containsExactly("RELIABILITY");
        assertThat(result.toString()).doesNotContain("试产计划数量","良率");readonly(before,state);
        state.put("reviewSetup",Map.of("tests",List.of(Map.of("code","drop","name","跌落"))));when(reviews.requireReliability(eq(42L),anyMap(),eq(service))).thenReturn(Map.of("roundId",12L));
        assertThat(preview(6).get("ready")).isEqualTo(true);launch.confirmStage(42L,6,state,service);assertThat(state).containsKey("stage6Basis");
    }
    @Test void trialDraftUsesInheritedSampleAndCurrentInputButDoesNotConfirmDateCostOrProductionFacts()throws Exception{
        var state=state(7,false);sample(state);state.put("noMold",Map.of("reason","无需新模具"));
        state.put("reviewSetup",Map.of("plannedQty",10,"minYieldRate",100,"fullInspection",true));
        state.put("unitCostVersions",List.of(Map.of("id",2L,"basisType","supplier_quote","adoptedQuote",Map.of("supplierCode","QA"))));
        String before=new ObjectMapper().writeValueAsString(state);
        var draft=new LinkedHashMap<String,Object>();draft.put("confirmationDate",java.time.LocalDate.now().toString());draft.put("pilotRoundId",13L);draft.put("unitCostVersionId",2L);draft.put("references",List.of());
        var result=service.stageReadiness(42L,new DevelopmentModels.StageReadinessInput(7,null,draft));
        assertThat(result.get("ready")).isEqualTo(true);readonly(before,state);assertThat(draft).doesNotContainKeys("sampleRoundId","phoneModelIds");
        draft.put("unitCostVersionId",99L);result=service.stageReadiness(42L,new DevelopmentModels.StageReadinessInput(7,null,draft));
        assertThat(checks(result).stream().filter(c->!c.complete())).extracting(DevelopmentRules.ReadinessCheck::code).containsExactly("UNIT_COST");readonly(before,state);
    }
    @Test void stageMismatchCannotInspectOrAdvanceAnotherStage(){state(2,false);assertThatThrownBy(()->preview(3)).hasMessageContaining("当前不在");verifyNoInteractions(molds);}
}

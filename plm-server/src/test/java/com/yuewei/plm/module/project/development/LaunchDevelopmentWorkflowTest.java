package com.yuewei.plm.module.project.development;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class LaunchDevelopmentWorkflowTest {
    @Test void initiationAllowsUnknownPhonesButDrawingRequiresRepresentativeScopeWhileThe3dFileMayFollow(){
        var state=new HashMap<String,Object>();state.put("planDraft",Map.of("phoneModelIds",List.of(),"pendingReason","等待新机","method","supplier","needsMold",true));
        assertThat(policy.planChecks(state)).allMatch(DevelopmentRules.ReadinessCheck::complete);
        var gaps=policy.stageChecks(1,2,state,mock(ProductDevelopmentService.class)).stream().filter(c->!c.complete()).toList();
        assertThat(gaps).extracting(DevelopmentRules.ReadinessCheck::code).containsExactly("REPRESENTATIVE_PHONES","PRODUCT_3D_DRAWING");
        assertThat(gaps).extracting(DevelopmentRules.ReadinessCheck::blocking).containsExactly(true,false);
        assertThatThrownBy(()->DevelopmentRules.requireChecks(gaps)).hasMessageContaining("开发型号");
        assertThat(state).doesNotContainKeys("plan","reviewRecords","engineeringBasis");
    }
    final LaunchDevelopmentWorkflow policy=new LaunchDevelopmentWorkflow(mock(org.springframework.jdbc.core.JdbcTemplate.class),new ObjectMapper(),mock(com.yuewei.plm.module.pilot.PilotAccessService.class),mock(DevelopmentWorkflowStore.class),mock(com.yuewei.plm.module.collaboration.MoldScheduleService.class),mock(com.yuewei.plm.module.development.DevelopmentFinanceService.class),mock(LaunchReviewWorkflow.class),mock(com.yuewei.plm.module.moldreference.PhoneModelReferenceService.class));
    Map<String,Object> state(){return Map.of("plan",Map.of("planVersion",1,"phoneModelIds",List.of(10L),"method","direct"));}
    Map<String,Object> facts(){return new HashMap<>(Map.of("phoneModelIds",List.of(10L),"makerName","供应商实样","designSummary","SKU-1／D1"));}
    DevelopmentModels.RoundInput input(String result,boolean confirm,String issues,LocalDate date){return new DevelopmentModels.RoundInput("sample",date,"实际拿样",result,issues,"","",List.of(),confirm);}
    @Test void unchangedPlanReferenceAndIdsDoNotRequireAnAdjustmentReason(){
        var old=new HashMap<String,Object>(Map.of("phoneModelIds",List.of(2),"method","own_mold","needsMold",true,"pilotDate","2026-11-30","references",List.of(Map.of("type","bom","id",99,"versionNo","V1"))));
        var draft=new HashMap<String,Object>(old);draft.put("phoneModelIds",List.of(2L));draft.put("references",List.of(Map.of("type","bom","id",99L,"versionNo","V1","content",Map.of("price",10))));draft.put("launchDate","");
        Object a=org.springframework.test.util.ReflectionTestUtils.invokeMethod(policy,"planBasis",old);Object b=org.springframework.test.util.ReflectionTestUtils.invokeMethod(policy,"planBasis",draft);assertThat(a).isEqualTo(b);
        draft.put("references",List.of(Map.of("type","bom","id",100L)));assertThat((Object)org.springframework.test.util.ReflectionTestUtils.invokeMethod(policy,"planBasis",draft)).isEqualTo(a);
        draft.put("pilotDate","2026-12-01");assertThat((Object)org.springframework.test.util.ReflectionTestUtils.invokeMethod(policy,"planBasis",draft)).isNotEqualTo(a);
    }
    @Test void financialMaskIsSharedWithCanonicalMoldHistoryAndDoesNotMutateTheOriginal(){
        var original=Map.of("kind","mold_node","data",Map.of("sample",Map.of("quoteEventId",9,"adoptedQuote",Map.of("amount",120,"conditions","含税报价"),"details",Map.of("depositNote","已收100","actualResult","试装通过"))));
        String masked=new ObjectMapper().valueToTree(FinancialSnapshotRedaction.withoutMoney(original)).toString();
        assertThat(masked).doesNotContain("含税报价","已收100","amount").contains("actualResult","试装通过","quoteEventId");
        assertThat(original.toString()).contains("含税报价","已收100");
        assertThatCode(()->FinancialSnapshotRedaction.withoutMoney(Map.of("basis",Map.of("node","testing")))) .doesNotThrowAnyException();
    }
    @Test void canonicalMoldHistoryDoesNotExposeAnotherProjectsPrivateEvidence(){
        var data=Map.of("node","ready","last",Map.of("basis",Map.of("projectId",99,"sample","private sample"),"note","private report","actualDate","2026-10-16","result","passed"));
        String protectedData=FinancialSnapshotRedaction.scopedMold(data,id->false).toString();assertThat(protectedData).doesNotContain("private sample","private report").contains("restricted=true","actualDate");
    }
    @Test void lowPermissionSummaryReusesTheRedactedPlanWithoutLeakingOrChangingSupplierEvidence(){
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var access=mock(com.yuewei.plm.module.pilot.PilotAccessService.class);var store=mock(DevelopmentWorkflowStore.class);
        var suppliers=mock(com.yuewei.plm.module.partner.SupplierProfileService.class);
        doThrow(new com.yuewei.plm.common.exception.BusinessException(40103,"没有供应商查看权限")).when(access).requirePermission("supplier:view");
        var assignments=List.of(Map.of("mode","supplier","supplierCode","PRIVATE-SUPPLIER","scope","保密部件范围","basis","保密采用依据"),Map.of("mode","internal","internalName","工程车间","scope","组装"));
        var state=Map.<String,Object>of("plan",Map.of("method","supplier","supplierAssignments",assignments));
        var original=new ObjectMapper().valueToTree(state);
        when(store.load(42L)).thenReturn(state);
        var redaction=new EngineeringBasisService(jdbc,access,suppliers);
        var detail=new HashMap<String,Object>();detail.put("workflow",redaction.redact(state));
        var flow=new LaunchDevelopmentWorkflow(jdbc,new ObjectMapper(),access,store,mock(com.yuewei.plm.module.collaboration.MoldScheduleService.class),mock(com.yuewei.plm.module.development.DevelopmentFinanceService.class),mock(LaunchReviewWorkflow.class),mock(com.yuewei.plm.module.moldreference.PhoneModelReferenceService.class));
        flow.enrich(42L,detail);
        assertThat(detail.get("plan")).isEqualTo(DevelopmentWorkflowStore.map(detail.get("workflow")).get("plan"));
        assertThat(detail.get("plan").toString()).doesNotContain("PRIVATE-SUPPLIER","保密部件范围","保密采用依据").contains("suppliersRestricted=true","工程车间");
        assertThat((com.fasterxml.jackson.databind.JsonNode)new ObjectMapper().valueToTree(state)).isEqualTo(original);assertThat(state.toString()).contains("PRIVATE-SUPPLIER","保密采用依据");
        verifyNoInteractions(suppliers);
    }
    @Test void directSampleDraftDoesNotInventReceiptDateOrValidation(){var f=facts();policy.validateSample(1,input("pending",false,"",null),f,state());assertThat(f).containsEntry("planVersion",1).doesNotContainKeys("actualResult","recordDate");}
    @Test void actualPassingSampleRequiresVerificationEvenWhenNoMoldIsNeeded(){var f=facts();assertThatThrownBy(()->policy.validateSample(1,input("passed",true,"",LocalDate.now()),f,state())).hasMessageContaining("实际验证说明");f.put("actualResult","试装、按键实测通过");policy.validateSample(1,input("passed",true,"",LocalDate.now()),f,state());assertThat(f).doesNotContainKeys("moldSetId","applicationId","producedQty");}
    @Test void failedSampleNeedsRealIssuesAndCannotBeAnEmptyPassingRecord(){var f=facts();assertThatThrownBy(()->policy.validateSample(1,input("failed",true,"",LocalDate.now()),f,state())).hasMessageContaining("样品问题");policy.validateSample(1,input("failed",true,"按键偏硬",LocalDate.now()),f,state());}
    @Test void futureReceiptAndOutOfScopePhonesCannotConfirm(){var f=facts();assertThatThrownBy(()->policy.validateSample(1,input("passed",true,"",LocalDate.now().plusDays(1)),f,state())).hasMessageContaining("真实收样");f.put("phoneModelIds",List.of(11L));assertThatThrownBy(()->policy.validateSample(1,input("pending",false,"",null),f,state())).hasMessageContaining("计划范围");}
    @Test void documentationGapsDependOnDevelopmentPathAndExactSelectedVersions(){assertThat(policy.releaseGaps(state(),List.of())).extracting("code").containsExactly("RELEASE_CUSTOMER_CONFIRM","RELEASE_TESTING");assertThat(policy.releaseGaps(Map.of("plan",Map.of("method","own_mold")),List.of())).hasSize(6);assertThat(policy.releaseGaps(state(),List.of(Map.of("type","file","category","customer_confirm"),Map.of("type","file","category","testing")))).isEmpty();}
    @Test void newFlowDefinitionsDoNotReplaceTheStandardModelWorkflow(){var provider=new com.yuewei.plm.module.project.service.TimelineDefinitionProvider();var p=new com.yuewei.plm.repository.entity.Product();p.setProductType("product_line");p.setDevelopmentMode("launch_v1");assertThat(provider.getDefinitions(p)).extracting("nodeName").containsExactly("立项","画3D产品图","3D打样","样品确认","模具设计","开模","试产","发模");p.setDevelopmentMode(null);assertThat(provider.getDefinitions(p)).hasSize(22);}
    @Test void aConfirmedDesignPlanNeedsRealScopeAndMethodButCanLeaveTrialDatesUnknown(){
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var access=mock(com.yuewei.plm.module.pilot.PilotAccessService.class);var actor=mock(com.yuewei.plm.common.security.CurrentUser.class);
        when(actor.displayName()).thenReturn("工程师");when(access.actor()).thenReturn(actor);
        when(jdbc.queryForList(startsWith("select m.id"),eq(10L))).thenReturn(List.of(Map.of("phoneModelId",10L)));
        var references=mock(com.yuewei.plm.module.moldreference.PhoneModelReferenceService.class);when(references.members(List.of(10L),true)).thenReturn(Map.of(10L,Map.of("phoneModelId",10L,"modelName","代表手机","brandName","品牌")));
        var flow=new LaunchDevelopmentWorkflow(jdbc,new ObjectMapper(),access,mock(DevelopmentWorkflowStore.class),mock(com.yuewei.plm.module.collaboration.MoldScheduleService.class),mock(com.yuewei.plm.module.development.DevelopmentFinanceService.class),mock(LaunchReviewWorkflow.class),references);
        var state=new HashMap<String,Object>();state.put("planDraft",Map.of("phoneModelIds",List.of(10L),"method","own_mold","needsMold",true));flow.confirmPlan(1L,state,mock(ProductDevelopmentService.class));
        assertThat(DevelopmentWorkflowStore.map(state.get("plan"))).doesNotContainKeys("pilotDate","launchDate");
    }

}

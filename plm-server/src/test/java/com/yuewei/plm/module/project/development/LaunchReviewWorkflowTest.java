package com.yuewei.plm.module.project.development;

import java.util.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LaunchReviewWorkflowTest {
    @Test void explicit3dRenderIsAProductDesignBasisButNotAT0ManufacturingDrawing(){
        var render=Map.<String,Object>of("type","file","id",31L,"familyId",31L,"category","render_3d","fileExt","pdf","versionNo","V1");
        var row=new HashMap<String,Object>(Map.of("checklist",Map.of("structure",true,"assembly",true,"appearance",true),"materialBasis","PC背板与TPU包边","references",List.of(render)));
        assertThat(LaunchReviewWorkflow.passingReviewChecks("design",row,true)).allMatch(DevelopmentRules.ReadinessCheck::complete);
        assertThatThrownBy(()->DevelopmentRules.requireChecks(LaunchReviewWorkflow.passingReviewChecks("t0",row,true))).hasMessageContaining("T0评审请采用具体图纸");
        row.put("references",List.of(Map.of("type","file","id",32L,"category","other","fileExt","pdf")));
        assertThatThrownBy(()->DevelopmentRules.requireChecks(LaunchReviewWorkflow.passingReviewChecks("design",row,true))).hasMessageContaining("工程师指定的3D效果图");
    }
    @Test void adoptedRenderChangesUseTheOriginalVersionReviewWithoutReplacingFormalDrawingGaps(){
        var service=mock(ProductDevelopmentService.class,CALLS_REAL_METHODS);
        var original=Map.<String,Object>of("type","file","id",31L,"familyId",31L,"category","render_3d","fileExt","png","versionNo","V1");
        var revised=Map.<String,Object>of("type","file","id",32L,"familyId",31L,"category","render_3d","fileExt","png","versionNo","V2");
        service.sameTechnical(List.of(original),List.of(original));service.validateReleaseVersions(List.of(original),List.of(original),Map.of());
        assertThatThrownBy(()->service.sameTechnical(List.of(original),List.of(revised))).hasMessageContaining("补充对应验证");
        assertThatThrownBy(()->service.validateReleaseVersions(List.of(original),List.of(revised),Map.of())).hasMessageContaining("补充对应验证");
        service.validateReleaseVersions(List.of(original),List.of(revised),Map.of("documentationOnly",true,"documentationReason","效果图标注更正，采用结构未改变"));
        var flow=mock(LaunchDevelopmentWorkflow.class,CALLS_REAL_METHODS);
        var gap=flow.releaseGaps(Map.of("plan",Map.of("method","own_mold")),List.of(original));
        assertThat(gap).extracting("code").contains("RELEASE_DRAWING","RELEASE_BOM","RELEASE_PROCESS");
    }
    @Test void designEvidenceChecklistUsesThePassingReviewRulesWithoutRequiringProductionData(){
        var row=new HashMap<String,Object>();row.put("checklist",Map.of("structure",true,"assembly",true,"manufacturability",true));
        var missing=LaunchReviewWorkflow.passingReviewChecks("design",row,true).stream().filter(c->!c.complete()).toList();
        assertThat(missing).extracting(DevelopmentRules.ReadinessCheck::code).containsExactly("MATERIAL_BASIS","DESIGN_DRAWING");
        assertThatThrownBy(()->DevelopmentRules.requireChecks(LaunchReviewWorkflow.passingReviewChecks("design",row,true))).hasMessage(missing.get(0).message());
        row.put("materialBasis","PC背板与TPU包边");row.put("references",List.of(Map.of("type","file","id",1,"category","drawing","status","draft")));
        DevelopmentRules.requireChecks(LaunchReviewWorkflow.passingReviewChecks("design",row,true));
        assertThat(LaunchReviewWorkflow.passingReviewChecks("design",Map.of("checklist",row.get("checklist")),false)).allMatch(DevelopmentRules.ReadinessCheck::complete);
    }
    Map<String,Object> pilotSetup(){return new HashMap<>(Map.of("plannedQty",1000,"minYieldRate",98,"fullInspection",true));}
    Map<String,Object> pilot(){return new HashMap<>(Map.of("producedQty",1000,"inspectedQty",1000,"defectQty",10,"defectSummary","外观10件，返工复检"));}
    @Test void missingYieldThresholdCannotPassAndZeroDefectsMustBeExplicit(){var setup=pilotSetup();setup.remove("minYieldRate");assertThatThrownBy(()->LaunchReviewWorkflow.validatePilot(setup,pilot())).hasMessageContaining("良率标准");var facts=pilot();facts.remove("defectQty");assertThatThrownBy(()->LaunchReviewWorkflow.validatePilot(pilotSetup(),facts)).hasMessageContaining("不良数量");facts.put("defectQty",0);LaunchReviewWorkflow.validatePilot(pilotSetup(),facts);assertThat(facts.get("yieldRate").toString()).isEqualTo("100.00000000");}
    @Test void enforcesPlannedThousandAndFullInspectionAndActualYield(){var f=pilot();f.put("producedQty",999);f.put("inspectedQty",999);assertThatThrownBy(()->LaunchReviewWorkflow.validatePilot(pilotSetup(),f)).hasMessageContaining("计划要求");f.put("producedQty",1000);assertThatThrownBy(()->LaunchReviewWorkflow.validatePilot(pilotSetup(),f)).hasMessageContaining("全检");f.put("inspectedQty",1000);f.put("defectQty",21);assertThatThrownBy(()->LaunchReviewWorkflow.validatePilot(pilotSetup(),f)).hasMessageContaining("未达到");f.put("defectQty",20);LaunchReviewWorkflow.validatePilot(pilotSetup(),f);assertThat(f.get("yieldRate").toString()).isEqualTo("98.00000000");}
    @Test void rejectsImpossibleFractionalOrInconsistentPilotQuantities(){var f=pilot();f.put("inspectedQty",1001);assertThatThrownBy(()->LaunchReviewWorkflow.validatePilot(pilotSetup(),f)).hasMessageContaining("不能超过生产");f.put("inspectedQty",1000);f.put("defectQty",1001);assertThatThrownBy(()->LaunchReviewWorkflow.validatePilot(pilotSetup(),f)).hasMessageContaining("不能超过检验");f.put("defectQty",0.5);assertThatThrownBy(()->LaunchReviewWorkflow.validatePilot(pilotSetup(),f)).hasMessageContaining("整数");}
    Map<String,Object> test(){return new HashMap<>(Map.of("code","magnetic","result","passed","actual","拉力7.1N，充电正常","method","拉力计","sampleQty",5,"tester","测试工程师","date",LocalDate.now().toString(),"measuredValue",7.1));}
    Map<String,Object> requirements(){return Map.of("tests",List.of(Map.of("code","magnetic","name","磁吸","requirement","至少7N","minValue",7)));}
    @Test void allRequiredTestsNeedActualEvidenceAndNumericMinimum(){var f=new HashMap<String,Object>();f.put("tests",List.of(test()));LaunchReviewWorkflow.validateTests(requirements(),f);var t=test();t.put("measuredValue",6.9);f.put("tests",List.of(t));assertThatThrownBy(()->LaunchReviewWorkflow.validateTests(requirements(),f)).hasMessageContaining("未达到");t.put("measuredValue",7.1);t.remove("actual");assertThatThrownBy(()->LaunchReviewWorkflow.validateTests(requirements(),f)).hasMessageContaining("实际结果");}
    @Test void testTickBoxesAloneAndMissingDatesCannotPass(){var t=test();t.put("result","pending");assertThatThrownBy(()->LaunchReviewWorkflow.validateTests(requirements(),Map.of("tests",List.of(t)))).hasMessageContaining("尚未通过");t.put("result","passed");t.put("date",LocalDate.now().plusDays(1).toString());assertThatThrownBy(()->LaunchReviewWorkflow.validateTests(requirements(),Map.of("tests",List.of(t)))).hasMessageContaining("晚于今天");assertThatThrownBy(()->LaunchReviewWorkflow.validateTests(requirements(),Map.of("tests",List.of()))).hasMessageContaining("缺少测试项目");}
    @Test void designReviewRequiresCurrentPhonePlanSampleAndTechnicalReferences(){
        var service=mock(ProductDevelopmentService.class);var policy=new LaunchReviewWorkflow(mock(org.springframework.jdbc.core.JdbcTemplate.class),mock(com.yuewei.plm.module.pilot.PilotAccessService.class),mock(DevelopmentWorkflowStore.class));
        var state=new HashMap<String,Object>();state.put("plan",Map.of("phoneModelIds",List.of(10L),"method","own_mold","needsMold",true));state.put("requirementVersion",1);
        var row=new HashMap<String,Object>(Map.of("id",1,"gate","design","status","confirmed","result","passed","sampleRoundId",11L,"requirementVersion",1,"revisionNo",0,"planBasis",LaunchReviewWorkflow.planBasis(state),"references",List.of()));state.put("reviewRecords",List.of(row));
        when(service.compatibleRequirement(state,1)).thenReturn(true);when(service.snapshotReferences(anyLong(),any())).thenReturn(List.of());
        assertThat(policy.requireDesign(1,state,Map.of("roundId",11L,"references",List.of()),service)).isSameAs(row);
        state.put("plan",Map.of("phoneModelIds",List.of(12L),"method","own_mold","needsMold",true));assertThatThrownBy(()->policy.requireDesign(1,state,Map.of("roundId",11L),service)).hasMessageContaining("设计评审");
    }
    @Test void trialRequirementsCannotBeWrittenDuringApplicationOrSampleValidation(){
        for(int stage:List.of(1,2))assertThatThrownBy(()->LaunchReviewWorkflow.stageSetup(stage,Map.of(),pilotSetup())).hasMessageContaining("小批量试产");
        assertThatThrownBy(()->LaunchReviewWorkflow.stageSetup(6,Map.of(),pilotSetup())).hasMessageContaining("试产数量");
        var testSetup=LaunchReviewWorkflow.stageSetup(6,Map.of(),requirements());assertThat(testSetup).doesNotContainKeys("plannedQty","minYieldRate","fullInspection");
        var state=Map.<String,Object>of("reviewSetup",testSetup);var trial=LaunchReviewWorkflow.stageSetup(7,state,pilotSetup());
        assertThat(trial.get("tests")).isEqualTo(testSetup.get("tests"));assertThat(trial.get("testVersion")).isEqualTo(testSetup.get("testVersion"));assertThat(trial.get("pilotVersion")).isEqualTo(1L);
        assertThatThrownBy(()->LaunchReviewWorkflow.stageSetup(7,state,Map.of("tests",List.of()))).hasMessageContaining("返回样品验证");
    }
    @Test void trialInspectionAndTestSampleCountsMustBeExplicit(){
        var setup=pilotSetup();setup.remove("fullInspection");assertThatThrownBy(()->LaunchReviewWorkflow.validatePilot(setup,pilot())).hasMessageContaining("检验要求");
        var t=test();t.put("sampleQty",1.5);assertThatThrownBy(()->LaunchReviewWorkflow.validateTests(requirements(),Map.of("tests",List.of(t)))).hasMessageContaining("正整数");
    }
    @Test void voidedSamplesAndReviewsAreNotReusableAndPublishedRevisionIdentityPersists(){
        assertThat(LaunchReviewWorkflow.roundVoided(Map.of("voidedRounds",Map.of("12",Map.of("reason","样品错误"))),12)).isTrue();
        assertThat(LaunchReviewWorkflow.revisionNo(Map.of("lastReviewRevisionNo",2))).isEqualTo(2);
        assertThat(LaunchReviewWorkflow.revisionNo(Map.of("lastReviewRevisionNo",2,"revision",Map.of("revisionNo",3,"technical",true)))).isEqualTo(3);
    }
    @Test void unchangedCriteriaCanBeSavedAgainWithoutInventingAModificationOrInvalidatingEvidence(){
        var setup=LaunchReviewWorkflow.stageSetup(6,Map.of(),requirements());
        var state=Map.<String,Object>of("reviewSetup",setup,"requirement",Map.of("productName","透明壳"));
        assertThat(LaunchReviewWorkflow.stageSetup(6,state,requirements())).isEqualTo(setup);
        var changed=new HashMap<String,Object>(requirements());changed.put("tests",List.of(Map.of("code","magnetic","name","磁吸","requirement","至少8N","minValue",8)));
        assertThatThrownBy(()->LaunchReviewWorkflow.stageSetup(6,state,changed)).hasMessageContaining("修改原因");
    }
    @Test void noMoldPathCarriesAcrossDesignRefinementButNotANewScopeOrTechnicalRevision(){
        var policy=new LaunchReviewWorkflow(mock(org.springframework.jdbc.core.JdbcTemplate.class),mock(com.yuewei.plm.module.pilot.PilotAccessService.class),mock(DevelopmentWorkflowStore.class));
        var state=new HashMap<String,Object>();state.put("plan",Map.of("phoneModelIds",List.of(10L),"method","direct","needsMold",false));
        var path=Map.<String,Object>of("designReviewId",1,"planBasis",LaunchReviewWorkflow.planBasis(state),"revisionNo",0,"reason","供应商成品");state.put("noMold",path);
        assertThat(policy.requireNoMold(state,Map.of("id",2))).isEqualTo(path);
        state.put("revision",Map.of("revisionNo",1,"technical",true));assertThatThrownBy(()->policy.requireNoMold(state,Map.of("id",2))).hasMessageContaining("重新核对");
        state.remove("revision");state.put("plan",Map.of("phoneModelIds",List.of(11L),"method","direct","needsMold",false));assertThatThrownBy(()->policy.requireNoMold(state,Map.of("id",2))).hasMessageContaining("重新核对");
    }
    @Test void productionInstructionsFirstCreatedForTrialDoNotDemandAnEarlierSampleButChangesStillDo(){
        var service=mock(ProductDevelopmentService.class,CALLS_REAL_METHODS);
        var bom=Map.<String,Object>of("type","bom","id",1,"content",Map.of("material","PC"));
        var sop=Map.<String,Object>of("type","file","id",2,"category","sop","familyId",20,"versionNo","V1");
        var trial=List.of(bom,sop);
        service.sameTechnical(List.of(bom),trial,true);
        assertThatThrownBy(()->service.sameTechnical(List.of(bom),trial)).hasMessageContaining("补充对应验证");
        var replacement=Map.<String,Object>of("type","file","id",3,"category","sop","familyId",20,"versionNo","V2");
        assertThatThrownBy(()->service.sameTechnical(trial,List.of(bom,replacement),true)).hasMessageContaining("补充对应验证");
        var changed=Map.<String,Object>of("type","bom","id",1,"content",Map.of("material","TPU"));
        assertThatThrownBy(()->service.sameTechnical(List.of(bom),List.of(changed,sop),true)).hasMessageContaining("补充对应验证");
        service.validateReleaseVersions(trial,trial,Map.of());
        assertThatThrownBy(()->service.validateReleaseVersions(trial,List.of(bom,replacement),Map.of())).hasMessageContaining("补充对应验证");
    }

    @Test void reviewAndHandoverCannotBeBackdatedBeforeTheirActualSourceWasAvailable(){
        var source=Map.<String,Object>of("recordDate","2026-10-16");
        assertThatThrownBy(()->LaunchReviewWorkflow.sourceAvailable("2026-10-15",source)).hasMessageContaining("不能早于来源");
        LaunchReviewWorkflow.sourceAvailable("2026-10-16",source);LaunchReviewWorkflow.sourceAvailable("2026-10-17",source);
    }

}

package com.yuewei.plm.module.project.development;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DevelopmentRulesTest {
    @Test void product3dRequiresAnExplicitFilePurposeAndSupportedFormatWithoutReclassifyingImages(){
        var nativeFile=Map.<String,Object>of("type","file","category","drawing","fileExt",".STEP");
        assertThat(DevelopmentRules.product3dSource(nativeFile)).isTrue();assertThat(DevelopmentRules.product3dRender(nativeFile)).isFalse();
        for(String ext:List.of("png","JPG","jpeg","webp","pdf")){
            var render=Map.<String,Object>of("type","file","category","render_3d","fileExt",ext);
            assertThat(DevelopmentRules.product3dRender(render)).as(ext).isTrue();assertThat(DevelopmentRules.product3dSource(render)).isFalse();assertThat(DevelopmentRules.product3dDrawing(render)).isTrue();assertThat(DevelopmentRules.technicalReference(render)).isTrue();
            assertThat(DevelopmentRules.requirementReferences(List.of(render))).isEmpty();
        }
        assertThat(DevelopmentRules.product3dDrawing(Map.of("type","file","category","drawing","fileExt","png"))).isFalse();
        assertThat(DevelopmentRules.product3dDrawing(Map.of("type","file","category","sample_image","fileExt","jpg"))).isFalse();
        assertThat(DevelopmentRules.product3dDrawing(Map.of("type","file","category","other","fileExt","step"))).isFalse();
        assertThat(DevelopmentRules.product3dDrawing(Map.of("type","file","category","render_3d","fileExt","step"))).isFalse();
        assertThat(DevelopmentRules.product3dDrawing(Map.of("type","bom","category","render_3d","fileExt","png"))).isFalse();
    }
    @Test void followupChecksAreVisibleButOnlyBlockingFailuresStopTheOriginalWriteGate(){
        var old=new DevelopmentRules.ReadinessCheck("OWNER","负责人","owner",false,"请选择负责人");
        var later=DevelopmentRules.check("DRAWING","3D图","drawings",false,()->{throw DevelopmentWorkflowStore.invalid("3D图可后补");});
        assertThat(old.blocking()).isTrue();assertThat(old.satisfied()).isFalse();
        assertThat(later.complete()).isFalse();assertThat(later.blocking()).isFalse();assertThat(later.satisfied()).isTrue();
        assertThatCode(()->DevelopmentRules.requireChecks(List.of(later))).doesNotThrowAnyException();
        assertThatThrownBy(()->DevelopmentRules.requireChecks(List.of(later,old))).hasMessage("请选择负责人");
        var json=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(later);
        assertThat(json.path("blocking").booleanValue()).isFalse();assertThat(json.path("complete").booleanValue()).isFalse();
    }
    @Test void incompleteRequirementsExposeEveryMissingFieldAndTheWriteGateUsesTheSameFailure(){
        var profile=Map.<String,Object>of("productName","透甲2.0");
        var missing=DevelopmentRules.requirementChecks(profile,false).stream().filter(c->!c.complete()).toList();
        assertThat(missing).extracting(DevelopmentRules.ReadinessCheck::code).containsExactly("REQUIREMENTS","OWNER");
        assertThatThrownBy(()->DevelopmentRules.requirement(profile,false)).hasMessage(missing.get(0).message());
        assertThat(DevelopmentRules.requirementChecks(Map.of("productName","透甲2.0","requirements","透明PC","ownerUserId",7),false)).allMatch(DevelopmentRules.ReadinessCheck::complete);
    }
    Map<String,Object> facts(){var f=new HashMap<String,Object>();f.put("phoneModelIds",List.of(1L));f.put("sourceRoundId",1L);f.put("actualResult","实际检验完成");f.put("inspectionMethod","抽检");f.put("producedQty",100);f.put("inspectedQty",20);f.put("defectQty",2);return f;}
    DevelopmentModels.RoundInput input(String kind,String result,boolean confirm){return new DevelopmentModels.RoundInput(kind,LocalDate.of(2026,10,15),"实际轮次",result,"","","",List.of(),confirm);}
    @Test void unchangedDesignDoesNotBecomeAChangeAfterJsonReloadOrConfirmationMetadata(){
        var confirmed=new java.util.HashMap<String,Object>(Map.of("productName","透甲","requirements","透明PC","phoneModelIds",List.of(2),"ownerUserId",1,"targetCost",new java.math.BigDecimal("18.2700"),"confirmedAt","2026-10-16","requirementVersion",1,"references",List.of(Map.of("type","bom","id",99,"versionNo","V1"))));
        var draft=new java.util.HashMap<String,Object>(Map.of("productName","透甲","requirements","透明PC","phoneModelIds",List.of(2L),"ownerUserId",1L,"targetCost",18.27,"references",List.of(Map.of("type","bom","id",99L))));
        assertThat(DevelopmentRules.sameRequirementDraft(confirmed,draft)).isTrue();draft.put("requirements","加厚PC");assertThat(DevelopmentRules.sameRequirementDraft(confirmed,draft)).isFalse();
        var old=Map.<String,Object>of("plan",Map.of("phoneModelIds",List.of(2),"method","own_mold","needsMold",true));
        var next=Map.<String,Object>of("plan",Map.of("phoneModelIds",List.of(2L),"method","own_mold","needsMold",true));
        assertThat(LaunchReviewWorkflow.planBasis(old)).isEqualTo(LaunchReviewWorkflow.planBasis(next));
    }
    @Test void incompleteRoundCanRemainDraftWithoutInventingFacts(){DevelopmentRules.round(input("pilot","pending",false),new HashMap<>());}
    @Test void pendingPhoneReasonCanConfirmInitiationButDoesNotInventAnIdentity(){DevelopmentRules.requirement(Map.of("productName","防摔壳","requirements","改善按键","pendingReason","等待新机尺寸","ownerUserId",7));}
    @Test void confirmsARealFailedTestWithoutAllowingAPendingConclusion(){var f=facts();f.put("testContent","按键手感");DevelopmentRules.round(input("test","failed",true),f);assertThatThrownBy(()->DevelopmentRules.round(input("test","pending",true),f)).hasMessageContaining("通过或未通过");}
    @Test void validatesInspectedCountsAgainstProductionWithoutAssumingUninspectedUnitsPassed(){var f=facts();DevelopmentRules.quantities(f);f.put("inspectedQty",101);assertThatThrownBy(()->DevelopmentRules.quantities(f)).hasMessageContaining("超过生产");f.put("inspectedQty",20);f.put("defectQty",21);assertThatThrownBy(()->DevelopmentRules.quantities(f)).hasMessageContaining("超过检验");}
    @Test void rejectsFractionalAndNegativeActualCounts(){var f=facts();f.put("producedQty",100.5);assertThatThrownBy(()->DevelopmentRules.quantities(f)).hasMessageContaining("整数");f.put("producedQty",100);f.put("defectQty",-1);assertThatThrownBy(()->DevelopmentRules.quantities(f)).hasMessageContaining("非负整数");}
    @Test void passingConclusionCannotCoexistWithBlockingIssues(){var f=facts();f.put("blockingIssues",true);assertThatThrownBy(()->DevelopmentRules.round(input("pilot","passed",true),f)).hasMessageContaining("仍有影响");DevelopmentRules.round(input("pilot","failed",true),f);}
    @Test void actualScopeMustBeCoveredByTheSelectedSource(){DevelopmentRules.covers(Map.of("phoneModelIds",List.of(1,2)),Map.of("phoneModelIds",List.of(1)));assertThatThrownBy(()->DevelopmentRules.covers(Map.of("phoneModelIds",List.of(1)),Map.of("phoneModelIds",List.of(1,2)))).hasMessageContaining("超出来源");}
    @Test void concurrentOrUnversionedWritesCannotOverwriteAnExistingDraft(){
        var store=new DevelopmentWorkflowStore(org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),new com.fasterxml.jackson.databind.ObjectMapper());
        assertThatThrownBy(()->store.check(Map.of("version",2L),1L)).hasMessageContaining("重新载入");
        assertThatThrownBy(()->store.check(Map.of("version",2L),null)).hasMessageContaining("刷新新版页面");
        store.check(Map.of("version",2L),2L);
    }
    @Test void onlyCurrentStageCanWriteItsOwnRecordsAndActions(){
        DevelopmentRules.atStage(null,Map.of(),1);
        for(var kind:List.of("sample","test","pilot")){
            int stage=DevelopmentRules.roundStage(kind);
            DevelopmentRules.atStage(stage,Map.of(),stage);
            assertThatThrownBy(()->DevelopmentRules.atStage(stage-1,Map.of(),stage)).hasMessageContaining("当前不在");
            assertThatThrownBy(()->DevelopmentRules.atStage(stage+1,Map.of(),stage)).hasMessageContaining("先退回");
        }
    }
    @Test void revisionStageOverridesThePreviouslyReleasedProductStage(){
        var state=Map.<String,Object>of("revision",Map.of("stage",2,"technical",true));
        DevelopmentRules.atStage(5,state,2);
        assertThatThrownBy(()->DevelopmentRules.atStage(5,state,5)).hasMessageContaining("当前不在");
        assertThatThrownBy(()->DevelopmentRules.atStage(5,state,1)).hasMessageContaining("先退回");
    }
    @Test void stoppedPilotCanRecordTheFailureWithoutMakingUpProductionAndInspection(){
        var partial=new HashMap<String,Object>(Map.of("phoneModelIds",List.of(1L),"sourceRoundId",7L,"actualResult","试产停机，尚未出件；检验尚未开始","producedQty",0));
        DevelopmentRules.round(input("pilot","failed",true),partial);assertThat(partial).doesNotContainKeys("inspectedQty","defectQty");
        assertThatThrownBy(()->DevelopmentRules.round(input("pilot","passed",true),partial)).hasMessageContaining("必须大于0");
        partial.put("inspectedQty",2);assertThatThrownBy(()->DevelopmentRules.round(input("pilot","failed",true),partial)).hasMessageContaining("超过生产");
    }

    @Test void addingReviewedEngineeringFilesDoesNotChangeProductRequirementsButChangedSourceSpecsDo(){
        var confirmed=new HashMap<String,Object>(Map.of("productName","透明壳","requirements","防摔方案","ownerUserId",7,"phoneModelIds",List.of(1),"references",List.of(Map.of("type","file","id",10,"category","other"))));
        var draft=new HashMap<>(confirmed);draft.put("references",List.of(Map.of("type","file","id",10,"category","other"),Map.of("type","bom","id",20),Map.of("type","file","id",21,"category","drawing")));
        assertThat(DevelopmentRules.sameRequirementDraft(confirmed,draft)).isTrue();
        draft.put("references",List.of(Map.of("type","file","id",11,"category","other"),Map.of("type","file","id",21,"category","drawing")));
        assertThat(DevelopmentRules.sameRequirementDraft(confirmed,draft)).isFalse();
        draft.put("references",confirmed.get("references"));draft.put("requirements","新增防水要求");assertThat(DevelopmentRules.sameRequirementDraft(confirmed,draft)).isFalse();
    }

}

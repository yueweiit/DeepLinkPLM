package com.yuewei.plm.module.moldreference;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PhoneModelCoverageServiceTest {
    Map<String,Object> physical() {var row=new LinkedHashMap<String,Object>();row.putAll(Map.of("moldSetId",10L,"phoneStatus","enabled","brandStatus","enabled","codeItemId",1L,"codeStatus","enabled","codeDeletedFlag",0,"pcCode","MHA201144","tpuCode","MHA101144"));return row;}
    @Test void approvalCreatesAPlanRatherThanClaimingMakingOrPhysicalStock() {assertThat(PhoneModelCoverageService.applicationState(Map.of("status","submitted","approvalGranted",true,"executionStatus","pending","provisioningStatus","ready"))).isEqualTo("planned");}
    @Test void draftPendingPausedTerminatedAndBlockedRemainSeparate() {
        assertThat(PhoneModelCoverageService.applicationState(Map.of("status","draft"))).isEqualTo("draft");
        assertThat(PhoneModelCoverageService.applicationState(Map.of("status","submitted","approvalGranted",false))).isEqualTo("applying");
        assertThat(PhoneModelCoverageService.applicationState(Map.of("status","converted","executionStatus","paused"))).isEqualTo("paused");
        assertThat(PhoneModelCoverageService.applicationState(Map.of("status","completed","remoteResult","refuse","approvalGranted",true))).isEqualTo("terminated");
        assertThat(PhoneModelCoverageService.applicationState(Map.of("status","submitted","approvalGranted",true,"provisioningStatus","blocked"))).isEqualTo("needs_review");
        assertThat(PhoneModelCoverageService.applicationState(Map.of("status","converted","approvalGranted",true,"businessStatus","rejected","provisioningStatus","ready"))).isEqualTo("terminated");
    }
    @Test void partialFitIsSeparateFromPartialPhysicalStock() {
        var row=physical();assertThat(PhoneModelCoverageService.moldState(row)).isEqualTo("planned");assertThat(PhoneModelCoverageService.partState(row,"pc")).isEqualTo("unknown");
        row.put("pcPhysicalStatus","confirmed");assertThat(PhoneModelCoverageService.moldState(row)).isEqualTo("confirmed");
        row.put("pcConfirmed",true);row.put("pcEvidence","PC样机核对");assertThat(PhoneModelCoverageService.moldState(row)).isEqualTo("partial");
        row.put("tpuPhysicalStatus","making");assertThat(PhoneModelCoverageService.moldState(row)).isEqualTo("partial");
    }
    @Test void onlyExplicitWholeSetCompatibilityAndRealBothPartsCanBeReusable() {
        var row=physical();row.put("pcPhysicalStatus","confirmed");row.put("tpuPhysicalStatus","confirmed");assertThat(PhoneModelCoverageService.moldState(row)).isEqualTo("confirmed");
        row.putAll(Map.of("compatibilityConfirmed",true,"pcConfirmed",true,"tpuConfirmed",true,"pcEvidence","PC样机核对","tpuEvidence","TPU样机核对"));assertThat(PhoneModelCoverageService.moldState(row)).isEqualTo("reusable");
        row.put("tpuEvidence","");assertThat(PhoneModelCoverageService.moldState(row)).isEqualTo("confirmed");
    }
    @Test void originalCodesAndRetiredReferencesStayVisibleButCannotAdvertiseSafeReuse() {
        var row=physical();row.putAll(Map.of("pcPhysicalStatus","confirmed","tpuPhysicalStatus","confirmed","compatibilityConfirmed",true,"pcConfirmed",true,"tpuConfirmed",true,"evidence","已核对","pcCode","1144-PC"));assertThat(PhoneModelCoverageService.moldState(row)).isEqualTo("confirmed");
        row.put("pcCode","MHA201144");row.put("codeStatus","disabled");assertThat(PhoneModelCoverageService.moldState(row)).isEqualTo("confirmed");
    }
    @Test void failedAcceptanceKeepsPhysicalExistenceButBlocksTheSharedReuseGate() {
        var row=physical();row.putAll(Map.of("pcPhysicalStatus","confirmed","tpuPhysicalStatus","confirmed","compatibilityConfirmed",true,"pcConfirmed",true,"tpuConfirmed",true,"pcEvidence","PC核对","tpuEvidence","TPU核对"));
        assertThat(MoldCompatibilityPolicy.reusable(row)).isTrue();
        for(String prefix:List.of("pc","tpu")) {row.put(prefix+"AcceptanceStatus","failed");assertThat(MoldCompatibilityPolicy.reusable(row)).isFalse();assertThat(PhoneModelCoverageService.partState(row,prefix)).isEqualTo("confirmed");assertThat(PhoneModelCoverageService.moldState(row)).isEqualTo("confirmed");row.remove(prefix+"AcceptanceStatus");}
    }
    @Test void archiveOnlyReferencesNeedSetReviewAndAStartDateDoesNotConfirmPhysicalParts() {
        var row=physical();row.remove("moldSetId");assertThat(PhoneModelCoverageService.moldState(row)).isEqualTo("needs_review");row.put("moldSetId",10L);row.put("openedDate","2026-10-14");assertThat(PhoneModelCoverageService.moldState(row)).isEqualTo("making");
    }
    @Test void reviewExplainsMissingApplicableSetWithoutRequestingAnotherPhysicalSet() {
        var row=physical();row.remove("moldSetId");row.put("registeredSets",2);
        assertThat(MoldCompatibilityPolicy.reviewReasons(row)).singleElement().satisfies(reason->assertThat(reason).containsEntry("code","applicable_set_missing").containsEntry("action","select_set"));
        row.put("registeredSets",0);assertThat(MoldCompatibilityPolicy.reviewReasons(row)).singleElement().satisfies(reason->assertThat(reason).containsEntry("action","register_existing"));
    }
    @Test void reviewExplainsTheReuseGateWithoutLosingDistinctPartEvidence() {
        var row=physical();row.putAll(Map.of("pcPhysicalStatus","confirmed","tpuPhysicalStatus","confirmed","pcConfirmed",true,"tpuConfirmed",true,"pcEvidence","PC尺寸核对","tpuEvidence","TPU尺寸核对","compatibilityConfirmed",true));
        assertThat(MoldCompatibilityPolicy.reviewReasons(row)).isEmpty();
        row.put("tpuEvidence","");row.put("pcAcceptanceStatus","failed");row.put("tpuCode","1144-TPU");
        assertThat(MoldCompatibilityPolicy.reviewReasons(row)).extracting(r->r.get("code")).containsExactlyInAnyOrder("tpu_fit_evidence_missing","pc_acceptance_failed","tpu_historical_code");
    }
    @Test void swappedMaterialCodesKeepPhysicalFactsButCannotAuthorizeReuse() {
        var row=physical();row.putAll(Map.of("pcPhysicalStatus","confirmed","tpuPhysicalStatus","confirmed","pcConfirmed",true,"tpuConfirmed",true,"pcEvidence","PC核对","tpuEvidence","TPU核对","compatibilityConfirmed",true,"pcCode","MHA101144","tpuCode","MHA201144"));
        assertThat(MoldCompatibilityPolicy.reusable(row)).isFalse();
        assertThat(MoldCompatibilityPolicy.partState(row,"pc")).isEqualTo("confirmed");
        assertThat(MoldCompatibilityPolicy.reviewReasons(row)).extracting(r->r.get("code")).containsExactlyInAnyOrder("pc_material_mismatch","tpu_material_mismatch");
        assertThat(row).containsEntry("pcCode","MHA101144").containsEntry("tpuCode","MHA201144");
    }
}

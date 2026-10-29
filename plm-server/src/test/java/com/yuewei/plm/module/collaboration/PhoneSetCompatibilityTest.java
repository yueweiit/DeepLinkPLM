package com.yuewei.plm.module.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PhoneSetCompatibilityTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void aPhoneCanFitOnlyPcOfOneSetAndBothPartsOfAnother() throws Exception {
        var rows=PhoneSetCompatibility.normalize(json.readTree("""
          [{"phoneModelId":4,"setCompatibilities":[
            {"moldSetId":12,"pcConfirmed":true,"tpuConfirmed":false,"pcEvidence":"PC尺寸核对","tpuEvidence":null},
            {"moldSetId":13,"pcConfirmed":true,"tpuConfirmed":true,"pcEvidence":"PC样机","tpuEvidence":"TPU样机"}]}]
          """));
        assertThat(rows.get(0)).containsEntry("pcConfirmed",true).containsEntry("tpuConfirmed",false);
        assertThat(rows.get(0).get("setIds").toString()).isEqualTo("[12, 13]");
        assertThat(PhoneSetCompatibility.normalize(json.valueToTree(rows))).isEqualTo(rows);
    }
    @Test void legacyPayloadExpandsOnlyItsExplicitSetsWithoutClaimingMoreFit() throws Exception {
        var rows=PhoneSetCompatibility.normalize(json.readTree("""
          [{"phoneModelId":4,"setIds":[13],"pcConfirmed":true,"tpuConfirmed":true,"evidence":"旧明确适配依据"}]
          """));
        assertThat(rows.get(0).get("setCompatibilities").toString()).contains("moldSetId=13","pcEvidence=旧明确适配依据","tpuEvidence=旧明确适配依据");
    }
    @Test void confirmationRequiresItsOwnEvidenceAndCannotUseAnotherSetsProof() throws Exception {
        var input=json.readTree("""
          [{"phoneModelId":4,"setCompatibilities":[{"moldSetId":12,"pcConfirmed":true,"tpuConfirmed":false,"pcEvidence":null,"tpuEvidence":"仅TPU依据"}]}]
          """);
        assertThatThrownBy(()->PhoneSetCompatibility.normalize(input)).hasMessageContaining("确认部件适配须填写依据");
    }
}

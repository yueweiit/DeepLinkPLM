package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MoldEngineeringPlanTest {
    private final ObjectMapper json=new ObjectMapper();
    private com.fasterxml.jackson.databind.JsonNode plan(String handling,String hole,List<Long> phones) {
        return json.valueToTree(Map.of("handling",handling,"specifications",List.of(Map.of("key","one","holeType",hole,"structureDescription","","phoneModelIds",phones))));
    }
    @Test void anIncompleteDraftDoesNotRequireAPhoneOrPretendItsEngineeringDecisionIsMade() {
        var draft=MoldEngineeringPlan.normalize(json,plan("pending","",List.of()));
        assertThat(MoldEngineeringPlan.phones(draft)).isEmpty();assertThat(draft.has("codePhoneModelId")).isFalse();
        assertThatThrownBy(()->MoldEngineeringPlan.requireHoles(draft,Map.of())).hasMessageContaining("孔型");
        var knownHole=MoldEngineeringPlan.normalize(json,plan("pending","large",List.of()));
        assertThatCode(()->MoldEngineeringPlan.requireHoles(knownHole,Map.of())).doesNotThrowAnyException();
        assertThatThrownBy(()->MoldEngineeringPlan.requireCoding(knownHole)).hasMessageContaining("办理方式待确认");
    }
    @Test void aMultiPhoneScopeDoesNotAutomaticallyPickTheFirstAsManufacturingIdentity() {
        var raw=(com.fasterxml.jackson.databind.node.ObjectNode)plan("new","large",List.of(1218L,1145L,1218L));
        var normalized=MoldEngineeringPlan.normalize(json,raw);assertThat(MoldEngineeringPlan.phones(normalized)).containsExactly(1145L,1218L);
        assertThatThrownBy(()->MoldEngineeringPlan.requireCoding(normalized)).hasMessageContaining("编码基准");
        raw.put("codePhoneModelId",1145L);assertThatCode(()->MoldEngineeringPlan.requireCoding(MoldEngineeringPlan.normalize(json,raw))).doesNotThrowAnyException();
        raw.put("codePhoneModelId",9999L);assertThatThrownBy(()->MoldEngineeringPlan.normalize(json,raw)).hasMessageContaining("本次适用范围");
    }
    @Test void otherHoleRequiresAnActualDescriptionAndReuseDoesNotCreateAnotherApproval() {
        var raw=(com.fasterxml.jackson.databind.node.ObjectNode)plan("new","other",List.of(1218L));
        var draft=MoldEngineeringPlan.normalize(json,raw);assertThatThrownBy(()->MoldEngineeringPlan.requireHoles(draft,Map.of())).hasMessageContaining("结构说明");
        ((com.fasterxml.jackson.databind.node.ObjectNode)raw.path("specifications").get(0)).put("structureDescription","可更换镜头盖结构");assertThatCode(()->MoldEngineeringPlan.requireHoles(MoldEngineeringPlan.normalize(json,raw),Map.of())).doesNotThrowAnyException();
        assertThatThrownBy(()->MoldEngineeringPlan.requireHoles(plan("reuse","large",List.of(1218L)),Map.of())).hasMessageContaining("不发起新的开模审批");
    }
    @Test void anEngineeringCompletionDoesNotOverwriteTheOriginalApprovalScope() {
        var approved=plan("pending","large",List.of());var completed=plan("reuse","large",List.of(1218L));
        var source=json.createObjectNode();source.set("engineeringPlan",approved);var resolution=json.createObjectNode();resolution.set("engineeringPlan",completed);
        var row=Map.<String,Object>of("sourceSnapshot",source,"executionResolution",resolution);
        assertThat(MoldEngineeringPlan.from(row)).isEqualTo(completed);assertThat(source.path("engineeringPlan")).isEqualTo(approved);
        assertThat(MoldEngineeringPlan.includesPhone(row,1218L)).isTrue();
    }
    @Test void overlapIncludesLegacyApplicationsWithoutGuessingTheirMissingHole() {
        var old=Map.<String,Object>of("phoneModelId",1218L,"form",json.valueToTree(Map.of("holeType","大孔Gran agujero")));
        assertThat(MoldEngineeringPlan.overlaps(plan("new","large",List.of(1218L,1145L)),old)).isTrue();
        assertThat(MoldEngineeringPlan.overlaps(plan("new","precision",List.of(1218L)),old)).isFalse();
        assertThat(MoldEngineeringPlan.overlaps(plan("new","large",List.of(999L)),old)).isFalse();
        assertThat(MoldEngineeringPlan.overlaps(plan("new","precision",List.of(1218L)),Map.of("phoneModelId",1218L,"form",json.createObjectNode()))).isTrue();
    }
    @Test void oldPhoneAndSlashTextCannotSubstituteForAnExplicitHoleDecision() {
        assertThatThrownBy(()->MoldEngineeringPlan.requireHoles(null,Map.of("model","IP 17 PRO MAX / 18 PRO MAX 精孔"))).hasMessageContaining("孔型");
        assertThatCode(()->MoldEngineeringPlan.requireHoles(null,Map.of("holeType","大孔"))).doesNotThrowAnyException();
        assertThatCode(()->MoldEngineeringPlan.requireHoles(null,Map.of("holeType","精孔Agujero fino"))).doesNotThrowAnyException();
        assertThatThrownBy(()->MoldEngineeringPlan.requireHoles(null,Map.of("holeType","其他"))).hasMessageContaining("结构说明");
    }
    @Test void anExplicitHoleUsesTheActualMappedTemplateOptionRatherThanChangingTheTemplate() {
        var form=MoldEngineeringPlan.withScope(Map.of(),plan("pending","large",List.of()));
        var controls=List.of(Map.<String,Object>of("key","holeType","id","real-control","options",List.of("大孔Gran agujero","精孔Agujero fino")));
        MoldEngineeringPlan.mapTemplateHole(form,controls);assertThat(form.get("holeType")).isEqualTo("大孔Gran agujero");
        assertThat(controls.get(0).get("id")).isEqualTo("real-control");
        assertThatThrownBy(()->MoldEngineeringPlan.mapTemplateHole(form,List.of(Map.of("key","holeType","options",List.of("新选项"))))).hasMessageContaining("真实选项");
    }
    @Test void specificationsMustRetainDistinctKeysAndAValidRelationVersion() {
        var raw=json.createObjectNode().put("handling","reuse");var specs=raw.putArray("specifications");specs.add(plan("reuse","large",List.of(1218L)).path("specifications").get(0));specs.add(specs.get(0).deepCopy());
        assertThatThrownBy(()->MoldEngineeringPlan.normalize(json,raw)).hasMessageContaining("重复");specs.remove(1);((com.fasterxml.jackson.databind.node.ObjectNode)specs.get(0)).put("manufacturingRelationId",7L);
        assertThatThrownBy(()->MoldEngineeringPlan.normalize(json,raw)).hasMessageContaining("版本");
    }
}

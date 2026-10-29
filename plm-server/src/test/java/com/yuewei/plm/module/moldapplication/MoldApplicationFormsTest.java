package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.yuewei.plm.module.moldapplication.MoldApplicationForms.*;

class MoldApplicationFormsTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void olderSchemaAndLiteralNullAreSafe()throws Exception {
        var detail=json.readTree("{\"form_component_values\":[{\"id\":\"TextField-IH4TZP18\",\"value\":\"OPPO RENO 16F 5G\"},{\"id\":\"TextField_1U6D0QQC4P340\",\"value\":\"null\"}]}");
        assertThat(normalize(detail)).containsEntry("model","OPPO RENO 16F 5G").containsEntry("generatedCodes",null).doesNotContainKey("newModel");
        assertThat(text(json.nullNode())).isNull();assertThat(date("null")).isNull();
        assertThat(date("2026-01-01 00:00:00").getOffset().getTotalSeconds()).isEqualTo(8*3600);
    }
    @Test void runningIsNotApprovalAndTerminationAlwaysWins()throws Exception {
        assertThat(granted(json.readTree("{\"status\":\"RUNNING\",\"result\":\"agree\"}"))).isFalse();
        assertThat(granted(json.readTree("{\"status\":\"RUNNING\",\"operation_records\":[{\"userid\":\"16693147192083157833\",\"operation_type\":\"EXECUTE_TASK_NORMAL\",\"operation_result\":\"AGREE\",\"date\":\"2026-10-13 12:00:00\"}]}"))).isTrue();
        assertThat(granted(json.readTree("{\"status\":\"TERMINATED\",\"operation_records\":[{\"userid\":\"16693147192083157833\",\"operation_type\":\"EXECUTE_TASK_NORMAL\",\"operation_result\":\"AGREE\"}]}"))).isFalse();
        assertThat(granted(json.readTree("{\"status\":\"COMPLETED\",\"result\":\"refuse\"}"))).isFalse();
    }
    @Test void automaticAuthorityNeedsACompleteTaskAndRefusalOverridesAllPriorApprovals()throws Exception {
        var detail=json.createObjectNode().put("status","RUNNING");var task=detail.putArray("tasks").addObject().put("userid",EXECUTION_APPROVER_USER_ID).put("task_status","COMPLETED").put("task_result","AGREE").put("finish_time","2026-10-13 12:00:00");
        assertThat(approvalEvidence(detail).taskAuthorized()).isFalse();
        task.put("taskid","verified-task").put("finish_time","invalid-date");assertThat(approvalEvidence(detail).taskAuthorized()).isFalse();
        task.put("finish_time","2026-10-13 12:00:00");assertThat(approvalEvidence(detail).taskAuthorized()).isTrue();
        detail.withArray("tasks").addObject().put("userid","later-reviewer").put("task_status","COMPLETED").put("task_result","REFUSE").put("taskid","later-refusal").put("finish_time","2026-10-13 13:00:00");
        assertThat(approvalEvidence(detail).taskAuthorized()).isFalse();assertThat(approvalEvidence(detail).authorization()).containsEntry("taskId","verified-task");assertThat(approvalEvidence(detail).exception()).containsEntry("state","rejected");
        var operation=json.readTree("{\"status\":\"RUNNING\",\"operation_records\":[{\"userid\":\"16693147192083157833\",\"operation_type\":\"EXECUTE_TASK_NORMAL\",\"operation_result\":\"APPROVE\",\"date\":\"2026-10-13 12:00:00\"}]}");
        assertThat(approvalEvidence(operation).granted()).isTrue();assertThat(approvalEvidence(operation).taskAuthorized()).isFalse();assertThat(authorization(operation)).containsEntry("evidenceSource","operation").containsKey("operationKey");
    }
    @Test void onlyTheVerifiedApproverCanAuthorizeAcrossTaskNodeVersions()throws Exception {
        for(String node:java.util.List.of("c509_130b","5b6e_39cb")){
            var detail=json.readTree("{\"status\":\"RUNNING\",\"tasks\":[{\"userid\":\"16693147192083157833\",\"taskid\":\"t-1\",\"activity_id\":\""+node+"\",\"task_status\":\"COMPLETED\",\"task_result\":\"AGREE\",\"finish_time\":\"2026-10-13 12:00:00\"}]}");
            assertThat(granted(detail)).isTrue();assertThat(authorization(detail)).containsEntry("activityId",node).containsEntry("approvedAt","2026-10-13 12:00:00");
        }
        assertThat(granted(json.readTree("{\"status\":\"RUNNING\",\"tasks\":[{\"userid\":\"someone-else\",\"task_status\":\"COMPLETED\",\"task_result\":\"AGREE\"}]}"))).isFalse();
        assertThat(granted(json.readTree("{\"status\":\"COMPLETED\",\"result\":\"agree\"}"))).isFalse();
        assertThat(granted(json.readTree("{\"status\":\"RUNNING\",\"tasks\":[{\"userid\":\"16693147192083157833\",\"task_status\":\"RUNNING\",\"task_result\":\"AGREE\"}]}"))).isFalse();
    }
    @Test void publishedControlsUseStableIdsNotTranslatedLabels()throws Exception {
        var schema=json.readTree("{\"schemaContent\":{\"items\":[{\"componentName\":\"DDSelectField\",\"props\":{\"id\":\"DDSelectField-JFX38WE3\",\"label\":\"Tipo 新名称\",\"required\":true,\"options\":[\"{\\\"value\\\":\\\"幻影Fantasía Case\\\"}\"]}}]}}");
        var control=controls(schema,json).get(0);assertThat(control).containsEntry("key","tipo").containsEntry("required",true).containsEntry("label","Tipo 新名称");
        assertThat((java.util.List<?>)control.get("options")).hasSize(1);
    }
    @Test void moldCodeExamplesAreExactAndDoNotAcceptEmbeddedOrShortIdentifiers() {
        var generator=new com.yuewei.plm.common.util.ProductBusinessCodeGenerator();
        assertThat(generator.generateMoldCode("FA","10","1294")).isEqualTo("MFA101294");
        assertThat(generator.generateMoldCode("FA","20","1294")).isEqualTo("MFA201294");
        assertThat(codes("MFA101294 / MFA201294 / MFA101294")).containsExactly("MFA101294","MFA201294");
        assertThat(codes("XMFA101294 / MFA10129 / MFA1012949")).isEmpty();
        assertThat(codes("MBA101199 / MBA201199 / MBA100185")).containsExactly("MBA101199","MBA201199","MBA100185");
        assertThat(hasValidCodes("MBA101199 / MBA201199")).isTrue();
        assertThat(hasValidCodes("MBA10 / MBA201199")).isFalse();
    }
    @Test void photoUrlsNeverEmbedTokensOrTemporarySignatures() {
        assertThat(MoldApplicationDingTalkFiles.durablePhotoUrl("https://static.dingtalk.com/media/photo.png")).isTrue();
        for(String u:java.util.List.of("http://static.dingtalk.com/media/photo.png","https://static.dingtalk.com.evil.test/photo.png","https://static.dingtalk.com/media/photo.png?access_token=secret","https://user@static.dingtalk.com/photo.png","https://bucket.oss-cn-shenzhen.aliyuncs.com/photo.png?Expires=1"))assertThat(MoldApplicationDingTalkFiles.durablePhotoUrl(u)).isFalse();
    }
}

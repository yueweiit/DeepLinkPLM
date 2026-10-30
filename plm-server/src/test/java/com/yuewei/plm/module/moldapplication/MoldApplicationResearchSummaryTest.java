package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MoldApplicationResearchSummaryTest {
    private final ObjectMapper json=new ObjectMapper();
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PilotAccessService access=mock(PilotAccessService.class);
    private final Map<String,Object> raw=new LinkedHashMap<>();
    private MoldApplicationService service;

    @BeforeEach void prepare(){
        var actor=new CurrentUser(7L,"engineer","工程",false);
        when(access.actor()).thenReturn(actor);
        when(access.access()).thenReturn(new PilotAccessService.Access(actor,"domestic",List.of("project:view"),true,false));
        when(access.hasPermission("project:view")).thenReturn(true);
        var props=new MoldApplicationProperties();props.setEnabled(true);
        var repo=new MoldApplicationRepository(jdbc,json);
        service=new MoldApplicationService(repo,props,mock(MoldDingTalkClient.class),mock(MoldApplicationMedia.class),mock(MoldApplicationCodes.class),access,json,mock(PlatformTransactionManager.class),mock(OperationLogService.class),mock(MoldApplicationDingTalkFiles.class),mock(MoldApplicationBackupImport.class));
        raw.putAll(Map.of("applicationId",88L,"productLineId",42L,"projectId",42L,"status","converted","businessStatus","approved","version",3L,"formJson","{}","sourceSnapshotJson","{\"research\":{\"projectId\":42}}"));
        raw.put("executionResolutionJson","{\"researchCoding\":{\"status\":\"ready\",\"materialCodes\":[\"10\"],\"missingFields\":[],\"generatedMoldCodes\":[\"MHA100030\"]}}");
        raw.put("authorizationJson","{\"approverUserId\":\"16693147192083157833\",\"approverName\":\"周汉琴\",\"taskId\":\"task-88\",\"evidenceSource\":\"task\",\"accessToken\":\"private-token\"}");
        raw.put("lastError","upstream token=private-token https://remote.invalid/?Signature=secret");
        when(jdbc.queryForList(startsWith(MoldApplicationRepository.SELECT),eq(42L))).thenAnswer(call->List.of(new LinkedHashMap<>(raw)));
    }

    @Test void summaryRetainsStoppedExecutionAndProjectsOnlySafeAuthorization(){
        for(String state:List.of("paused","terminated")){
            raw.put("executionStatus",state);raw.put("approvalGranted",false);raw.put("businessStatus","terminated");
            var result=service.researchApplications(42L).get(0);
            assertThat(result).containsEntry("executionStatus",state).containsEntry("approvalGranted",false);
            assertThat((JsonNode)result.get("authorization")).isEqualTo(json.valueToTree(Map.of("approverUserId","16693147192083157833","approverName","周汉琴","taskId","task-88","evidenceSource","task")));
            assertThat(((JsonNode)result.get("researchCoding")).path("generatedMoldCodes").get(0).asText()).isEqualTo("MHA100030");
            assertThat(result).doesNotContainKeys("lastError","sourceSnapshot","authorizationJson","form","rawJson");
            assertThat(json.valueToTree(result).toString()).doesNotContain("private-token","Signature=","accessToken");
        }
        verify(access,times(2)).requireProduct(42L);
    }

    @Test void summaryCarriesProvisioningFailureAndKeepsMissingAuthorizationNull(){
        raw.put("executionStatus","active");raw.put("approvalGranted",true);raw.put("provisioningStatus","blocked");raw.put("provisioningError","已有模具套，请核对原申请意图");raw.put("authorizationJson",null);
        var result=service.researchApplications(42L).get(0);
        assertThat(result).containsEntry("provisioningError","已有模具套，请核对原申请意图").containsEntry("executionStatus","active").containsEntry("authorization",null);
        assertThat(((JsonNode)result.get("researchCoding")).path("missingMessages").toString()).contains("已有模具套，请核对原申请意图");
        assertThat(json.valueToTree(result).toString()).doesNotContain("private-token","Signature=","lastError");
    }
}

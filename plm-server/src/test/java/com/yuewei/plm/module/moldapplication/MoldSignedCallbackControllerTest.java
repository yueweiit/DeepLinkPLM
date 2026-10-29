package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MoldSignedCallbackControllerTest {
    final ObjectMapper json=new ObjectMapper();
    final String key=Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);
    MoldApplicationProperties props;MoldApplicationRepository repo;MoldApplicationService service;
    JdbcTemplate jdbc;MoldSignedCallbackController controller;MoldCallbackCrypto crypto;
    @BeforeEach void configure(){props=new MoldApplicationProperties();props.setEnabled(true);props.setReadSyncEnabled(true);props.setCallbackSignatureToken("callback-test-token");props.setCallbackAesKey(key);props.setCallbackOwnerKey("test-corp");repo=mock(MoldApplicationRepository.class);jdbc=mock(JdbcTemplate.class);when(repo.jdbc()).thenReturn(jdbc);service=mock(MoldApplicationService.class);controller=new MoldSignedCallbackController(props,repo,service,json);crypto=new MoldCallbackCrypto(props.getCallbackSignatureToken(),key,"test-corp");}
    Map<String,String> deliver(Map<String,Object> event)throws Exception{var signed=crypto.encrypt(json.writeValueAsString(event));return controller.receive(Map.of("msg_signature",signed.get("msg_signature"),"timeStamp",signed.get("timeStamp"),"nonce",signed.get("nonce")),json.createObjectNode().put("encrypt",signed.get("encrypt")));}
    @Test void urlValidationAcknowledgesEncryptedSuccessWithoutPullingOa()throws Exception{var ack=deliver(Map.of("EventType","check_url"));assertThat(crypto.decrypt(ack.get("msg_signature"),ack.get("timeStamp"),ack.get("nonce"),ack.get("encrypt"))).isEqualTo("success");verifyNoInteractions(jdbc);verify(service,never()).syncInternal(anyString());}
    @Test void formalApprovalEnqueuesBeforeAcknowledgementAndDoesNotConvertOrSynchronizeInline()throws Exception{deliver(Map.of("EventType","bpms_instance_change","processCode",props.getProcessCode(),"processInstanceId","formal-test-instance","type","finish","result","agree"));verify(jdbc).update(startsWith("insert into plm_mold_event_inbox"),anyString(),eq("formal-test-instance"),anyString());verify(service,never()).callback(anyString(),anyString(),anyBoolean());verify(service,never()).syncInternal(anyString());}
    @Test void signedForeignProcessIsIgnoredAndForgedMessagesCannotEnqueue()throws Exception{deliver(Map.of("EventType","bpms_instance_change","processCode","PROC-BD65F530-F66F-46B9-8F72-0567DD68F60C","processInstanceId","old-test-instance"));var e=crypto.encrypt("{}");assertThatThrownBy(()->controller.receive(Map.of("msg_signature","forged","timeStamp",e.get("timeStamp"),"nonce",e.get("nonce")),json.createObjectNode().put("encrypt",e.get("encrypt")))).hasMessageContaining("验证失败");verifyNoInteractions(jdbc);}
    @Test void inboxWorkerUsesTrustedAgreeAndPreservesRetriesWhenOaIsUnavailable()throws Exception{String payload=json.writeValueAsString(Map.of("type","finish","result","agree"));when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("event_key","event-key","instance_id","formal-test-instance","payload",payload,"attempts",1)));when(repo.node(payload)).thenReturn(json.readTree(payload));when(service.callback("formal-test-instance",props.getProcessCode(),true)).thenThrow(new IllegalStateException("offline"));controller.processInbox();verify(service).callback("formal-test-instance",props.getProcessCode(),true);verify(jdbc).update(startsWith("update plm_mold_event_inbox set last_error="),anyLong(),eq("event-key"));verify(jdbc,never()).update(startsWith("update plm_mold_event_inbox set completed_at="),anyString());}
}

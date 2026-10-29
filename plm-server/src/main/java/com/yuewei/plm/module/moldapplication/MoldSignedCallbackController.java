package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import static com.yuewei.plm.module.moldapplication.MoldApplicationForms.*;

@RestController @RequiredArgsConstructor
public class MoldSignedCallbackController {
    private final MoldApplicationProperties props;private final MoldApplicationRepository repo;
    private final MoldApplicationService service;private final ObjectMapper json;
    @PostMapping("/api/v1/integrations/dingtalk/mold-callback")
    public Map<String,String> receive(@RequestParam Map<String,String> query,@RequestBody JsonNode body)throws Exception {
        service.feature();var crypto=new MoldCallbackCrypto(props.getCallbackSignatureToken(),props.getCallbackAesKey(),props.getCallbackOwnerKey());
        String payload=crypto.decrypt(query.getOrDefault("msg_signature",query.get("signature")),query.getOrDefault("timeStamp",query.get("timestamp")),query.get("nonce"),text(body.path("encrypt")));var event=json.readTree(payload);
        String type=event.path("EventType").asText();
        if(!type.equals("check_url")) {
            if(!Set.of("bpms_task_change","bpms_instance_change").contains(type)||!props.getProcessCode().equals(event.path("processCode").asText(event.path("process_code").asText())))return crypto.encrypt("success");
            String instance=text(event.path("processInstanceId"));if(instance==null)throw invalid("回调缺少审批实例ID");
            // Acknowledge only after durable enqueue. Slow OA reads run outside DingTalk's callback deadline.
            repo.jdbc().update("insert into plm_mold_event_inbox(event_key,instance_id,payload) values(?,?,?::jsonb) on conflict(event_key) do nothing",MoldApplicationMedia.hash(payload.getBytes(StandardCharsets.UTF_8)),instance,payload);
        }
        return crypto.encrypt("success");
    }
    @Scheduled(fixedDelay=10000,initialDelay=10000)
    public void processInbox(){
        if(!props.isEnabled()||!props.isReadSyncEnabled())return;
        var pending=repo.jdbc().queryForList("""
            update plm_mold_event_inbox set attempts=attempts+1,next_attempt_at=now()+interval '60 seconds'
            where event_key in(select event_key from plm_mold_event_inbox where completed_at is null and next_attempt_at<=now() order by next_attempt_at limit 10 for update skip locked)
            returning event_key,instance_id,payload::text,attempts
            """);
        for(var row:pending)try{
            var event=repo.node(row.get("payload"));boolean approved="finish".equalsIgnoreCase(event.path("type").asText())&&"agree".equalsIgnoreCase(event.path("result").asText());
            service.callback(row.get("instance_id").toString(),props.getProcessCode(),approved);repo.jdbc().update("update plm_mold_event_inbox set completed_at=now(),last_error=null where event_key=?",row.get("event_key"));
        }catch(Exception e){long delay=Math.min(900,15L*(1L<<Math.min(6,((Number)row.get("attempts")).intValue())));repo.jdbc().update("update plm_mold_event_inbox set last_error='OA补拉失败，将自动重试',next_attempt_at=now()+(?*interval '1 second') where event_key=?",delay,row.get("event_key"));}
    }
}

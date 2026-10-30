package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.infrastructure.http.SafeHttpTransport;
import com.yuewei.plm.module.integration.dingtalk.service.DingTalkAccessTokenService;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MoldDingTalkClient {
    private final DingTalkAccessTokenService tokens;
    private final ObjectMapper json;
    private final HttpClient http=SafeHttpTransport.client();
    public MoldDingTalkClient(DingTalkAccessTokenService tokens,ObjectMapper json){this.tokens=tokens;this.json=json;}
    public static class RemoteFailure extends RuntimeException {
        public final boolean uncertain;
        public RemoteFailure(boolean uncertain,String message){super(message);this.uncertain=uncertain;}
    }
    private JsonNode call(String uri,Object payload,boolean old,boolean write) {
        try {
            String token=tokens.getAccessToken();
            String address=uri+(old?(uri.contains("?")?"&":"?")+"access_token="+URLEncoder.encode(token,StandardCharsets.UTF_8):"");
            var request=HttpRequest.newBuilder(URI.create(address)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
            if(!old)request.header("x-acs-dingtalk-access-token",token);
            if(payload==null)request.GET();else request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
            var response=http.send(request.build(),SafeHttpTransport.boundedBody(8*1024*1024));
            var node=json.readTree(response.body());
            if(response.statusCode()!=200||node.path("errcode").asInt(0)!=0||node.has("code")&&!node.path("code").asText().equals("0"))
                throw new RemoteFailure(write&&response.statusCode()>=500,"钉钉拒绝请求（"+node.path("code").asText(node.path("errcode").asText("remote_error"))+"），请检查应用权限和表单配置");
            return node;
        }catch(RemoteFailure e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new RemoteFailure(write,"钉钉响应未确认，请同步核对后再操作");}
    }
    private JsonNode old(String path,Object body){return call("https://oapi.dingtalk.com"+path,body,true,false);}
    public void warmup(){for(String p:List.of("getbyuserid","listbyuserid"))try{old("/topapi/process/"+p,Map.of("userid","manager1"));}catch(RemoteFailure ignored){}}
    public JsonNode schema(String processCode){return call("https://api.dingtalk.com/v1.0/workflow/forms/schemas/processCodes?processCode="+URLEncoder.encode(processCode,StandardCharsets.UTF_8),null,false,false).path("result");}
    public List<String> ids(String processCode,Instant from,Instant until) {
        var ids=new LinkedHashSet<String>();
        while(from.isBefore(until)) {
            Instant stop=from.plus(Duration.ofDays(30));if(stop.isAfter(until))stop=until;
            long cursor=0;Set<Long> seen=new HashSet<>();
            do {
                var result=old("/topapi/processinstance/listids",Map.of("process_code",processCode,"start_time",from.toEpochMilli(),"end_time",stop.toEpochMilli(),"size",10,"cursor",cursor)).path("result");
                result.path("list").forEach(x->ids.add(x.asText()));cursor=result.path("next_cursor").asLong(0);
                if(cursor!=0&&!seen.add(cursor))throw new RemoteFailure(false,"钉钉分页重复，请稍后同步");
            }while(cursor!=0);
            from=stop;
        }
        return List.copyOf(ids);
    }
    public JsonNode instance(String id){return old("/topapi/processinstance/get",Map.of("process_instance_id",id)).path("process_instance");}
    public JsonNode user(String id){return old("/topapi/v2/user/get",Map.of("userid",id)).path("result");}
    public JsonNode department(long id){return old("/topapi/v2/department/get",Map.of("dept_id",id)).path("result");}
    public String submit(Map<String,Object> payload){var result=call("https://api.dingtalk.com/v1.0/workflow/processInstances",payload,false,true);String id=result.path("instanceId").asText();if(id.isBlank())throw new RemoteFailure(true,"钉钉未返回实例编号，请先对账");return id;}
    public void withdraw(String id,String userId){call("https://api.dingtalk.com/v1.0/workflow/processInstances/terminate",Map.of("processInstanceId",id,"operatingUserId",userId,"isSystem",false,"remark","PLM撤回后修订重提"),false,true);}
    public JsonNode legacyWrite(String path,Object payload){return call("https://oapi.dingtalk.com"+path,payload,true,true);}
    public JsonNode modern(String path,Object payload){return call("https://api.dingtalk.com"+path,payload,false,false);}
    public JsonNode modernWrite(String path,Object payload){return call("https://api.dingtalk.com"+path,payload,false,true);}
    public String approvalFileDownloadUrl(String instanceId,String fileId) {
        var response=modern("/v1.0/workflow/processInstances/spaces/files/urls/download",Map.of("processInstanceId",instanceId,"fileId",fileId));
        var result=response.path("result");String address=MoldApplicationForms.text(result.path("downloadUri"));
        if(!response.path("success").asBoolean()||address==null)throw new RemoteFailure(false,"钉钉未授权下载原审批附件，请核对应用的审批附件下载权限");
        if(result.hasNonNull("fileId")&&!fileId.equals(result.path("fileId").asText()))throw new RemoteFailure(false,"钉钉返回的附件身份与原文件不一致");
        return address;
    }
    public String uploadImage(byte[] bytes,String name,String contentType) {
        try {
            String token=tokens.getAccessToken(),boundary="plm-"+UUID.randomUUID();
            var out=new java.io.ByteArrayOutputStream();
            String safeName=name.replaceAll("[\\r\\n\"\\\\]","_");
            out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"media\"; filename=\""+safeName+"\"\r\nContent-Type: "+contentType+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(bytes);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
            var request=HttpRequest.newBuilder(URI.create("https://oapi.dingtalk.com/media/upload?type=image&access_token="+URLEncoder.encode(token,StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(30)).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
            var response=http.send(request,SafeHttpTransport.boundedBody(1024*1024));var body=json.readTree(response.body());
            if(response.statusCode()!=200||body.path("errcode").asInt(0)!=0||body.path("media_id").asText().isBlank())throw new RemoteFailure(false,"钉钉图片上传失败，请检查应用上传权限");
            // A photo control needs a durable CDN URL, never a token-bearing or expiring download URL.
            var download=http.send(HttpRequest.newBuilder(URI.create("https://oapi.dingtalk.com/media/get?access_token="+URLEncoder.encode(token,StandardCharsets.UTF_8)+"&media_id="+URLEncoder.encode(body.path("media_id").asText(),StandardCharsets.UTF_8))).timeout(Duration.ofSeconds(20)).GET().build(),SafeHttpTransport.boundedBody(50*1024*1024));
            String location=download.headers().firstValue("Location").orElse("");
            if(MoldApplicationDingTalkFiles.durablePhotoUrl(location))return location;
            throw new RemoteFailure(false,"钉钉未提供可用于审批的长期图片地址；图片已保留在PLM，未发起审批，请通过钉钉图片控件上传后重试");
        }catch(RemoteFailure e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new RemoteFailure(false,"钉钉图片上传未确认；图片已保留在PLM，未发起审批");}
    }
}

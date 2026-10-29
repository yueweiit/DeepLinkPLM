package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.*;
import com.yuewei.plm.infrastructure.http.SafeHttpTransport;
import com.yuewei.plm.module.integration.dingtalk.config.DingTalkIntegrationProperties;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.yuewei.plm.module.moldapplication.MoldApplicationForms.*;

/** Complete preparation before create-instance; no chosen file may be omitted. */
@Service
public class MoldApplicationDingTalkFiles {
    private final MoldApplicationMedia media;private final MoldDingTalkClient client;
    private final MoldApplicationRepository repo;private final ObjectMapper json;
    private final DingTalkIntegrationProperties properties;private final HttpClient http=SafeHttpTransport.client();
    public MoldApplicationDingTalkFiles(MoldApplicationMedia media,MoldDingTalkClient client,MoldApplicationRepository repo,ObjectMapper json,DingTalkIntegrationProperties properties){this.media=media;this.client=client;this.repo=repo;this.json=json;this.properties=properties;}
    public static boolean durablePhotoUrl(String address) {
        try{var u=URI.create(address);String host=Objects.toString(u.getHost(),"");return "https".equals(u.getScheme())&&u.getUserInfo()==null&&u.getQuery()==null&&(host.equals("static.dingtalk.com")||host.endsWith(".alicdn.com"));}catch(Exception e){return false;}
    }
    private static URI uploadUri(String address){var u=URI.create(address);String h=Objects.toString(u.getHost(),"");if(!"https".equals(u.getScheme())||u.getUserInfo()!=null||!(h.endsWith(".aliyuncs.com")||h.endsWith(".dingtalk.com")))throw invalid("钉钉上传地址不受信任");return u;}
    public void fillForm(long id,String userId,Map<String,Object> form) {
        var keys=new ArrayList<String>();var samples=new ArrayList<String>();var attachments=new ArrayList<Map<String,Object>>();
        for(var f:media.internal(id)) {
            if(!"downloaded".equals(f.get("download_status")))throw invalid("所选文件尚未归档，禁止发起审批");
            String category=f.get("file_category").toString();long fileId=((Number)f.get("attachment_id")).longValue();
            if(category.endsWith("photo")) {
                String address=text(f.get("source_url"));JsonNode prior=repo.node(f.get("dingtalk_storage_json"));
                if(!durablePhotoUrl(address))address=text(prior.path("photoUrl"));
                if(!durablePhotoUrl(address)) {
                    address=client.uploadImage(media.bytes(id,fileId),Objects.toString(f.get("original_file_name"),"photo.jpg"),Objects.toString(f.get("content_type"),"image/jpeg"));
                    repo.jdbc().update("update plm_attachment set dingtalk_storage_json=?::jsonb where attachment_id=?",repo.json(Map.of("photoUrl",address)),fileId);
                }
                if(!durablePhotoUrl(address))throw invalid("图片未取得钉钉长期地址，禁止发起审批");
                (category.equals("key_photo")?keys:samples).add(address);
            }else {
                JsonNode stored=repo.node(f.get("dingtalk_storage_json"));Map<String,Object> data;
                if(text(stored.path("fileId"))!=null&&userId.equals(stored.path("uploadedBy").asText()))data=json.convertValue(stored,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
                else {data=uploadAttachment(id,fileId,userId,f);repo.jdbc().update("update plm_attachment set dingtalk_storage_json=?::jsonb where attachment_id=?",repo.json(data),fileId);}
                var value=new LinkedHashMap<>(data);value.remove("uploadedBy");attachments.add(value);
            }
        }
        if(!keys.isEmpty())form.put("keyPhotos",repo.json(keys));
        if(!samples.isEmpty())form.put("samplePhotos",repo.json(samples));
        if(!attachments.isEmpty())form.put("attachments",repo.json(attachments));
    }
    private Map<String,Object> uploadAttachment(long id,long fileId,String userId,Map<String,Object> f) {
        String union=text(client.user(userId).path("unionid"));if(union==null)throw invalid("钉钉用户缺少UnionID，无法上传附件");
        var spaceBody=new LinkedHashMap<String,Object>();spaceBody.put("userId",userId);if(text(properties.getAgentId())!=null)spaceBody.put("agentId",Long.parseLong(properties.getAgentId()));
        String space=client.modern("/v1.0/workflow/processInstances/spaces/infos/query",spaceBody).path("result").path("spaceId").asText();
        if(!space.matches("[0-9]+"))throw invalid("钉钉审批空间不可用");
        String query="?unionId="+java.net.URLEncoder.encode(union,java.nio.charset.StandardCharsets.UTF_8);
        var info=client.modern("/v1.0/storage/spaces/"+space+"/files/uploadInfos/query"+query,Map.of("protocol","HEADER_SIGNATURE","multipart",false));
        byte[] bytes=media.bytes(id,fileId);
        try {
            var signature=info.path("headerSignatureInfo");String url=signature.path("resourceUrls").path(0).asText();
            var req=HttpRequest.newBuilder(uploadUri(url)).timeout(Duration.ofSeconds(60));
            signature.path("headers").fields().forEachRemaining(h->{if(!Set.of("host","content-length").contains(h.getKey().toLowerCase(Locale.ROOT)))req.header(h.getKey(),h.getValue().asText());});
            var result=http.send(req.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),SafeHttpTransport.boundedBody(1024*1024));
            if(result.statusCode()<200||result.statusCode()>=300)throw invalid("钉钉附件上传失败，未发起审批");
        }catch(com.yuewei.plm.common.exception.BusinessException e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw invalid("钉钉附件上传未确认，未发起审批");}
        String name=Objects.toString(f.get("original_file_name"),"attachment.bin");
        var entry=client.modernWrite("/v1.0/storage/spaces/"+space+"/files/commit"+query,Map.of("name",name,"parentId","0","uploadKey",info.path("uploadKey").asText())).path("dentry");
        if(text(entry.path("id"))==null)throw invalid("钉钉附件入库未确认，未发起审批");
        return Map.of("spaceId",space,"fileId",entry.path("id").asText(),"fileName",name,"fileSize",String.valueOf(bytes.length),"fileType",Objects.toString(f.get("file_ext"),"bin"),"uploadedBy",userId);
    }
}

package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.*;
import com.yuewei.plm.common.config.AppProperties;
import com.yuewei.plm.infrastructure.http.SafeHttpTransport;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import static com.yuewei.plm.module.moldapplication.MoldApplicationForms.*;

@Service
public class MoldApplicationMedia {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final AppProperties app;
    private final HttpClient http=SafeHttpTransport.client();private static final int MAX=50*1024*1024;
    public MoldApplicationMedia(JdbcTemplate jdbc,ObjectMapper json,AppProperties app){this.jdbc=jdbc;this.json=json;this.app=app;}
    public static String hash(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);}}
    public Path root(){return Path.of(app.getStorage().getLocalRoot()).toAbsolutePath().normalize();}
    private Path file(String key){Path file=root().resolve(key).normalize();if(!file.startsWith(root()))throw invalid("文件路径无效");return file;}
    public List<Map<String,Object>> list(long id){return jdbc.queryForList("""
        select attachment_id as "attachmentId",file_category as category,original_file_name as "fileName",
          file_size as "fileSize",content_type as "contentType",content_sha256 as sha256,
          download_status as "downloadStatus",case when download_status='download_failed' then remark end as "archiveError",source_component_id as "componentId",
          case when source_file_id like 'selection:%' then substring(source_file_id from 11) end as "selectionId",
          case when source_file_id like 'research-file:%' then substring(source_file_id from 15)::bigint end as "sourceAttachmentId",version_no as "versionNo"
        from plm_attachment where owner_object_type='MoldApplication' and owner_object_id=? and deleted_flag=0 order by attachment_id
        """,id);}
    public List<Map<String,Object>> internal(long id){return jdbc.queryForList("select * from plm_attachment where owner_object_type='MoldApplication' and owner_object_id=? and deleted_flag=0 order by attachment_id",id);}
    public long store(long id,String component,String category,String name,String sourceId,String sourceUrl,byte[] bytes,String contentType,String actor,String downloadStatus) {
        return store(id,component,category,name,sourceId,sourceUrl,bytes,contentType,actor,downloadStatus,null);
    }
    private long store(long id,String component,String category,String name,String sourceId,String sourceUrl,byte[] bytes,String contentType,String actor,String downloadStatus,Long sourceSize) {
        String sha=bytes==null?null:hash(bytes);String ext=Optional.ofNullable(name).orElse("file.bin").replaceAll(".*\\.","").toLowerCase(Locale.ROOT);
        if(!ext.matches("[a-z0-9]{1,8}"))ext="bin";
        String storage=bytes==null?"dingtalk://"+sourceId:"mold-applications/"+sha+"."+ext;
        if(bytes!=null)try{Path dest=file(storage);Files.createDirectories(dest.getParent());if(!Files.exists(dest))Files.write(dest,bytes,StandardOpenOption.CREATE_NEW);}catch(IOException e){throw invalid("本地附件归档失败");}
        return jdbc.queryForObject("""
            insert into plm_attachment(owner_object_type,owner_object_id,file_category,file_name,original_file_name,file_ext,
              content_type,file_size,checksum,content_sha256,storage_type,storage_key,preview_type,preview_status,
              source_system,source_file_id,source_url,version_no,status,download_status,source_component_id,created_by,updated_by)
            values('MoldApplication',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'V1','draft',?,?,?,?)
            on conflict(owner_object_id,source_component_id,source_file_id) where owner_object_type='MoldApplication' and deleted_flag=0
            do update set file_size=case when plm_attachment.download_status='downloaded' and excluded.download_status<>'downloaded' then plm_attachment.file_size else excluded.file_size end,
              content_sha256=case when plm_attachment.download_status='downloaded' and excluded.download_status<>'downloaded' then plm_attachment.content_sha256 else excluded.content_sha256 end,
              checksum=case when plm_attachment.download_status='downloaded' and excluded.download_status<>'downloaded' then plm_attachment.checksum else excluded.checksum end,
              storage_type=case when plm_attachment.download_status='downloaded' and excluded.download_status<>'downloaded' then plm_attachment.storage_type else excluded.storage_type end,
              storage_key=case when plm_attachment.download_status='downloaded' and excluded.download_status<>'downloaded' then plm_attachment.storage_key else excluded.storage_key end,
              download_status=case when plm_attachment.download_status='downloaded' and excluded.download_status<>'downloaded' then plm_attachment.download_status else excluded.download_status end,
              content_type=case when plm_attachment.download_status='downloaded' and excluded.download_status<>'downloaded' then plm_attachment.content_type else excluded.content_type end,
              preview_type=case when plm_attachment.download_status='downloaded' and excluded.download_status<>'downloaded' then plm_attachment.preview_type else excluded.preview_type end,
              dingtalk_storage_json=case when plm_attachment.download_status='downloaded' and excluded.download_status<>'downloaded' then plm_attachment.dingtalk_storage_json when plm_attachment.content_sha256 is distinct from excluded.content_sha256 then null else plm_attachment.dingtalk_storage_json end,updated_at=now()
            returning attachment_id
            """,Long.class,id,category,name,name,ext,contentType,bytes==null?sourceSize:Long.valueOf(bytes.length),sha,sha,bytes==null?"dingtalk":"local",storage,
            contentType!=null&&contentType.startsWith("image/")?"image":"unsupported","ready",sourceId.startsWith("plm:")||sourceId.startsWith("selection:")?"plm":"dingtalk",sourceId,sourceUrl,downloadStatus,component,actor,actor);
    }
    /** Adopt the explicitly selected original revision; reuse its immutable storage object, never copy bytes or choose latest. */
    public List<Long> inheritResearchFiles(long id,long project,List<Map<String,Object>> references,String actor,com.yuewei.plm.module.pilot.PilotAccessService access) {
        var adopted=new ArrayList<Long>();
        for(var reference:references.stream().filter(r->"file".equals(r.get("type"))).sorted(Comparator.comparingLong(r->((Number)r.get("id")).longValue())).toList()){
            long source=((Number)reference.get("id")).longValue();access.requireFile(source);
            var rows=jdbc.queryForList("""
                select a.* from plm_attachment a where a.attachment_id=? and a.deleted_flag=0 and
                  ((lower(a.owner_object_type) in ('product','project') and a.owner_object_id=?) or
                    exists(select 1 from plm_document_reference r where r.family_id=coalesce(a.family_id,a.attachment_id) and r.object_type='product' and r.object_id=?)) for update of a
                """,source,project,project);
            if(rows.size()!=1)throw invalid("来源附件不属于该新品项目，请在图片与附件中核对原版本");
            var original=rows.get(0);String storage=Objects.toString(original.get("storage_key"),"");String sourceName=Objects.toString(original.get("original_file_name"),Objects.toString(original.get("file_name"),"附件 #"+source));
            if(!"local".equals(original.get("storage_type"))||storage.isBlank())throw invalid("附件「"+sourceName+"」尚未归档，请回新品项目的图片与附件重新上传或修复后重试");
            try{if(Files.size(file(storage))>MAX)throw invalid("附件「"+sourceName+"」超过开模附件50MB上限，请回新品项目压缩或拆分后重试");}catch(IOException missing){throw invalid("附件「"+sourceName+"」本地文件不存在，请回新品项目的图片与附件重新上传后重试");}
            String mime=Objects.toString(original.get("content_type"),Objects.toString(original.get("mime_type"),"application/octet-stream"));
            boolean photo=Set.of("image/jpeg","image/png","image/webp").contains(mime);
            String category=photo?(Objects.toString(original.get("file_category"),"").toLowerCase(Locale.ROOT).contains("sample")?"sample_photo":"key_photo"):"attachment";
            String component=COMPONENTS.get(photo?(category.equals("key_photo")?"keyPhotos":"samplePhotos"):"attachments");
            jdbc.update("""
                insert into plm_attachment(owner_object_type,owner_object_id,file_category,file_name,original_file_name,file_ext,
                  content_type,file_size,checksum,content_sha256,storage_type,storage_key,preview_type,preview_status,
                  source_system,source_file_id,version_no,status,download_status,source_component_id,created_by,updated_by)
                select 'MoldApplication',?,?,a.file_name,coalesce(a.original_file_name,a.file_name),a.file_ext,
                  ?,a.file_size,a.checksum,coalesce(a.content_sha256,a.checksum),'local',a.storage_key,?,'ready',
                  'plm',?,a.version_no,'draft','downloaded',?,?,? from plm_attachment a where a.attachment_id=?
                on conflict(owner_object_id,source_component_id,source_file_id) where owner_object_type='MoldApplication' and deleted_flag=0 do nothing
                """,id,category,mime,photo?"image":"unsupported","research-file:"+source,component,actor,actor,source);
            adopted.add(source);
        }
        return adopted;
    }

    public long upload(long id,String category,MultipartFile source,String actor) {
        return upload(id,category,source,actor,null);
    }
    public record Selection(String selectionId,String category,String fileName,long fileSize,String contentType){}
    public void select(long id,Selection s,String actor){
        if(s==null||s.category()==null||s.selectionId()==null||!s.selectionId().matches("[A-Za-z0-9_-]{8,80}")||s.fileName()==null||s.fileName().length()>240||s.fileSize()<1||s.fileSize()>MAX)throw invalid("文件选择信息无效");
        String component=COMPONENTS.get(switch(s.category()){case "key_photo"->"keyPhotos";case "sample_photo"->"samplePhotos";case "attachment"->"attachments";default->throw invalid("文件分类无效");});
        var existing=jdbc.queryForList("select original_file_name,file_size from plm_attachment where owner_object_type='MoldApplication' and owner_object_id=? and source_component_id=? and source_file_id=? and deleted_flag=0",id,component,"selection:"+s.selectionId());
        if(!existing.isEmpty()){if(!s.fileName().equals(existing.get(0).get("original_file_name"))||((Number)existing.get(0).get("file_size")).longValue()!=s.fileSize())throw invalid("文件选择ID已用于另一个文件");return;}
        long attachment=store(id,component,s.category(),s.fileName(),"selection:"+s.selectionId(),null,null,s.contentType(),actor,"pending_upload");jdbc.update("update plm_attachment set file_size=? where attachment_id=?",s.fileSize(),attachment);
    }
    public void discardSelection(long id,long attachment){if(jdbc.update("update plm_attachment set deleted_flag=1,download_status='cancelled' where attachment_id=? and owner_object_type='MoldApplication' and owner_object_id=? and download_status='pending_upload' and deleted_flag=0",attachment,id)!=1)throw invalid("只能移除未归档的文件选择；已归档文件会保留");}
    public long upload(long id,String category,MultipartFile source,String actor,String selectionId) {
        String key=switch(category){case "key_photo"->"keyPhotos";case "sample_photo"->"samplePhotos";case "attachment"->"attachments";default->throw invalid("文件分类无效");};
        if(source.isEmpty()||source.getSize()>MAX)throw invalid("文件为空或超过50MB");
        String type=source.getContentType();if(category.endsWith("photo")&&(type==null||!Set.of("image/jpeg","image/png","image/webp").contains(type)))throw invalid("图片须为 JPG、PNG 或 WebP");
        String name=Path.of(Objects.toString(source.getOriginalFilename(),"file").replace('\\','/')).getFileName().toString();
        if(name.length()>240)throw invalid("文件名称过长");
        String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        if(!Arrays.asList(app.getStorage().getAllowedExtensions().split(",")).contains(ext))throw invalid("不支持此文件格式");
        if(selectionId!=null){var selected=jdbc.queryForList("select original_file_name,file_size from plm_attachment where owner_object_type='MoldApplication' and owner_object_id=? and source_component_id=? and source_file_id=? and deleted_flag=0",id,COMPONENTS.get(key),"selection:"+selectionId);if(selected.size()!=1||!name.equals(selected.get(0).get("original_file_name"))||((Number)selected.get(0).get("file_size")).longValue()!=source.getSize())throw invalid("上传文件与已登记选择不一致");}
        try{byte[] bytes=source.getBytes();return store(id,COMPONENTS.get(key),category,name,selectionId==null?"plm:"+hash(bytes):"selection:"+selectionId,null,bytes,type,actor,"downloaded");}
        catch(IOException e){throw invalid("上传文件读取失败");}
    }
    public void requireResearchFileAccess(long id,Long attachment,com.yuewei.plm.module.pilot.PilotAccessService access) {
        var files=jdbc.queryForList("select source_file_id from plm_attachment where owner_object_type='MoldApplication' and owner_object_id=? and deleted_flag=0 and source_file_id like 'research-file:%'"+(attachment==null?"":" and attachment_id=?"),attachment==null?new Object[]{id}:new Object[]{id,attachment});
        if(files.isEmpty())return;
        var project=jdbc.queryForObject("select (source_snapshot_json->'research'->>'projectId')::bigint from plm_mold_application where application_id=?",Long.class,id);
        if(project==null)throw invalid("来源项目关联缺失，请联系工程人员核对原附件");
        access.requirePermission("project:view");access.requireProduct(project);
        for(var file:files)access.requireFile(Long.parseLong(file.get("source_file_id").toString().substring("research-file:".length())));
    }

    public byte[] bytes(long id,long attachment) {
        var rows=jdbc.queryForList("select storage_key,storage_type from plm_attachment where attachment_id=? and owner_object_type='MoldApplication' and owner_object_id=? and deleted_flag=0",attachment,id);
        if(rows.size()!=1||!"local".equals(rows.get(0).get("storage_type")))throw invalid("文件尚未下载，请管理员重试同步");
        try{Path f=file(rows.get(0).get("storage_key").toString());if(Files.size(f)>MAX)throw invalid("文件超过下载限制");return Files.readAllBytes(f);}catch(IOException e){throw invalid("本地文件不存在，请重新同步");}
    }
    public void importFiles(long id,JsonNode media,Path backup) {
        for(var m:media) {
            byte[] bytes=null;
            if("downloaded".equals(m.path("downloadStatus").asText()))try{
                Path source=backup.resolve(m.path("path").asText()).normalize();
                if(!source.startsWith(backup)||Files.size(source)>MAX)throw invalid("备份附件路径或大小无效");
                bytes=Files.readAllBytes(source);if(!hash(bytes).equals(m.path("sha256").asText()))throw invalid("备份附件校验失败");
            }catch(IOException e){throw invalid("备份附件读取失败");}
            String url=text(m.path("sourceUrl"));String sourceId=Optional.ofNullable(text(m.path("fileId"))).orElse(hash(Objects.toString(url,m.toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            store(id,m.path("componentId").asText(),m.path("category").asText(),m.path("fileName").asText("钉钉文件"),sourceId,url,
                bytes,m.path("contentType").asText("application/octet-stream"),"dingtalk-import",m.path("downloadStatus").asText("pending_authorization"));
        }
    }
    public void archiveRemote(long id,JsonNode detail) {archiveRemote(id,detail,null);}
    /** Only an explicit archive action supplies the scoped official file resolver; scheduled sync never grants downloads. */
    public void archiveRemote(long id,JsonNode detail,java.util.function.Function<String,String> officialFileUrl) {
        for(var field:detail.path("form_component_values")) {
            String type=field.path("component_type").asText();if(!Set.of("DDPhotoField","DDAttachment").contains(type)||text(field.path("value"))==null)continue;
            try {
                var items=json.readTree(field.path("value").asText());if(items==null||!items.isArray())throw invalid("审批附件字段不是文件列表，请核对原审批资料后重试");
                for(var item:items)archiveFile(id,field,item,type,officialFileUrl);
            }catch(Exception failure){
                // Normal OA sync remains best-effort; malformed media must not block approval synchronization or provisioning.
                if(officialFileUrl!=null)throw failure instanceof com.yuewei.plm.common.exception.BusinessException business?business:invalid("审批附件字段无法解析，请核对原审批资料后重试");
            }
        }
    }
    private void archiveFile(long id,JsonNode field,JsonNode item,String type,java.util.function.Function<String,String> officialFileUrl) {
        String direct=item.isTextual()?text(item):text(item.path("downloadUrl"));String fileId=text(item.path("fileId"));
        if(fileId==null&&direct==null)throw invalid("原审批附件缺少fileId及下载地址，请核对原文件");
        String sourceId=fileId==null?hash(direct.getBytes(java.nio.charset.StandardCharsets.UTF_8)):fileId,component=field.path("id").asText();
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_attachment where owner_object_type='MoldApplication' and owner_object_id=? and source_component_id=? and source_file_id=? and download_status='downloaded' and deleted_flag=0)",Boolean.class,id,component,sourceId)))return;
        String name=item.path("fileName").asText("钉钉文件"),category=component.equals(COMPONENTS.get("keyPhotos"))?"key_photo":type.equals("DDPhotoField")?"sample_photo":"attachment";
        byte[] bytes=null;String mime="application/octet-stream",error=null;
        try {
            boolean authorized="DDAttachment".equals(type)&&fileId!=null&&officialFileUrl!=null;
            String address=authorized?officialFileUrl.apply(fileId):direct;
            if(text(address)==null)throw invalid("原审批仅提供文件ID，请管理员点击归档原审批附件");
            URI uri=archiveUri(address,authorized);
            var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),SafeHttpTransport.boundedBody(MAX));
            if(response.statusCode()!=200)throw invalid("附件下载返回HTTP "+response.statusCode()+"，请重试原审批附件归档");
            bytes=response.body();if(bytes.length==0)throw invalid("原审批附件内容为空，请核对原文件");
            mime=archivedContentType(bytes,response.headers().firstValue("Content-Type").orElse(mime));
            if(item.isTextual())name=Path.of(uri.getPath()).getFileName().toString();
        }catch(Exception failure){
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            error=failure instanceof com.yuewei.plm.common.exception.BusinessException||failure instanceof MoldDingTalkClient.RemoteFailure?failure.getMessage():"附件下载超时、连接失败或超过50MB限制，请重试归档";
        }
        // Signed URLs are transient. The original file ID and local SHA remain the durable provenance.
        long attachment=store(id,component,category,name,sourceId,direct,bytes,mime,"dingtalk-sync",error==null?"downloaded":"download_failed",item.path("fileSize").asLong(-1)>=0?item.path("fileSize").asLong():null);
        jdbc.update("update plm_attachment set remark=? where attachment_id=? and download_status=?",error,attachment,error==null?"downloaded":"download_failed");
    }
    static URI archiveUri(String address,boolean official) {
        URI uri;try{uri=URI.create(address);}catch(Exception malformed){throw invalid("审批附件下载地址格式无效");}
        String host=Objects.toString(uri.getHost(),"").toLowerCase(Locale.ROOT);
        boolean cdn=host.endsWith(".dingtalk.com")||host.endsWith(".alicdn.com"),oss=official&&host.matches("[a-z0-9.-]+\\.oss-[a-z0-9-]+\\.aliyuncs\\.com");
        if(uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getPort()!=-1&&uri.getPort()!=443||!cdn&&!oss)throw invalid("不允许的审批附件下载域名");
        if(oss&&"http".equals(uri.getScheme()))uri=URI.create("https:"+uri.toASCIIString().substring(5));
        if(!"https".equals(uri.getScheme()))throw invalid("审批附件下载必须使用HTTPS");return uri;
    }
    static String archivedContentType(byte[] bytes,String declared) {
        if(bytes.length>=8&&Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10}))return "image/png";
        if(bytes.length>=3&&(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255)return "image/jpeg";
        if(bytes.length>=12&&new String(bytes,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")&&new String(bytes,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"))return "image/webp";
        return declared.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
    }
}

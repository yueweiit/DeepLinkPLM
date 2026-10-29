package com.yuewei.plm.module.masterdata;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import static com.yuewei.plm.module.masterdata.MasterDataSnapshot.*;

/** Only immutable, hash-checked local source directories. No URL or browser JSON import. */
@Component @RequiredArgsConstructor
public class MasterDataSnapshotStore {
    private final Environment environment;
    private final ObjectMapper mapper;
    public record Loaded(String name,String hash,JsonNode manifest,MasterDataSnapshot snapshot) {}
    public boolean enabled(){return environment.getProperty("APP_MASTER_DATA_ENABLED",Boolean.class,false);}
    public void requireEnabled(){require(enabled(),"主数据对齐功能未启用");}
    public Loaded load(String name){
        requireEnabled();require(name!=null&&name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,100}")&&!name.contains(".."),"来源快照名称无效");
        try{
            Path base=root(),directory=base.resolve(name).toRealPath();require(directory.startsWith(base)&&directory.getParent().equals(base),"来源快照不在受限目录内");
            Path source=directory.resolve("source.json"),manifestFile=directory.resolve("manifest.json");
            require(Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)&&Files.isRegularFile(manifestFile,LinkOption.NOFOLLOW_LINKS),"来源快照文件缺失或为链接");
            require(Files.size(source)<=32*1024*1024&&Files.size(manifestFile)<=1024*1024,"来源快照超过限制");
            var json=mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            var manifest=json.readTree(Files.readAllBytes(manifestFile));byte[] bytes=Files.readAllBytes(source);String hash=sha256(bytes);
            require(manifest.path("formatVersion").asInt()==1&&manifest.path("complete").asBoolean()&&"source.json".equals(text(manifest,"sourceFile"))&&hash.equals(text(manifest,"sha256")),"来源快照未完成或 SHA-256 校验失败");
            var snapshot=new MasterDataSnapshot(json.readTree(bytes));require(snapshot.organization().equals(text(manifest,"organizationId")),"快照组织与清单不一致");
            for(String section:List.of("crm","mes"))manifest.path("counts").path(section).fields().forEachRemaining(entry->require(snapshot.rows(section,entry.getKey()).size()==entry.getValue().asInt(-1),"来源快照记录数不匹配："+section+"/"+entry.getKey()));
            require(manifest.path("listedSkuCount").asInt(-1)==strings(snapshot.data().path("shop").path("listedSkuIds")).size(),"上架清单记录数不匹配");
            return new Loaded(name,hash,manifest,snapshot);
        }catch(IOException e){throw invalid("来源快照不可读取，请核对本地受限目录与文件权限");}
    }
    public List<Map<String,Object>> available(){
        requireEnabled();try(var paths=Files.list(root())){
            var out=new ArrayList<Map<String,Object>>();paths.filter(Files::isDirectory).map(p->p.getFileName().toString()).filter(n->n.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,100}")&&!n.contains("..")).sorted().forEach(n->{
                try{var loaded=load(n);out.add(Map.of("name",n,"hash",loaded.hash(),"capturedAt",loaded.manifest().path("capturedAt").asText(),"counts",loaded.manifest().path("counts"),"listedSkuCount",loaded.manifest().path("listedSkuCount").asInt(),"unlistedSkuCount",loaded.manifest().path("unlistedSkuCount").asInt()));}
                catch(com.yuewei.plm.common.exception.BusinessException ignored){/* Incomplete private export directories are not import choices. */}
            });return List.copyOf(out);
        }catch(IOException e){throw invalid("本地来源目录未配置或不可读取");}
    }
    private Path root() throws IOException {String raw=environment.getProperty("APP_MASTER_DATA_SNAPSHOT_ROOT","");require(!raw.isBlank(),"本地来源目录未配置");return Path.of(raw).toRealPath();}
    public static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}

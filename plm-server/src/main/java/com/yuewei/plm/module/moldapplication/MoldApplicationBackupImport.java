package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yuewei.plm.module.moldapplication.MoldApplicationForms.*;

/** Immutable, verified import checkpoint. Reopening the app must not replay stale OA state. */
@Service
public class MoldApplicationBackupImport {
    private final MoldApplicationRepository repo;private final MoldApplicationMedia media;
    private final ObjectMapper json;private final PlatformTransactionManager transactions;
    public MoldApplicationBackupImport(MoldApplicationRepository repo,MoldApplicationMedia media,ObjectMapper json,PlatformTransactionManager transactions){this.repo=repo;this.media=media;this.json=json;this.transactions=transactions;}
    public void run(Path root,String process)throws Exception {
        root=root.toAbsolutePath().normalize();byte[] manifest=Files.readAllBytes(root.resolve("SHA256SUMS"));String digest=MoldApplicationMedia.hash(manifest);
        for(String line:new String(manifest,StandardCharsets.UTF_8).split("\\R")) {
            if(line.isBlank())continue;String[] entry=line.split("  ",2);if(entry.length!=2||!entry[0].matches("[a-f0-9]{64}"))throw invalid("备份校验清单格式无效");
            Path file=root.resolve(entry[1]).normalize();if(!file.startsWith(root)||Files.isSymbolicLink(file)||!MoldApplicationMedia.hash(Files.readAllBytes(file)).equals(entry[0]))throw invalid("备份完整性校验失败，未导入");
        }
        if(Boolean.TRUE.equals(repo.jdbc().queryForObject("select exists(select 1 from plm_mold_import_job where manifest_sha256=? and completed_at is not null)",Boolean.class,digest)))return;
        repo.jdbc().update("insert into plm_mold_import_job(manifest_sha256,source_directory) values(?,?) on conflict do nothing",digest,root.toString());
        var schema=json.readTree(root.resolve("schema.json").toFile());repo.saveSchema(process,schema);var tx=new TransactionTemplate(transactions);
        Path database=root.resolve("database");if(Files.isDirectory(database))try(var files=Files.list(database)) {
            for(Path file:files.filter(f->f.getFileName().toString().endsWith(".ndjson")).toList()) {
                String name=file.getFileName().toString().replaceFirst("\\.ndjson$","");String[] source=name.split("__",2);if(source.length!=2)throw invalid("主数据文件名无效");
                try(var lines=Files.lines(file)){for(String line:(Iterable<String>)lines::iterator){JsonNode row=json.readTree(line);String key=text(row.path("id"));if(key==null)key=MoldApplicationMedia.hash(line.getBytes(StandardCharsets.UTF_8));final String sourceKey=key;
                    tx.executeWithoutResult(status->{repo.jdbc().update("insert into plm_mold_master_snapshot(source_database,source_table,source_key,payload,manifest_sha256) values(?,?,?,?::jsonb,?) on conflict(source_database,source_table,source_key) do update set payload=excluded.payload,manifest_sha256=excluded.manifest_sha256",source[0],source[1],sourceKey,line,digest);});
                }}
            }
        }
        tx.executeWithoutResult(status->{
            repo.jdbc().execute("select pg_advisory_xact_lock(66817399)");
            repo.jdbc().update("""
                insert into plm_mold_model_alias(source_database,source_id,phone_code,normalized_name,source_name,payload)
                select source_database,source_key,payload->>'code',lower(btrim(payload->>'name')),payload->>'name',payload
                from plm_mold_master_snapshot where source_table='model' and payload->>'code'~'^[0-9]{4}$'
                  and length(payload->>'name') between 1 and 255 and coalesce(payload->>'status','1')='1'
                on conflict(source_database,source_id) do update set phone_code=excluded.phone_code,normalized_name=excluded.normalized_name,source_name=excluded.source_name,payload=excluded.payload
                """);
            // Never overwrite local code labels, or guess a match across conflicting master records.
            repo.jdbc().update("""
                insert into plm_code_item(code_type,code_value,code_name,status,sort_order,created_by,updated_by)
                select 'phone_model',a.phone_code,min(a.source_name),'enabled',0,'mold-master-import','mold-master-import'
                from plm_mold_model_alias a
                where not exists(select 1 from plm_code_item c where c.code_type='phone_model' and
                  (c.code_value=a.phone_code or lower(btrim(c.code_name))=a.normalized_name))
                  and not exists(select 1 from plm_mold_model_alias b where b.normalized_name=a.normalized_name and b.phone_code<>a.phone_code)
                group by a.phone_code having count(distinct a.normalized_name)=1
                on conflict do nothing
                """);
            repo.jdbc().update("update plm_mold_code_sequence set last_value=greatest(last_value,(select coalesce(max(phone_code::integer),0) from plm_mold_model_alias)) where sequence_key='phone_model'");
        });
        int count=0;try(var lines=Files.lines(root.resolve("instances.ndjson"))){for(String line:(Iterable<String>)lines::iterator){var record=json.readTree(line);if(!process.equals(record.path("processCode").asText()))throw invalid("备份正式流程码不符");final Path backup=root;
            tx.executeWithoutResult(status->{long id=repo.ingest(process,record.path("instanceId").asText(),record.path("detail"),schema,record.path("originator"),record.path("department"));media.importFiles(id,record.path("media"),backup);
                String uid=text(record.path("originator").path("userid"));if(uid!=null)repo.jdbc().update("insert into plm_mold_dingtalk_directory(user_id,display_name,departments) values(?,?,?::jsonb) on conflict(user_id) do update set display_name=excluded.display_name,departments=excluded.departments,fetched_at=now()",uid,text(record.path("originator").path("name")),repo.json(record.path("originator").path("dept_id_list")));
            });count++;
        }}
        repo.jdbc().update("update plm_mold_application_activity a set actor_name=d.display_name from plm_mold_dingtalk_directory d where a.actor_id=d.user_id and a.actor_name is null");
        repo.jdbc().update("update plm_mold_import_job set completed_at=now(),report=?::jsonb where manifest_sha256=?",repo.json(Map.of("formalInstances",count,"source",process,"sha256Verified",true)),digest);
    }
}

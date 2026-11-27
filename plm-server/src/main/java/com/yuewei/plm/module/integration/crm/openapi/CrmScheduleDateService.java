package com.yuewei.plm.module.integration.crm.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuewei.plm.module.collaboration.CollaborationService;
import com.yuewei.plm.module.collaboration.MoldWorkflowRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CrmScheduleDateService {
    public record Patch(Map<String,JsonNode> dates,String reason,String reference,JsonNode body){}
    private final JdbcTemplate jdbc;private final CollaborationService collaboration;private final CrmSyncStore store;private final CrmQueryService query;private final CrmOpenApiProperties properties;private final ObjectMapper mapper;private final TransactionTemplate tx;
    public CrmScheduleDateService(JdbcTemplate jdbc,CollaborationService collaboration,CrmSyncStore store,CrmQueryService query,CrmOpenApiProperties p,ObjectMapper mapper,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.collaboration=collaboration;this.store=store;this.query=query;properties=p;this.mapper=mapper;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Map<String,Object> save(long id,String ifMatch,String key,String body,HttpServletRequest request){
        var principal=CrmServicePrincipal.from(request);principal.require("crm:schedule-dates:write");principal.require("crm:mold-schedule:read");
        if(!properties.isWriteEnabled())throw new CrmApiException(403,"FORBIDDEN","CRM 日期写入未开放");
        if(ifMatch==null)throw new CrmApiException(428,"PRECONDITION_REQUIRED","缺少 If-Match");
        if(!ifMatch.matches("\"v3-[a-f0-9]{64}\""))throw CrmApiException.invalid("If-Match 必须使用详情返回的 ETag");
        if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw CrmApiException.invalid("缺少有效 Idempotency-Key");
        Patch patch=parse(body,mapper);String hash=CrmTokenCodec.hash("PATCH\n"+id+"\n"+ifMatch+"\n"+canonical(patch.body()));
        // A repeatable-read snapshot taken before waiting for the idempotency lock can be
        // stale. Retry the entire transaction, never an individual statement/save.
        for(int attempt=0;;attempt++) {
            try {return tx.execute(status->{
            var control=store.control();
            jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,781190032))",principal.subjectId()+":"+key);
            var prior=jdbc.queryForList("select request_hash,response_json::text from plm_crm_openapi_idempotency where subject_id=? and request_key=? and expires_at>clock_timestamp()",principal.subjectId(),key);
            if(!prior.isEmpty()){
                if(!hash.equals(prior.get(0).get("request_hash")))throw new CrmApiException(409,"IDEMPOTENCY_KEY_REUSED","此幂等键已用于不同请求");
                try{return mapper.readValue(prior.get(0).get("response_json").toString(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalStateException(e);}
            }
            jdbc.update("delete from plm_crm_openapi_idempotency where subject_id=? and request_key=? and expires_at<=clock_timestamp()",principal.subjectId(),key);
            String auditReason="[CRM "+principal.subjectId()+" / "+patch.reference()+"]"
                +(patch.body().has("operator")?" [operator "+patch.body().get("operator")+"]":"")+" "+patch.reason();
            var result=collaboration.saveCrmScheduleDates(id,patch.dates(),auditReason,current->{
                var resource=store.resource("MOLD_SET",id,true);
                String life=jdbc.queryForObject("select plm_crm_openapi_lifecycle('MOLD_SET',?)",String.class,id);
                if(!"ACTIVE".equals(life))throw new CrmApiException("DELETED".equals(life)?404:409,"DELETED".equals(life)?"NOT_FOUND":"RESOURCE_INACTIVE","记录不可修改");
                if(!ifMatch.equals("\""+store.version("MOLD_SET",id,resource,control)+"\""))throw new CrmApiException(412,"VERSION_CONFLICT","记录已更新，请重新查询核对后提交");
                if(new MoldWorkflowRepository(jdbc).isPaused(id)&&patch.dates().keySet().stream().anyMatch(k->!k.equals("actualMexicoArrivalDate")))throw new CrmApiException(409,"EXECUTION_RESTRICTED","暂停期间不能修改或清空计划日期");
            },request);
            if(!"saved".equals(result.status()))throw new CrmApiException(412,"VERSION_CONFLICT",result.message());
            Map<String,Object> saved=query.detailInTransaction("MOLD_SET",id,control);
            try {
                jdbc.update("insert into plm_crm_openapi_idempotency(subject_id,request_key,request_hash,response_json,etag,expires_at) values(?,?,?,?::jsonb,?,clock_timestamp()+(?*interval '1 day'))",principal.subjectId(),key,hash,mapper.writeValueAsString(saved),saved.get("recordVersion"),properties.getIdempotencyRetentionDays());
            }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
            return saved;
            });}catch(org.springframework.dao.ConcurrencyFailureException|org.springframework.dao.DuplicateKeyException race){
                if(attempt>=2)throw new CrmApiException(503,"TEMPORARILY_UNAVAILABLE","并发事务竞争，请使用原幂等键重试");
            }
        }
    }
    public static Patch parse(String body,ObjectMapper base){
        try {
            if(body==null||body.length()>32768)throw CrmApiException.invalid("请求正文为空或过大");
            var parser=base.getFactory().createParser(body);parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root;try(parser){root=base.readTree(parser);if(parser.nextToken()!=null)throw CrmApiException.invalid("请求包含多份 JSON");}
            keys(root,Set.of("dates","reason","crmReference","operator"));
            String reason=required(root,"reason",2000),reference=required(root,"crmReference",128);
            JsonNode dates=root.get("dates");keys(dates,Set.copyOf(CrmQueryService.WRITE_FIELDS));if(dates.isEmpty())throw CrmApiException.invalid("至少提交一个日期");
            var values=new LinkedHashMap<String,JsonNode>();dates.fields().forEachRemaining(e->{
                if(!e.getValue().isNull()){
                    if(!e.getValue().isTextual()||!e.getValue().asText().matches("\\d{4}-\\d{2}-\\d{2}"))throw CrmApiException.invalid("日期须为 YYYY-MM-DD 或 null");
                    try{if(LocalDate.parse(e.getValue().asText()).getYear()<1)throw new IllegalArgumentException();}catch(Exception x){throw CrmApiException.invalid("日期无效");}
                }values.put(e.getKey(),e.getValue());
            });
            if(root.has("operator")){keys(root.get("operator"),Set.of("id","name"));for(String k:List.of("id","name"))if(root.get("operator").has(k))required(root.get("operator"),k,128);}
            return new Patch(Collections.unmodifiableMap(values),reason,reference,root);
        }catch(CrmApiException e){throw e;}catch(Exception e){throw CrmApiException.invalid("JSON 格式不正确或存在重复字段");}
    }
    private static void keys(JsonNode node,Set<String> allowed){if(node==null||!node.isObject())throw CrmApiException.invalid("请求对象结构无效");node.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw CrmApiException.invalid("不允许的字段："+k);});}
    private static String required(JsonNode node,String key,int max){JsonNode value=node.get(key);if(value==null||!value.isTextual()||value.asText().isBlank()||value.asText().length()>max)throw CrmApiException.invalid("缺少有效 "+key);return value.asText().trim();}
    static String canonical(JsonNode node){
        if(node.isObject()){
            var fields=new TreeMap<String,JsonNode>();node.fields().forEachRemaining(e->fields.put(e.getKey(),e.getValue()));
            var result=new StringJoiner(",","{","}");fields.forEach((key,value)->result.add(com.fasterxml.jackson.databind.node.TextNode.valueOf(key).toString()+":"+canonical(value)));return result.toString();
        }
        if(node.isArray()){var result=new StringJoiner(",","[","]");node.forEach(value->result.add(canonical(value)));return result.toString();}
        return node.toString();
    }
}

package com.yuewei.plm.module.integration.crm.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuewei.plm.module.collaboration.MoldSchedulePolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CrmSyncStore {
    private final JdbcTemplate jdbc;private final CrmOpenApiProperties properties;private final CrmTokenCodec codec;
    private final TransactionTemplate read;
    public CrmSyncStore(JdbcTemplate jdbc,CrmOpenApiProperties p,CrmTokenCodec codec,PlatformTransactionManager manager){
        this.jdbc=jdbc;properties=p;this.codec=codec;
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Map<String,Object> control(){
        var rows=jdbc.queryForList("select * from plm_crm_openapi_control where singleton");
        if(!properties.configured()||rows.size()!=1||!Boolean.TRUE.equals(rows.get(0).get("ready"))||!Boolean.TRUE.equals(rows.get(0).get("capture_enabled")))throw CrmApiException.unavailable();
        return rows.get(0);
    }
    public Map<String,Object> resource(String kind,long id,boolean lock){
        var rows=jdbc.queryForList("select * from plm_crm_openapi_resource where entity_type=? and entity_id=?"+(lock?" for update":""),kind,id);
        if(rows.size()!=1)throw CrmApiException.unavailable();return rows.get(0);
    }
    public String version(String kind,long id,Map<String,Object> state,Map<String,Object> control){
        // A deterministic, opaque version; changes across resources, epochs, dependencies and business days.
        String day=kind.equals("MOLD_SET")?MoldSchedulePolicy.today().toString():"";
        String projection=kind.equals("MOLD_SET")?"|products-skus-v2":"";
        return "v3-"+CrmTokenCodec.hash(kind+"|"+id+"|"+state.get("revision")+"|"+day+"|"+control.get("stream_epoch")+"|"+properties.getSubjectId()+"|"+properties.getScopes()+"|"+properties.isWriteEnabled()+"|"+properties.getSigningSecret()+projection);
    }
    public String cursor(long last,Long upper,Map<String,Object> control){
        var map=new LinkedHashMap<String,Object>();map.put("kind","change");map.put("subject",properties.getSubjectId());map.put("epoch",control.get("stream_epoch").toString());map.put("last",last);map.put("upper",upper);
        map.put("expires",Instant.now().plusSeconds(properties.getChangeRetentionDays()*86400L).getEpochSecond());return codec.encode(map);
    }
    public Map<String,Object> changes(String cursor,int size){
        return read.execute(status->changesInTransaction(cursor,size));
    }
    private Map<String,Object> changesInTransaction(String cursor,int size){
        var control=control();JsonNode token=codec.decode(cursor,"change",properties.getSubjectId(),control.get("stream_epoch").toString());
        long last=token.path("last").asLong(-1),head=((Number)control.get("published_head")).longValue();
        if(last<((Number)control.get("retained_after")).longValue())throw new CrmApiException(410,"CURSOR_EXPIRED","所需变更已过保留期，请重新完整同步");
        long upper=token.path("upper").isNumber()?token.path("upper").asLong():head;
        if(last<0||last>upper||upper>head)throw CrmApiException.invalid("同步游标边界无效");
        var rows=jdbc.queryForList("select * from plm_crm_openapi_change where stream_epoch=?::uuid and change_id>? and change_id<=? order by change_id limit ?",control.get("stream_epoch").toString(),last,upper,size+1);
        boolean more=rows.size()>size;if(more)rows=new ArrayList<>(rows.subList(0,size));
        var result=new ArrayList<Map<String,Object>>();
        for(var row:rows)result.add(Map.of("changeId",row.get("change_id").toString(),"entityType",row.get("entity_type"),"recordId",row.get("entity_id").toString(),"action",row.get("action"),"lifecycleStatus",row.get("lifecycle"),"changedAt",CrmQueryService.timestamp(row.get("changed_at"))));
        long next=more?((Number)rows.get(rows.size()-1).get("change_id")).longValue():upper;
        return Map.of("changes",result,"hasMore",more,"nextCursor",cursor(next,more?upper:null,control));
    }
}

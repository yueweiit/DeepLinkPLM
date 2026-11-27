package com.yuewei.plm.module.integration.crm.openapi;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CrmChangeJobs {
    private final JdbcTemplate jdbc;private final CrmOpenApiProperties properties;private final TransactionTemplate tx;
    public CrmChangeJobs(JdbcTemplate jdbc,CrmOpenApiProperties p,PlatformTransactionManager manager){this.jdbc=jdbc;properties=p;tx=new TransactionTemplate(manager);}
    @Scheduled(fixedDelayString="${plm.integrations.crm.openapi.publisher-interval-ms:1000}")
    public void publish(){
        if(!properties.isEnabled())return;
        try {publishBatch();}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).error("CRM change publication failed",e);}
    }
    public int publishBatch(){
        return tx.execute(status->{
            var control=jdbc.queryForList("select * from plm_crm_openapi_control where singleton and capture_enabled and ready for update");
            if(control.isEmpty())return 0;var c=control.get(0);long head=((Number)c.get("published_head")).longValue();String epoch=c.get("stream_epoch").toString();
            var rows=jdbc.queryForList("select * from plm_crm_openapi_capture where stream_epoch=?::uuid and not published order by capture_id limit 200 for update",epoch);
            for(var row:rows){
                jdbc.update("insert into plm_crm_openapi_change(stream_epoch,change_id,capture_id,entity_type,entity_id,revision,action,lifecycle,changed_at) values(?::uuid,?,?,?,?,?,?,?,?)",epoch,++head,row.get("capture_id"),row.get("entity_type"),row.get("entity_id"),row.get("revision"),row.get("action"),row.get("lifecycle"),row.get("captured_at"));
                jdbc.update("update plm_crm_openapi_capture set published=true where capture_id=?",row.get("capture_id"));
            }
            jdbc.update("update plm_crm_openapi_control set published_head=? where singleton",head);return rows.size();
        });
    }
    @Scheduled(fixedDelayString="${plm.integrations.crm.openapi.maintenance-interval-ms:60000}")
    public void maintain(){
        if(!properties.isEnabled())return;
        try {advanceBusinessDate();cleanup();}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).error("CRM maintenance failed",e);}
    }
    public int advanceBusinessDate(){
        return tx.execute(status->{
            // Independent advisory lock avoids holding publisher control while waiting for resources.
            if(!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(781190031)",Boolean.class)))return 0;
            if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_crm_openapi_control where singleton and ready and capture_enabled)",Boolean.class)))return 0;
            var ids=jdbc.queryForList("select entity_id from plm_crm_openapi_resource where entity_type='MOLD_SET' and lifecycle='ACTIVE' and business_date is distinct from (clock_timestamp() at time zone 'Asia/Shanghai')::date order by entity_id limit 200",Long.class);
            for(long id:ids){jdbc.queryForList("select plm_crm_openapi_touch('MOLD_SET',?)",id);jdbc.update("update plm_crm_openapi_resource set business_date=(clock_timestamp() at time zone 'Asia/Shanghai')::date where entity_type='MOLD_SET' and entity_id=?",id);}
            if(ids.isEmpty())jdbc.update("update plm_crm_openapi_control set last_business_date=(clock_timestamp() at time zone 'Asia/Shanghai')::date where singleton");return ids.size();
        });
    }
    public void cleanup(){tx.executeWithoutResult(status->{
        var control=jdbc.queryForList("select * from plm_crm_openapi_control where singleton and ready and capture_enabled for update");if(control.isEmpty())return;
        String epoch=control.get(0).get("stream_epoch").toString();
        Long cutoff=jdbc.queryForObject("select coalesce(max(change_id),0) from plm_crm_openapi_change where stream_epoch=?::uuid and published_at < clock_timestamp() - (? * interval '1 day')",Long.class,epoch,properties.getChangeRetentionDays());
        if(cutoff!=null&&cutoff>0){
            jdbc.update("delete from plm_crm_openapi_capture where capture_id in (select capture_id from plm_crm_openapi_change where stream_epoch=?::uuid and change_id<=?) and published",epoch,cutoff);
            jdbc.update("delete from plm_crm_openapi_change where stream_epoch=?::uuid and change_id<=?",epoch,cutoff);
            jdbc.update("update plm_crm_openapi_control set retained_after=greatest(retained_after,?) where singleton",cutoff);
        }
        jdbc.update("delete from plm_crm_openapi_idempotency where expires_at<clock_timestamp()");
        // Epoch reset invalidates old cursors. Retain those transport markers only for
        // the configured horizon as well, without touching current unpublished work.
        jdbc.update("delete from plm_crm_openapi_change where stream_epoch<>?::uuid and published_at<clock_timestamp()-(?*interval '1 day')",epoch,properties.getChangeRetentionDays());
        jdbc.update("delete from plm_crm_openapi_capture where stream_epoch<>?::uuid and captured_at<clock_timestamp()-(?*interval '1 day')",epoch,properties.getChangeRetentionDays());
    });}
}

package com.yuewei.plm.module.integration.crm.productlisting;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** No production client is registered until the CRM contract is approved. */
@Component
public class CrmProductListingWorker {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final CrmProductListingProperties properties;
    private final List<CrmProductListingClient> clients;

    public CrmProductListingWorker(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager transactions,
                                  CrmProductListingProperties properties,List<CrmProductListingClient> clients) {
        this.jdbc=jdbc;this.json=json;this.tx=new TransactionTemplate(transactions);this.properties=properties;this.clients=clients;
    }

    @Scheduled(fixedDelayString="${plm.integrations.crm.product-listing.poll-ms:10000}")
    public void tick() {
        if(!properties.isEnabled()||clients.size()!=1||!clients.get(0).ready())return;
        var task=claim();if(task==null)return;
        long id=number(task.get("task_id"));String token=text(task.get("lease_token"));
        var snapshot=decode(json,task.get("snapshot"));String key="plm-listing:"+task.get("listing_id")+":"+task.get("snapshot_version");
        boolean retry=false;
        try {
            if(!sourcesUnchanged(task,snapshot)) { fail(id,token,false,"SOURCE_CHANGED");return; }
            String crmId=text(task.get("crm_product_id"));
            if(crmId.isEmpty()) {
                if(!renew(id,token))return;
                crmId=externalId(clients.get(0).upsertProduct(map(snapshot.get("product")),key+":product"));
                if(jdbc.update("update plm_crm_listing_task set crm_product_id=?,updated_at=now() where task_id=? and lease_token=?::uuid and status='processing'",crmId,id,token)!=1)return;
            }
            for(var sku:maps(snapshot.get("skus"))) {
                long skuId=number(sku.get("internalSkuId"));
                if(Boolean.TRUE.equals(jdbc.queryForObject("select status='synced' or (status='failed' and error_code='CRM_REJECTED') from plm_crm_listing_sku where task_id=? and internal_sku_id=?",Boolean.class,id,skuId)))continue;
                if(!sourcesUnchanged(task,snapshot)) { fail(id,token,false,"SOURCE_CHANGED");return; }
                if(!renew(id,token))return;
                try {
                    String remote=externalId(clients.get(0).upsertSku(crmId,sku,key+":sku:"+skuId));
                    skuResult(id,token,skuId,"synced",remote,"");
                } catch(RuntimeException e) {
                    boolean transientFailure=!(e instanceof CrmProductListingClient.DeliveryException d)||d.retryable();retry|=transientFailure;
                    skuResult(id,token,skuId,"failed",null,transientFailure?"CRM_UNAVAILABLE":"CRM_REJECTED");
                }
            }
            finish(id,token,retry);
        } catch(RuntimeException e) {
            boolean transientFailure=!(e instanceof CrmProductListingClient.DeliveryException d)||d.retryable();
            fail(id,token,transientFailure,transientFailure?"CRM_UNAVAILABLE":"CRM_REJECTED");
        }
    }

    Map<String,Object> claim() {
        return tx.execute(s->{
            // Recover crashed leases using the original task and remote idempotency keys.
            jdbc.update("update plm_crm_listing_task set status=case when attempts>=? then 'failed' else 'pending' end,lease_token=null,lease_until=null,error_code='LEASE_EXPIRED',error_message='投递结果待核对',updated_at=now() where status='processing' and lease_until<now()",maxAttempts());
            var rows=jdbc.queryForList("select task_id from plm_crm_listing_task where status='pending' and next_attempt_at<=now() order by task_id for update skip locked limit 1");
            if(rows.isEmpty())return null;long id=number(rows.get(0).get("task_id"));
            String token=UUID.randomUUID().toString();
            return jdbc.queryForMap("update plm_crm_listing_task set status='processing',attempts=attempts+1,lease_token=?::uuid,lease_until=now()+interval '5 minutes',updated_at=now() where task_id=? returning task_id,listing_id,snapshot_version,snapshot::text,lease_token,crm_product_id",token,id);
        });
    }
    private int maxAttempts() { return Math.max(1,Math.min(10,properties.getMaxAttempts())); }
    private boolean renew(long id,String token) { return jdbc.update("update plm_crm_listing_task set lease_until=now()+interval '5 minutes' where task_id=? and lease_token=?::uuid and status='processing'",id,token)==1; }
    private String externalId(String id) { require(id!=null&&!id.isBlank()&&id.length()<=255,"CRM 未返回有效标识");return id; }

    private boolean sourcesUnchanged(Map<String,Object> task,Map<String,Object> snapshot) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_partner_record l join plm_partner_record p on p.record_id=l.parent_record_id where l.record_id=? and l.deleted_flag=0 and l.status='not_connected' and p.deleted_flag=0 and p.status<>'inactive' and l.version=? and p.version=?)",Boolean.class,number(task.get("listing_id")),number(snapshot.get("listingVersion")),number(snapshot.get("productVersion")))))return false;
        for(var sku:maps(snapshot.get("skus"))) {
            if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_product where product_id=? and product_type='sku' and deleted_flag=0 and master_data_status='active' and master_data_version=?)",Boolean.class,number(sku.get("internalSkuId")),number(sku.get("masterDataVersion")))))return false;
            if(!jdbc.queryForList("with recursive parents as (select product_id,parent_product_id,deleted_flag,master_data_status,array[product_id] as path from plm_product where product_id=? union all select p.product_id,p.parent_product_id,p.deleted_flag,p.master_data_status,a.path||p.product_id from plm_product p join parents a on p.product_id=a.parent_product_id where not p.product_id=any(a.path)) select product_id from parents where deleted_flag<>0 or master_data_status<>'active'",number(sku.get("internalSkuId"))).isEmpty())return false;
            if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_phone_model_member m join plm_phone_brand b on b.id=m.brand_id join plm_code_item c on c.code_item_id=m.code_item_id where m.id=? and m.status='enabled' and b.status='enabled' and c.deleted_flag=0 and c.code_value=?)",Boolean.class,number(sku.get("phoneModelId")),text(sku.get("phoneModelCode")))))return false;
            if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_partner_spec where spec_id=? and record_id=? and deleted_flag=0 and data->>'internalSkuId'=?)",Boolean.class,number(sku.get("specId")),number(map(snapshot.get("product")).get("productId")),text(sku.get("internalSkuId")))))return false;
            var evidence=map(sku.get("evidence"));
            if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_partner_record where record_id=? and kind='inspection' and status='signed' and deleted_flag=0 and data->>'basisHash'=? and jsonb_exists(data,'referenceSample'))",Boolean.class,number(evidence.get("inspectionId")),text(evidence.get("inspectionHash")))))return false;
            String catalogKey=text(evidence.get("catalogSpecKey"));
            String scope=catalogKey.isEmpty()?"x->>'specId'=?":"x->>'catalogSpecKey'=?";
            var newer=jdbc.queryForList("select record_id from plm_partner_record q where q.kind='inspection' and q.deleted_flag=0 and q.status in ('pending_signature','signed') and q.record_id>? and q.supplier_code=? and exists(select 1 from jsonb_array_elements(coalesce(q.data->'resultSnapshot'->'scope','[]'::jsonb)) x where "+scope+")",number(evidence.get("inspectionId")),text(sku.get("supplierCode")),catalogKey.isEmpty()?text(evidence.get("specId")):catalogKey);
            if(!newer.isEmpty())return false;
        }
        for(var photo:maps(map(snapshot.get("product")).get("photos")))if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_attachment where attachment_id=? and deleted_flag=0 and version_no=? and checksum is not distinct from ?)",Boolean.class,number(photo.get("id")),text(photo.get("version")),photo.get("checksum"))))return false;
        return true;
    }
    private void skuResult(long id,String token,long sku,String status,String remote,String code) {
        jdbc.update("update plm_crm_listing_sku set status=?,crm_sku_id=coalesce(?,crm_sku_id),error_code=?,error_message=?,updated_at=now() where task_id=? and internal_sku_id=? and exists(select 1 from plm_crm_listing_task where task_id=? and lease_token=?::uuid and status='processing')",status,remote,code,code.isEmpty()?"":"CRM 未确认接收，请核对或重试",id,sku,id,token);
    }
    private void fail(long id,String token,boolean retry,String code) {
        jdbc.update("update plm_crm_listing_task t set status=case when ? and attempts<? then 'pending' when exists(select 1 from plm_crm_listing_sku s where s.task_id=t.task_id and s.status='synced') then 'partial' else 'failed' end,next_attempt_at=now()+least(900,30*attempts)*interval '1 second',error_code=?,error_message=?,lease_token=null,lease_until=null,updated_at=now() where task_id=? and lease_token=?::uuid and status='processing'",retry,maxAttempts(),code,code.equals("SOURCE_CHANGED")?"资料或质检依据已变化，请重新确认":"CRM 未确认接收，请核对或重试",id,token);
    }
    private void finish(long id,String token,boolean retry) {
        jdbc.update("update plm_crm_listing_task t set status=case when not exists(select 1 from plm_crm_listing_sku s where s.task_id=t.task_id and s.status<>'synced') then 'synced' when ? and attempts<? then 'pending' when exists(select 1 from plm_crm_listing_sku s where s.task_id=t.task_id and s.status='synced') then 'partial' else 'failed' end,next_attempt_at=now()+least(900,30*attempts)*interval '1 second',error_code='',error_message='',lease_token=null,lease_until=null,updated_at=now() where task_id=? and lease_token=?::uuid and status='processing'",retry,maxAttempts(),id,token);
    }
}

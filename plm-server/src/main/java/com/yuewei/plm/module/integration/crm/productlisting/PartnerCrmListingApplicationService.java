package com.yuewei.plm.module.integration.crm.productlisting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.partner.PartnerOperationsAccess;
import com.yuewei.plm.module.partner.PartnerStore;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.partner.PartnerValues.*;

@Service
@RequiredArgsConstructor
public class PartnerCrmListingApplicationService {
    private final PartnerCrmListingSnapshotFactory snapshots;
    private final PartnerStore store;
    private final PartnerOperationsAccess permissions;
    private final PilotAccessService access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CrmProductListingProperties properties;
    private final List<CrmProductListingClient> clients;

    public Map<String,Object> capabilities() {
        access.requireDomesticView(); access.requirePermission("partner:view");
        boolean authorized=access.hasPermission("partner:listing")&&access.hasPermission("partner:listing:crm-push");
        boolean ready=clients.size()==1&&clients.get(0).ready();
        return Map.of("enabled",properties.isEnabled(),"adapterReady",ready,"authorized",authorized,"canSubmit",authorized&&properties.isEnabled()&&ready,
            "reason",!authorized?"未获 CRM 外发权限":!properties.isEnabled()?"CRM 商品上架推送尚未启用":!ready?"等待 CRM 接收接口接入":"");
    }

    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> options(long id) {
        var out=snapshots.options(id,false);out.putAll(capabilities());out.put("sync",sync(id));return out;
    }

    private void requirePush() {
        permissions.sales("listing");access.requirePermission("partner:listing:crm-push");
        var capability=capabilities();require(Boolean.TRUE.equals(capability.get("canSubmit")),text(capability.get("reason")));
    }

    @Transactional
    public Map<String,Object> submit(String requestId,List<Map<String,Object>> items) {
        requirePush();require(items!=null&&!items.isEmpty()&&items.size()<=20,"每次请选择1至20条上架记录");
        var normalized=new ArrayList<Map<String,Object>>();var seen=new HashSet<Long>();
        for(var item:items) {
            long id=number(item.get("listingId"));require(id>0&&seen.add(id),"上架记录重复或无效");
            var skuIds=phoneIds(item.get("internalSkuIds"));require(!skuIds.isEmpty(),"请明确选择本次推送的正式 SKU");
            normalized.add(Map.of("listingId",id,"snapshotVersion",required(item.get("snapshotVersion"),"资料版本"),"internalSkuIds",skuIds));
        }
        normalized.sort(Comparator.comparingLong(i->number(i.get("listingId"))));
        String hash=CrmListingDigest.of(json,Map.of("operation","submit","items",normalized));
        var replay=replay(requestId,hash);if(replay!=null)return replay;
        var tasks=new ArrayList<Long>();
        for(var item:normalized) {
            long id=number(item.get("listingId"));var current=snapshots.options(id,true);
            require(text(current.get("snapshotVersion")).equals(text(item.get("snapshotVersion"))),"上架资料已变化，请重新载入并核对 SKU");
            require(((List<?>)current.get("reasons")).isEmpty(),"商品资料不满足推送条件："+current.get("reasons"));
            require(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_crm_listing_task where listing_id=? and (status in ('pending','processing') or snapshot_version=?))",Boolean.class,id,current.get("snapshotVersion"))),"本记录已有任务，请查看结果或重试失败任务");
            var chosen=phoneIds(item.get("internalSkuIds"));var skus=new ArrayList<Map<String,Object>>();
            for(long sku:chosen) {
                var candidate=maps(current.get("skus")).stream().filter(s->number(s.get("internalSkuId"))==sku).findFirst().orElseThrow(()->invalid("SKU 不属于本次已确认的上架范围"));
                require(Boolean.TRUE.equals(candidate.get("eligible")),text(candidate.get("reason")));skus.add(candidate);
            }
            var snapshot=Map.of("product",current.get("product"),"skus",skus,"listingId",id,"listingVersion",current.get("listingVersion"),"productVersion",current.get("productVersion"),"snapshotVersion",current.get("snapshotVersion"));
            long task=jdbc.queryForObject("insert into plm_crm_listing_task(listing_id,product_id,snapshot_version,snapshot,created_by) values(?,?,?,?::jsonb,?) returning task_id",Long.class,id,number(map(current.get("product")).get("productId")),current.get("snapshotVersion"),encode(json,snapshot),access.actorId());
            for(long sku:chosen)jdbc.update("insert into plm_crm_listing_sku(task_id,internal_sku_id) values(?,?)",task,sku);
            store.event(id,"crm_listing_push",Map.of("taskId",task,"internalSkuIds",chosen,"snapshotVersion",current.get("snapshotVersion")),requestId);
            tasks.add(task);
        }
        remember(requestId,hash,tasks);return Map.of("taskIds",tasks,"status","pending");
    }

    private Map<String,Object> replay(String requestId,String hash) {
        require(requestId!=null&&requestId.matches("[A-Za-z0-9_-]{8,80}"),"提交标识无效");
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))","crm-listing:"+access.actorId()+":"+requestId);
        var rows=jdbc.queryForList("select request_hash,task_ids::text from plm_crm_listing_request where actor_id=? and request_id=?",access.actorId(),requestId);
        if(rows.isEmpty())return null;var row=rows.get(0);require(hash.equals(text(row.get("request_hash"))),"提交标识已用于其他内容");
        var ids=phoneIds(decode(json,"{\"ids\":"+row.get("task_ids")+"}").get("ids"));
        for(long task:ids) {
            var saved=jdbc.queryForMap("select listing_id,product_id,snapshot::text from plm_crm_listing_task where task_id=?",task);
            store.load(number(saved.get("listing_id")),"listing",false);store.load(number(saved.get("product_id")),"product",false);
            for(var sku:maps(decode(json,saved.get("snapshot")).get("skus")))access.requireProduct(number(sku.get("internalSkuId")));
        }
        return Map.of("taskIds",ids,"replayed",true);
    }
    private void remember(String requestId,String hash,List<Long> tasks) {
        jdbc.update("insert into plm_crm_listing_request(actor_id,request_id,request_hash,task_ids) values(?,?,?,?::jsonb)",access.actorId(),requestId,hash,encode(json,tasks));
    }

    public Map<String,Object> sync(long id) {
        var listing=store.load(id,"listing",false);store.load(number(listing.get("productId")),"product",false);
        var tasks=jdbc.queryForList("select task_id as \"taskId\",status,crm_product_id as \"crmProductId\",attempts,error_code as \"errorCode\",error_message as \"errorMessage\",updated_at as \"updatedAt\" from plm_crm_listing_task where listing_id=? order by task_id desc limit 1",id);
        if(tasks.isEmpty())return Map.of("listingId",id,"status","not_submitted","skus",List.of());
        var out=tasks.get(0);out.put("listingId",id);
        for(var sku:jdbc.queryForList("select internal_sku_id from plm_crm_listing_sku where task_id=?",number(out.get("taskId"))))access.requireProduct(number(sku.get("internal_sku_id")));
        out.put("skus",jdbc.queryForList("select internal_sku_id as \"internalSkuId\",status,listing_status as \"listingStatus\",crm_sku_id as \"crmSkuId\",error_code as \"errorCode\",error_message as \"errorMessage\" from plm_crm_listing_sku where task_id=? order by internal_sku_id",number(out.get("taskId"))));return out;
    }

    @Transactional
    public Map<String,Object> retry(long id,long taskId,String requestId) {
        requirePush();String hash=CrmListingDigest.of(json,Map.of("operation","retry","listingId",id,"taskId",taskId));
        var repeated=replay(requestId,hash);if(repeated!=null)return repeated;
        var current=snapshots.options(id,true);
        var rows=jdbc.queryForList("select task_id,status,snapshot_version from plm_crm_listing_task where listing_id=? order by task_id desc limit 1 for update",id);
        require(rows.size()==1&&number(rows.get(0).get("task_id"))==taskId,"任务已变化，请刷新同步结果");var task=rows.get(0);
        require(Set.of("failed","partial").contains(text(task.get("status"))),"只能重试失败或部分失败任务");
        require(text(task.get("snapshot_version")).equals(text(current.get("snapshotVersion")))&&((List<?>)current.get("reasons")).isEmpty(),"资料或资格已变化，请重新核对后创建新推送");
        var selected=jdbc.queryForList("select internal_sku_id from plm_crm_listing_sku where task_id=?",taskId);
        for(var sku:selected)require(maps(current.get("skus")).stream().anyMatch(s->number(s.get("internalSkuId"))==number(sku.get("internal_sku_id"))&&Boolean.TRUE.equals(s.get("eligible"))),"原任务 SKU 已不可推送");
        jdbc.update("update plm_crm_listing_sku set status='pending',error_code='',error_message='' where task_id=? and status<>'synced'",taskId);
        jdbc.update("update plm_crm_listing_task set status='pending',attempts=0,next_attempt_at=now(),error_code='',error_message='',lease_token=null,lease_until=null,updated_at=now() where task_id=?",taskId);
        store.event(id,"crm_listing_retry",Map.of("taskId",taskId),requestId);remember(requestId,hash,List.of(taskId));return Map.of("taskIds",List.of(taskId),"status","pending");
    }
}

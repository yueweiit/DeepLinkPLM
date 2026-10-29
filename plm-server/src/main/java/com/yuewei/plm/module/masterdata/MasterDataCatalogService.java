package com.yuewei.plm.module.masterdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.vo.PageVO;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.yuewei.plm.module.masterdata.MasterDataSnapshot.*;

/** Read projection joining CRM identities to the original PLM objects. No second write path. */
@Service @RequiredArgsConstructor
public class MasterDataCatalogService {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final PilotAccessService access;private final MasterDataSnapshotStore store;
    @Data public static class Query {private int page=1,size=50;private String keyword,seriesCode,brandId,listingStatus,productionStatus;}
    public Map<String,Object> status(){
        access.requireDomesticView();boolean enabled=store.enabled();var out=new LinkedHashMap<String,Object>();out.put("enabled",enabled);out.put("administrator",access.actor().allPermissions());
        if(enabled){var batches=jdbc.queryForList("select source_hash as \"sourceHash\",committed_at as \"committedAt\" from plm_master_data_import where status='committed' order by committed_at desc limit 1");out.put("ready",!batches.isEmpty());if(!batches.isEmpty())out.put("batch",batches.get(0));}else out.put("ready",false);return out;
    }
    public PageVO<Map<String,Object>> skus(Query q){
        read();require(q.page>=1&&q.size>=1&&q.size<=100&&q.page<=100000,"分页范围无效");var args=new ArrayList<Object>();String from=from()+filter(q,args);
        long count=Objects.requireNonNull(jdbc.queryForObject("select count(*)"+from,Long.class,args.toArray()));args.add(q.size);args.add((q.page-1)*q.size);
        var rows=jdbc.queryForList("""
            select s.source_id as "sourceId",s.object_id as "objectId",s.object_type as "objectType",s.phone_model_id as "phoneModelId",
              s.source_code as "itemCode",s.source_data->>'productSeriesId' as "seriesId",s.source_data->>'seriesCode' as "seriesCode",
              s.source_data->>'productName' as "productName",s.source_data->>'modelName' as "modelName",
              s.source_data->>'brandName' as "brandName",s.source_data->>'phoneBrandId' as "brandId",s.source_data->>'modelCode' as "modelCode",
              s.source_data->>'colorShortCode' as "colorShortCode",s.source_data->>'colorNameZh' as "colorNameZh",
              s.source_data->>'colorNameEn' as "colorNameEn",s.source_data->>'colorNameEs' as "colorNameEs",s.source_data->>'version' as "commercialVersion",
              s.source_data->>'_listingStatus' as "listingStatus",s.source_status as "sourceStatus",s.observed_at as "observedAt",
              p.master_data_status as "masterDataStatus",coalesce(p.production_status,'unknown') as "productionStatus",c.production_status as "phoneProductionStatus",p.sales_status as "salesStatus",
              coalesce((s.source_data->>'_purchased')::boolean,false) as purchased,
              (select count(*) from plm_master_data_source same where same.source_system='crm' and same.source_kind='product_sku' and same.source_code=s.source_code and same.organization_id=s.organization_id) as "sameItemCount",
              ps.source_data->>'seriesNameZh' as "seriesName",p.status as "developmentStatus"
            """+from+" order by s.source_data->>'modelCode' desc nulls last,s.source_code,s.source_id limit ? offset ?",args.toArray());
        if(access.hasPermission("finance:read"))for(var row:rows){var price=jdbc.queryForMap("select source_data->>'basePrice' as \"basePrice\" from plm_master_data_source where source_system='crm' and source_kind='product_sku' and source_id=?",row.get("sourceId"));row.putAll(price);row.put("currency",null);}
        return new PageVO<>(rows,q.page,q.size,count,(count+q.size-1)/q.size);
    }
    public Map<String,Object> options(){
        read();String from=from()+filter(new Query(),new ArrayList<>());var out=new LinkedHashMap<String,Object>();
        var series=jdbc.queryForList("select distinct s.source_data->>'seriesCode' as code,ps.source_data->>'seriesNameZh' as name,ps.object_id as \"objectId\",ps.object_type as \"objectType\""+from+" order by code");
        for(var row:series){if("product".equals(row.get("objectType"))){var statuses=jdbc.queryForList("select master_data_status from plm_product where product_id=? and deleted_flag=0",String.class,row.get("objectId"));row.put("masterDataStatus",statuses.isEmpty()?"inactive":statuses.get(0));}String code=row.get("code").toString();row.put("skuCount",jdbc.queryForObject("select count(*)"+from+" and s.source_data->>'seriesCode'=?",Long.class,code));
            var master=jdbc.queryForList("select source_data::text as data from plm_master_data_source where source_system='mes' and source_kind='mes_product' and source_code=?",code);row.put("mes",master.isEmpty()?null:parse(master.get(0).get("data")));}
        out.put("series",series);
        out.put("brands",jdbc.queryForList("select distinct s.source_data->>'phoneBrandId' as id,s.source_data->>'brandName' as name"+from+" order by name"));
        out.put("total",jdbc.queryForObject("select count(*)"+from,Long.class));out.put("listed",jdbc.queryForObject("select count(*)"+from+" and s.source_data->>'_listingStatus'='listed'",Long.class));out.put("purchased",jdbc.queryForObject("select count(*)"+from+" and s.object_type='partner'",Long.class));return out;
    }
    public Map<String,Object> processes(){
        store.requireEnabled();access.requireDomesticView();access.requirePermission("process:view");var result=new LinkedHashMap<String,Object>();
        for(var entry:Map.of("routes","process_flows","operations","operation_templates").entrySet()){
            var rows=jdbc.queryForList("select s.object_id as \"objectId\",s.source_code as \"sourceCode\",s.source_data::text as source from plm_master_data_source s join plm_process p on p.process_id=s.object_id where s.source_kind=? and s.source_system='mes' and p.deleted_flag=0 and (p.product_id is null or "+access.visibleSqlClause("product","p.product_id")+") order by s.source_code",entry.getValue());rows.forEach(r->r.put("source",parse(r.get("source"))));result.put(entry.getKey(),rows);
        }return result;
    }
    public Map<String,Object> sku(String sourceId){
        read();var rows=jdbc.queryForList("select s.source_data::text as data,s.object_type as type,s.object_id as id"+from()+filter(new Query(),new ArrayList<>())+" and s.source_id=?",sourceId);require(rows.size()==1,"当前 SKU 不存在或未获授权");var row=rows.get(0);JsonNode data=parse(row.get("data"));if(!access.hasPermission("finance:read"))((com.fasterxml.jackson.databind.node.ObjectNode)data).remove("basePrice");return Map.of("source",data,"objectType",row.get("type"),"objectId",row.get("id"),"manufacturing",manufacturing(sourceId));
    }
    /** Exact article scope and per-node materials; never expands all shared rows into each SKU. */
    public List<Map<String,Object>> manufacturing(String sourceId){
        read();var source=jdbc.queryForList("select s.source_code,s.object_type,s.object_id,s.source_data::text as data"+from()+filter(new Query(),new ArrayList<>())+" and s.source_id=?",sourceId);require(source.size()==1,"SKU 不存在或未获授权");var row=source.get(0);if("partner".equals(row.get("object_type")))return List.of();String article=row.get("source_code").toString();var data=parse(row.get("data"));String color=text(data,"colorNameZh"),product=text(data,"seriesCode");
        var routes=jdbc.queryForList("""
            select b.product_bom_id as "bomId",b.product_id as "projectId",b.bom_code as "internalBomCode",ms.source_code as "bomCode",b.quantity::text as "outputQty",b.output_inventory_code as "parentCode",b.output_inventory_name as "parentName",b.custom_bom_specification as specification,
              ms.source_data->>'version' as "sourceVersion",b.version_no as "versionNo",b.status,b.confirmed_by as "confirmedBy",b.confirmed_at as "confirmedAt",
              r.product_bom_route_id as "bomRouteId",r.process_id as "processId",r.route_code as "routeCode",r.route_name as "routeName",r.binding_priority as priority
            from plm_master_data_source ms join plm_product_bom baseline on baseline.product_bom_id=ms.object_id
              join plm_product_bom b on b.deleted_flag=0 and (b.product_bom_id=baseline.product_bom_id or (b.source_type='mes_derivative' and b.source_product_bom_id=baseline.product_bom_id and b.status='released' and exists(select 1 from plm_product_bom_route_formal_selection fs where fs.product_bom_id=b.product_bom_id and fs.status='active' and fs.deleted_flag=0)))
              join plm_product_bom_route r on r.product_bom_id=b.product_bom_id and r.deleted_flag=0 and r.status='active'
            where ms.source_system='mes' and ms.source_kind='bom_main' and b.output_inventory_code=? and ?=any(r.article_codes)
              and (b.product_bom_id<>baseline.product_bom_id or not exists(select 1 from plm_product_bom derived join plm_product_bom_route_formal_selection fs on fs.product_bom_id=derived.product_bom_id and fs.status='active' and fs.deleted_flag=0 where derived.source_type='mes_derivative' and derived.source_product_bom_id=baseline.product_bom_id and derived.status='released' and derived.deleted_flag=0))
            order by r.route_code,r.binding_priority desc,b.product_bom_id desc
            """,product,article);
        var result=new ArrayList<Map<String,Object>>();var chosen=new HashMap<String,Integer>();
        for(var route:routes){String code=route.get("routeCode").toString();int priority=((Number)route.get("priority")).intValue();if(chosen.containsKey(code)){require(chosen.get(code)!=priority,"同优先级 BOM 冲突，请工程核对");continue;}
            if(!routeMatches(product,code,color))continue;chosen.put(code,priority);
            long routeId=((Number)route.get("bomRouteId")).longValue();
            String money=access.hasPermission("finance:read")?",i.unit_cost_snapshot as \"unitPrice\",i.currency_code as currency":"";
            var items=jdbc.queryForList("""
                select i.product_bom_item_id as id,i.line_no as "lineNo",i.inventory_id as "inventoryId",i.item_code as "childCode",i.item_name as "childName",i.quantity::text as quantity,i.uom_code as unit,
                  i.unit_raw as "unitRaw",i.unit_cn as "unitCn",i.unit_es as "unitEs",i.component_row_id as "componentRowId",
                  n.process_id as "nodeId",ns.source_code as "nodeCode",n.process_name as "nodeName",op.process_code as "operationCode",t.article_codes is not null as "articleSpecific",
                  inv.stock_uom as "stockUnit",material.source_data->>'_mesEffectiveUnit' as "mesEffectiveUnit",material.source_data->'_unitProjection' as "unitProjection"
                """+money+" from plm_bom_item_target t join plm_product_bom_item i on i.product_bom_item_id=t.product_bom_item_id and i.deleted_flag=0 join plm_process n on n.process_id=t.process_id left join plm_process op on op.process_id=n.operation_master_process_id left join plm_inventory inv on inv.inventory_id=i.inventory_id left join plm_master_data_source material on material.object_id=i.inventory_id and material.source_kind='material_items' left join plm_master_data_source ns on ns.object_type='process' and ns.object_id=n.process_id and ns.source_kind='process_flow_steps' where t.product_bom_route_id=? and (t.article_codes is null or ?=any(t.article_codes)) order by n.sequence_no,i.line_no,i.product_bom_item_id",routeId,article);
            items.forEach(item->{if(item.get("unitProjection")!=null)item.put("unitProjection",parse(item.get("unitProjection")));});
            require(!items.isEmpty(),"当前成品缺少适用投料行；不能显示共享配方全部物料");route.put("items",items);route.put("graph",graph(((Number)route.get("processId")).longValue()));result.add(route);
        }return result;
    }
    public Map<String,Object> graph(long route){
        var owner=jdbc.queryForList("select product_id from plm_process where process_id=? and process_type='routing' and deleted_flag=0",route);require(owner.size()==1,"路线不存在");access.requireProduct(((Number)owner.get(0).get("product_id")).longValue());
        var nodes=jdbc.queryForList("select n.process_id as id,coalesce(s.source_code,n.node_code) as \"nodeCode\",n.local_node_code as \"localNodeCode\",n.process_name as name,n.sequence_no as \"stepNo\",n.position_x as x,n.position_y as y,coalesce(op.process_code,n.process_param_json->>'operationCode') as \"operationCode\",n.process_param_json as config from plm_process n left join plm_process op on op.process_id=n.operation_master_process_id left join plm_master_data_source s on s.object_id=n.process_id and s.source_kind='process_flow_steps' where n.parent_process_id=? and n.deleted_flag=0 order by n.sequence_no,n.process_id",route);
        nodes.forEach(n->{if(n.get("config")!=null)n.put("config",parse(n.get("config")));});
        var edges=jdbc.queryForList("select edge_id as id,from_process_id as \"fromNodeId\",to_process_id as \"toNodeId\",edge_no as \"edgeNo\",relation_type as \"relationType\",label,condition_json as condition from plm_process_edge where route_id=? order by edge_no,edge_id",route);
        edges.forEach(e->{if(e.get("condition")!=null)e.put("condition",parse(e.get("condition")));});return Map.of("nodes",nodes,"edges",edges);
    }
    private boolean routeMatches(String product,String route,String color){
        var bindings=jdbc.queryForList("select source_data->'condition_json' as condition from plm_master_data_source where source_system='mes' and source_kind='product_route_binding' and source_data->>'product_code'=? and source_data->>'route_code'=? and source_status='enabled'",product,route);
        for(var binding:bindings){Object raw=binding.get("condition");if(raw==null||"null".equals(raw.toString()))return true;var condition=parse(raw);var include=strings(condition.path("color_names"));var exclude=strings(condition.path("exclude_color_names"));if((include.isEmpty()||include.contains(color))&&!exclude.contains(color))return true;}return false;
    }
    private String from(){return " from plm_master_data_source s left join plm_product p on s.object_type='product' and p.product_id=s.object_id and p.deleted_flag=0 left join plm_phone_model_member pm on pm.id=s.phone_model_id left join plm_code_item c on c.code_item_id=pm.code_item_id left join plm_master_data_source ps on ps.source_system='crm' and ps.source_kind='product_series' and ps.organization_id=s.organization_id and ps.source_id=s.source_data->>'productSeriesId'";}
    private String filter(Query q,List<Object> args){
        String where=" where s.source_system='crm' and s.source_kind='product_sku' and s.batch_hash=(select source_hash from plm_master_data_import where status='committed' order by committed_at desc limit 1) and ((s.object_type='product' and p.product_id is not null and "+access.visibleSqlClause("product","s.object_id")+") or (s.object_type='partner' and "+access.visibleSqlClause("partner","s.object_id")+"))";
        if(q.keyword!=null&&!q.keyword.isBlank()){require(q.keyword.length()<=128,"关键词过长");where+=" and (s.source_code ilike ? or s.source_data->>'productName' ilike ? or s.source_data->>'modelName' ilike ?)";String value="%"+q.keyword.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";args.addAll(List.of(value,value,value));}
        if(q.seriesCode!=null&&!q.seriesCode.isBlank()){where+=" and s.source_data->>'seriesCode'=?";args.add(q.seriesCode);}
        if(q.brandId!=null&&!q.brandId.isBlank()){where+=" and s.source_data->>'phoneBrandId'=?";args.add(q.brandId);}
        if(q.listingStatus!=null&&!q.listingStatus.isBlank()){require(Set.of("listed","unlisted").contains(q.listingStatus),"上架状态无效");where+=" and s.source_data->>'_listingStatus'=?";args.add(q.listingStatus);}
        if(q.productionStatus!=null&&!q.productionStatus.isBlank()){require(Set.of("unknown","active","discontinued").contains(q.productionStatus),"生产状态无效");where+=" and coalesce(p.production_status,'unknown')=?";args.add(q.productionStatus);}return where;
    }
    private JsonNode parse(Object raw){try{return json.readTree(raw.toString());}catch(Exception e){throw new IllegalStateException("来源资料解析失败",e);}}
    private void read(){store.requireEnabled();access.requireDomesticView();if(!access.hasPermission("product:view"))access.requirePermission("collaboration:view");}
}

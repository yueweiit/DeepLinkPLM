package com.yuewei.plm.module.masterdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuewei.plm.module.bom.service.BomConfirmationState;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
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
    private void checkBomPage(Query q) {
        read();
        require(q.page >= 1 && q.page <= 100000 && q.size >= 1 && q.size <= 100, "分页范围无效");
    }
    private String visibleBomRows(Query q,List<Object> args) {
        return """
            select jsonb_build_array(s.organization_id,s.source_data->>'productSeriesId',
                     case when nullif(s.source_data->>'productSeriesId','') is null then s.source_id end)::text as "productKey",
                   jsonb_build_array(s.organization_id,s.source_data->>'productSeriesId',s.source_data->>'phoneModelId',
                     coalesce(s.source_data->>'version',''),case when nullif(s.source_data->>'productSeriesId','') is null
                        or nullif(s.source_data->>'phoneModelId','') is null then s.source_id end)::text as "groupKey",
                   s.source_id as "sourceId", s.object_id as "objectId", s.object_type as "objectType", s.source_code as "itemCode",
                   s.source_data->>'seriesCode' as "seriesCode", coalesce(ps.source_data->>'seriesNameZh',s.source_data->>'seriesCode') as "seriesName",
                   ps.source_data->>'seriesNameEs' as "seriesNameEs", s.source_data->>'modelName' as "modelName", coalesce(p.production_status,'unknown') as "productionStatus",
                   s.source_data->>'version' as "commercialVersion", s.source_data->>'modelCode' as "modelCode",
                   s.source_data->>'colorNameZh' as "colorNameZh", s.source_data->>'colorNameEs' as "colorNameEs", s.source_data->>'colorNameEn' as "colorNameEn",
                   s.source_data->>'colorShortCode' as "colorShortCode", s.source_data->>'colorCode' as "colorCode"
            """ + ", " + keyword(q,args,true) + " as matched " + from() + filter(q,args,true);
    }

    public PageVO<Map<String,Object>> bomProducts(Query q) {
        checkBomPage(q);
        var args = new ArrayList<Object>();
        String cte = "with visible as (" + visibleBomRows(q,args) + "), products as (" + """
            select "productKey" as key,min("seriesName") as "seriesName",min("seriesNameEs") as "seriesNameEs",
                   min("seriesCode") as "seriesCode",count(distinct "groupKey") as "modelCount"
            from visible where matched group by "productKey")
            """;
        long total = jdbc.queryForObject(cte+"select count(*) from products",Long.class,args.toArray());
        args.add(q.size); args.add((q.page-1)*q.size);
        var products=jdbc.queryForList(cte+"select * from products order by \"modelCount\" desc,\"seriesCode\",key limit ? offset ?",args.toArray());
        return new PageVO<>(products,q.page,q.size,total,(total+q.size-1)/q.size);
    }

    public PageVO<Map<String,Object>> bomGroups(Query q,String productKey) {
        checkBomPage(q);
        var args = new ArrayList<Object>();
        String visible = "select * from ("+visibleBomRows(q,args)+") scoped where \"productKey\"=?";
        args.add(productKey);
        String cte = "with visible as materialized (" + visible + "), groups as (select \"groupKey\",max(\"modelCode\") as model from visible group by \"groupKey\" having bool_or(matched)) ";
        long total = jdbc.queryForObject(cte + "select count(*) from groups",Long.class,args.toArray());
        args.add(q.size); args.add((q.page-1)*q.size);
        var rows = jdbc.queryForList(cte + """
            , page as (select * from groups order by model desc nulls last,"groupKey" limit ? offset ?)
            select v.* from page join visible v using ("groupKey")
            order by page.model desc nulls last,page."groupKey",v."colorShortCode" nulls last,v."itemCode",v."sourceId"
            """,args.toArray());
        var routes = lookup(rows.stream().filter(row -> "product".equals(row.get("objectType"))).toList());
        var groups = new LinkedHashMap<String,List<Map<String,Object>>>();
        for (var row : rows) {
            var snapshots = BomCatalogRecipes.applicable(routes.getOrDefault(row.get("sourceId"), List.of()),true);
            var summaries = snapshots.stream().map(r -> Map.of("bomId",r.get("bomId"),"bomRouteId",r.get("bomRouteId"),
                "version",r.get("displayVersion"),"state",r.get("engineeringState"),"routeCode",r.get("routeCode"))).toList();
            row.put("recipes",summaries);
            groups.computeIfAbsent((String)row.remove("groupKey"),key -> new ArrayList<>()).add(row);
        }
        List<Map<String,Object>> content = groups.entrySet().stream()
            .map(entry -> Map.<String,Object>of("key",entry.getKey(),"colors",entry.getValue())).toList();
        return new PageVO<>(content,q.page,q.size,total,(total+q.size-1)/q.size);
    }

    private Map<String,List<Map<String,Object>>> lookup(List<Map<String,Object>> rows) {
        if (rows.isEmpty()) return Map.of();
        return BomCatalogRecipes.find(jdbc,json,rows,access.visibleSqlClause("product","b.product_id"));
    }

    private Map<String,Object> sourceSku(String sourceId) {
        read();
        var rows=jdbc.queryForList("select s.source_data::text as data,s.object_type as type,s.object_id as id,s.source_code as article"+from()+filter(new Query(),new ArrayList<>())+" and s.source_id=?",sourceId);
        require(rows.size()==1,"当前 SKU 不存在或未获授权");
        return rows.get(0);
    }

    public Map<String,Object> sku(String sourceId) { return sku(sourceId,false); }
    public Map<String,Object> sku(String sourceId,boolean versions) {
        var source=sourceSku(sourceId);
        var data=parse(source.get("data"));
        if(!access.hasPermission("finance:read")) ((com.fasterxml.jackson.databind.node.ObjectNode)data).remove("basePrice");
        return Map.of("source",data,"objectType",source.get("type"),"objectId",source.get("id"),
            "manufacturing",materials(sourceId,source,data,versions));
    }
    public List<Map<String,Object>> manufacturing(String sourceId) {
        var source=sourceSku(sourceId);
        return materials(sourceId,source,parse(source.get("data")),false);
    }
    private List<Map<String,Object>> recipesFor(String sourceId, Map<String,Object> source, JsonNode data, boolean versions) {
        if (!"product".equals(source.get("type"))) return List.of();
        var request=new LinkedHashMap<String,Object>();
        request.put("sourceId",sourceId); request.put("seriesCode",text(data,"seriesCode"));
        request.put("itemCode",source.get("article")); for (String key : List.of("colorCode","colorNameZh","colorNameEn","colorNameEs")) request.put(key,text(data,key));
        var routes=lookup(List.of(request)).getOrDefault(sourceId,List.of());
        var selected=BomCatalogRecipes.applicable(routes,versions);
        require(selected.stream().noneMatch(r -> "conflict".equals(r.get("engineeringState"))),"同优先级 BOM 冲突，请工程核对");
        return selected;
    }
    private List<Map<String,Object>> materials(String sourceId,Map<String,Object> source,JsonNode data,boolean versions) {
        var result=new ArrayList<Map<String,Object>>();
        for(var route:recipesFor(sourceId,source,data,versions)){
            String article=source.get("article").toString();
            long routeId=((Number)route.get("bomRouteId")).longValue();
            String money=access.hasPermission("finance:read")?",i.unit_cost_snapshot as \"unitPrice\",i.currency_code as currency":"";
            var items=jdbc.queryForList("""
                select i.product_bom_item_id as id,i.line_no as "lineNo",i.inventory_id as "inventoryId",i.item_code as "childCode",i.item_name as "childName",i.quantity::text as quantity,i.uom_code as unit,
                  i.unit_raw as "unitRaw",i.unit_cn as "unitCn",i.unit_es as "unitEs",i.component_row_id as "componentRowId",
                  n.process_id as "nodeId",ns.source_code as "nodeCode",n.process_name as "nodeName",op.process_code as "operationCode",t.article_codes is not null as "articleSpecific",
                  inv.stock_uom as "stockUnit",material.source_data->>'_mesEffectiveUnit' as "mesEffectiveUnit",material.source_data->'_unitProjection' as "unitProjection"
                """+money+" from plm_bom_item_target t join plm_product_bom_item i on i.product_bom_item_id=t.product_bom_item_id and i.deleted_flag=0 join plm_process n on n.process_id=t.process_id left join plm_process op on op.process_id=n.operation_master_process_id left join plm_inventory inv on inv.inventory_id=i.inventory_id left join plm_master_data_source material on material.object_id=i.inventory_id and material.source_kind='material_items' left join plm_master_data_source ns on ns.object_type='process' and ns.object_id=n.process_id and ns.source_kind='process_flow_steps' where t.product_bom_route_id=? and (t.article_codes is null or ?=any(t.article_codes)) order by n.sequence_no,i.line_no,i.product_bom_item_id",routeId,article);
            items.forEach(item->{if(item.get("unitProjection")!=null)item.put("unitProjection",parse(item.get("unitProjection")));});
            if(!versions)require(!items.isEmpty(),"当前成品缺少适用投料行；不能显示共享配方全部物料");route.put("items",items);route.put("graph",graph(((Number)route.get("processId")).longValue()));result.add(route);
        }return result;
    }
    public Map<String,Object> graph(long route){
        var owner=jdbc.queryForList("select product_id from plm_process where process_id=? and process_type='routing' and deleted_flag=0",route);require(owner.size()==1,"路线不存在");access.requireProduct(((Number)owner.get(0).get("product_id")).longValue());
        var nodes=jdbc.queryForList("select n.process_id as id,coalesce(s.source_code,n.node_code) as \"nodeCode\",n.local_node_code as \"localNodeCode\",n.process_name as name,n.sequence_no as \"stepNo\",n.position_x as x,n.position_y as y,coalesce(op.process_code,n.process_param_json->>'operationCode') as \"operationCode\",n.process_param_json as config from plm_process n left join plm_process op on op.process_id=n.operation_master_process_id left join plm_master_data_source s on s.object_id=n.process_id and s.source_kind='process_flow_steps' where n.parent_process_id=? and n.deleted_flag=0 order by n.sequence_no,n.process_id",route);
        nodes.forEach(n->{if(n.get("config")!=null)n.put("config",parse(n.get("config")));});
        var edges=jdbc.queryForList("select edge_id as id,from_process_id as \"fromNodeId\",to_process_id as \"toNodeId\",edge_no as \"edgeNo\",relation_type as \"relationType\",label,condition_json as condition from plm_process_edge where route_id=? order by edge_no,edge_id",route);
        edges.forEach(e->{if(e.get("condition")!=null)e.put("condition",parse(e.get("condition")));});return Map.of("nodes",nodes,"edges",edges);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> confirmationScope(String sourceId,long bomId,long routeId) {
        var source=sourceSku(sourceId);
        var target=recipesFor(sourceId,source,parse(source.get("data")),true).stream()
            .filter(r -> ((Number)r.get("bomId")).longValue()==bomId && ((Number)r.get("bomRouteId")).longValue()==routeId
                && "pending".equals(r.get("engineeringState"))).findFirst();
        require(target.isPresent(),"版本状态已变化，请刷新用料");
        var recipe=target.orElseThrow();
        access.requirePermission("bom:publish"); access.requireEngineeringMaintenance();
        access.requireProduct(((Number)recipe.get("projectId")).longValue());
        require(Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from plm_bom_item_target t join plm_product_bom_item i
                on i.product_bom_item_id=t.product_bom_item_id and i.deleted_flag=0
                where t.product_bom_route_id=? and (t.article_codes is null or ?=any(t.article_codes)))
            """,Boolean.class,routeId,source.get("article"))),"此品目缺少适用投料");
        String scope="with previous_routes as ("+BomConfirmationState.PREVIOUS_ROUTES+"), "+"""
            new_codes as (select distinct unnest(article_codes) as code from plm_product_bom_route
                          where product_bom_id=? and deleted_flag=0 and status='active'),
            removed_codes as (select unnest(article_codes) from previous_routes except select code from new_codes),
            coverage(code,removed) as (select code,false from new_codes union all select *,true from removed_codes)
            """;
        var counts=jdbc.queryForMap(scope+"select count(code) filter(where not removed) as included,count(code) filter(where removed) as removed from coverage",bomId,bomId);
        var visible=jdbc.queryForList(scope+"select distinct s.source_code as code,s.source_data->>'modelName' as model,cv.removed"
            +from()+" join coverage cv on cv.code=s.source_code "+filter(new Query(),new ArrayList<>())
            +" and s.object_type='product' and s.source_data->>'seriesCode'=? order by code,model",bomId,bomId,recipe.get("parentCode"));
        var articles=new ArrayList<Map<String,Object>>();var removed=new ArrayList<Map<String,Object>>();
        for(var item:visible){(Boolean.TRUE.equals(item.remove("removed"))?removed:articles).add(item);}
        var previous=jdbc.queryForList("""
            select distinct case when b.product_bom_id=? then coalesce(nullif(?,''),b.version_no) else b.version_no end
              from plm_product_bom b join plm_product_bom_route_formal_selection f on f.product_bom_id=b.product_bom_id
             where f.status='active' and f.deleted_flag=0 and b.deleted_flag=0 and b.product_id=?
               and (b.product_bom_id=? or b.source_product_bom_id=?) and b.product_bom_id<>? order by 1
            """,String.class,recipe.get("family"),recipe.get("sourceVersion"),recipe.get("projectId"),recipe.get("family"),recipe.get("family"),bomId);
        return Map.of("count",counts.get("included"),"articles",articles,"removedCount",counts.get("removed"),"removedArticles",removed,"previousVersions",previous,"revision",BomConfirmationState.revision(jdbc,bomId));
    }
    private String from(){return " from plm_master_data_source s left join plm_product p on s.object_type='product' and p.product_id=s.object_id and p.deleted_flag=0 left join plm_phone_model_member pm on pm.id=s.phone_model_id left join plm_code_item c on c.code_item_id=pm.code_item_id left join plm_master_data_source ps on ps.source_system='crm' and ps.source_kind='product_series' and ps.organization_id=s.organization_id and ps.source_id=s.source_data->>'productSeriesId'";}
    private String filter(Query q,List<Object> args){return filter(q,args,false);}
    private String filter(Query q,List<Object> args,boolean bom){
        String where=" where s.source_system='crm' and s.source_kind='product_sku' and s.batch_hash=(select source_hash from plm_master_data_import where status='committed' order by committed_at desc limit 1) and ((s.object_type='product' and p.product_id is not null and "+access.visibleSqlClause("product","s.object_id")+") or (s.object_type='partner' and "+access.visibleSqlClause("partner","s.object_id")+"))";
        if(!bom)where+=" and "+keyword(q,args,false);
        if(q.seriesCode!=null&&!q.seriesCode.isBlank()){where+=" and s.source_data->>'seriesCode'=?";args.add(q.seriesCode);}
        if(q.brandId!=null&&!q.brandId.isBlank()){where+=" and s.source_data->>'phoneBrandId'=?";args.add(q.brandId);}
        if(q.listingStatus!=null&&!q.listingStatus.isBlank()){require(Set.of("listed","unlisted").contains(q.listingStatus),"上架状态无效");where+=" and s.source_data->>'_listingStatus'=?";args.add(q.listingStatus);}
        if(q.productionStatus!=null&&!q.productionStatus.isBlank()){require(Set.of("unknown","active","discontinued").contains(q.productionStatus),"生产状态无效");where+=" and coalesce(p.production_status,'unknown')=?";args.add(q.productionStatus);}return where;
    }
    private String keyword(Query q,List<Object> args,boolean bom){
        if(q.keyword!=null&&!q.keyword.isBlank()){
            require(q.keyword.length()<=128,"关键词过长");
            String value="%"+q.keyword.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
            var fields=new ArrayList<>(List.of("s.source_code","s.source_data->>'productName'","s.source_data->>'modelName'"));
            if(bom) fields.addAll(List.of("ps.source_data->>'seriesNameZh'","ps.source_data->>'seriesNameEs'","s.source_data->>'modelCode'","s.source_data->>'colorNameZh'","s.source_data->>'colorNameEs'"));
            String predicate="("+String.join(" or ",fields.stream().map(f->f+" ilike ?").toList())+")";
            fields.forEach(f->args.add(value));return predicate;
        }
        return "true";
    }
    private JsonNode parse(Object raw){try{return json.readTree(raw.toString());}catch(Exception e){throw new IllegalStateException("来源资料解析失败",e);}}
    private void read(){store.requireEnabled();access.requireDomesticView();if(!access.hasPermission("product:view"))access.requirePermission("collaboration:view");}
}

package com.yuewei.plm.module.masterdata;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.yuewei.plm.module.masterdata.MasterDataSnapshot.matchesColor;
import org.springframework.jdbc.core.JdbcTemplate;

/** One resolver for color rows, scoped materials and the engineering confirmation target. */
final class BomCatalogRecipes {
    private BomCatalogRecipes() {}

    static Map<String,List<Map<String,Object>>> find(JdbcTemplate jdbc, ObjectMapper json, List<Map<String,Object>> requests, String productScope) {
        String sql = """
            with requested as materialized (
              select * from jsonb_to_recordset(?::jsonb)
                as s("sourceId" text, "seriesCode" text, "itemCode" text)
            ), families as materialized (
              select ms.object_id as family, ms.source_code as "bomCode", ms.source_data->>'version' as "sourceVersion",
                     exists(select 1 from plm_product_bom d join plm_product_bom_route_formal_selection f
                       on f.product_bom_id=d.product_bom_id and f.status='active' and f.deleted_flag=0
                       where d.source_product_bom_id=ms.object_id and d.source_type='mes_derivative'
                         and d.status='released' and d.deleted_flag=0) as replaced
              from plm_master_data_source ms where ms.source_system='mes' and ms.source_kind='bom_main'
            ), revisions as materialized (
              select fam.*, b.product_bom_id as "bomId", b.product_id as "projectId", b.bom_code as "internalBomCode",
                     b.output_inventory_code as "parentCode", b.output_inventory_name as "parentName", b.quantity::text as "outputQty",
                     b.custom_bom_specification as specification, b.version_no as "versionNo", b.status,
                     b.confirmed_by as "confirmedBy", b.confirmed_at as "confirmedAt",
                     case when b.product_bom_id=fam.family then coalesce(nullif(fam."sourceVersion",''),b.version_no) else b.version_no end as "displayVersion",
                     r.product_bom_route_id as "bomRouteId", r.process_id as "processId", r.route_code as "routeCode",
                     r.route_name as "routeName", r.binding_priority as priority, r.article_codes,
                     (b.product_bom_id=fam.family and not fam.replaced or sel.active) as effective,
                     case when sel.active then 'released'
                       when b.status='released' or (b.product_bom_id=fam.family and fam.replaced) then 'history'
                       else 'pending' end as "engineeringState"
              from families fam join plm_product_bom b on (b.product_bom_id=fam.family
                or b.source_type='mes_derivative' and b.source_product_bom_id=fam.family)
              join plm_product_bom_route r on r.product_bom_id=b.product_bom_id and r.deleted_flag=0 and r.status='active'
              cross join lateral (select exists(select 1 from plm_product_bom_route_formal_selection f
                where f.product_bom_id=b.product_bom_id and f.product_bom_route_id=r.product_bom_route_id
                  and f.status='active' and f.deleted_flag=0) as active) sel
              where b.deleted_flag=0 and b.status in ('frozen','reviewing','released')
                and b.output_inventory_code in (select "seriesCode" from requested) and (%s)
            ), bindings as materialized (
              select source_data->>'product_code' as product, source_data->>'route_code' as route, jsonb_agg(source_data->'condition_json')::text as conditions
                from plm_master_data_source where source_system='mes' and source_kind='product_route_binding' and source_status='enabled'
                group by 1,2
            )
            select s."sourceId", r.family, r."bomId",r."projectId",r."bomCode",r."internalBomCode",r."outputQty",r."parentCode",r."parentName",
                   r.specification,r."sourceVersion",r."versionNo",r."displayVersion",r.status,r."engineeringState",r."confirmedBy",r."confirmedAt",
                   r."bomRouteId",r."processId",r."routeCode",r."routeName",r.priority,r.effective,a.conditions
              from requested s join revisions r on r."parentCode"=s."seriesCode" and s."itemCode"=any(r.article_codes)
              join bindings a on a.product=r."parentCode" and a.route=r."routeCode"
              order by s."sourceId",r."routeCode",r.priority desc,r."bomId" desc,r."bomRouteId"
            """.formatted(productScope);
        var results = new LinkedHashMap<String,List<Map<String,Object>>>();
        var skuById = new HashMap<String,JsonNode>();
        JsonNode requested = json.valueToTree(requests);
        requested.forEach(sku -> skuById.put(sku.path("sourceId").asText(),sku));
        var bindings = new HashMap<String,JsonNode>();
        for (var row : jdbc.queryForList(sql, requested.toString())) {
            String id = (String)row.remove("sourceId");
            var conditions = bindings.computeIfAbsent((String)row.remove("conditions"), raw -> {
                try { return json.readTree(raw); } catch (java.io.IOException e) { throw new IllegalStateException("路线适用条件无效",e); }
            });
            for (var condition : conditions) {
                if (matchesColor(condition,skuById.get(id))) {
                    results.computeIfAbsent(id,key -> new ArrayList<>()).add(row);
                    break;
                }
            }
        }
        return results;
    }

    /** Binding priority competes between source families, never between versions of the same family. */
    static List<Map<String,Object>> applicable(List<Map<String,Object>> candidates, boolean withVersions) {
        var result = new ArrayList<Map<String,Object>>();
        var byRoute = new LinkedHashMap<Object,List<Map<String,Object>>>();
        for (var row : candidates) byRoute.computeIfAbsent(row.get("routeCode"), key -> new ArrayList<>()).add(row);
        for (var rows : byRoute.values()) {
            var current = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("effective"))).toList();
            var eligible = current;
            if (current.isEmpty() && withVersions) {
                var pending = rows.stream().filter(r -> "pending".equals(r.get("engineeringState"))).toList();
                eligible = pending.isEmpty() ? rows : pending;
            }
            if (eligible.isEmpty()) continue;
            var first = eligible.get(0);
            var leaders = eligible.stream().filter(r -> Objects.equals(r.get("priority"),first.get("priority"))).toList();
            if (leaders.stream().map(r -> r.get("family")).distinct().count()>1 || current.size()>1
                    && leaders.stream().filter(r -> Boolean.TRUE.equals(r.get("effective"))).count()>1) {
                var conflict = new LinkedHashMap<>(first);
                conflict.put("engineeringState","conflict");
                result.add(conflict);
                continue;
            }
            if (!withVersions) result.add(first);
            else rows.stream().filter(r -> Objects.equals(r.get("family"),first.get("family"))).forEach(result::add);
        }
        return result;
    }
}

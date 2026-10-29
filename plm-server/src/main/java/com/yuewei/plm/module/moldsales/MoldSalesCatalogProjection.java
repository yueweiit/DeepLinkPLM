package com.yuewei.plm.module.moldsales;

import com.yuewei.plm.module.moldreference.PhoneIdentityMatcher;
import com.yuewei.plm.module.moldreference.PhoneModelCoverageService;
import com.yuewei.plm.module.moldreference.PhoneSelectionRelations;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only identity decoration. Historical order aliases never become phone identities. */
final class MoldSalesCatalogProjection {
    private final JdbcTemplate jdbc;private final PilotAccessService access;
    private List<Map<String,Object>> phones,products;
    private Map<Long,List<Map<String,Object>>> coverage;
    MoldSalesCatalogProjection(JdbcTemplate jdbc,PilotAccessService access){this.jdbc=jdbc;this.access=access;}
    List<Map<String,Object>> products(){
        if(products==null)products=jdbc.queryForList("select p.product_id as \"productLineId\",p.product_name as \"productName\",p.product_code as \"productCode\",p.product_specific_code as \"styleCode\",p.status,p.master_data_status as \"masterDataStatus\" from plm_product p where p.deleted_flag=0 and p.product_type='product_line' and ("+access.visibleSqlClause("product","p.product_id")+" or exists(select 1 from plm_collaboration_item i where i.deleted_flag=0 and i.product_line_id=p.product_id and ("+access.visibleSqlClause("item","i.item_id")+"))) order by p.product_name,p.product_id");
        return products;
    }
    List<Map<String,Object>> phones(){
        if(phones==null)phones=jdbc.queryForList("select m.id as \"phoneModelId\",m.brand_id as \"brandId\",b.name as \"brandName\",m.model_name as \"modelName\",m.code_item_id as \"codeItemId\",c.code_value as \"phoneCode\" from plm_phone_model_member m join plm_phone_brand b on b.id=m.brand_id left join plm_code_item c on c.code_item_id=m.code_item_id");
        return phones;
    }
    Map<Long,List<Map<String,Object>>> coverage(){
        if(coverage==null)coverage=new PhoneModelCoverageService(jdbc,access).batch(phones().stream().map(p->id(p.get("phoneModelId"))).toList());return coverage;
    }
    @SuppressWarnings("unchecked") void enrich(List<Map<String,Object>> groups){
        var codeRows=jdbc.queryForList("select c.code_value as \"phoneCode\",c.code_name as \"modelName\",b.id as \"brandId\",b.name as \"brandName\" from plm_code_item c left join plm_phone_code_brand_effective cb on cb.code_item_id=c.code_item_id left join plm_phone_brand b on b.id=cb.brand_id where c.code_type='phone_model' and c.deleted_flag=0");
        var byCode=new HashMap<String,List<Map<String,Object>>>();for(var c:codeRows)byCode.computeIfAbsent(Objects.toString(c.get("phoneCode"),""),k->new ArrayList<>()).add(c);
        var membersByCode=new HashMap<String,List<Map<String,Object>>>();for(var p:phones())membersByCode.computeIfAbsent(Objects.toString(p.get("phoneCode"),""),k->new ArrayList<>()).add(p);
        var membersByItem=new HashMap<Long,Set<Long>>();
        for(var r:jdbc.queryForList("with "+PhoneSelectionRelations.members()+" select distinct im.item_id,im.phone_id from item_members im join plm_collaboration_item i on i.item_id=im.item_id where i.deleted_flag=0 and ("+access.visibleSqlClause("item","i.item_id")+")"))membersByItem.computeIfAbsent(id(r.get("item_id")),k->new TreeSet<>()).add(id(r.get("phone_id")));
        var directBrands=new HashMap<Long,Map<String,Object>>();for(var r:jdbc.queryForList("select i.item_id,b.id as \"brandId\",b.name as \"brandName\" from plm_collaboration_item i join plm_phone_brand b on b.id=i.brand_id where i.deleted_flag=0 and ("+access.visibleSqlClause("item","i.item_id")+")"))directBrands.put(id(r.get("item_id")),r);
        var phoneById=new HashMap<Long,Map<String,Object>>();for(var p:phones())phoneById.put(id(p.get("phoneModelId")),p);
        var rootById=new HashMap<Long,Map<String,Object>>();for(var p:products())rootById.put(id(p.get("productLineId")),p);
        for(var g:groups){
            var canonical=new TreeSet<String>();for(var r:(List<Map<String,Object>>)g.get("productionRecords")){String code=Objects.toString(r.get("phoneCode"),"");if(code.matches("[0-9]{4}"))canonical.add(code);}
            // Explicit fit codes are genuine group identities; raw reconciled order codes are not.
            var aliasCodes=new HashSet<String>();for(var alias:(List<com.fasterxml.jackson.databind.JsonNode>)g.getOrDefault("salesAliases",List.of()))aliasCodes.add(alias.path("phoneCode").asText());
            for(String code:(Collection<String>)g.get("phoneCodes"))if(!aliasCodes.contains(code))canonical.add(code);
            var phoneIds=new TreeSet<Long>();var brands=new TreeMap<Long,String>();var names=new LinkedHashSet<>((Collection<String>)g.get("modelNames"));
            var confirmedScope=new LinkedHashSet<String>();for(var alias:(List<com.fasterxml.jackson.databind.JsonNode>)g.getOrDefault("salesAliases",List.of()))for(var model:alias.path("scopeEvidence").path("confirmedModels"))confirmedScope.add(model.asText());
            if(!confirmedScope.isEmpty())names.add(String.join(" / ",confirmedScope)+"（历史订单共模范围）");g.put("modelNames",names);
            boolean historical=!confirmedScope.isEmpty()||names.stream().anyMatch(n->n.contains("历史订单通用款"));g.put("salesScopeOnly",historical);
            for(long item:MoldSalesMatrixProjection.ids(g.get("itemIds"))){phoneIds.addAll(membersByItem.getOrDefault(item,Set.of()));var brand=directBrands.get(item);if(brand!=null)brands.put(id(brand.get("brandId")),Objects.toString(brand.get("brandName")));}
            for(String code:canonical){
                for(var row:byCode.getOrDefault(code,List.of()))if(row.get("brandId")!=null)brands.put(id(row.get("brandId")),Objects.toString(row.get("brandName")));
                // A shared code is not proof that this archive fits each member individually.
                var candidates=membersByCode.getOrDefault(code,List.of());var codeNames=byCode.getOrDefault(code,List.of());
                if(!historical&&candidates.size()==1&&codeNames.stream().allMatch(c->PhoneIdentityMatcher.isSingleModel(Objects.toString(c.get("modelName"),""))))phoneIds.add(id(candidates.get(0).get("phoneModelId")));
            }
            for(long phone:phoneIds){var p=phoneById.get(phone);if(p!=null)brands.put(id(p.get("brandId")),Objects.toString(p.get("brandName")));}
            boolean shared=historical||canonical.size()>1||phoneIds.size()>1||names.stream().anyMatch(n->!PhoneIdentityMatcher.isSingleModel(n))||canonical.stream().anyMatch(c->byCode.getOrDefault(c,List.of()).stream().anyMatch(r->!PhoneIdentityMatcher.isSingleModel(Objects.toString(r.get("modelName"),""))));
            String kind=shared?"shared":phoneIds.size()==1?"single":"unresolved";
            boolean customScope=canonical.size()==1&&names.stream().anyMatch(n->!PhoneIdentityMatcher.isSingleModel(n))&&canonical.stream().allMatch(c->byCode.getOrDefault(c,List.of()).stream().allMatch(r->PhoneIdentityMatcher.isSingleModel(Objects.toString(r.get("modelName"),""))));
            String rowKey=historical?"history:"+g.get("groupId"):customScope?"archive:"+g.get("groupId"):!shared&&phoneIds.size()==1?"phone:"+phoneIds.first():!canonical.isEmpty()?"code:"+String.join("+",canonical):"archive:"+g.get("groupId");
            g.put("rowKey",rowKey);g.put("canonicalModelNames",phoneIds.stream().map(phoneById::get).filter(Objects::nonNull).map(p->Objects.toString(p.get("modelName"),"")).filter(n->!n.isBlank()).distinct().toList());g.put("canonicalPhoneCodes",canonical);g.put("phoneModelIds",phoneIds);g.put("brandIds",brands.keySet());g.put("brandNames",brands.values());g.put("modelKind",kind);g.put("coverageState","historical");g.put("coverageLabel",MoldSalesMatrixProjection.stateLabel("historical"));
            var product=rootById.get(id(g.get("productLineId")));g.put("productName",product==null?g.get("styleName"):product.get("productName"));
        }
    }
    Map<String,Object> options(List<Map<String,Object>> groups){
        var salesByProduct=new HashMap<Long,BigDecimal>();for(var g:groups)if(g.get("salesQuantity")!=null)salesByProduct.merge(id(g.get("productLineId")),new BigDecimal(g.get("salesQuantity").toString()),BigDecimal::add);
        var defaults=products().stream().sorted(Comparator.<Map<String,Object>,BigDecimal>comparing(p->salesByProduct.getOrDefault(id(p.get("productLineId")),BigDecimal.ZERO),Comparator.reverseOrder()).thenComparing(p->Objects.toString(p.get("productName"))).thenComparingLong(p->id(p.get("productLineId")))).limit(4).map(p->p.get("productLineId")).toList();
        var brands=new TreeMap<Long,String>();for(var group:groups){var ids=new ArrayList<>(MoldSalesMatrixProjection.ids(group.get("brandIds")));var names=new ArrayList<>((Collection<String>)group.get("brandNames"));for(int i=0;i<ids.size();i++)brands.put(ids.get(i),names.get(i));}
        // Brand choices include exact, authorized application/catalog coverage with no sales yet.
        var allowed=products().stream().map(p->id(p.get("productLineId"))).collect(java.util.stream.Collectors.toSet());
        for(var p:phones())if(coverage().getOrDefault(id(p.get("phoneModelId")),List.of()).stream().anyMatch(c->allowed.contains(id(c.get("productLineId")))))brands.put(id(p.get("brandId")),Objects.toString(p.get("brandName")));
        return Map.of("products",products(),"brands",brands.entrySet().stream().map(e->Map.of("brandId",e.getKey(),"brandName",e.getValue())).toList(),"defaultProductLineIds",defaults);
    }
    private static long id(Object v){return v instanceof Number n?n.longValue():0;}
}

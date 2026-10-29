package com.yuewei.plm.module.moldreference;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Existing product/SKU anchors, without turning a catalogue record into verified mold stock. */
public final class PhoneProductEvidence {
    private PhoneProductEvidence() {}
    public static String historicalSkuSql(String p) {
        return "("+p+".project_kind is null and nullif(btrim("+p+".finished_product_code),'') is not null and ("+p+".product_type='sku' or ("+p+".product_type='model_variant' and nullif(btrim("+p+".color),'') is not null)))";
    }
    public static String skuForProjectSql(String sku,String project) {
        String child="("+sku+".product_type='sku' and "+sku+".parent_product_id="+project+".product_id and "+skuParentIdentitySql(sku,project)+")";
        String legacy="("+sku+".product_type='model_variant' and nullif("+project+".mold_development_variant,'') is null and "+sku+".parent_product_id="+project+".parent_product_id and ("+sku+".phone_model_member_id="+project+".phone_model_member_id or "+memberRelationSql(sku,project+".phone_model_member_id")+"))";
        return historicalSkuSql(sku)+" and "+project+".phone_model_member_id is not null and ("+child+" or "+legacy+")";
    }
    public static String memberRelationSql(String sku,String phone) {
        return "exists(select 1 from plm_product_phone_model explicit_link join plm_phone_model_member linked on linked.id=explicit_link.phone_model_id where explicit_link.product_id="+sku+".product_id and explicit_link.phone_model_id="+phone+" and (nullif("+sku+".phone_model_code,'') is null or exists(select 1 from plm_code_item relation_code where relation_code.code_item_id=linked.code_item_id and relation_code.code_value="+sku+".phone_model_code and relation_code.deleted_flag=0)) and ("+sku+".phone_model_member_id is null or linked.id="+sku+".phone_model_member_id or exists(select 1 from plm_phone_model_member old_member where old_member.id="+sku+".phone_model_member_id and old_member.code_item_id=linked.code_item_id)))";
    }
    public static String skuParentIdentitySql(String sku,String parent) {
        return "("+parent+".phone_model_member_id is not null and ("+sku+".phone_model_member_id is null or "+sku+".phone_model_member_id="+parent+".phone_model_member_id) and (nullif("+sku+".phone_model_code,'') is null or "+sku+".phone_model_code="+parent+".phone_model_code))";
    }
    public static List<Map<String,Object>> rows(JdbcTemplate jdbc,List<Long> phones,String scope) {
        if(phones.isEmpty())return List.of();
        String ids=String.join(",",Collections.nCopies(phones.size(),"?"));
        String inherited="p.product_type='sku' and "+skuParentIdentitySql("p","parent");
        String identity="coalesce(declared.phone_id,um.phone_id)";
        var rows=jdbc.queryForList("""
            with unique_codes as (
              select code_value,min(code_item_id) code_item_id from plm_code_item where code_type='phone_model'
              group by code_value having count(*)=1 and bool_and(status='enabled' and deleted_flag=0)
            ), unique_members as (
              select m.code_item_id,min(m.id) phone_id from plm_phone_model_member m
              join plm_phone_brand b on b.id=m.brand_id where m.status='enabled' and b.status='enabled'
              group by m.code_item_id having count(*)=1
            )
            select %s as "phoneModelId",
              p.product_id as "productId",p.parent_product_id as "parentProductId",p.product_code as "productCode",p.product_name as "productName",p.product_type as "productType",
              coalesce(nullif(p.model,''),case when p.product_type='sku' then parent.model end) as model,
              coalesce(nullif(p.phone_model_code,''),case when p.product_type='sku' then parent.phone_model_code end) as "phoneModelCode",
              coalesce(line.product_id,grand.product_id) as "productLineId",coalesce(line.product_name,grand.product_name,'产品待归类') as "productLineName",
              p.finished_product_code as "finishedProductCode",p.project_kind as "projectKind",p.status,p.production_status as "productionStatus",p.color,
              %s as "historicalSkuEvidence",
              coalesce(declared.identity_source,'code') as "identitySource"
            from plm_product p
              left join plm_product parent on parent.product_id=p.parent_product_id and parent.deleted_flag=0
              left join plm_product line on line.product_id=p.parent_product_id and line.product_type='product_line' and line.deleted_flag=0
              left join plm_product grand on grand.product_id=parent.parent_product_id and grand.product_type='product_line' and grand.deleted_flag=0
              left join unique_codes c on c.code_value=coalesce(nullif(p.phone_model_code,''),case when p.product_type='sku' then parent.phone_model_code end)
              left join unique_members um on um.code_item_id=c.code_item_id
              left join lateral (
                select p.phone_model_member_id phone_id,'member'::text identity_source where p.phone_model_member_id is not null
                union all
                select parent.phone_model_member_id,'parent'::text where p.phone_model_member_id is null and %s
                union all
                select source_phone.phone_model_id,'member_relation'::text from plm_product_phone_model source_phone
                  where source_phone.product_id=p.product_id and %s
                    and source_phone.phone_model_id is distinct from p.phone_model_member_id
                    and (p.phone_model_member_id is not null or not (%s) or source_phone.phone_model_id is distinct from parent.phone_model_member_id)
              ) declared on true
            where (p.product_type<>'sku' or parent.phone_model_member_id is null or %s)
              and p.deleted_flag=0 and p.product_type in ('model_variant','sku') and (%s)
              and %s in (%s)
            order by p.product_id
            """.formatted(identity,historicalSkuSql("p"),inherited,memberRelationSql("p","source_phone.phone_model_id"),inherited,skuParentIdentitySql("p","parent"),scope,identity,ids),phones.toArray());
        var repository=new PhoneModelReferenceRepository(jdbc);var members=repository.members(phones);var brands=new HashMap<Long,Map<String,Object>>();repository.allBrands().forEach(b->brands.put(((Number)b.get("brandId")).longValue(),b));
        // One code can describe a combination even before every member has been created.
        // A raw code is therefore insufficient to assign a different product model to the sole current member.
        return rows.stream().filter(product->{
            if(!"code".equals(product.get("identitySource")))return true;
            var phone=members.get(((Number)product.get("phoneModelId")).longValue());if(phone==null)return false;
            var brand=brands.get(((Number)phone.get("brandId")).longValue());String model=Objects.toString(product.get("model"),"");
            return brand!=null&&PhoneIdentityMatcher.isSingleModel(model)&&sameFamily(model,phone,brand);
        }).toList();
    }
    public static List<Map<String,Object>> matchingPhones(JdbcTemplate jdbc,List<Map<String,Object>> phones,List<Map<String,Object>> brands) {
        var byId=new HashMap<Long,Map<String,Object>>();phones.forEach(p->byId.put(((Number)p.get("phoneModelId")).longValue(),p));
        var byBrand=new HashMap<Long,Map<String,Object>>();brands.forEach(b->byBrand.put(((Number)b.get("brandId")).longValue(),b));
        var aliases=new HashMap<Long,Set<String>>();
        for(var product:rows(jdbc,phones.stream().map(p->((Number)p.get("phoneModelId")).longValue()).toList(),"true")) {
            String model=Objects.toString(product.get("model"),"");
            long id=((Number)product.get("phoneModelId")).longValue();var phone=byId.get(id);var brand=byBrand.get(((Number)phone.get("brandId")).longValue());
            if(brand!=null&&PhoneIdentityMatcher.isSingleModel(model)&&sameFamily(model,phone,brand))aliases.computeIfAbsent(id,k->new LinkedHashSet<>()).add(model);
        }
        return phones.stream().map(phone->{var row=new LinkedHashMap<>(phone);row.put("matchingAliases",List.copyOf(aliases.getOrDefault(((Number)phone.get("phoneModelId")).longValue(),Set.of())));return (Map<String,Object>)row;}).toList();
    }
    @SuppressWarnings("unchecked") private static boolean sameFamily(String model,Map<String,Object> phone,Map<String,Object> brand) {
        String key=PhoneIdentityMatcher.modelKey(model,brand);var names=new ArrayList<String>();names.add(phone.get("modelName").toString());names.addAll((List<String>)phone.getOrDefault("aliases",List.of()));
        for(String name:names)if(PhoneIdentityMatcher.isSingleModel(name)) {
            String standard=PhoneIdentityMatcher.modelKey(name,brand);
            if(key.replace(" ","").equals(standard.replace(" ","")))return true;
            // A real product anchor may prove an omitted network suffix; explicit 4G vs 5G never agrees.
            boolean a=key.matches(".* [45]g$"),b=standard.matches(".* [45]g$");
            if(a!=b&&key.replaceAll(" [45]g$","").replace(" ","").equals(standard.replaceAll(" [45]g$","").replace(" ","")))return true;
        }
        return false;
    }
}

package com.yuewei.plm.module.moldreference;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Enrich only the already-authorized option IDs supplied by each business service. */
public final class PhoneSelectionMetadata {
    private PhoneSelectionMetadata() {}
    public static void enrich(JdbcTemplate jdbc,List<Map<String,Object>> rows,String idKey,String kind) {
        if(rows.isEmpty())return;
        if(!Set.of("product","item","mold_set").contains(kind))throw new IllegalArgumentException("Unsupported selection metadata");
        var ids=rows.stream().map(r->((Number)r.get(idKey)).longValue()).distinct().toList();
        var phones=new HashMap<Long,List<Map<String,Object>>>();
        for(int start=0;start<ids.size();start+=1000){
            var batch=ids.subList(start,Math.min(start+1000,ids.size()));String in=String.join(",",Collections.nCopies(batch.size(),"?"));
            String members=switch(kind){case "item"->"select item_id object_id,phone_id from item_members";case "mold_set"->"select mold_set_id object_id,phone_id from set_members";default->"select product_id object_id,phone_id from product_members union select s.product_id,"+PhoneSelectionRelations.safeId("x.value")+" from plm_product_development_state s cross join lateral "+PhoneSelectionRelations.jsonIds("s.data->'plan'->'phoneModelIds'")+" x(value)";};
            String targets=switch(kind){case "item"->"select i.item_id object_id,i.phone_model_code raw_code,i.compatible_models raw_name,i.brand_id from plm_collaboration_item i where i.deleted_flag=0";case "mold_set"->"select s.mold_set_id object_id,null::text raw_code,s.set_name raw_name,null::bigint brand_id from plm_collaboration_mold_set s where s.deleted_flag=0";default->"select p.product_id object_id,p.phone_model_code raw_code,p.model raw_name,null::bigint brand_id from plm_product p where p.deleted_flag=0";};
            jdbc.query("with "+PhoneSelectionRelations.members()+", members as ("+members+"), targets as ("+targets+"), details as ("+"""
              select t.object_id,m.id phone_id,b.id brand_id,b.name brand_name,m.model_name,c.code_value phone_code,
                concat_ws(' ',array_to_string(b.aliases,' '),array_to_string(m.aliases,' ')) aliases
              from targets t join members l on l.object_id=t.object_id join plm_phone_model_member m on m.id=l.phone_id
                join plm_phone_brand b on b.id=m.brand_id left join plm_code_item c on c.code_item_id=m.code_item_id and c.deleted_flag=0
              union
              select t.object_id,null::bigint,b.id,b.name,t.raw_name,t.raw_code,array_to_string(b.aliases,' ')
              from targets t join plm_code_item c on c.code_type='phone_model' and c.code_value=t.raw_code and c.deleted_flag=0
                join plm_phone_code_brand_effective cb on cb.code_item_id=c.code_item_id join plm_phone_brand b on b.id=cb.brand_id
              where not exists(select 1 from members l where l.object_id=t.object_id and l.phone_id is not null)
              union
              select t.object_id,null::bigint,b.id,b.name,t.raw_name,t.raw_code,array_to_string(b.aliases,' ')
              from targets t join plm_phone_brand b on b.id=t.brand_id
              where not exists(select 1 from members l where l.object_id=t.object_id and l.phone_id is not null)
                and not exists(select 1 from plm_code_item c join plm_phone_code_brand_effective cb on cb.code_item_id=c.code_item_id where c.code_type='phone_model' and c.code_value=t.raw_code and c.deleted_flag=0)
              ) select distinct * from details where object_id in (%s) order by phone_code desc nulls last,phone_id
              """.formatted(in),rs->{
                var phone=new LinkedHashMap<String,Object>();phone.put("phoneModelId",rs.getObject("phone_id",Long.class));phone.put("brandId",rs.getLong("brand_id"));phone.put("brandName",rs.getString("brand_name"));phone.put("modelName",Objects.toString(rs.getString("model_name"),"型号待核对"));phone.put("phoneModelCode",rs.getString("phone_code"));phone.put("aliases",List.of(Objects.toString(rs.getString("aliases"),"")));
                phones.computeIfAbsent(rs.getLong("object_id"),unused->new ArrayList<>()).add(phone);
            },batch.toArray());
        }
        for(var row:rows)row.put("phoneModels",phones.getOrDefault(((Number)row.get(idKey)).longValue(),List.of()));
    }
}

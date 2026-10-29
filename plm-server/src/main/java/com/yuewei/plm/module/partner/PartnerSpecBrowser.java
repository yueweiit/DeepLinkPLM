package com.yuewei.plm.module.partner;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.yuewei.plm.module.moldreference.PhoneSelectionRelations;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Paged selection of the existing commercial specs, under their original owner/supplier permissions. */
@Service @RequiredArgsConstructor
public class PartnerSpecBrowser {
    private final PartnerStore store;
    private final SupplierProfileService suppliers;
    private final PartnerVisibility visibility;
    private record Scope(String cte,String where,List<Object> args,List<Map<String,Object>> brands,List<Map<String,Object>> lines,int page,int size,Long brand,Long phone) {}
    private Scope scope(Map<String,String> q) {
        store.access.requireDomesticView();store.access.requirePermission("partner:view");
        long rawPage=number(q.getOrDefault("page","1")),rawSize=number(q.getOrDefault("size","30"));
        require(rawPage>0&&rawPage<=1000000&&rawSize>0&&rawSize<=100,"规格查询页码或范围无效");
        String keyword=text(q.get("keyword"));require(keyword.length()<=128,"搜索内容最长128字");
        Long brand=id(q,"brandId"),phone=id(q,"phoneModelId"),product=id(q,"productId"),selected=id(q,"id");
        String base="s.deleted_flag=0 and p.deleted_flag=0 and p.kind='product' and "+store.access.visibleSqlClause("partner","p.record_id");var args=new ArrayList<Object>();
        if("true".equals(q.get("activeOnly"))&&selected==null&&text(q.get("ids")).isEmpty())base+=" and p.status<>'inactive'";
        if(!text(q.get("supplierCode")).isEmpty()){suppliers.requireSupplier(q.get("supplierCode"));base+=" and exists(select 1 from jsonb_array_elements(case when jsonb_typeof(s.data->'sources')='array' then s.data->'sources' else '[]'::jsonb end) x where x->>'supplierCode'=?)";args.add(q.get("supplierCode"));}
        if(product!=null){base+=" and p.record_id=?";args.add(product);}
        if(selected!=null){base+=" and s.spec_id=?";args.add(selected);}
        if(!text(q.get("ids")).isEmpty()){
            String[] ids=q.get("ids").split(",",-1);require(ids.length<=300,"一次最多读取300个规格");var values=new ArrayList<Long>();
            for(String value:ids){require(value.matches("[1-9][0-9]{0,17}"),"规格ID无效");values.add(Long.valueOf(value));}
            base+=" and s.spec_id in ("+String.join(",",Collections.nCopies(values.size(),"?"))+")";args.addAll(values);
        }
        String cte="with targets as (select s.spec_id id,s.code,s.name,s.website_sku,s.data,p.record_id product_id,p.name product_name from plm_partner_spec s join plm_partner_record p on p.record_id=s.record_id where "+base+"), phone_details as (select distinct t.id,m.id phone_id,b.id brand_id,b.name brand_name,m.model_name,c.code_value phone_code,concat_ws(' ',b.name,array_to_string(b.aliases,' '),m.model_name,array_to_string(m.aliases,' '),c.code_value) search_text from targets t cross join lateral "+PhoneSelectionRelations.jsonIds("t.data->'phoneModelIds'")+" x(value) join plm_phone_model_member m on m.id="+PhoneSelectionRelations.safeId("x.value")+" join plm_phone_brand b on b.id=m.brand_id left join plm_code_item c on c.code_item_id=m.code_item_id and c.deleted_flag=0) ";
        String where="true";
        if(!keyword.isEmpty())for(String token:keyword.split("\\s+")){where+=" and (concat_ws(' ',t.code,t.name,t.website_sku,t.product_name,t.data->>'specification') ilike ? or exists(select 1 from phone_details pd where pd.id=t.id and pd.search_text ilike ?))";args.add("%"+token+"%");args.add("%"+token+"%");}
        var brands=store.jdbc.queryForList(cte+"select distinct pd.brand_id as \"brandId\",pd.brand_name as name from targets t join phone_details pd on pd.id=t.id where "+where+" order by name,\"brandId\"",args.toArray());
        var lines=store.jdbc.queryForList(cte+"select distinct t.product_id as \"productLineId\",t.product_name as name from targets t where "+where+" order by name,\"productLineId\"",args.toArray());
        Long line=id(q,"productLineId");if(line!=null){where+=" and t.product_id=?";args.add(line);}
        if(brand!=null||phone!=null){where+=" and exists(select 1 from phone_details pd where pd.id=t.id";if(brand!=null){where+=" and pd.brand_id=?";args.add(brand);}if(phone!=null){where+=" and pd.phone_id=?";args.add(phone);}where+=")";}
        if("true".equals(q.get("unlinkedPhone")))where+=" and not exists(select 1 from phone_details pd where pd.id=t.id)";
        return new Scope(cte,where,args,brands,lines,(int)rawPage,(int)rawSize,brand,phone);
    }
    private Long id(Map<String,String> q,String key){if(text(q.get(key)).isEmpty())return null;require(q.get(key).matches("[1-9][0-9]{0,17}"),"规格筛选ID无效");return Long.valueOf(q.get(key));}
    @SuppressWarnings("unchecked") public Map<String,Object> page(Map<String,String> q) {
        var s=scope(q);long total=store.jdbc.queryForObject(s.cte+"select count(*) from targets t where "+s.where,Long.class,s.args.toArray());var args=new ArrayList<>(s.args);args.add(s.size);args.add((long)(s.page-1)*s.size);
        var rows=store.jdbc.queryForList(s.cte+"""
            select t.id,t.code,t.name,t.product_id as "productId",t.product_name as "productName",t.website_sku as "websiteSku",t.data::text,
              coalesce((select jsonb_agg(jsonb_build_object('phoneModelId',pd.phone_id,'brandId',pd.brand_id,'brandName',pd.brand_name,'modelName',pd.model_name,'phoneModelCode',pd.phone_code) order by pd.phone_code desc nulls last,pd.phone_id) from phone_details pd where pd.id=t.id),'[]'::jsonb)::text as "phoneModels"
            from targets t where %s order by (select max(pd.phone_code) from phone_details pd where pd.id=t.id) desc nulls last,t.id desc limit ? offset ?
            """.formatted(s.where),args.toArray());
        for(var row:rows){row.put("details",decode(store.json,row.remove("data")));try{row.put("phoneModels",store.json.readValue(text(row.get("phoneModels")),List.class));}catch(Exception e){throw new IllegalStateException("规格机型关系读取失败",e);}}
        return (Map<String,Object>)visibility.redact(result(s,rows,total));
    }
    public Map<String,Object> phones(Map<String,String> q) {
        var s=scope(q);var args=new ArrayList<>(s.args);String where=s.where;
        // A spec can cover multiple brands. Restrict the directory rows as well as the owning spec.
        if(s.brand!=null){where+=" and pd.brand_id=?";args.add(s.brand);}if(s.phone!=null){where+=" and pd.phone_id=?";args.add(s.phone);}
        long total=store.jdbc.queryForObject(s.cte+"select count(distinct pd.phone_id) from targets t join phone_details pd on pd.id=t.id where "+where,Long.class,args.toArray());
        long unlinked=store.jdbc.queryForObject(s.cte+"select count(*) from targets t where "+s.where+" and not exists(select 1 from phone_details pd where pd.id=t.id)",Long.class,s.args.toArray());
        args.add(s.size);args.add((long)(s.page-1)*s.size);
        var rows=store.jdbc.queryForList(s.cte+"select pd.phone_id as \"phoneModelId\",pd.brand_id as \"brandId\",pd.brand_name as \"brandName\",pd.model_name as \"modelName\",pd.phone_code as \"phoneModelCode\",count(distinct t.id) as \"referenceCount\" from targets t join phone_details pd on pd.id=t.id where "+where+" group by pd.phone_id,pd.brand_id,pd.brand_name,pd.model_name,pd.phone_code order by pd.phone_code desc nulls last,pd.model_name,pd.phone_id limit ? offset ?",args.toArray());
        var out=result(s,rows,total);out.put("unlinkedCount",unlinked);return out;
    }
    private Map<String,Object> result(Scope s,List<Map<String,Object>> rows,long total){var out=new LinkedHashMap<String,Object>();out.put("content",rows);out.put("totalElements",total);out.put("page",s.page);out.put("size",s.size);out.put("totalPages",(total+s.size-1)/s.size);out.put("brands",s.brands);out.put("productLines",s.lines);return out;}
}

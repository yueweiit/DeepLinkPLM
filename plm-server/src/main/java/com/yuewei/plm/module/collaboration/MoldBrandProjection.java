package com.yuewei.plm.module.collaboration;

/**
 * Brand is reference data, independent of whether a physical set has confirmed PC/TPU fit.
 * Keep the archive's writable brand_id intact; all readers use this single effective projection.
 */
final class MoldBrandProjection {
    private MoldBrandProjection() {}
    static final String COLUMNS="""
        effective_brand.brand_name as brand_name,
        coalesce(effective_brand.brands_json,'[]') as brands_json,
        coalesce(to_json(effective_brand.brand_ids),'[]'::json)::text as brand_ids_json,
        coalesce(effective_brand.brand_source,'unresolved') as brand_source,
        coalesce(effective_brand.brand_conflict,false) as brand_conflict,
        case when effective_brand.brand_conflict then '品牌资料不一致：'||effective_brand.brand_evidence_detail
          when effective_brand.brand_source='member' then '沿用已关联标准手机品牌'
          when effective_brand.brand_source='code' then '沿用手机编码已核实品牌'
          when effective_brand.brand_source='archive' then '沿用已保存品牌'
          when effective_brand.brand_source='raw' then '历史原品牌已匹配品牌资料'
          else '缺少可确认的手机或品牌关联' end as brand_source_detail
        """;
    static String join(String setScope) {
        return """
        left join lateral (
          with member_brands as (
            select distinct m.brand_id from plm_phone_model_member m join (
              select cs.phone_model_id from plm_collaboration_phone_set_compatibility cs
                join plm_collaboration_mold_set s on s.item_id=cs.item_id and s.mold_set_id=cs.mold_set_id
                where cs.item_id=i.item_id and s.deleted_flag=0 and (%s)
              union select a.phone_model_id from plm_mold_application a
                left join plm_collaboration_mold_set s on s.item_id=a.item_id and s.mold_set_id=a.mold_set_id
                where a.item_id=i.item_id and a.phone_model_id is not null and a.deleted_flag=0
                  and (a.mold_set_id is null or (s.deleted_flag=0 and (%s)))
            ) linked on linked.phone_model_id=m.id
          ), code_brands as (
            select e.brand_id from plm_phone_code_brand_effective e join plm_code_item code on code.code_item_id=e.code_item_id
              where e.code_item_id=i.phone_model_code_item_id and code.code_type='phone_model' and code.deleted_flag=0
          ), raw_candidates as (
            select b.id as brand_id from plm_phone_brand b where b.status='enabled'
              and nullif(btrim(i.brand),'') is not null and exists (
                select 1 from (
                  select b.name as token union select unnest(b.aliases)
                  union select p->>'prefix' from jsonb_array_elements(b.prefixes) p
                ) dictionary where lower(btrim(regexp_replace(dictionary.token,'[[:space:] ]+',' ','g')))
                  =lower(btrim(regexp_replace(i.brand,'[[:space:] ]+',' ','g')))
              )
          ), facts as (
            select brand_id,1 priority,'member'::text source from member_brands
            union all select brand_id,2,'code' from code_brands
            union all select i.brand_id,3,'archive' where i.brand_id is not null
            union all select brand_id,4,'raw' from raw_candidates where (select count(*) from raw_candidates)=1
          ), preferred as (
            select distinct f.brand_id,f.source from facts f where priority=(select min(priority) from facts)
          )
          select array_agg(b.id order by b.id) brand_ids,
            string_agg(b.name,' / ' order by b.name,b.id) brand_name,min(p.source) brand_source,
            jsonb_agg(jsonb_build_object('brandId',b.id,'name',b.name,'status',b.status,'source',p.source) order by b.name,b.id)::text brands_json,
            exists(select 1 from facts f group by f.source
              having not bool_or(f.brand_id in(select brand_id from preferred))) brand_conflict,
            (select string_agg(source_name||'='||names,'；' order by priority) from (
              select f.priority,case f.source when 'member' then '标准手机' when 'code' then '手机编码'
                when 'archive' then '档案品牌' else '原始品牌' end source_name,
                string_agg(fb.name,' / ' order by fb.name,fb.id) names
              from facts f join plm_phone_brand fb on fb.id=f.brand_id group by f.priority,f.source
            ) evidence) brand_evidence_detail
          from preferred p join plm_phone_brand b on b.id=p.brand_id
        ) effective_brand on true
        """.formatted(setScope,setScope);
    }
}

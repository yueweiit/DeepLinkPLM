package com.yuewei.plm.module.attachment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.moldreference.PhoneSelectionRelations;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read projection of the existing reference catalogue; selection never creates an identity or a link. */
@Service
@RequiredArgsConstructor
public class DocumentReferenceBrowser {
    private final DocumentService documents;
    private final JdbcTemplate jdbc;
    private final PilotAccessService access;
    private final ObjectMapper json;

    @Data public static class Query {
        private String type, productType, keyword;
        private Long brandId, phoneModelId, productLineId;
        private boolean projectOnly, unlinkedPhone;
        private int page = 1, size = 50;
    }

    String projection() {
        return "with " + documents.catalog() + "," + PhoneSelectionRelations.members() + "," + """
            targets as (
              select c.*,p.product_type,p.color,p.model,p.phone_model_code,p.project_kind,p.project_registered_at,p.development_mode,
                line.product_id product_line_id,line.product_name product_line_name,
                coalesce(p.product_id,mc.product_id) product_id,
                i.item_id,s.mold_set_id,coalesce(nullif(p.phone_model_code,''),src.phone_model_code,i.phone_model_code) raw_phone_code,
                i.brand_id archive_brand_id
              from catalog c
                left join plm_product_mold_code mc on c.type='mold_code' and mc.product_mold_code_id=c.id
                left join plm_product p on p.product_id=case when c.type in ('product','sku') then c.id else mc.product_id end
                left join plm_product parent on parent.product_id=p.parent_product_id and parent.deleted_flag=0
                left join plm_product grand on grand.product_id=parent.parent_product_id and grand.deleted_flag=0
                left join plm_collaboration_mold_set s on c.type='mold_set' and s.mold_set_id=c.id
                left join plm_collaboration_item i on i.item_id=case when c.type='item' then c.id else s.item_id end
                left join plm_mold_code_source src on c.type='source_code' and src.source_code_id=c.id
                left join plm_product line on line.product_type='product_line' and line.deleted_flag=0 and %s
                  and line.product_id=coalesce(case when p.product_type='product_line' then p.product_id
                    when parent.product_type='product_line' then parent.product_id
                    when grand.product_type='product_line' then grand.product_id end,i.product_line_id)
              where c.type in ('product','sku','item','mold_code','source_code','mold_set')
                and (c.type<>'source_code' or exists(select 1 from plm_mold_code_source src where src.source_code_id=c.id and not src.source_disabled))
            ), target_members as (
              select t.type,t.id,pm.phone_id from targets t join product_members pm on pm.product_id=t.product_id
                where t.type in ('product','sku','mold_code')
              union select t.type,t.id,im.phone_id from targets t join item_members im on im.item_id=t.item_id where t.type='item'
              union select t.type,t.id,sm.phone_id from targets t join set_members sm on sm.mold_set_id=t.mold_set_id where t.type='mold_set'
            ), phone_details as (
              select tm.type,tm.id,m.id phone_id,b.id brand_id,b.name brand_name,m.model_name,
                c.code_value phone_code,concat_ws(' ',b.name,array_to_string(b.aliases,' '),m.model_name,array_to_string(m.aliases,' '),c.code_value) search_text
                from target_members tm join plm_phone_model_member m on m.id=tm.phone_id
                join plm_phone_brand b on b.id=m.brand_id
                left join plm_code_item c on c.code_item_id=m.code_item_id and c.deleted_flag=0
              union
              select t.type,t.id,null::bigint,b.id,b.name,coalesce(t.model,t.name),t.raw_phone_code,
                concat_ws(' ',b.name,array_to_string(b.aliases,' '),t.raw_phone_code)
                from targets t join plm_code_item code on code.code_type='phone_model' and code.code_value=t.raw_phone_code and code.deleted_flag=0
                join plm_phone_code_brand_effective cb on cb.code_item_id=code.code_item_id
                join plm_phone_brand b on b.id=cb.brand_id
                where not exists(select 1 from target_members tm where tm.type=t.type and tm.id=t.id)
              union
              select t.type,t.id,null::bigint,b.id,b.name,t.name,t.raw_phone_code,b.name
                from targets t join plm_phone_brand b on b.id=t.archive_brand_id
                where t.type='item' and not exists(select 1 from target_members tm where tm.type=t.type and tm.id=t.id)
                  and not exists(select 1 from plm_code_item code join plm_phone_code_brand_effective cb on cb.code_item_id=code.code_item_id
                    where code.code_type='phone_model' and code.code_value=t.raw_phone_code and code.deleted_flag=0)
            )
            """.formatted(access.visibleSqlClause("product","line.product_id"));
    }

    private Scope scope(Query q,String permission) {
        access.requireDomesticView();access.requirePermission(permission);
        if(q.type!=null&&!Set.of("product","sku","item","mold_code","source_code","mold_set").contains(q.type))throw invalid("关联对象类型不支持");
        if(q.productType!=null&&!Set.of("product_line","model_variant").contains(q.productType))throw invalid("产品层级不支持");
        if(q.page<1||q.size<1||q.size>100||q.keyword!=null&&q.keyword.length()>128)throw invalid("关联对象查询范围无效");
        for(Long id:Arrays.asList(q.brandId,q.phoneModelId,q.productLineId))if(id!=null&&id<1)throw invalid("筛选 ID 无效");
        var args=new ArrayList<Object>();var filters=new ArrayList<String>();
        if(q.type!=null){filters.add("t.type=?");args.add(q.type);}
        if(q.productType!=null){filters.add("t.product_type=?");args.add(q.productType);}
        if(q.projectOnly)filters.add("t.type='product' and t.project_kind is not null and t.project_registered_at is not null");
        if(permission.equals("project:view"))filters.add("t.type='product' and t.product_type='product_line' and t.development_mode='launch_v1'");
        if(q.keyword!=null&&!q.keyword.isBlank())for(String token:q.keyword.strip().split("\\s+")) {
            filters.add("(concat_ws(' ',t.code,t.name,t.subtitle,t.product_line_name,t.raw_phone_code) ilike ? or exists(select 1 from phone_details pd where pd.type=t.type and pd.id=t.id and pd.search_text ilike ?))");
            args.add("%"+token+"%");args.add("%"+token+"%");
        }
        String cte=projection(),base=filters.isEmpty()?"true":String.join(" and ",filters);
        // Facets deliberately ignore the current brand/model/product, so switching never hides the other choices.
        var brands=jdbc.queryForList(cte+"select distinct pd.brand_id as \"brandId\",pd.brand_name as name from targets t join phone_details pd on pd.type=t.type and pd.id=t.id where "+base+" order by name,\"brandId\"",args.toArray());
        var lines=jdbc.queryForList(cte+"select distinct t.product_line_id as \"productLineId\",t.product_line_name as name from targets t where "+base+" and t.product_line_id is not null order by name,\"productLineId\"",args.toArray());
        if(q.brandId!=null||q.phoneModelId!=null){var scope=new ArrayList<String>();if(q.brandId!=null){scope.add("pd.brand_id=?");args.add(q.brandId);}if(q.phoneModelId!=null){scope.add("pd.phone_id=?");args.add(q.phoneModelId);}filters.add("exists(select 1 from phone_details pd where pd.type=t.type and pd.id=t.id and "+String.join(" and ",scope)+")");}
        if(q.productLineId!=null){filters.add("t.product_line_id=?");args.add(q.productLineId);}
        if(q.unlinkedPhone)filters.add("not exists(select 1 from phone_details pd where pd.type=t.type and pd.id=t.id and pd.phone_id is not null)");
        String where=filters.isEmpty()?"true":String.join(" and ",filters);
        return new Scope(cte,args,where,brands,lines);
    }
    private record Scope(String cte,List<Object> args,String where,List<Map<String,Object>> brands,List<Map<String,Object>> lines) {}
    private void collaborationScope(Query q) {
        if(!Set.of("item","product").contains(Objects.toString(q.type,"")))throw invalid("协作关联仅支持模具档案或型号项目");
        if("product".equals(q.type)){if(q.productType!=null&&!q.productType.equals("model_variant"))throw invalid("请选择已有型号项目");q.productType="model_variant";}
        else if(q.productType!=null)throw invalid("模具档案不能指定产品层级");
    }
    public Map<String,Object> collaborationPage(Query q) {
        collaborationScope(q);var result=page(q,"collaboration:view");
        if("product".equals(q.type)) {
            @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)result.get("content");
            var ids=rows.stream().map(r->((Number)r.get("id")).longValue()).toList();
            if(!ids.isEmpty()) {
                var links=jdbc.queryForList("select project_id,item_id from plm_collaboration_item_project where project_id in ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",ids.toArray());
                for(var row:rows)row.put("linkedItemId",links.stream().filter(l->Objects.equals(l.get("project_id"),row.get("id"))).map(l->l.get("item_id")).findFirst().orElse(null));
            }
        }
        return result;
    }
    public Map<String,Object> collaborationPhones(Query q) { collaborationScope(q);return phones(q,"collaboration:view"); }
    private void researchScope(Query q) {
        if(q.type!=null&&!q.type.equals("product")||q.productType!=null&&!q.productType.equals("product_line"))throw invalid("请选择新品研发项目");
        q.type="product";q.productType="product_line";q.projectOnly=false;
    }
    public Map<String,Object> researchPage(Query q) {researchScope(q);return page(q,"project:view");}
    public Map<String,Object> researchPhones(Query q) {researchScope(q);return phones(q,"project:view");}
    public Map<String,Object> page(Query q) { return page(q,"file:read"); }
    private Map<String,Object> page(Query q,String permission) {
        var scope=scope(q,permission);var args=new ArrayList<>(scope.args);String cte=scope.cte,where=scope.where;var brands=scope.brands;var lines=scope.lines;

        long total=Objects.requireNonNullElse(jdbc.queryForObject(cte+"select count(*) from targets t where "+where,Long.class,args.toArray()),0L);
        args.add(q.size);args.add((long)(q.page-1)*q.size);
        var rows=jdbc.queryForList(cte+"""
            select t.type,t.id,'ALL' as "partType",t.code,t.name,t.subtitle,t.product_type as "productType",t.color,
              t.product_line_id as "productLineId",t.product_line_name as "productLineName",t.raw_phone_code as "phoneModelCode",t.model as "modelName",
              coalesce((select jsonb_agg(jsonb_build_object('phoneModelId',pd.phone_id,'brandId',pd.brand_id,'brandName',pd.brand_name,'modelName',pd.model_name,'phoneModelCode',pd.phone_code) order by pd.phone_code desc nulls last,pd.phone_id) from phone_details pd where pd.type=t.type and pd.id=t.id),'[]'::jsonb)::text as "phoneModels"
            from targets t where %s
            order by (select max(pd.phone_code) from phone_details pd where pd.type=t.type and pd.id=t.id) desc nulls last,t.type,t.code,t.id limit ? offset ?
            """.formatted(where),args.toArray());
        for(var row:rows)try{row.put("phoneModels",json.readValue(row.get("phoneModels").toString(),new TypeReference<List<Map<String,Object>>>(){}));}catch(Exception e){throw new IllegalStateException("手机关联投影无法读取",e);}
        var result=new LinkedHashMap<String,Object>();result.put("content",rows);result.put("page",q.page);result.put("size",q.size);result.put("totalElements",total);result.put("totalPages",(total+q.size-1)/q.size);result.put("brands",brands);result.put("productLines",lines);return result;
    }
    public Map<String,Object> phones(Query q) { return phones(q,"file:read"); }
    private Map<String,Object> phones(Query q,String permission) {
        var scope=scope(q,permission);var args=new ArrayList<>(scope.args);String cte=scope.cte,where=scope.where;
        String phoneWhere=where+" and pd.phone_id is not null";
        // The object can span brands; its directory rows must still match the selected brand/model.
        if(q.brandId!=null){phoneWhere+=" and pd.brand_id=?";args.add(q.brandId);}
        if(q.phoneModelId!=null){phoneWhere+=" and pd.phone_id=?";args.add(q.phoneModelId);}
        long total=Objects.requireNonNullElse(jdbc.queryForObject(cte+"select count(distinct pd.phone_id) from targets t join phone_details pd on pd.type=t.type and pd.id=t.id where "+phoneWhere,Long.class,args.toArray()),0L);
        long unlinked=Objects.requireNonNullElse(jdbc.queryForObject(cte+"select count(*) from targets t where "+where+" and not exists(select 1 from phone_details pd where pd.type=t.type and pd.id=t.id and pd.phone_id is not null)",Long.class,scope.args.toArray()),0L);
        args.add(q.size);args.add((long)(q.page-1)*q.size);
        var rows=jdbc.queryForList(cte+"""
            select pd.phone_id as "phoneModelId",pd.brand_id as "brandId",pd.brand_name as "brandName",
              pd.model_name as "modelName",pd.phone_code as "phoneModelCode",count(distinct (t.type,t.id)) as "referenceCount"
            from targets t join phone_details pd on pd.type=t.type and pd.id=t.id where %s
            group by pd.phone_id,pd.brand_id,pd.brand_name,pd.model_name,pd.phone_code
            order by pd.phone_code desc nulls last,pd.model_name,pd.phone_id limit ? offset ?
            """.formatted(phoneWhere),args.toArray());
        var result=new LinkedHashMap<String,Object>();result.put("content",rows);result.put("page",q.page);result.put("size",q.size);result.put("totalElements",total);result.put("totalPages",(total+q.size-1)/q.size);result.put("unlinkedCount",unlinked);result.put("brands",scope.brands);result.put("productLines",scope.lines);return result;
    }
    private static BusinessException invalid(String message){return new BusinessException(40001,message);}
}

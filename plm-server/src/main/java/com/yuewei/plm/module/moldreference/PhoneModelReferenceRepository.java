package com.yuewei.plm.module.moldreference;

import com.yuewei.plm.common.vo.PageVO;
import java.sql.Array;
import java.sql.SQLException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.yuewei.plm.module.moldreference.PhoneModelReferenceTypes.*;

@Repository @RequiredArgsConstructor
public class PhoneModelReferenceRepository {
    private final JdbcTemplate jdbc;
    private static final String BRAND = """
        select b.id,b.id as "brandId",b.name,b.aliases,b.prefixes::text as prefixes,b.status,
          b.source_system as "sourceSystem",b.source_record_id as "sourceRecordId",b.source_snapshot::text as "sourceSnapshot",
          b.source_system as "legacySourceSystem",b.source_record_id as "legacySourceRecordId",b.source_snapshot::text as "legacySourceSnapshot",
          b.created_at as "createdAt",b.updated_at as "updatedAt",coalesce((to_jsonb(b)->>'version')::bigint,0) as version,coalesce((to_jsonb(b)->>'deleted_flag')::int,0) as "deletedFlag" from plm_phone_brand b
        """;
    /** Public search hints distinguish brand aliases from configured series prefixes; no identity writes. */
    private static final String SEARCH_TEXTS = """
        array_remove((array[m.model_name,b.name,c.code_value,c.code_name]::text[] || m.aliases ||
          array(select a.value from unnest(b.aliases) a(value) where not exists(
            select 1 from jsonb_array_elements(b.prefixes) p(value)
            where lower(a.value)<>lower(b.name) and lower(btrim(p.value->>'replacement'))<>lower(b.name)
              and (lower(a.value)=lower(p.value->>'prefix') or
                strpos(' '||lower(p.value->>'replacement')||' ',' '||lower(a.value)||' ')>0))) ||
          array(select p.value->>'replacement' from jsonb_array_elements(b.prefixes) p(value)
            where nullif(btrim(p.value->>'replacement'),'') is not null and
              strpos(' '||regexp_replace(lower(concat_ws(' ',m.model_name,array_to_string(m.aliases,' '),c.code_name)),'[^[:alpha:]]+',' ','g')||' ',
                ' '||regexp_replace(lower(p.value->>'prefix'),'[^[:alpha:]]+',' ','g')||' ')>0 and
              (length(regexp_replace(p.value->>'prefix','[^[:alpha:]]','','g'))>2 or
                strpos(' '||regexp_replace(regexp_replace(lower(concat_ws(' ',m.model_name,array_to_string(m.aliases,' '),c.code_name)), '[[:digit:]]+', ' # ', 'g'), '[^[:alpha:]#]+',' ','g')||' ',
                  ' '||lower(p.value->>'prefix')||' # ')>0)
            order by length(p.value->>'prefix') desc,p.value->>'prefix',p.value->>'replacement' limit 1)),null)
        """;
    private static final String PHONE = """
        select m.id,m.id as "phoneModelId",m.brand_id as "brandId",b.name as "brandName",b.status as "brandStatus",b.aliases as "brandAliases",
          %s as "searchTexts",m.model_name as "modelName",coalesce(to_jsonb(m)->>'mold_plan_status','planned') as "moldPlanStatus",
          to_jsonb(m)->>'mold_plan_reason' as "moldPlanReason",(to_jsonb(m)->>'mold_plan_updated_at')::timestamptz as "moldPlanUpdatedAt",
          to_jsonb(m)->>'mold_plan_updated_by' as "moldPlanUpdatedBy",m.aliases,m.code_item_id as "codeItemId",c.code_value as "phoneModelCode",
          c.code_value as "codeValue",c.code_name as "originalCodeName",c.status as "codeStatus",
          c.deleted_flag as "codeDeletedFlag",c.production_status as "groupProductionStatus",m.status,
          m.source_system as "sourceSystem",m.source_record_id as "sourceRecordId",m.source_snapshot::text as "sourceSnapshot",
          m.source_system as "legacySourceSystem",m.source_record_id as "legacySourceRecordId",m.source_snapshot::text as "legacySourceSnapshot",
          m.created_at as "createdAt",m.updated_at as "updatedAt",coalesce((to_jsonb(m)->>'version')::bigint,0) as version,coalesce((to_jsonb(m)->>'deleted_flag')::int,0) as "deletedFlag"
        from plm_phone_model_member m join plm_phone_brand b on b.id=m.brand_id
          left join plm_code_item c on c.code_item_id=m.code_item_id
        """.formatted(SEARCH_TEXTS);

    public PageVO<Map<String,Object>> brands(Query query) {
        var args = new ArrayList<Object>(); String where = filters(query,"b",args);
        if (text(query.getKeyword())) { where += " and (b.name ilike ? or array_to_string(b.aliases,' ') ilike ?)"; args.add("%"+query.getKeyword().trim()+"%");args.add("%"+query.getKeyword().trim()+"%"); }
        return page(BRAND,"plm_phone_brand b",where,"b.name,b.id",query,args);
    }
    public List<Map<String,Object>> selectionBrands() {
        // Selection callers need the dictionary, not maintenance or source/audit details.
        return jdbc.queryForList("select b.id,b.id as \"brandId\",b.name,b.status from plm_phone_brand b where coalesce((to_jsonb(b)->>'deleted_flag')::int,0)=0 order by b.name,b.id");
    }
    public PageVO<Map<String,Object>> phones(Query query) {
        var args = new ArrayList<Object>(); String where = filters(query,"m",args);
        if (query.getBrandId()!=null) { where += " and m.brand_id=?"; args.add(query.getBrandId()); }
        if (query.getCodeItemId()!=null) { where += " and m.code_item_id=?"; args.add(query.getCodeItemId()); }
        if (text(query.getKeyword())) {
            if(query.isSelectionSearch()) {
                // Selection search is a read projection; fuzzy tokens never establish identity or fit.
                String haystack="array_to_string(array(select regexp_replace(regexp_replace(lower(v),'([[:digit:]])[^[:alnum:]]+(?=[[:digit:]])','\\1#','g'),'[^[:alnum:]#]','','g') from unnest("+SEARCH_TEXTS+") s(v)),' ')";
                int tokens=0;
                for(String token:query.getKeyword().strip().split("\\s+")) {
                    String normalized=token.toLowerCase(Locale.ROOT).replaceAll("(\\p{Nd})[^\\p{L}\\p{N}]+(?=\\p{Nd})","$1#").replaceAll("[^\\p{L}\\p{N}#]","");
                    if(!normalized.isEmpty()) {
                        String pattern=(Character.isDigit(normalized.codePointAt(0))?"(^|[^[:digit:]])":"")+normalized+
                            (Character.isDigit(normalized.codePointBefore(normalized.length()))?"([^[:digit:]]|$)":"");
                        where+=" and "+haystack+" ~ ?";args.add(pattern);tokens++;
                    }
                }
                if(tokens==0)where+=" and false";
            } else {
                where += " and (m.model_name ilike ? or b.name ilike ? or c.code_value ilike ? or c.code_name ilike ? or array_to_string(m.aliases,' ') ilike ?)";
                for (int n=0;n<5;n++) args.add("%"+query.getKeyword().trim()+"%");
            }
        }
        if(text(query.getProductionStatus())){where+=" and coalesce(c.production_status,'unknown')=?";args.add(com.yuewei.plm.module.productionmetadata.ProductionStatus.normalize(query.getProductionStatus()));}
        return page(PHONE,"plm_phone_model_member m join plm_phone_brand b on b.id=m.brand_id left join plm_code_item c on c.code_item_id=m.code_item_id",where,"c.code_value desc nulls last,m.model_name,m.id",query,args);
    }
    private String filters(Query query,String alias,List<Object> args) {
        String where = " where coalesce((to_jsonb("+alias+")->>'deleted_flag')::int,0)=0";
        if (text(query.getStatus())) { where += " and "+alias+".status=?"; args.add(query.getStatus()); }
        return where;
    }
    private PageVO<Map<String,Object>> page(String select,String from,String where,String order,Query q,List<Object> args) {
        long count=Objects.requireNonNull(jdbc.queryForObject("select count(*) from "+from+where,Long.class,args.toArray()));
        var paging=new ArrayList<>(args);paging.add(q.getSize());paging.add((q.getPage()-1L)*q.getSize());
        return PageVO.<Map<String,Object>>builder().content(rows(select+where+" order by "+order+" limit ? offset ?",paging.toArray()))
            .page(q.getPage()).size(q.getSize()).totalElements(count).totalPages((count+q.getSize()-1)/q.getSize()).build();
    }
    public Map<String,Object> brand(long id,boolean lock) {
        return one(BRAND+" where b.id=?"+(lock?" for update of b":""),id);
    }
    public Map<String,Object> phone(long id,boolean lock) {
        return one(PHONE+" where m.id=?"+(lock?" for update of m":""),id);
    }
    public List<Map<String,Object>> allBrands() {return rows(BRAND+" order by b.id");}
    public List<Map<String,Object>> allPhones() {return rows(PHONE+" order by m.id");}
    public Map<Long,Map<String,Object>> members(List<Long> ids) {
        if(ids.isEmpty()) return Map.of();
        var out=new LinkedHashMap<Long,Map<String,Object>>();
        rows(PHONE+" where m.id in ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") order by m.id",ids.toArray())
            .forEach(r->out.put(((Number)r.get("id")).longValue(),r));
        return out;
    }
    private Map<String,Object> one(String sql,long id) {
        var list=rows(sql,id);return list.isEmpty()?null:list.get(0);
    }
    private List<Map<String,Object>> rows(String sql,Object... args) {
        List<Map<String,Object>> selected=jdbc.query(sql,(rs,n)-> {
            var row=new LinkedHashMap<String,Object>();var metadata=rs.getMetaData();
            for(int i=1;i<=metadata.getColumnCount();i++) {
                Object value=rs.getObject(i);
                if(metadata.getColumnLabel(i).equals("prefixes"))try {value=new com.fasterxml.jackson.databind.ObjectMapper().readValue(value.toString(),List.class);}catch(Exception e){throw new SQLException("品牌系列规则解析失败",e);}
                if(value instanceof Array array) { value=Arrays.asList((String[])array.getArray());array.free(); }
                row.put(metadata.getColumnLabel(i),value);
            }
            if(row.containsKey("codeItemId")) {
                row.put("productionStatus",com.yuewei.plm.module.productionmetadata.ProductionStatus.display(Objects.toString(row.remove("groupProductionStatus"),null)));
                row.put("codeLinkStatus",row.get("codeItemId")==null?"unassigned":Objects.equals(row.get("codeDeletedFlag"),1)?"missing":!"enabled".equals(row.get("codeStatus"))?"disabled":"linked");
                String brand=Objects.toString(row.get("brandName"),""),model=Objects.toString(row.get("modelName"),"");
                row.put("displayName",model.toLowerCase(Locale.ROOT).startsWith(brand.toLowerCase(Locale.ROOT)+" ")?model:brand+" "+model);
            }
            return row;
        },args);
        return withCurrentSource(selected);
    }
    /** V20261014_1720 also reads here before the later planning/source migrations exist.
     * Keep the original identity query replayable; optional CRM provenance is one batched projection,
     * never another identity writer. Missing-table support remains while this historical caller exists.
     */
    private List<Map<String,Object>> withCurrentSource(List<Map<String,Object>> selected) {
        if(selected.isEmpty()||!Boolean.TRUE.equals(jdbc.queryForObject(
            "select to_regclass(format('%I.plm_master_data_source',current_schema())) is not null",Boolean.class)))return selected;
        String kind=selected.get(0).containsKey("phoneModelId")?"phone_model":"phone_brand";
        var parameters=new ArrayList<Object>();parameters.add(kind);
        selected.forEach(row->parameters.add(row.get("id")));
        var sources=jdbc.queryForList("""
            select distinct on(object_id) object_id,source_id,source_data::text as source_snapshot
            from plm_master_data_source where source_system='crm' and source_kind=? and object_id in (
            """+String.join(",",Collections.nCopies(selected.size(),"?"))+") order by object_id,source_key desc",parameters.toArray());
        var byId=new HashMap<Long,Map<String,Object>>();
        sources.forEach(source->byId.put(((Number)source.get("object_id")).longValue(),source));
        for(var row:selected) {
            var source=byId.get(((Number)row.get("id")).longValue());
            if(source==null)continue;
            row.put("sourceSystem","crm");
            if(source.get("source_id")!=null)row.put("sourceRecordId",source.get("source_id"));
            if(source.get("source_snapshot")!=null)row.put("sourceSnapshot",source.get("source_snapshot"));
        }
        return selected;
    }
    private static boolean text(String value) { return value!=null&&!value.isBlank(); }
}

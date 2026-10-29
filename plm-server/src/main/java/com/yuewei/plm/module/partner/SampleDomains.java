package com.yuewei.plm.module.partner;

import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** A view of the original source, never a second inventory or a client-controlled classification. */
public final class SampleDomains {
    private SampleDomains() {}
    public static String validate(String value) {
        String domain=text(value);
        require(Set.of("", "model", "research", "partner", "unclassified").contains(domain), "样品业务范围无效");
        return domain;
    }
    public static String project(Map<String,Object> row) {
        if ("model_extension".equals(row.get("projectKind"))) return "model";
        if ("product_research".equals(row.get("projectKind")) ||
                "product_line".equals(row.get("type")) && "launch_v1".equals(row.get("developmentMode"))) return "research";
        return "unclassified";
    }
    public static String sql(String alias) {
        if (!alias.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid sample alias");
        String project="select case when p.project_kind='model_extension' then 'model' " +
                "when p.project_kind='product_research' or (p.product_type='product_line' and p.development_mode='launch_v1') then 'research' " +
                "else 'unclassified' end from plm_product p where p.product_id="+alias+".source_project_id and p.deleted_flag=0";
        return "(case when "+alias+".data->>'sourceType' in ('partner','catalog') then 'partner' " +
                "when "+alias+".data->>'sourceType' in ('model','device','trial') then 'model' " +
                "when "+alias+".data->>'sourceType'='self' and "+alias+".data->>'sourceDomain' in ('model','research') then "+alias+".data->>'sourceDomain' " +
                "when "+alias+".data->>'sourceType'='self' then coalesce(("+project+"),'unclassified') " +
                "else 'unclassified' end)";
    }
    public static void filter(List<String> where,List<Object> args,Map<String,String> query,String alias) {
        String domain=validate(query.get("domain"));
        if (!domain.isEmpty()) {where.add(sql(alias)+"=?");args.add(domain);}
        String kind=text(query.get("sampleKind"));
        if (!kind.isEmpty()) {
            require(Set.of("phone","dummy","pc","tpu","case","prototype","supplier","unspecified").contains(kind),"样品类别无效");
            where.add("coalesce("+alias+".data->>'sourceType','')<>'model' and coalesce(nullif("+alias+".data->>'sampleKind',''),case when "+alias+".data->>'sourceType' in ('partner','catalog') then 'supplier' else 'unspecified' end)=?");args.add(kind);
        }
    }
    public static void enrich(PartnerStore store,List<Map<String,Object>> rows,String idKey) {
        if (rows.isEmpty()) return;
        var ids=rows.stream().filter(r->text(r.get("sourceType")).equals("self")).map(r->number(r.get(idKey))).distinct().toList();
        var byId=new HashMap<Long,String>();
        if(!ids.isEmpty())for(var row:store.jdbc.queryForList("select r.record_id as id,"+sql("r")+" as domain from plm_partner_record r where r.kind='sample' and r.record_id in ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",ids.toArray()))
            byId.put(number(row.get("id")),text(row.get("domain")));
        for(var row:rows) {
            String source=text(row.get("sourceType"));
            row.put("sampleDomain",Set.of("partner","catalog").contains(source)?"partner":Set.of("model","device","trial").contains(source)?"model":byId.getOrDefault(number(row.get(idKey)),"unclassified"));
            if(text(row.get("sampleKind")).isEmpty()&&!source.equals("model"))row.put("sampleKind",Set.of("partner","catalog").contains(source)?"supplier":"unspecified");
        }
        if(!store.access.hasPermission("project:view"))return;
        var researchIds=rows.stream().filter(r->"research".equals(r.get("sampleDomain"))).map(r->number(r.get(idKey))).distinct().toList();
        if(researchIds.isEmpty())return;
        var progress=new HashMap<Long,Map<String,Object>>();
        String roundId="case when r.data->>'roundId' ~ '^[0-9]{1,18}$' then (r.data->>'roundId')::bigint else 0 end";
        for(var current:store.jdbc.queryForList("select r.record_id as id,rr.status,rr.result,rr.updated_at as \"updatedAt\",exists(select 1 from plm_product_development_state st where st.product_id=p.product_id and jsonb_exists(coalesce(st.data->'voidedRounds','{}'::jsonb),rr.round_id::text)) as voided from plm_partner_record r join plm_product p on p.product_id=r.source_project_id and p.deleted_flag=0 join plm_product_development_round rr on rr.product_id=p.product_id and rr.round_id=("+roundId+") and rr.deleted_flag=0 where r.record_id in ("+String.join(",",Collections.nCopies(researchIds.size(),"?"))+") and "+store.access.visibleSqlClause("product","p.product_id"),researchIds.toArray())) {
            long id=number(current.remove("id"));progress.put(id,current);
        }
        for(var row:rows)if(progress.containsKey(number(row.get(idKey))))row.put("researchProgress",progress.get(number(row.get(idKey))));
    }
}

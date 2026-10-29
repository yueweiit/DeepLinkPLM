package com.yuewei.plm.module.collaboration;

import com.yuewei.plm.common.vo.PageVO;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** One aggregate query over the existing archive, parts and sets, never tooling quantity. */
final class MoldMasterRepository {
    private final JdbcTemplate jdbc;
    private final CollaborationRepository repository;
    MoldMasterRepository(JdbcTemplate jdbc,CollaborationRepository repository) {this.jdbc=jdbc;this.repository=repository;}
    static String confirmed(String prefix) {return com.yuewei.plm.module.moldreference.MoldCompatibilityPolicy.confirmedSql(prefix);}
    private String from(String setScope) {
        String pc=confirmed("pc"),tpu=confirmed("tpu");
        return """
          from plm_collaboration_item i
          left join plm_product line on line.product_id=i.product_line_id and line.deleted_flag=0
          left join (select s.item_id,count(*) registered_sets,
            count(*) filter(where %s and %s) complete_sets,
            count(*) filter(where %s) pc_count,count(*) filter(where %s) tpu_count,
            count(*) filter(where not coalesce(%s and %s,false) and (pc.physical_status='making' or tpu.physical_status='making' or (pc.physical_status is null and pc.status='making') or (tpu.physical_status is null and tpu.status='making'))) making_sets,
            array_remove(array_agg(distinct s.manufacturer_name),null) manufacturers,
            max(s.updated_at) latest_set_at
            from plm_collaboration_mold_set s
            left join plm_collaboration_mold_part pc on pc.mold_set_id=s.mold_set_id and pc.part_type='PC'
            left join plm_collaboration_mold_part tpu on tpu.mold_set_id=s.mold_set_id and tpu.part_type='TPU'
            where s.deleted_flag=0 and (%s) group by s.item_id) stock on stock.item_id=i.item_id
          left join (select s.item_id,array_agg(distinct coalesce(p.current_location,'unknown') order by coalesce(p.current_location,'unknown')) locations
            from plm_collaboration_mold_set s join plm_collaboration_mold_part p using(mold_set_id)
            where s.deleted_flag=0 and (%s) group by s.item_id) loc on loc.item_id=i.item_id
          left join (select item_id,jsonb_agg(jsonb_build_object('currency',quote_currency,'minAmount',low,'maxAmount',high,'count',n) order by quote_currency)::text quotes_json
            from (select s.item_id,s.quote_currency,min(s.quote_amount) low,max(s.quote_amount) high,count(*) n
              from plm_collaboration_mold_set s where s.deleted_flag=0 and s.quote_confirmed and s.quote_amount is not null and (%s)
              group by s.item_id,s.quote_currency) q group by item_id) prices on prices.item_id=i.item_id
          left join (select cm.item_id,jsonb_agg(jsonb_build_object('phoneModelId',m.id,'id',m.id,'brandId',m.brand_id,'brandName',mb.name,
              'modelName',m.model_name,'displayName',case when lower(m.model_name)=lower(mb.name) or lower(left(m.model_name,length(mb.name)+1))=lower(mb.name)||' ' then m.model_name else mb.name||' '||m.model_name end,
              'aliases',m.aliases,'brandStatus',mb.status,'codeStatus',c.status,'codeDeletedFlag',c.deleted_flag,'originalCodeName',c.code_name,
              'codeLinkStatus',case when m.code_item_id is null then 'unassigned' when c.deleted_flag<>0 then 'missing' when c.status<>'enabled' then 'disabled' else 'linked' end,
              'codeItemId',m.code_item_id,'phoneModelCode',c.code_value,'codeValue',c.code_value,'status',m.status,
              'setIds',cm.mold_set_ids,'pcConfirmed',cm.pc_confirmed,'tpuConfirmed',cm.tpu_confirmed,'evidence',cm.evidence,'setCompatibilities',cm.set_compatibilities) order by m.id)::text members_json
          from (select cs.item_id,cs.phone_model_id,bool_and(cs.pc_confirmed) pc_confirmed,bool_and(cs.tpu_confirmed) tpu_confirmed,
            string_agg(distinct concat_ws('；',cs.pc_evidence,cs.tpu_evidence),'；') evidence,array_agg(s.mold_set_id order by s.mold_set_id) mold_set_ids,
            jsonb_agg(jsonb_build_object('moldSetId',s.mold_set_id,'pcConfirmed',cs.pc_confirmed,'tpuConfirmed',cs.tpu_confirmed,'pcEvidence',cs.pc_evidence,'tpuEvidence',cs.tpu_evidence) order by s.mold_set_id) set_compatibilities
            from plm_collaboration_phone_set_compatibility cs join plm_collaboration_mold_set s on s.item_id=cs.item_id and s.mold_set_id=cs.mold_set_id
            where s.deleted_flag=0 and (%s) group by cs.item_id,cs.phone_model_id) cm join plm_phone_model_member m on m.id=cm.phone_model_id
            join plm_phone_brand mb on mb.id=m.brand_id left join plm_code_item c on c.code_item_id=m.code_item_id group by cm.item_id) members on members.item_id=i.item_id
          """.formatted(pc,tpu,pc,tpu,pc,tpu,setScope,setScope,setScope,setScope)+MoldBrandProjection.join(setScope);
    }
    private static String stockState() {
        return "case when stock.registered_sets>0 and stock.complete_sets=stock.registered_sets then 'confirmed' when stock.pc_count>0 or stock.tpu_count>0 then 'partial' when stock.making_sets>0 then 'making' else 'pending' end";
    }
    private static final String SELECT="""
      select i.item_id,i.master_data_status,i.master_data_version,i.product_line_id,line.product_name as product_line_name,i.style_name,i.brand,
        i.compatible_models,i.phone_model_code,i.phone_model_code_item_id,i.mold_name,i.engraved_code,
        i.engraved_code_confirmed,i.brand_id,
      """+MoldBrandProjection.COLUMNS+"""
        ,
        coalesce(stock.registered_sets,0) as registered_sets,coalesce(stock.complete_sets,0) as complete_sets,
        coalesce(stock.pc_count,0) as pc_count,coalesce(stock.tpu_count,0) as tpu_count,coalesce(stock.making_sets,0) as making_sets,
        coalesce(stock.registered_sets-stock.complete_sets-stock.making_sets,0) as pending_sets,
        coalesce(to_json(stock.manufacturers),'[]'::json)::text manufacturers_json,
        coalesce(to_json(loc.locations),'[]'::json)::text locations_json,
        coalesce(prices.quotes_json,'[]') quotes_json,coalesce(members.members_json,'[]') members_json,
        greatest(i.updated_at,stock.latest_set_at) as updated_at,
      """+stockState()+" as stock_state ";
    PageVO<Map<String,Object>> page(CollaborationTypes.Query q,String itemScope,String setScope) {
        List<Object> args=new ArrayList<>();StringBuilder where=new StringBuilder(" where i.deleted_flag=0 and ("+itemScope+")");
        if(q.getItemId()==null) where.append(" and (coalesce(stock.registered_sets,0)>0 or i.mold_name is not null or i.engraved_code is not null)");
        if(q.getItemId()!=null) {where.append(" and i.item_id=?");args.add(q.getItemId());}
        if(q.getProjectId()!=null) {where.append(" and "+com.yuewei.plm.module.project.ProjectBusinessLinks.itemExists("i.item_id"));args.add(q.getProjectId());}
        if(q.getProductLineId()!=null) {where.append(" and i.product_line_id=?");args.add(q.getProductLineId());}
        if(q.getBrandId()!=null) {where.append(" and ?=any(effective_brand.brand_ids)");args.add(q.getBrandId());}
        if(q.getStockState()!=null) {where.append(" and (").append(stockState()).append(")=?");args.add(q.getStockState());}
        if(q.getLocation()!=null) {where.append(" and ?=any(loc.locations)");args.add(q.getLocation());}
        if(q.getKeyword()!=null&&!q.getKeyword().isBlank()) {
            where.append(" and concat_ws(' ',i.mold_name,i.engraved_code,i.style_name,i.brand,i.compatible_models,i.phone_model_code,line.product_name,effective_brand.brand_name,members.members_json,array_to_string(stock.manufacturers,' ')) ilike ?");
            args.add("%"+q.getKeyword().trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");
        }
        String from=from(setScope);long total=Objects.requireNonNullElse(jdbc.queryForObject("select count(*)"+from+where,Long.class,args.toArray()),0L);
        args.add(q.getSize());args.add((long)(q.getPage()-1)*q.getSize());
        var rows=jdbc.query(SELECT+from+where+" order by greatest(i.updated_at,stock.latest_set_at) desc,i.item_id desc limit ? offset ?",repository::map,args.toArray());
        rows.forEach(this::structure);
        return new PageVO<>(rows,q.getPage(),q.getSize(),total,(total+q.getSize()-1)/q.getSize());
    }
    Map<String,Object> detail(long id,String itemScope,String setScope) {
        // A historical project link can identify an archive before its mold fields or physical sets are completed.
        // Keep default browsing's presence filter, but let an authorized exact-ID link open those existing records.
        var rows=jdbc.query(SELECT+from(setScope)+" where i.deleted_flag=0 and ("+itemScope+") and i.item_id=?",repository::map,id);
        if(rows.isEmpty()) throw new com.yuewei.plm.common.exception.BusinessException(40401,"模具档案不存在");
        var row=rows.get(0);structure(row);return row;
    }
    List<Map<String,Object>> setOptions(long itemId,String setScope) {
        return jdbc.query("select s.mold_set_id,s.set_no,s.master_data_status,"+CollaborationRepository.assetSql()+" as asset_number from plm_collaboration_mold_set s where s.item_id=? and s.deleted_flag=0 and ("+setScope+") order by s.set_no,s.mold_set_id",repository::map,itemId);
    }
    @SuppressWarnings("unchecked")
    private void structure(Map<String,Object> row) {
        Map<String,Object> summary=new LinkedHashMap<>();
        for(String key:List.of("registeredSets","completeSets","pcCount","tpuCount","makingSets","pendingSets","stockState","manufacturers","locations","quotes")) summary.put(key,row.remove(key));
        row.put("summary",summary);
        List<Map<String,Object>> members=(List<Map<String,Object>>)row.get("members");
        row.put("compatiblePhoneModelIds",members.stream().map(m->((Number)m.get("phoneModelId")).longValue()).sorted().toList());
        row.put("compatiblePhones",members.stream().map(member->{Map<String,Object> relation=new LinkedHashMap<>();relation.put("phoneModelId",member.get("phoneModelId"));relation.put("setIds",member.get("setIds"));relation.put("pcConfirmed",member.get("pcConfirmed"));relation.put("tpuConfirmed",member.get("tpuConfirmed"));relation.put("evidence",member.get("evidence"));relation.put("setCompatibilities",member.get("setCompatibilities"));return relation;}).toList());
        row.put("compatibilityMoldSetIds",members.stream().flatMap(m->((List<Number>)m.get("setIds")).stream()).map(Number::longValue).distinct().sorted().toList());
        row.put("compatibilityPcConfirmed",!members.isEmpty()&&members.stream().allMatch(m->Boolean.TRUE.equals(m.get("pcConfirmed"))));
        row.put("compatibilityTpuConfirmed",!members.isEmpty()&&members.stream().allMatch(m->Boolean.TRUE.equals(m.get("tpuConfirmed"))));
        row.put("compatibilityEvidence",members.isEmpty()?null:String.join("；",members.stream().map(m->m.get("evidence").toString()).distinct().sorted().toList()));
        row.put("displayName",members.isEmpty()?Objects.requireNonNullElse(row.get("moldName"),row.get("compatibleModels")):String.join(" / ",members.stream().map(m->m.get("displayName").toString()).toList()));
    }
}

package com.yuewei.plm.module.collaboration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.vo.PageVO;
import java.sql.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CollaborationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    static final String PHONE_NAMES_JOIN="""
        left join (select cm.item_id,string_agg(pm.model_name,' / ' order by pm.id) as current_models
          from plm_collaboration_phone_member cm join plm_phone_model_member pm on pm.id=cm.phone_model_id
          group by cm.item_id) current_phones on current_phones.item_id=i.item_id
        """;
    /** Task names use this exact physical set; a source application is intent, never fit evidence. */
    static final String SET_PHONE_NAMES_JOIN="""
        left join (select phone_scope.mold_set_id,
          string_agg(
            (case when lower(pm.model_name)=lower(b.name) or left(lower(pm.model_name),length(b.name)+1) in
              (lower(b.name)||' ',lower(b.name)||'-',lower(b.name)||':',lower(b.name)||'/')
              then pm.model_name else concat_ws(' ',b.name,pm.model_name) end)
            ||coalesce(' · '||c.code_value,'')
            ||case when phone_scope.source_only then '（来源申请，适配待确认）'
                   when phone_scope.fit_confirmed then '' else '（适配待确认）' end,
            ' / ' order by pm.id) current_models
          from (
            select cs.mold_set_id,cs.phone_model_id,false source_only,
              cs.pc_confirmed and cs.tpu_confirmed and nullif(btrim(cs.pc_evidence),'') is not null
                and nullif(btrim(cs.tpu_evidence),'') is not null fit_confirmed
            from plm_collaboration_phone_set_compatibility cs
            union all
            select distinct a.mold_set_id,a.phone_model_id,true,false
            from plm_mold_application a where a.deleted_flag=0 and a.mold_set_id is not null and a.phone_model_id is not null
              and not exists(select 1 from plm_collaboration_phone_set_compatibility cs
                where cs.mold_set_id=a.mold_set_id and cs.phone_model_id=a.phone_model_id)
          ) phone_scope join plm_phone_model_member pm on pm.id=phone_scope.phone_model_id
          left join plm_phone_brand b on b.id=pm.brand_id left join plm_code_item c on c.code_item_id=pm.code_item_id
          group by phone_scope.mold_set_id) current_phones on current_phones.mold_set_id=s.mold_set_id
        """;
    private static final String PROJECT_JOIN="""
        left join (select item_id,count(*) as project_count,
            jsonb_agg(jsonb_build_object('projectId',p.product_id,'productCode',p.product_code,
                'productName',p.product_name,'color',p.color,'status',p.status) order by p.product_id) as projects
          from plm_collaboration_item_project ip join plm_product p on p.product_id=ip.project_id and p.deleted_flag=0
          group by item_id) links on links.item_id=i.item_id
        left join plm_product line on line.product_id=i.product_line_id and line.deleted_flag=0
        left join plm_code_item phone on phone.code_item_id=i.phone_model_code_item_id and phone.code_type='phone_model'
        left join (select item_id,array_agg(phone_model_id order by phone_model_id) as phone_ids,
          bool_and(pc_confirmed) as pc_confirmed,bool_and(tpu_confirmed) as tpu_confirmed,
          string_agg(distinct evidence,'；' order by evidence) as evidence,
          jsonb_agg(jsonb_build_object('phoneModelId',phone_model_id,'setIds',mold_set_ids,'pcConfirmed',pc_confirmed,'tpuConfirmed',tpu_confirmed,'evidence',evidence,'setCompatibilities',set_compatibilities) order by phone_model_id) as phone_relations
          from (select cs.item_id,cs.phone_model_id,array_agg(cs.mold_set_id order by cs.mold_set_id) as mold_set_ids,
            bool_and(cs.pc_confirmed) pc_confirmed,bool_and(cs.tpu_confirmed) tpu_confirmed,
            string_agg(distinct concat_ws('；',cs.pc_evidence,cs.tpu_evidence),'；') evidence,
            jsonb_agg(jsonb_build_object('moldSetId',cs.mold_set_id,'pcConfirmed',cs.pc_confirmed,'tpuConfirmed',cs.tpu_confirmed,'pcEvidence',cs.pc_evidence,'tpuEvidence',cs.tpu_evidence) order by cs.mold_set_id) set_compatibilities
            from plm_collaboration_phone_set_compatibility cs join plm_collaboration_mold_set ms on ms.item_id=cs.item_id and ms.mold_set_id=cs.mold_set_id and ms.deleted_flag=0
            group by cs.item_id,cs.phone_model_id) relation group by item_id) compatible on compatible.item_id=i.item_id
        left join (select cs.item_id,array_agg(distinct cs.mold_set_id order by cs.mold_set_id) as set_ids
          from plm_collaboration_phone_set_compatibility cs join plm_collaboration_mold_set ms on ms.item_id=cs.item_id and ms.mold_set_id=cs.mold_set_id and ms.deleted_flag=0
          group by cs.item_id) compatible_sets on compatible_sets.item_id=i.item_id
        """+PHONE_NAMES_JOIN;
    private static final String PART_JOIN="""
        left join plm_collaboration_mold_part pc on pc.mold_set_id=s.mold_set_id and pc.part_type='PC'
        left join plm_collaboration_mold_part tpu on tpu.mold_set_id=s.mold_set_id and tpu.part_type='TPU'
        left join plm_product_mold_code pcm on pcm.product_mold_code_id=pc.product_mold_code_id and pcm.deleted_flag=0
        left join plm_product_mold_code tpum on tpum.product_mold_code_id=tpu.product_mold_code_id and tpum.deleted_flag=0
        left join plm_inventory pci on pci.inventory_id=pc.inventory_id and pci.deleted_flag=0
        left join plm_inventory tpui on tpui.inventory_id=tpu.inventory_id and tpui.deleted_flag=0
        left join sys_user owner on owner.user_id=s.owner_user_id
        """;
    static String statusSql(String view) {
        return switch(view) {
            case "delivery" -> "case when s.actual_mexico_arrival_date is not null then 'mexico_arrived' when s.actual_ship_date is not null then 'shipped' when pc.status='ready' and tpu.status='ready' then 'ready' when s.opened_date is not null or pc.status<>'pending' or tpu.status<>'pending' then 'making' else 'pending' end";
            case "production" -> "case when i.pc_total is not null or i.tpu_total is not null or i.finished_total is not null then 'recorded' else 'missing' end";
            default -> "case when coalesce(links.project_count,0)>0 then 'linked' else 'candidate' end";
        };
    }
    public static String moldStatusSql() {
        return "case when pc.acceptance_status='failed' or tpu.acceptance_status='failed' then 'acceptance_failed' "+
            "when pc.acceptance_status='passed' and tpu.acceptance_status='passed' and pc.factory_arrival_date is not null and tpu.factory_arrival_date is not null then 'delivered' "+
            "when pc.acceptance_status='passed' or tpu.acceptance_status='passed' then 'partially_accepted' "+
            "when (pc.factory_arrival_date is not null or pc.current_location='mexico_factory') and (tpu.factory_arrival_date is not null or tpu.current_location='mexico_factory') then 'awaiting_acceptance' "+
            "when pc.factory_arrival_date is not null or tpu.factory_arrival_date is not null or pc.current_location='mexico_factory' or tpu.current_location='mexico_factory' then 'partially_arrived' else "+statusSql("delivery")+" end";
    }
    public static String assetSql() {return "('MS-'||lpad(s.mold_set_id::text,greatest(6,length(s.mold_set_id::text)),'0'))";}
    private static String from(String view) {
        return from(view,"true");
    }
    private static String from(String view,String setScope) {
        return " from plm_collaboration_item i "+("delivery".equals(view)
            ?"join plm_collaboration_mold_set s on s.item_id=i.item_id and s.deleted_flag=0 "+PART_JOIN:"")+PROJECT_JOIN+MoldBrandProjection.join(setScope);
    }
    private static String select(String view) {
        return select(view,view.equals("delivery"));
    }
    private static String select(String view,boolean ledger) {
        String extra="delivery".equals(view)?"""
            ,s.mold_set_id,s.master_data_status as set_master_data_status,s.master_data_version as set_master_data_version,s.set_no,s.set_name,s.opened_date,s.planned_mold_delivery_date,s.actual_ship_date,
             s.planned_factory_arrival_date,s.actual_mexico_arrival_date,s.arrival_confirmed_basis_date,s.notes,
             pc.status as pc_status,tpu.status as tpu_status,
             pc.product_mold_code_id as pc_mold_code_id,tpu.product_mold_code_id as tpu_mold_code_id,
             pcm.mold_code as pc_catalog_mold_code,tpum.mold_code as tpu_catalog_mold_code,
             coalesce(pcm.mold_code,pc.original_mold_code) as pc_mold_code,coalesce(tpum.mold_code,tpu.original_mold_code) as tpu_mold_code,
             pc.original_mold_code as pc_original_mold_code,tpu.original_mold_code as tpu_original_mold_code,
             pc.inventory_id as pc_inventory_id,tpu.inventory_id as tpu_inventory_id,
             pci.inventory_code as pc_inventory_code,pci.inventory_name as pc_inventory_name,
             tpui.inventory_code as tpu_inventory_code,tpui.inventory_name as tpu_inventory_name,
             s.source_mold_set_id,s.manufacturer_name,s.owner_user_id,owner.display_name as owner_name,
             s.quote_amount,s.quote_currency,s.quote_basis,s.quote_confirmed,
             pc.physical_status as pc_physical_status,tpu.physical_status as tpu_physical_status,
             pc.current_location as pc_current_location,tpu.current_location as tpu_current_location,
             pc.location_detail as pc_location_detail,tpu.location_detail as tpu_location_detail,
             pc.factory_arrival_date as pc_factory_arrival_date,tpu.factory_arrival_date as tpu_factory_arrival_date,
             pc.acceptance_status as pc_acceptance_status,tpu.acceptance_status as tpu_acceptance_status,
             pc.acceptance_date as pc_acceptance_date,tpu.acceptance_date as tpu_acceptance_date,
             pc.acceptance_issues as pc_acceptance_issues,tpu.acceptance_issues as tpu_acceptance_issues,
             s.updated_at as updated_at,
             s.plan_start_date,s.manufacturing_days,s.holiday_days,s.extra_days,
             s.forecast_delivery_date,s.forecast_factory_date,s.forecast_acceptance_date,s.forecast_production_date,
             s.planned_acceptance_date,s.planned_production_date,s.actual_port_arrival_date,
             s.pc_signed_date,s.tpu_signed_date,s.actual_supplier_trial_date,s.actual_trial_date,s.actual_production_date,s.market_ready_date,
             s.logistics_owner_user_id,s.mexico_owner_user_id,s.production_owner_user_id,s.schedule_notes,
             (s.planned_mold_delivery_date+7) as suggested_factory_arrival_date,
             (s.planned_factory_arrival_date is not null and s.arrival_confirmed_basis_date is distinct from s.planned_mold_delivery_date) as arrival_needs_review
            """+","+assetSql()+" as asset_number,"+assetSql()+"||'-PC' as pc_asset_number,"+assetSql()+"||'-TPU' as tpu_asset_number,"+moldStatusSql()+" as delivery_status":",i.notes,i.updated_at";
        return """
            select i.item_id,i.master_data_status,i.master_data_version,i.product_line_id,i.style_name,i.brand,i.compatible_models,i.phone_model_code,
              current_phones.current_models as current_compatible_models,
              i.mold_name,i.engraved_code,i.engraved_code_confirmed,i.brand_id,
            """+MoldBrandProjection.COLUMNS+"""
              ,
              coalesce(to_json(compatible.phone_ids),'[]'::json)::text as compatible_phone_model_ids_json,
              coalesce(to_json(compatible_sets.set_ids),'[]'::json)::text as compatibility_mold_set_ids_json,
              coalesce(compatible.pc_confirmed,false) as compatibility_pc_confirmed,
              coalesce(compatible.tpu_confirmed,false) as compatibility_tpu_confirmed,
              compatible.evidence as compatibility_evidence,
              coalesce(compatible.phone_relations,'[]'::jsonb)::text as compatible_phones_json,
              i.phone_model_code_item_id,phone.code_name as phone_model_code_name,phone.code_value as linked_phone_model_code,
              case when i.phone_model_code_item_id is null then 'unlinked'
                when phone.code_item_id is null or phone.deleted_flag<>0 then 'missing'
                when phone.status<>'enabled' then 'disabled' else 'linked' end as phone_model_link_status,
              i.phone_price,i.price_currency,i.sales_platform,i.reference_url,i.pc_total,i.tpu_total,i.finished_total,
              i.production_notes,line.product_name as product_line_name,coalesce(links.project_count,0) as project_count,
              coalesce(links.projects,'[]'::jsonb)::text as projects_json,
              (select count(*) from plm_collaboration_mold_set ms where ms.item_id=i.item_id and ms.deleted_flag=0) as mold_set_count,
            """+(ledger?moldStatusSql():statusSql(view))+" as status"+extra;
    }
    public PageVO<Map<String,Object>> page(CollaborationTypes.Query q,Set<String> writableGroups) {
        return page(q,writableGroups,"true");
    }
    public PageVO<Map<String,Object>> page(CollaborationTypes.Query q,Set<String> writableGroups,String scope) {
        String view=q.getView(); StringBuilder where=new StringBuilder(" where i.deleted_flag=0");
        where.append(" and (").append(scope).append(")");
        List<Object> args=new ArrayList<>();
        if(q.getProjectId()!=null) {where.append(" and "+(view.equals("delivery")?com.yuewei.plm.module.project.ProjectBusinessLinks.setExists("s.mold_set_id"):com.yuewei.plm.module.project.ProjectBusinessLinks.itemExists("i.item_id")));args.add(q.getProjectId());}
        if(q.getItemId()!=null) {where.append(" and i.item_id=?");args.add(q.getItemId());}
        if("delivery".equals(view)&&q.getMoldSetId()!=null) {where.append(" and s.mold_set_id=?");args.add(q.getMoldSetId());}
        if(q.getProductLineId()!=null) {where.append(" and i.product_line_id=?");args.add(q.getProductLineId());}
        if(q.getBrandId()!=null) {where.append(" and ?=any(effective_brand.brand_ids)");args.add(q.getBrandId());}
        if(q.getKeyword()!=null&&!q.getKeyword().isBlank()) {
            where.append(" and (concat_ws(' ',i.style_name,i.brand,i.compatible_models,current_phones.current_models,i.phone_model_code,line.product_name,phone.code_name,phone.code_value,effective_brand.brand_name,i.sales_platform) ilike ?");
            String pattern="%"+q.getKeyword().trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
            args.add(pattern);
            if("delivery".equals(view)) {where.append(" or concat_ws(' ',s.set_name,"+assetSql()+",pcm.mold_code,tpum.mold_code,pc.original_mold_code,tpu.original_mold_code,s.manufacturer_name,owner.display_name) ilike ?");args.add(pattern);}
            where.append(")");
        }
        if(q.getStatus()!=null&&!q.getStatus().isBlank()) {where.append(" and (").append(q.isSchedule()?"case when s.market_ready_date is not null then 'market_ready' else "+moldStatusSql()+" end":q.isMoldLedger()?moldStatusSql():statusSql(view)).append(")=?");args.add(q.getStatus());}
        if("delivery".equals(view)&&q.getLocation()!=null&&!q.getLocation().isBlank()) {
            if(q.getLocation().equals("unknown")) where.append(" and (pc.current_location is null or tpu.current_location is null)");
            else {where.append(" and (pc.current_location=? or tpu.current_location=?)");args.add(q.getLocation());args.add(q.getLocation());}
        }
        if(q.isMineOnly()) where.append(" and (").append(q.isSchedule()?MoldScheduleService.missingSql(writableGroups):missingSql(view,writableGroups)).append(")");
        if(q.isSchedule()&&q.isOverdueOnly()) where.append(" and (").append(MoldSchedulePolicy.overdueSql()).append(")");
        String from=from(view,"delivery".equals(view)?scope:"true");
        Long count=jdbc.queryForObject("select count(*)"+from+where,Long.class,args.toArray());
        long total=count==null?0:count;
        args.add(q.getSize());args.add((long)(q.getPage()-1)*q.getSize());
        String sort="delivery".equals(view)?"s.opened_date desc nulls last,s.mold_set_id desc":"i.updated_at desc,i.item_id desc";
        List<Map<String,Object>> rows=jdbc.query(select(view,q.isMoldLedger())+from+where+" order by "+sort+" limit ? offset ?",this::map,args.toArray());
        return new PageVO<>(rows,q.getPage(),q.getSize(),total,(total+q.getSize()-1)/q.getSize());
    }
    static String missingSql(String view,Set<String> groups) {
        List<String> missing=new ArrayList<>();
        if(view.equals("market")&&groups.contains("market")) missing.add("effective_brand.brand_name is null or i.reference_url is null");
        if(view.equals("production")&&groups.contains("production")) missing.add("i.pc_total is null or i.tpu_total is null or i.finished_total is null");
        if(view.equals("delivery")) {
            if(groups.contains("engineering")) missing.add("s.opened_date is null or s.planned_mold_delivery_date is null or s.manufacturer_name is null or s.owner_user_id is null or coalesce(pcm.mold_code,pc.original_mold_code) is null or coalesce(tpum.mold_code,tpu.original_mold_code) is null");
            if(groups.contains("logistics")) missing.add("s.planned_mold_delivery_date is not null and (s.planned_factory_arrival_date is null or s.arrival_confirmed_basis_date is distinct from s.planned_mold_delivery_date or (pc.status='ready' and tpu.status='ready' and s.actual_ship_date is null))");
            if(groups.contains("logistics")) missing.add("pc.current_location is null or tpu.current_location is null or (s.actual_ship_date is not null and s.actual_mexico_arrival_date is null)");
            if(groups.contains("mexico")) missing.add("(s.actual_ship_date is not null or s.actual_mexico_arrival_date is not null or pc.factory_arrival_date is not null or tpu.factory_arrival_date is not null or pc.current_location='mexico_factory' or tpu.current_location='mexico_factory') and (pc.factory_arrival_date is null or tpu.factory_arrival_date is null or pc.acceptance_status<>'passed' or tpu.acceptance_status<>'passed')");
        }
        return missing.isEmpty()?"false":String.join(" or ",missing.stream().map(s->"("+s+")").toList());
    }
    public List<Map<String,Object>> moldRows(List<Long> ids,String scope) {
        if(ids.isEmpty())return List.of();String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        return jdbc.query(select("delivery",true)+from("delivery",scope)+" where i.deleted_flag=0 and s.mold_set_id in ("+placeholders+") and ("+scope+")",this::map,ids.toArray());
    }
    public Map<String,Object> row(String entity,long id) {
        String view="mold_set".equals(entity)?"delivery":"market";
        List<Map<String,Object>> rows=jdbc.query(select(view)+from(view)+" where i.deleted_flag=0 and "+
            (view.equals("delivery")?"s.mold_set_id":"i.item_id")+"=?",this::map,id);
        if(rows.isEmpty()) throw new com.yuewei.plm.common.exception.BusinessException(40401,"协作记录不存在");
        return rows.get(0);
    }
    /** Import previews share two bulk snapshots rather than querying once per spreadsheet row. */
    public List<Map<String,Object>> previewRows(String entity) {
        return previewRows(entity,"true");
    }
    public List<Map<String,Object>> previewRows(String entity,String scope) {
        String view="mold_set".equals(entity)?"delivery":"market";
        return jdbc.query(select(view)+from(view)+" where i.deleted_flag=0 and ("+scope+")",this::map);
    }
    public List<Map<String,Object>> productLines() {
        return productLines("true");
    }
    public List<Map<String,Object>> productLines(String scope) {
        return jdbc.query("select p.product_id as product_line_id,p.product_name,p.product_code,p.status,p.sales_status from plm_product p where p.deleted_flag=0 and p.product_type='product_line' and ("+scope+") order by p.product_name",this::map);
    }
    public List<Map<String,Object>> users() {
        return jdbc.query("select user_id,username,display_name,department_name,all_permissions from sys_user where deleted_flag=0 and status='active' order by user_id",this::map);
    }
    public List<Map<String,Object>> projectOptions(Long productLineId,String keyword) {
        return projectOptions(productLineId,keyword,"true");
    }
    public List<Map<String,Object>> projectOptions(Long productLineId,String keyword,String scope) {
        List<Object> args=new ArrayList<>();String where="where p.deleted_flag=0 and p.product_type='model_variant' and ("+scope+")";
        if(productLineId!=null) {where+=" and p.parent_product_id=?";args.add(productLineId);}
        if(keyword!=null&&!keyword.isBlank()) {where+=" and concat_ws(' ',p.product_code,p.product_name,p.model,p.phone_model_code) ilike ?";args.add("%"+keyword.trim().replace("%","\\%").replace("_","\\_")+"%");}
        return jdbc.query("select p.product_id as project_id,p.parent_product_id as product_line_id,p.product_code,p.product_name,p.model,p.phone_model_code,p.color,p.status,ip.item_id as linked_item_id from plm_product p left join plm_collaboration_item_project ip on ip.project_id=p.product_id "+where+" order by p.product_id desc limit 100",this::map,args.toArray());
    }
    public List<Map<String,Object>> moldCodes(Long itemId,String keyword) {
        return moldCodes(itemId,keyword,"true");
    }
    public List<Map<String,Object>> moldCodes(Long itemId,String keyword,String scope) {
        List<Object> args=new ArrayList<>();String where="where mc.deleted_flag=0 and ("+scope+")";
        if(itemId!=null) {where+=" and (exists(select 1 from plm_collaboration_item_project ip where ip.item_id=? and ip.project_id=mc.product_id) or mc.product_id=(select product_line_id from plm_collaboration_item where item_id=?))";args.add(itemId);args.add(itemId);}
        if(keyword!=null&&!keyword.isBlank()) {where+=" and concat_ws(' ',mc.mold_code,mc.mold_name) ilike ?";args.add("%"+keyword.trim().replace("%","\\%").replace("_","\\_")+"%");}
        return jdbc.query("select mc.product_mold_code_id as mold_code_id,mc.product_id,mc.mold_code,mc.mold_name,mc.key_code from plm_product_mold_code mc "+where+" order by mc.mold_code limit 100",this::map,args.toArray());
    }
    public List<Map<String,Object>> moldInventoryOptions(String keyword) {
        if(keyword==null||keyword.trim().length()<2) return List.of();
        String pattern="%"+keyword.trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        return jdbc.query("select inventory_id,inventory_code,inventory_name,inventory_type,specification from plm_inventory where deleted_flag=0 and concat_ws(' ',inventory_code,inventory_name,specification) ilike ? order by inventory_code limit 100",this::map,pattern);
    }
    public PageVO<Map<String,Object>> moldHistory(long id,int page,int size) {
        row("mold_set",id);
        Long total=jdbc.queryForObject("select count(*) from plm_collaboration_history where entity_type='mold_set' and entity_id=?",Long.class,id);
        List<Map<String,Object>> rows=jdbc.query("select history_id,entity_type,entity_id,field_name,before_value::text as before_json,after_value::text as after_json,reason,actor_name,changed_at from plm_collaboration_history where entity_type='mold_set' and entity_id=? order by history_id desc limit ? offset ?",this::map,id,size,(long)(page-1)*size);
        return new PageVO<>(rows,page,size,total==null?0:total,(total==null?0:total+size-1)/size);
    }
    public Map<String,Object> references(long itemId) {
        Map<String,Object> item=row("item",itemId);
        List<Map<String,Object>> express=jdbc.query("""
            select e.mold_transfer_express_id,e.project_id,e.timeline_node_key,e.tracking_no,e.shipped_at,e.latest_status_text
            from plm_project_mold_transfer_express e join plm_collaboration_item_project ip on ip.project_id=e.project_id
            where ip.item_id=? and e.deleted_flag=0 and e.status='active' order by e.mold_transfer_express_id desc
            """,this::map,itemId);
        return Map.of("item",item,"expressRecords",express);
    }
    public PageVO<Map<String,Object>> history(long itemId,int page,int size) {
        row("item",itemId);
        Long total=jdbc.queryForObject("select count(*) from plm_collaboration_history where item_id=?",Long.class,itemId);
        List<Map<String,Object>> rows=jdbc.query("select history_id,entity_type,entity_id,field_name,before_value::text as before_json,after_value::text as after_json,reason,actor_id,actor_name,changed_at from plm_collaboration_history where item_id=? order by history_id desc limit ? offset ?",this::map,itemId,size,(long)(page-1)*size);
        return new PageVO<>(rows,page,size,total==null?0:total,(total==null?0:total+size-1)/size);
    }
    Map<String,Object> map(ResultSet rs,int row) throws SQLException {
        Map<String,Object> values=new LinkedHashMap<>();ResultSetMetaData meta=rs.getMetaData();
        for(int n=1;n<=meta.getColumnCount();n++) {
            String key=camel(meta.getColumnLabel(n));Object value=rs.getObject(n);
            if(value instanceof java.sql.Date date) value=date.toLocalDate().toString();
            else if(value instanceof Timestamp time) value=time.toInstant().toString();
            if(key.endsWith("Json")) {
                key=key.substring(0,key.length()-4);
                try {value=value==null?null:json.readerFor(new TypeReference<Object>(){}).with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(value.toString());}
                catch(Exception e) {throw new SQLException("Invalid collaboration JSON",e);}
            }
            values.put(key,value);
        }
        return values;
    }
    public static String camel(String snake) {
        StringBuilder text=new StringBuilder();boolean upper=false;
        for(char c:snake.toCharArray()) {if(c=='_') upper=true;else {text.append(upper?Character.toUpperCase(c):c);upper=false;}}
        return text.toString();
    }
}

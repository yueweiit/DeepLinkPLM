package com.yuewei.plm.module.moldsharing;

import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository @RequiredArgsConstructor
public class ExistingMoldGroupMappingRepository {
    private final JdbcTemplate jdbc;
    private final PilotAccessService access;

    public Map<String,Object> groups(long line,String keyword,int page,int size) {
        var args=new ArrayList<Object>(List.of(line));
        String where=" where i.product_line_id=? and i.deleted_flag=0 and "+access.visibleSqlClause("item","i.item_id");
        if(!keyword.isBlank()) {
            where+=" and (concat_ws(' ',i.mold_name,i.style_name,i.engraved_code,i.compatible_models,i.phone_model_code) ilike ? or exists(select 1 from plm_collaboration_phone_member cm join plm_phone_model_member pm on pm.id=cm.phone_model_id left join plm_code_item c on c.code_item_id=pm.code_item_id where cm.item_id=i.item_id and concat_ws(' ',pm.model_name,c.code_value) ilike ?))";
            args.add("%"+keyword+"%");args.add("%"+keyword+"%");
        }
        long total=jdbc.queryForObject("select count(*) from plm_collaboration_item i"+where,Long.class,args.toArray());
        args.add(size);args.add((long)(page-1)*size);
        var rows=jdbc.queryForList("select i.item_id as \"itemId\",coalesce(i.mold_name,i.compatible_models) as \"moldName\",i.engraved_code as \"engravedCode\",i.compatible_models as \"legacyModels\",i.master_data_status as status from plm_collaboration_item i"+where+" order by i.item_id desc limit ? offset ?",args.toArray());
        for(var row:rows) {
            var sets=jdbc.queryForList("select s.mold_set_id as \"moldSetId\",s.set_no as \"setNo\",s.set_name as \"setName\",s.master_data_status as status from plm_collaboration_mold_set s where s.item_id=? and s.deleted_flag=0 and "+access.visibleSqlClause("mold","s.mold_set_id")+" order by s.set_no",row.get("itemId"));
            for(var set:sets) {
                set.put("members",members(((Number)row.get("itemId")).longValue(),((Number)set.get("moldSetId")).longValue()));
                set.put("parts",parts(((Number)set.get("moldSetId")).longValue()));
                set.put("holeTypes",jdbc.queryForList("select distinct hole_type from plm_mold_entity_identity where mold_set_id=? union select distinct hole_type from plm_manufacturing_relation where mold_set_id=? and hole_type in ('large','precision','mixed') and status='active' order by hole_type",String.class,set.get("moldSetId"),set.get("moldSetId")));
            }
            row.put("sets",sets);
        }
        return Map.of("content",rows,"totalElements",total,"number",page-1,"size",size);
    }
    public Map<String,Object> source(long line,long item,long set,boolean lock) {
        var rows=jdbc.queryForList("select i.*,s.mold_set_id,s.master_data_version as set_version,s.master_data_status as set_status,s.updated_at as set_updated_at from plm_collaboration_item i join plm_collaboration_mold_set s on s.item_id=i.item_id where i.product_line_id=? and i.item_id=? and s.mold_set_id=? and i.deleted_flag=0 and s.deleted_flag=0"+(lock?" for update of i,s":""),line,item,set);
        if(rows.size()!=1)return null;
        var row=rows.get(0);
        if(lock)jdbc.queryForList("select * from plm_collaboration_mold_part where mold_set_id=? order by part_type for update",set);
        row.put("parts",parts(set));row.put("members",members(item,set));
        row.put("entities",jdbc.queryForList("select * from plm_mold_entity_identity where mold_set_id=? order by entity_id",set));
        row.put("relations",jdbc.queryForList("select * from plm_manufacturing_relation where mold_set_id=? order by relation_id",set));
        row.put("aliases",jdbc.queryForList("select m.* from plm_mold_entity_code_mapping m join plm_mold_entity_identity e using(entity_id) where e.mold_set_id=? order by m.mapping_code_id",set));
        return row;
    }
    public List<Map<String,Object>> parts(long set) {
        return jdbc.queryForList("select part.*,mc.mold_code as catalog_code,mc.product_id as catalog_product_id,mc.status as catalog_status,mc.deleted_flag as catalog_deleted_flag from plm_collaboration_mold_part part left join plm_product_mold_code mc on mc.product_mold_code_id=part.product_mold_code_id where part.mold_set_id=? order by part.part_type",set);
    }
    public List<Map<String,Object>> members(long item,long set) {
        return jdbc.queryForList("select cm.phone_model_id as \"phoneModelId\",pm.model_name as \"modelName\",c.code_value as \"phoneModelCode\",pm.status,cs.pc_confirmed,cs.tpu_confirmed,cs.pc_evidence,cs.tpu_evidence from plm_collaboration_phone_member cm join plm_phone_model_member pm on pm.id=cm.phone_model_id left join plm_code_item c on c.code_item_id=pm.code_item_id left join plm_collaboration_phone_set_compatibility cs on cs.item_id=cm.item_id and cs.phone_model_id=cm.phone_model_id and cs.mold_set_id=? where cm.item_id=? and (cs.mold_set_id is not null or ?=any(cm.mold_set_ids)) order by cm.phone_model_id",set,item,set);
    }
}

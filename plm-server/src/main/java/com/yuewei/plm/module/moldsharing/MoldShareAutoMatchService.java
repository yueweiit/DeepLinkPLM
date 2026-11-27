package com.yuewei.plm.module.moldsharing;

import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.pilot.PilotAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class MoldShareAutoMatchService {
    private final JdbcTemplate jdbc;
    private final PilotAccessService access;
    private final ExistingMoldGroupMappingService existing;

    public record Input(long productLineId,long referencePhoneModelId,long targetPhoneModelId,String holeType,
        List<String> materialCodes,Long selectedMoldSetId,String matchToken,String evidence,String reason) {}

    public Map<String,Object> preview(Input in) {
        validate(in);access.requireEngineeringMaintenance();access.requireProduct(in.productLineId());
        var candidates=candidates(in);var result=new LinkedHashMap<String,Object>();
        result.put("matchStatus",candidates.isEmpty()?"none":candidates.size()==1?"unique":"multiple");
        result.put("candidateCount",candidates.size());result.put("candidates",candidates);
        var chosen=choose(in,candidates,false);
        if(chosen!=null) {
            var delegated=existing.preview(delegate(in,chosen,""));
            result.put("matchedMold",chosen);result.put("codes",delegated.get("codes"));result.put("matchToken",delegated.get("sourceDigest"));
        } else {result.put("codes",List.of());result.put("matchToken","");}
        return result;
    }

    @Transactional public Map<String,Object> confirm(Input in,HttpServletRequest request) {
        validate(in);required(in.evidence(),"工程确认依据");required(in.reason(),"确认原因");
        access.requireEngineeringMaintenance();access.requireProduct(in.productLineId());
        var candidates=candidates(in);var chosen=choose(in,candidates,true);
        require(chosen!=null,"未找到可共用的有效实体模具");
        return existing.confirm(delegate(in,chosen,text(in.matchToken())),request);
    }

    private List<Map<String,Object>> candidates(Input in) {
        var selected=materials(in.materialCodes());
        var phone=jdbc.queryForMap("select pm.model_name,coalesce(c.code_value,'') as phone_code from plm_phone_model_member pm left join plm_code_item c on c.code_item_id=pm.code_item_id where pm.id=? and pm.status='enabled'",in.referencePhoneModelId());
        var target=jdbc.queryForMap("select pm.model_name from plm_phone_model_member pm where pm.id=? and pm.status='enabled'",in.targetPhoneModelId());
        String model=text(phone.get("model_name")),targetModel=text(target.get("model_name")),phoneCode=text(phone.get("phone_code"));
        String modelTail=model.contains(" ")?model.substring(model.indexOf(' ')+1):model,targetTail=targetModel.contains(" ")?targetModel.substring(targetModel.indexOf(' ')+1):targetModel;
        String placeholders=String.join(",",Collections.nCopies(selected.size(),"?"));
        var args=new ArrayList<Object>(List.of(in.productLineId(),in.referencePhoneModelId(),in.holeType(),in.referencePhoneModelId(),in.holeType(),in.referencePhoneModelId(),in.referencePhoneModelId(),"%"+model+"%","%"+model+"%",phoneCode,phoneCode,in.productLineId(),model,modelTail,targetModel,targetTail));
        args.addAll(selected);args.add(selected.size());
        String sql="""
            select i.product_line_id as "sourceProductLineId",p.product_name as "sourceProductLineName",i.item_id as "itemId",s.mold_set_id as "moldSetId",s.set_no as "setNo",s.set_name as "setName",
              coalesce(i.mold_name,i.compatible_models,'已有模具') as "moldName"
            from plm_collaboration_item i join plm_collaboration_mold_set s on s.item_id=i.item_id
            left join plm_product p on p.product_id=i.product_line_id
            where i.deleted_flag=0 and s.deleted_flag=0
              and i.master_data_status='active' and s.master_data_status='active'
              and (
                (i.product_line_id=? and (
                  exists(select 1 from plm_manufacturing_relation r where r.item_id=i.item_id and r.mold_set_id=s.mold_set_id and r.phone_model_id=? and r.hole_type=? and r.status='active')
                  or exists(select 1 from plm_mold_entity_identity e join plm_mold_entity_code_mapping m on m.entity_id=e.entity_id where e.product_line_id=i.product_line_id and e.item_id=i.item_id and e.mold_set_id=s.mold_set_id and m.phone_model_id=? and e.hole_type=? and e.status='active' and m.status='active')
                  or exists(select 1 from plm_collaboration_phone_set_compatibility c where c.item_id=i.item_id and c.mold_set_id=s.mold_set_id and c.phone_model_id=?)
                  or exists(select 1 from plm_collaboration_phone_member cm where cm.item_id=i.item_id and cm.phone_model_id=? and s.mold_set_id=any(cm.mold_set_ids))
                  or coalesce(i.compatible_models,'') ilike ? or coalesce(i.mold_name,'') ilike ? or (?<>'' and i.phone_model_code=?)
                ))
                or (i.product_line_id<>?
                  and exists(select 1 from unnest(string_to_array(concat_ws('/',i.compatible_models,i.mold_name),'/')) v where lower(btrim(v)) in (lower(?),lower(?)))
                  and exists(select 1 from unnest(string_to_array(concat_ws('/',i.compatible_models,i.mold_name),'/')) v where lower(btrim(v)) in (lower(?),lower(?))))
              )
              and (select count(distinct case when p.part_type='TPU' then '10' when p.part_type='PC' then '20' end) from plm_collaboration_mold_part p where p.mold_set_id=s.mold_set_id and case when p.part_type='TPU' then '10' when p.part_type='PC' then '20' end in ("""+placeholders+"))=? order by i.item_id,s.set_no";
        var rows=jdbc.queryForList(sql,args.toArray());
        if(in.selectedMoldSetId()!=null&&in.selectedMoldSetId()>0)rows.removeIf(row->number(row.get("moldSetId"))!=in.selectedMoldSetId());
        return rows;
    }

    private Map<String,Object> choose(Input in,List<Map<String,Object>> candidates,boolean confirming) {
        if(candidates.size()==1)return candidates.get(0);
        if(in.selectedMoldSetId()!=null&&in.selectedMoldSetId()>0&&candidates.size()==1)return candidates.get(0);
        if(confirming)require(!candidates.isEmpty()&&in.selectedMoldSetId()!=null&&in.selectedMoldSetId()>0,"存在多套或未找到实体模具，请重新预览并明确选择");
        return null;
    }

    private ExistingMoldGroupMappingService.Input delegate(Input in,Map<String,Object> chosen,String digest) {
        return new ExistingMoldGroupMappingService.Input(in.productLineId(),number(chosen.get("itemId")),number(chosen.get("moldSetId")),in.referencePhoneModelId(),in.targetPhoneModelId(),in.holeType(),text(in.evidence()),text(in.reason()),digest,new ArrayList<>(materials(in.materialCodes())),number(chosen.get("sourceProductLineId")));
    }
    private static void validate(Input in){require(in!=null&&in.productLineId()>0&&in.referencePhoneModelId()>0&&in.targetPhoneModelId()>0,"请选择产品、已开模机型和新增适用机型");require(in.referencePhoneModelId()!=in.targetPhoneModelId(),"已开模机型与新增适用机型不能相同");require(Set.of("large","precision","mixed").contains(text(in.holeType())),"请选择有效孔位类型");materials(in.materialCodes());}
    private static Set<String> materials(List<String> values){var result=values==null?Set.<String>of():new LinkedHashSet<>(values);require(!result.isEmpty()&&Set.of("10","20").containsAll(result),"请至少选择一种有效材质");return result;}
    private static void required(String value,String label){require(!text(value).isEmpty()&&text(value).length()<=2000,"请填写有效的"+label);}
    private static String text(Object value){return Objects.toString(value,"").strip();}
    private static long number(Object value){return value instanceof Number n?n.longValue():0;}
    private static void require(boolean ok,String message){if(!ok)throw new BusinessException(40901,message);}
}

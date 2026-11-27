package com.yuewei.plm.module.moldsharing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import com.yuewei.plm.module.manufacturing.ManufacturingIdentityService;
import com.yuewei.plm.module.masterdata.ProductMoldLifecycleService;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Independent mold identity mapping. It never redirects customer phones or finished goods. */
@Service @RequiredArgsConstructor
public class MoldShareMappingRegistryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PilotAccessService access;
    private final ManufacturingIdentityService manufacturing;
    private final PhoneModelReferenceService phones;
    private final OperationLogService audit;
    private final ProductBusinessCodeGenerator codeGenerator;
    public record WriteInput(Long version,long productLineId,long sourcePhoneModelId,long referencePhoneModelId,
        String materialCode,String holeType,String structureDescription,long manufacturingRelationId,
        long manufacturingRelationVersion,String evidence,String reason,String status) {}
    private static final String SELECT="""
        select m.mapping_id as id,m.version,m.product_line_id as "productLineId",p.product_name as "productLineName",
          m.source_phone_model_id as "sourcePhoneModelId",source.model_name as "sourcePhoneName",sc.code_value as "sourcePhoneCode",
          m.reference_phone_model_id as "referencePhoneModelId",ref.model_name as "referencePhoneName",rc.code_value as "referencePhoneCode",
          m.material_code as "materialCode",m.hole_type as "holeType",m.structure_description as "structureDescription",
          m.product_mold_code_id as "productMoldCodeId",mc.mold_code as "moldCode",
          m.manufacturing_relation_id as "manufacturingRelationId",m.manufacturing_relation_version as "manufacturingRelationVersion",
          r.item_id as "itemId",r.mold_set_id as "moldSetId",m.evidence,m.status,m.updated_at as "updatedAt",
          (r.status='active' and r.version=m.manufacturing_relation_version and mc.status='active' and mc.deleted_flag=0) as "relationCurrent"
        from plm_mold_share_mapping m join plm_product p on p.product_id=m.product_line_id
          join plm_product_mold_code mc on mc.product_mold_code_id=m.product_mold_code_id
          join plm_phone_model_member source on source.id=m.source_phone_model_id
          join plm_phone_model_member ref on ref.id=m.reference_phone_model_id
          left join plm_code_item sc on sc.code_item_id=source.code_item_id
          left join plm_code_item rc on rc.code_item_id=ref.code_item_id
          join plm_manufacturing_relation r on r.relation_id=m.manufacturing_relation_id
        """;
    private String visible(){return "("+access.visibleSqlClause("product","m.product_line_id")+") and ("+access.visibleSqlClause("item","r.item_id")+") and ("+access.visibleSqlClause("mold","r.mold_set_id")+")";}
    private void read(){access.requireDomesticView();access.requirePermission("collaboration:view");}
    public Map<String,Object> page(Map<String,String> query){
        read();String where=" where "+visible();var args=new ArrayList<Object>();
        for(var field:Map.of("productLineId","m.product_line_id","sourcePhoneModelId","m.source_phone_model_id","referencePhoneModelId","m.reference_phone_model_id").entrySet())if(!text(query.get(field.getKey())).isEmpty()){where+=" and "+field.getValue()+"=?";args.add(positive(query.get(field.getKey())));}
        for(var field:Map.of("holeType","m.hole_type","structureDescription","m.structure_description","materialCode","m.material_code").entrySet())if(query.containsKey(field.getKey())){where+=" and "+field.getValue()+"=?";args.add(text(query.get(field.getKey())));}
        String status=text(query.getOrDefault("status","active"));require(Set.of("active","inactive","all").contains(status),"映射状态无效");if(!status.equals("all")){where+=" and m.status=?";args.add(status);}
        String keyword=text(query.get("keyword"));if(!keyword.isEmpty()){where+=" and concat_ws(' ',p.product_name,source.model_name,sc.code_value,ref.model_name,rc.code_value,mc.mold_code) ilike ?";args.add("%"+keyword+"%");}
        long page=positive(query.getOrDefault("page","1")),size=positive(query.getOrDefault("size","20"));require(size<=100,"每页最多100条");
        long total=jdbc.queryForObject("select count(*) from ("+SELECT+where+") counted",Long.class,args.toArray());args.add(size);args.add(Math.multiplyExact(page-1,size));
        return Map.of("content",jdbc.queryForList(SELECT+where+" order by m.mapping_id desc limit ? offset ?",args.toArray()),"totalElements",total,"number",page-1,"size",size,"canWrite",access.canMaintainEngineeringData());
    }
    @Transactional public Map<String,Object> save(Long id,WriteInput input,HttpServletRequest request){
        var actor=access.requireEngineeringMaintenance();require(input!=null,"请填写共模映射");
        access.requireProduct(input.productLineId());ProductMoldLifecycleService.requireActive(jdbc,"product",input.productLineId());
        // Product locks precede mapping locks in allocation, confirmation and maintenance alike.
        jdbc.execute("select pg_advisory_xact_lock(610131110)");
        Map<String,Object> before=id==null?null:row(id,true);
        if(before!=null)require(number(before.get("productLineId"))==input.productLineId(),"不能更改原映射所属产品");
        if(before!=null)require(input.version()!=null&&number(before.get("version"))==input.version(),"映射已变化，请刷新后核对");
        String status=text(input.status()).isEmpty()?"active":input.status();require(Set.of("active","inactive").contains(status),"映射状态无效");
        if(before!=null)require(number(before.get("sourcePhoneModelId"))==input.sourcePhoneModelId()&&before.get("materialCode").equals(input.materialCode())&&before.get("holeType").equals(input.holeType())&&before.get("structureDescription").equals(text(input.structureDescription())),"原映射范围不能改成另一规格，请停用后新增");
        required(input.reason(),2000,"修改原因");required(input.evidence(),2000,"共模确认依据");
        require(Set.of("large","precision","mixed").contains(text(input.holeType())),"映射须明确大孔、精孔或混合");
        require(text(input.structureDescription()).length()<=1000,"结构说明最多1000字");
        require(ProductBusinessCodeGenerator.moldPartType(input.materialCode())!=null,"请选择TPU(10)或PC(20)");
        require(input.sourcePhoneModelId()!=input.referencePhoneModelId(),"映射源机型与基准机型不能相同");
        // Disabling never rewrites the original scope or depends on now-stale engineering evidence.
        if(before!=null&&status.equals("inactive")){
            jdbc.update("update plm_mold_share_mapping set status='inactive',version=version+1,updated_by=?,updated_at=now() where mapping_id=?",actor.username(),id);
        }else{
            require(status.equals("active"),"请先确认有效映射后再停用");
            if("mixed".equals(input.holeType()))require(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_constraint where conrelid='plm_manufacturing_relation'::regclass and conname='plm_manufacturing_relation_hole_type_check' and pg_get_constraintdef(oid) like '%mixed%')",Boolean.class)),"当前制造关系尚未支持混合孔型，请先完成独立升级");
            var relation=manufacturing.requireResolved(new ManufacturingIdentityService.ResolveInput(input.productLineId(),input.sourcePhoneModelId(),input.holeType(),text(input.structureDescription()),input.manufacturingRelationId(),input.manufacturingRelationVersion()));
            var reference=phones.requireEnabledModels(List.of(input.referencePhoneModelId())).get(input.referencePhoneModelId());
            String phoneCode=text(reference.get("phoneModelCode"));
            String partType=ProductBusinessCodeGenerator.moldPartType(input.materialCode());String code=null;
            for(JsonNode part:json.valueToTree(relation.get("parts")))if(partType.equals(part.path("partType").asText()))code=part.path("code").asText();
            require(ProductBusinessCodeGenerator.isCompleteMoldCode(code),"原实物部件缺少完整模具编码");
            codeGenerator.parseMoldCode(code,null,List.of(input.materialCode()),phoneCode);
            String marker=holeMarker(code);String expectedMarker=switch(input.holeType()){case "large" -> "D";case "precision" -> "J";case "mixed" -> "H";default -> "";};require(marker.isEmpty()||marker.equals(expectedMarker),"原模具孔型与映射孔型不一致");
            var identities=jdbc.queryForList("select product_mold_code_id from plm_product_mold_code where product_id=? and mold_code=? and deleted_flag=0 and status='active'",Long.class,input.productLineId(),code);
            require(identities.size()==1,"请先完善原模具编码台账，不能新造共模身份");
            require(jdbc.queryForObject("select count(*) from plm_mold_share_mapping where product_line_id=? and hole_type=? and structure_description=? and material_code=? and status='active' and (source_phone_model_id=? or reference_phone_model_id=?) and mapping_id<>?",Long.class,input.productLineId(),input.holeType(),text(input.structureDescription()),input.materialCode(),input.referencePhoneModelId(),input.sourcePhoneModelId(),id==null?0:id)==0,"共模映射必须直接指向原始基准，不能创建链式映射或循环");
            require(jdbc.queryForObject("select count(*) from plm_mold_share_mapping where product_line_id=? and source_phone_model_id=? and material_code=? and hole_type=? and structure_description=? and status='active' and mapping_id<>?",Long.class,input.productLineId(),input.sourcePhoneModelId(),input.materialCode(),input.holeType(),text(input.structureDescription()),id==null?0:id)==0,"此手机、材质和孔型已有有效映射，请维护原记录");
            if(id==null)id=jdbc.queryForObject("""
                insert into plm_mold_share_mapping(product_line_id,source_phone_model_id,reference_phone_model_id,material_code,hole_type,structure_description,
                  product_mold_code_id,manufacturing_relation_id,manufacturing_relation_version,evidence,status,created_by,updated_by)
                values(?,?,?,?,?,?,?,?,?,?,'active',?,?) returning mapping_id
                """,Long.class,input.productLineId(),input.sourcePhoneModelId(),input.referencePhoneModelId(),input.materialCode(),input.holeType(),text(input.structureDescription()),identities.get(0),input.manufacturingRelationId(),input.manufacturingRelationVersion(),input.evidence().strip(),actor.username(),actor.username());
            else {
                require(number(before.get("productLineId"))==input.productLineId()&&number(before.get("sourcePhoneModelId"))==input.sourcePhoneModelId()&&before.get("materialCode").equals(input.materialCode())&&before.get("holeType").equals(input.holeType())&&before.get("structureDescription").equals(text(input.structureDescription())),"原映射范围不能改成另一规格，请停用后新增");
                jdbc.update("update plm_mold_share_mapping set reference_phone_model_id=?,product_mold_code_id=?,manufacturing_relation_id=?,manufacturing_relation_version=?,evidence=?,status='active',version=version+1,updated_by=?,updated_at=now() where mapping_id=?",input.referencePhoneModelId(),identities.get(0),input.manufacturingRelationId(),input.manufacturingRelationVersion(),input.evidence().strip(),actor.username(),id);
            }
        }
        var after=row(id,false);var changes=new LinkedHashMap<String,Object>();changes.put("before",before);changes.put("after",after);changes.put("reason",input.reason());
        audit.logRequired(OperationLogCreateCommand.builder().action("MOLD_SHARE_MAPPING_SAVE").businessType("MOLD_SHARE_MAPPING").businessId(id.toString()).businessName("共模映射").detailJson(encode(changes)).request(request).build(),actor);return after;
    }
    private Map<String,Object> row(long id,boolean lock){
        if(lock)jdbc.queryForList("select mapping_id from plm_mold_share_mapping where mapping_id=? for update",id);
        var found=jdbc.queryForList(SELECT+" where m.mapping_id=? and "+visible(),id);if(found.size()!=1)throw new BusinessException(40103,"共模映射不存在或未获授权");return found.get(0);
    }
    // Old runtime parsers accept legacy codes only. Never bundle a new generator just for this registry.
    private static String holeMarker(String code){
        if(code.length()==9)return "";
        try{return (String)ProductBusinessCodeGenerator.class.getMethod("moldHoleMarker",String.class).invoke(null,code);}
        catch(ReflectiveOperationException error){throw new BusinessException(40901,"当前编码解析器尚未支持新版孔型码，请先完成独立升级");}
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException(error);}}
    private static void required(String value,int max,String label){require(!text(value).isEmpty()&&text(value).length()<=max,"请填写有效的"+label);}
    private static String text(Object value){return Objects.toString(value,"").strip();}
    private static long number(Object value){return ((Number)value).longValue();}
    private static long positive(String value){try{long n=Long.parseLong(value);require(n>0,"编号及页码须为正数");return n;}catch(NumberFormatException error){throw new BusinessException(40001,"编号或页码无效");}}
    private static void require(boolean condition,String message){if(!condition)throw new BusinessException(40901,message);}
}


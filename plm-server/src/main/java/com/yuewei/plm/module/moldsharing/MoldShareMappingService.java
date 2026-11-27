package com.yuewei.plm.module.moldsharing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.util.ManufacturingHole;
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
public class MoldShareMappingService {
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
    public record PlanInput(long productLineId,JsonNode engineeringPlan) {}
    public record ConfirmationInput(String requestId,long productLineId,JsonNode engineeringPlan,String reason) {}
    public record ApplicationConfirmation(long version,String reason) {}
    public record EntityMappingInput(long productLineId,long sourceRelationId,long sourceRelationVersion,long targetPhoneModelId,String evidence,String reason,String holeType) {}
    private static final String SELECT="""
        select m.mapping_id as id,m.version,m.product_line_id as "productLineId",p.product_name as "productLineName",
          m.source_phone_model_id as "sourcePhoneModelId",source.model_name as "sourcePhoneName",sc.code_value as "sourcePhoneCode",
          m.reference_phone_model_id as "referencePhoneModelId",ref.model_name as "referencePhoneName",rc.code_value as "referencePhoneCode",
          m.material_code as "materialCode",m.hole_type as "holeType",m.structure_description as "structureDescription",
          m.product_mold_code_id as "productMoldCodeId",mc.mold_code as "moldCode",
          m.manufacturing_relation_id as "manufacturingRelationId",m.manufacturing_relation_version as "manufacturingRelationVersion",
          r.item_id as "itemId",r.mold_set_id as "moldSetId",m.evidence,m.status,m.updated_at as "updatedAt"
        from plm_mold_share_mapping m join plm_product p on p.product_id=m.product_line_id
          join plm_product_mold_code mc on mc.product_mold_code_id=m.product_mold_code_id
          join plm_phone_model_member source on source.id=m.source_phone_model_id
          join plm_phone_model_member ref on ref.id=m.reference_phone_model_id
          left join plm_code_item sc on sc.code_item_id=source.code_item_id
          left join plm_code_item rc on rc.code_item_id=ref.code_item_id
          join plm_manufacturing_relation r on r.relation_id=m.manufacturing_relation_id
        """;
    private String visible(){return access.visibleSqlClause("product","m.product_line_id")+" and "+access.visibleSqlClause("item","r.item_id")+" and "+access.visibleSqlClause("mold","r.mold_set_id");}
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
    public Map<String,Object> entityMappings(Map<String,String> query){
        read();String where=" where "+access.visibleSqlClause("product","m.product_line_id")+" and "+access.visibleSqlClause("item","e.item_id")+" and "+access.visibleSqlClause("mold","e.mold_set_id");var args=new ArrayList<Object>();
        if(!text(query.get("productLineId")).isEmpty()){where+=" and m.product_line_id=?";args.add(positive(query.get("productLineId")));}
        if(!text(query.get("status")).isEmpty()&&!"all".equals(query.get("status"))){where+=" and m.status=?";args.add(text(query.get("status")));}
        String keyword=text(query.get("keyword"));if(!keyword.isEmpty()){where+=" and concat_ws(' ',p.product_name,target.model_name,target_code.code_value,reference.model_name,reference_code.code_value,m.mapping_code,e.primary_code) ilike ?";args.add("%"+keyword+"%");}
        String select="select m.mapping_code as \"mappingCode\",e.primary_code as \"primaryCode\",m.mapping_code_id as id,m.status,m.material_code as \"materialCode\",m.hole_type as \"holeType\",m.structure_description as \"structureDescription\",m.product_line_id as \"productLineId\",p.product_name as \"productLineName\",m.phone_model_id as \"targetPhoneModelId\",target.model_name as \"targetPhoneName\",target_code.code_value as \"targetPhoneCode\",r.phone_model_id as \"referencePhoneModelId\",reference.model_name as \"referencePhoneName\",reference_code.code_value as \"referencePhoneCode\",e.item_id as \"itemId\",e.mold_set_id as \"moldSetId\",m.source_manufacturing_relation_id as \"sourceRelationId\",m.evidence,m.created_at as \"createdAt\" from plm_mold_entity_code_mapping m join plm_mold_entity_identity e on e.entity_id=m.entity_id join plm_product p on p.product_id=m.product_line_id join plm_phone_model_member target on target.id=m.phone_model_id left join plm_code_item target_code on target_code.code_item_id=target.code_item_id left join plm_manufacturing_relation r on r.relation_id=m.source_manufacturing_relation_id left join plm_phone_model_member reference on reference.id=r.phone_model_id left join plm_code_item reference_code on reference_code.code_item_id=reference.code_item_id";
        long page=positive(query.getOrDefault("page","1")),size=positive(query.getOrDefault("size","20"));require(size<=100,"每页最多100条");long total=jdbc.queryForObject("select count(*) from ("+select+where+") counted",Long.class,args.toArray());args.add(size);args.add((page-1)*size);return Map.of("content",jdbc.queryForList(select+where+" order by m.mapping_code_id desc limit ? offset ?",args.toArray()),"totalElements",total,"page",page,"size",size,"canWrite",access.canMaintainEngineeringData());
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
            var relation=manufacturing.requireResolved(new ManufacturingIdentityService.ResolveInput(input.productLineId(),input.sourcePhoneModelId(),input.holeType(),text(input.structureDescription()),input.manufacturingRelationId(),input.manufacturingRelationVersion()));
            var reference=phones.requireEnabledModels(List.of(input.referencePhoneModelId())).get(input.referencePhoneModelId());
            String phoneCode=text(reference.get("phoneModelCode"));
            String partType=ProductBusinessCodeGenerator.moldPartType(input.materialCode());String code=null;
            for(JsonNode part:json.valueToTree(relation.get("parts")))if(partType.equals(part.path("partType").asText()))code=part.path("code").asText();
            require(ProductBusinessCodeGenerator.isCompleteMoldCode(code),"原实物部件缺少完整模具编码");
            require(ProductBusinessCodeGenerator.moldPhoneCode(code).equals(phoneCode),"基准手机须与已有原模具编码一致，不能按编号先后猜测");
            String marker=ProductBusinessCodeGenerator.moldHoleMarker(code);require(marker.isEmpty()||marker.equals(ManufacturingHole.moldMarker(input.holeType())),"原模具孔型与映射孔型不一致");
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
    public static boolean moldOnly(JsonNode plan){return plan!=null&&"mold_only".equals(plan.path("identityMode").asText());}
    public static boolean mappedMold(JsonNode plan){return moldOnly(plan)&&Set.of("reuse","new_set","shared_existing").contains(plan.path("handling").asText());}
    /** Full-set resolution: every original part must have an explicit same-scope mapping. */
    @Transactional public List<Map<String,Object>> resolvePlan(long productLineId,JsonNode plan){
        plan=com.yuewei.plm.module.moldapplication.MoldEngineeringPlan.normalize(json,plan);
        read();require(moldOnly(plan)&&plan.path("codeRuleVersion").asInt()==2,"请选择新版仅共模方案");
        require(Set.of("reuse","new_set","shared_existing").contains(plan.path("handling").asText()),"共模映射请选择沿用、共用已有或加开一套");
        access.requireProduct(productLineId);ProductMoldLifecycleService.requireActive(jdbc,"product",productLineId);
        jdbc.execute("select pg_advisory_xact_lock_shared(610131110)");
        if("shared_existing".equals(plan.path("handling").asText()))return resolveSharedApplicationPlan(productLineId,plan);
        var results=new ArrayList<Map<String,Object>>();Long selectedSet=null;
        for(JsonNode spec:plan.path("specifications")){
            require(spec.path("phoneModelIds").size()==1,"共模每行须明确一款自身手机");long phone=spec.path("phoneModelIds").get(0).asLong();
            var sharing=spec.path("moldSharing");var selections=sharing.path("mappings");require(selections.isArray()&&!selections.isEmpty(),"请选择已确认的共模映射");
            var snapshots=new ArrayList<Map<String,Object>>();Map<String,Object> relation=null;var materials=new HashSet<String>();
            for(JsonNode selection:selections){
                var mapping=row(selection.path("mappingId").asLong(),false);
                require(number(mapping.get("version"))==selection.path("version").asLong()&&"active".equals(mapping.get("status")),"共模映射已更新或停用，请重新选择");
                require(number(mapping.get("productLineId"))==productLineId&&number(mapping.get("sourcePhoneModelId"))==phone&&mapping.get("holeType").equals(spec.path("holeType").asText())&&mapping.get("structureDescription").equals(spec.path("structureDescription").asText("")),"映射款式、手机、孔型或结构与本次规格不一致");
                require(number(mapping.get("referencePhoneModelId"))==sharing.path("referencePhoneModelId").asLong(),"各材质须指向同一基准手机");
                require(materials.add(text(mapping.get("materialCode")))&&mapping.get("materialCode").equals(selection.path("materialCode").asText()),"共模材质重复或不一致");
                jdbc.queryForList("select relation_id from plm_manufacturing_relation where relation_id=? for share",mapping.get("manufacturingRelationId"));
                var actual=manufacturing.requireResolved(new ManufacturingIdentityService.ResolveInput(productLineId,phone,spec.path("holeType").asText(),spec.path("structureDescription").asText(""),number(mapping.get("manufacturingRelationId")),number(mapping.get("manufacturingRelationVersion"))));
                if(relation!=null)require(number(relation.get("relationId"))==number(actual.get("relationId")),"整套共模须使用同一工程关系");relation=actual;
                boolean matches=false;for(JsonNode part:json.valueToTree(actual.get("parts")))if(ProductBusinessCodeGenerator.moldPartType(text(mapping.get("materialCode"))).equals(part.path("partType").asText())&&(mapping.get("moldCode").equals(part.path("code").asText())||mapping.get("moldCode").equals(part.path("entityPrimaryCode").asText())))matches=true;
                require(matches,"原实物部件编码已变化，请工程重新核对映射");
                ProductMoldLifecycleService.requireActive(jdbc,"product",productLineId);
                require(jdbc.queryForObject("select count(*) from plm_product_mold_code where product_mold_code_id=? and deleted_flag=0 and status='active'",Long.class,mapping.get("productMoldCodeId"))==1,"原模具编码已停用");snapshots.add(mapping);
            }
            require(relation!=null,"共模关系缺失");var parts=json.valueToTree(relation.get("parts"));require(parts.size()==materials.size(),"部分部件通用不能作为整套共模，请补齐全部材质映射");
            long set=number(relation.get("moldSetId"));require(selectedSet==null||selectedSet==set,"本次共模须指向同一实物套，不同套请分别办理");selectedSet=set;
            var resolved=new LinkedHashMap<>(relation);resolved.put("moldOnly",true);resolved.put("mappingSnapshots",snapshots);results.add(resolved);
        }
        require(!results.isEmpty(),"请选择适用规格");return List.copyOf(results);
    }
    private List<Map<String,Object>> resolveSharedApplicationPlan(long productLineId,JsonNode plan){
        var results=new ArrayList<Map<String,Object>>();Long selectedSet=null;
        for(JsonNode spec:plan.path("specifications")){
            require(spec.path("phoneModelIds").size()==1,"共模申请每行须选择一款目标手机");long targetPhone=spec.path("phoneModelIds").get(0).asLong();
            var sharing=spec.path("moldSharing");long referencePhone=sharing.path("referencePhoneModelId").asLong();require(referencePhone>0&&referencePhone!=targetPhone,"基准机型须是已开模的其他机型");
            var selected=sharing.path("mappings");require(selected.isArray()&&!selected.isEmpty(),"请选择目标实体的完整材质部件");var materials=new HashSet<String>();Map<String,Object> relation=null;var snapshots=new ArrayList<Map<String,Object>>();
            for(JsonNode choice:selected){String material=choice.path("materialCode").asText();require(materials.add(material),"共模部件材质重复");
                var actual=manufacturing.requireResolved(new ManufacturingIdentityService.ResolveInput(productLineId,referencePhone,spec.path("holeType").asText(),spec.path("structureDescription").asText(""),choice.path("mappingId").asLong(),choice.path("version").asLong()));
                if(relation!=null)require(number(relation.get("relationId"))==number(actual.get("relationId")),"共模部件须来自同一已确认制造关系");relation=actual;
                JsonNode parts=json.valueToTree(actual.get("parts"));var matching=new ArrayList<JsonNode>();for(JsonNode part:parts)if(ProductBusinessCodeGenerator.moldPartType(material).equals(part.path("partType").asText()))matching.add(part);
                require(matching.size()==1,"目标实物套缺少唯一的对应材质部件");JsonNode part=matching.get(0);String primary=text(part.path("entityPrimaryCode").asText(part.path("code").asText()));
                require(ProductBusinessCodeGenerator.isCompleteMoldCode(primary),"目标模具部件缺少完整主编码");require(part.path("catalogCodeId").asLong()>0,"目标模具部件尚未关联编码主档，请先由工程核实");var snapshot=new LinkedHashMap<String,Object>();snapshot.put("id",choice.path("mappingId").asLong());snapshot.put("version",choice.path("version").asLong());snapshot.put("materialCode",material);snapshot.put("moldCode",primary);snapshot.put("productMoldCodeId",part.path("catalogCodeId").asLong());snapshot.put("manufacturingRelationId",actual.get("relationId"));snapshot.put("manufacturingRelationVersion",actual.get("relationVersion"));snapshots.add(snapshot);
            }
            require(relation!=null&&json.valueToTree(relation.get("parts")).size()==materials.size(),"共模申请须覆盖目标实体全部PC/TPU部件");long set=number(relation.get("moldSetId"));require(selectedSet==null||selectedSet==set,"同一共模申请须指向同一副实体模具");selectedSet=set;
            var resolved=new LinkedHashMap<>(relation);resolved.put("moldOnly",true);resolved.put("mappingSnapshots",snapshots);resolved.put("referencePhoneModelId",referencePhone);results.add(resolved);
        }
        require(!results.isEmpty(),"请选择适用规格");return List.copyOf(results);
    }
    /** An existing canonical identity must be reused or explicitly expanded through approval. */
    public void requireUnmappedNew(long productLineId,JsonNode plan){
        if(plan==null||plan.path("codeRuleVersion").asInt()!=2||!"new".equals(plan.path("handling").asText()))return;
        access.requireProduct(productLineId);ProductMoldLifecycleService.requireActive(jdbc,"product",productLineId);
        jdbc.execute("select pg_advisory_xact_lock_shared(610131110)");
        for(JsonNode spec:plan.path("specifications"))for(JsonNode phone:spec.path("phoneModelIds"))
            require(jdbc.queryForObject("select count(*) from plm_mold_share_mapping where product_line_id=? and source_phone_model_id=? and hole_type=? and structure_description=? and status='active'",Long.class,productLineId,phone.asLong(),spec.path("holeType").asText(),spec.path("structureDescription").asText(""))==0,"此规格已有共模映射，请选择沿用已有；确需新增实物请选加开一套并填写原因");
    }
    @Transactional public Map<String,Object> confirm(ConfirmationInput input,HttpServletRequest request){
        var actor=access.requireEngineeringMaintenance();require(input!=null&&input.requestId()!=null&&input.requestId().matches("[A-Za-z0-9:_-]{8,128}"),"请提供有效请求ID");required(input.reason(),2000,"采用依据");
        input=new ConfirmationInput(input.requestId(),input.productLineId(),com.yuewei.plm.module.moldapplication.MoldEngineeringPlan.normalize(json,input.engineeringPlan()),input.reason().strip());
        require(input.engineeringPlan()!=null&&"reuse".equals(input.engineeringPlan().path("handling").asText()),"直接采用请选择沿用已有模具");
        access.requireProduct(input.productLineId());jdbc.execute("select pg_advisory_xact_lock(610131111)");String hash=digest(encode(input));
        var prior=jdbc.queryForList("select confirmation_id as \"confirmationId\",request_hash,snapshot::text from plm_mold_share_confirmation where request_id=?",input.requestId());
        if(!prior.isEmpty()){require(hash.equals(prior.get(0).get("request_hash")),"此请求已用于另一份采用确认");var snapshot=decode(prior.get(0).get("snapshot"));access.requireItem(number(snapshot.get("itemId")));access.requireMold(number(snapshot.get("moldSetId")));snapshot.put("confirmationId",prior.get(0).get("confirmationId"));return snapshot;}
        var resolved=resolvePlan(input.productLineId(),input.engineeringPlan());require(input.engineeringPlan().path("specifications").size()==1,"直接采用请逐个业务手机办理");
        var relation=resolved.get(0);long phone=input.engineeringPlan().path("specifications").get(0).path("phoneModelIds").get(0).asLong();
        var snapshot=new LinkedHashMap<String,Object>();snapshot.put("engineeringPlan",input.engineeringPlan());snapshot.put("moldSnapshots",resolved);snapshot.put("itemId",relation.get("itemId"));snapshot.put("moldSetId",relation.get("moldSetId"));snapshot.put("phoneModelId",phone);snapshot.put("identityMode","mold_only");
        long id=jdbc.queryForObject("insert into plm_mold_share_confirmation(request_id,request_hash,product_line_id,phone_model_id,item_id,mold_set_id,snapshot,reason,created_by) values(?,?,?,?,?,?,?::jsonb,?,?) returning confirmation_id",Long.class,input.requestId(),hash,input.productLineId(),phone,relation.get("itemId"),relation.get("moldSetId"),encode(snapshot),input.reason().strip(),actor.username());snapshot.put("confirmationId",id);
        audit.logRequired(OperationLogCreateCommand.builder().action("MOLD_SHARE_ADOPT").businessType("MOLD_SHARE_CONFIRMATION").businessId(Long.toString(id)).businessName("仅共模采用确认").detailJson(encode(snapshot)).request(request).build(),actor);return snapshot;
    }
    /** Read the persisted adoption, never trust a caller-supplied snapshot or re-resolve a later mapping version. */
    public Map<String,Object> confirmations(Map<String,String> query){
        read();long page=positive(query.getOrDefault("page","1")),size=positive(query.getOrDefault("size","20"));require(size<=100,"每页最多100条");
        String where=" where "+access.visibleSqlClause("product","c.product_line_id")+" and "+access.visibleSqlClause("item","c.item_id")+" and "+access.visibleSqlClause("mold","c.mold_set_id");
        var args=new ArrayList<Object>();if(!text(query.get("productLineId")).isEmpty()){where+=" and c.product_line_id=?";args.add(positive(query.get("productLineId")));}
        long total=jdbc.queryForObject("select count(*) from plm_mold_share_confirmation c"+where,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
        var rows=jdbc.queryForList("select c.confirmation_id,c.product_line_id from plm_mold_share_confirmation c"+where+" order by c.confirmation_id desc limit ? offset ?",args.toArray());
        return Map.of("content",rows.stream().map(row->confirmation(number(row.get("product_line_id")),number(row.get("confirmation_id")))).toList(),"totalElements",total,"number",page-1,"size",size);
    }
    public Map<String,Object> confirmation(long productLineId,long confirmationId){
        read();access.requireProduct(productLineId);
        var rows=jdbc.queryForList("select snapshot::text,created_by,created_at,reason from plm_mold_share_confirmation where confirmation_id=? and product_line_id=?",confirmationId,productLineId);
        require(rows.size()==1,"采用确认不存在或不属于本产品");var row=rows.get(0);var result=decode(row.get("snapshot"));
        access.requireItem(number(result.get("itemId")));access.requireMold(number(result.get("moldSetId")));
        result.put("confirmationId",confirmationId);result.put("createdBy",row.get("created_by"));result.put("createdAt",row.get("created_at"));result.put("reason",row.get("reason"));return result;
    }
    public List<Map<String,Object>> confirmedPlan(long productLineId,long confirmationId,long moldSetId,JsonNode plan){
        var adopted=confirmation(productLineId,confirmationId);
        require(number(adopted.get("moldSetId"))==moldSetId&&Objects.equals(
            com.yuewei.plm.module.moldapplication.MoldEngineeringPlan.normalize(json,json.valueToTree(adopted.get("engineeringPlan"))),
            com.yuewei.plm.module.moldapplication.MoldEngineeringPlan.normalize(json,plan)),"采用确认与本次规格或实物套不一致");
        var result=new ArrayList<Map<String,Object>>();
        for(var snapshot:json.valueToTree(adopted.get("moldSnapshots"))){
            Map<String,Object> row=json.convertValue(snapshot,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
            long phone=snapshot.path("businessPhone").path("phoneModelId").asLong();
            phones.requireCompatibility(productLineId,phone,number(row.get("itemId")),moldSetId,snapshot.path("compatibilityDigest").asText());
            result.add(row);
        }
        require(!result.isEmpty(),"采用确认缺少实物适配快照");return List.copyOf(result);
    }
    @Transactional public Map<String,Object> confirmApprovedApplication(long applicationId,ApplicationConfirmation input,HttpServletRequest request){
        var actor=access.requireEngineeringMaintenance();required(input.reason(),2000,"工程确认依据");
        var app=jdbc.queryForList("select application_id,product_line_id,version_no,approval_no,remote_status,remote_result,approval_granted,authorization_json::text,source_snapshot_json::text,execution_resolution_json::text from plm_mold_application where application_id=? and deleted_flag=0 for update",applicationId);
        require(app.size()==1,"开模申请不存在");var row=app.get(0);require(number(row.get("version_no"))==input.version(),"申请已更新，请刷新后核对");
        require(Boolean.TRUE.equals(row.get("approval_granted"))&&"COMPLETED".equals(row.get("remote_status"))&&Set.of("AGREE","AGREED","APPROVED").contains(text(row.get("remote_result")).toUpperCase(Locale.ROOT)),"钉钉审批尚未明确完成同意");
        var authorization=decode(row.get("authorization_json"));require("task".equals(authorization.get("evidenceSource")),"缺少可追溯的审批任务证据");
        var source=decode(row.get("source_snapshot_json"));JsonNode plan=com.yuewei.plm.module.moldapplication.MoldEngineeringPlan.normalize(json,json.valueToTree(source.get("engineeringPlan")));
        require(plan!=null&&"shared_existing".equals(plan.path("handling").asText())&&moldOnly(plan),"此申请不是共用已有模具审批分支");
        var resolution=decode(row.get("execution_resolution_json"));if("active".equals(resolution.get("sharedMoldMappingStatus")))return Map.of("applicationId",applicationId,"status","active","mappings",resolution.get("sharedMoldMappings"));
        jdbc.execute("select pg_advisory_xact_lock(66817399)");jdbc.execute("select pg_advisory_xact_lock(610131102)");jdbc.execute("select pg_advisory_xact_lock(610131110)");
        long line=number(row.get("product_line_id"));access.requireProduct(line);var resolved=resolvePlan(line,plan);var result=new ArrayList<Map<String,Object>>();
        for(int i=0;i<plan.path("specifications").size();i++){
            JsonNode spec=plan.path("specifications").get(i);long phone=spec.path("phoneModelIds").get(0).asLong();String phoneCode=Objects.toString(phones.requireEnabledModels(List.of(phone)).get(phone).get("phoneModelCode"),"");
            var relation=resolved.get(i);long item=number(relation.get("itemId")),set=number(relation.get("moldSetId"));access.requireItem(item);access.requireMold(set);
            JsonNode selections=spec.path("moldSharing").path("mappings");@SuppressWarnings("unchecked") List<Map<String,Object>> targetParts=(List<Map<String,Object>>)relation.get("mappingSnapshots");
            for(int choiceIndex=0;choiceIndex<selections.size();choiceIndex++){JsonNode selection=selections.get(choiceIndex);Map<String,Object> mapping=targetParts.get(choiceIndex);long relationId=number(mapping.get("manufacturingRelationId"));String material=text(mapping.get("materialCode"));String primaryCode=text(mapping.get("moldCode"));
                require(ProductBusinessCodeGenerator.isCompleteMoldCode(primaryCode)&&!ProductBusinessCodeGenerator.moldHoleMarker(primaryCode).isEmpty(),"实体主模具编码不是可映射的v2完整编码，请人工核对");
                require(ProductBusinessCodeGenerator.moldPartType(primaryCode).equals(ProductBusinessCodeGenerator.moldPartType(material)),"实体主码材质与映射部件不一致");
                String alias=codeGenerator.generateMoldCode(primaryCode.substring(1,3),material,phoneCode,spec.path("holeType").asText());
                require(jdbc.queryForObject("select (select count(*) from plm_product_mold_code where upper(mold_code)=? and deleted_flag=0)+(select count(*) from plm_mold_code_reservation r cross join lateral jsonb_array_elements(coalesce(r.response->'moldCodes','[]'::jsonb)) as reserved_codes(code_row) where upper(reserved_codes.code_row->>'code')=?)+(select count(*) from plm_mold_application where deleted_flag=0 and upper(coalesce(generated_codes,'')) like '%'||?||'%')",Long.class,alias,alias,alias)==0,"映射编码已存在于模具主档、编码预留或申请记录，已阻止覆盖，请工程人工核验");
                long entity=jdbc.queryForObject("insert into plm_mold_entity_identity(product_line_id,item_id,mold_set_id,material_code,hole_type,structure_description,primary_product_mold_code_id,primary_code,evidence,created_by) values(?,?,?,?,?,?,?,?,?,?) on conflict(product_line_id,mold_set_id,material_code,hole_type,structure_description) do update set primary_code=plm_mold_entity_identity.primary_code returning entity_id",Long.class,line,item,set,material,spec.path("holeType").asText(),spec.path("structureDescription").asText(""),mapping.get("productMoldCodeId"),primaryCode,input.reason().strip(),actor.username());
                var ids=jdbc.queryForList("select entity_id,primary_code,primary_product_mold_code_id from plm_mold_entity_identity where entity_id=? for update",entity);
                require(ids.size()==1&&primaryCode.equals(ids.get(0).get("primary_code"))&&number(ids.get(0).get("primary_product_mold_code_id"))==number(mapping.get("productMoldCodeId")),"实体模具已锁定其他主编码，需人工核验");
                Long codeId=jdbc.queryForObject("insert into plm_mold_entity_code_mapping(entity_id,product_line_id,phone_model_id,material_code,hole_type,structure_description,mapping_code,source_application_id,source_manufacturing_relation_id,evidence,created_by) values(?,?,?,?,?,?,?,?,?,?,?) on conflict(source_application_id,phone_model_id,material_code) do update set mapping_code=plm_mold_entity_code_mapping.mapping_code where plm_mold_entity_code_mapping.entity_id=excluded.entity_id and plm_mold_entity_code_mapping.mapping_code=excluded.mapping_code returning mapping_code_id",Long.class,entity,line,phone,material,spec.path("holeType").asText(),spec.path("structureDescription").asText(""),alias,applicationId,relationId,input.reason().strip(),actor.username());
                require(codeId!=null,"映射码已被其他实体或机型占用，请人工核对");result.add(Map.of("mappingCodeId",codeId,"entityId",entity,"mappingCode",alias,"primaryCode",primaryCode,"phoneModelId",phone,"materialCode",material,"itemId",item,"moldSetId",set));
            }
            long conflictingRelations=jdbc.queryForObject("select count(*) from plm_manufacturing_relation where product_line_id=? and phone_model_id=? and hole_type=? and structure_description=? and status='active'",Long.class,line,phone,spec.path("holeType").asText(),spec.path("structureDescription").asText(""));require(conflictingRelations==0,"该机型规格已有制造关系，请先核对原关系，避免把映射到其他实物套");
            String evidence="开模申请 "+applicationId+" / 审批 "+Objects.toString(row.get("approval_no"),"待核对")+"；工程确认："+input.reason().strip();String relationDigest=digest(applicationId+"|"+phone+"|"+set+"|"+evidence);
            jdbc.update("insert into plm_manufacturing_relation(product_line_id,item_id,mold_set_id,phone_model_id,hole_type,structure_description,shared_material,manufacturing_product_id,sku_ids,mold_evidence,material_evidence,compatibility_digest,status,created_by,updated_by) values(?,?,?,?,?,?,false,null,'[]'::jsonb,?,null,?,'active',?,?)",line,item,set,phone,spec.path("holeType").asText(),spec.path("structureDescription").asText(""),evidence,relationDigest,actor.username(),actor.username());
        }
        jdbc.update("update plm_mold_application set execution_resolution_json=coalesce(execution_resolution_json,'{}'::jsonb)||jsonb_build_object('sharedMoldMappingStatus','active','sharedMoldMappings',?::jsonb,'sharedMoldConfirmedBy',?,'sharedMoldConfirmedAt',now(),'sharedMoldConfirmationReason',?),version_no=version_no+1,updated_at=now() where application_id=?",encode(result),actor.username(),input.reason().strip(),applicationId);
        audit.logRequired(OperationLogCreateCommand.builder().action("MOLD_SHARED_APPLICATION_CONFIRM").businessType("MOLD_APPLICATION").businessId(Long.toString(applicationId)).businessName("开模申请共模工程确认").detailJson(encode(Map.of("mappings",result,"reason",input.reason()))).request(request).build(),actor);
        return Map.of("applicationId",applicationId,"status","active","mappings",result);
    }
    /** Shared by preview and confirmation; no catalog, reservation, or physical-mold writes. */
    private record EntityPlan(Map<String,Object> relation,String hole,String structure,List<Map<String,Object>> codes) {}
    private EntityPlan entityPlan(EntityMappingInput input){
        require(input!=null&&input.productLineId()>0&&input.sourceRelationId()>0&&input.sourceRelationVersion()>0&&input.targetPhoneModelId()>0,"请选择产品、已开模基准关系和目标机型");
        access.requireProduct(input.productLineId());ProductMoldLifecycleService.requireActive(jdbc,"product",input.productLineId());
        var relation=manufacturing.detail(input.sourceRelationId());require(number(relation.get("productLineId"))==input.productLineId()&&number(relation.get("version"))==input.sourceRelationVersion(),"基准制造关系已变化或不属于本产品，请刷新核对");
        require(number(relation.get("phoneModelId"))!=input.targetPhoneModelId(),"目标机型不能与基准机型相同");
        var source=manufacturing.requireResolved(new ManufacturingIdentityService.ResolveInput(input.productLineId(),number(relation.get("phoneModelId")),text(relation.get("holeType")),text(relation.get("structureDescription")),input.sourceRelationId(),input.sourceRelationVersion()));
        String known=text(relation.get("holeType")),hole=text(input.holeType()).isEmpty()?known:text(input.holeType());
        require(Set.of("large","precision","mixed").contains(hole),"请选择大孔、精孔或混合孔型");
        require(!Set.of("large","precision","mixed").contains(known)||known.equals(hole),"所选孔型与已有模具的明确孔型不一致");
        long item=number(relation.get("itemId")),set=number(relation.get("moldSetId"));access.requireItem(item);access.requireMold(set);
        require(jdbc.queryForObject("select count(*) from plm_mold_entity_identity where product_line_id=? and mold_set_id=? and status='active' and hole_type<>?",Long.class,input.productLineId(),set,hole)==0,"该实物模具已有不同的明确孔型，请核对后选择");
        var models=phones.requireEnabledModels(List.of(number(relation.get("phoneModelId")),input.targetPhoneModelId()));
        String sourcePhone=text(models.get(number(relation.get("phoneModelId"))).get("phoneModelCode")),targetPhone=text(models.get(input.targetPhoneModelId()).get("phoneModelCode"));
        Object raw=source.get("parts");require(raw instanceof List<?> && !((List<?>)raw).isEmpty(),"基准实物关系缺少模具部件");
        var codes=new ArrayList<Map<String,Object>>();var materials=new HashSet<String>();
        for(Object value:(List<?>)raw){require(value instanceof Map<?,?>,"基准部件数据无效");var part=(Map<?,?>)value;String type=text(part.get("partType")),material=type.equals("PC")?"20":type.equals("TPU")?"10":"";
            require(!material.isEmpty()&&materials.add(material),"基准实物部件材质重复或不支持");String primary=text(part.get("entityPrimaryCode"));if(primary.isEmpty())primary=text(part.get("code"));
            var row=new LinkedHashMap<String,Object>(entityCodes(codeGenerator,primary,material,sourcePhone,targetPhone,hole));
            long catalogId=part.get("catalogCodeId") instanceof Number n?n.longValue():0;require(catalogId>0,"基准主编码尚未关联模具编码主档");row.put("catalogCodeId",catalogId);codes.add(row);
        }
        require(materials.containsAll(Set.of("10","20")),"共模映射必须核对完整PC/TPU实物部件");
        return new EntityPlan(relation,hole,text(relation.get("structureDescription")),codes);
    }
    static Map<String,Object> entityCodes(ProductBusinessCodeGenerator generator,String primary,String material,String sourcePhone,String targetPhone,String hole){
        require(ProductBusinessCodeGenerator.isCompleteMoldCode(primary),"基准部件缺少完整原模具编码，目录前缀不能作为实物模具");
        var parsed=generator.parseMoldCode(primary,null,List.of(material),null);
        String marker=ProductBusinessCodeGenerator.moldHoleMarker(primary);require(marker.isEmpty()||marker.equals(ManufacturingHole.moldMarker(hole)),"原模具编码的孔型与所选孔型不一致");
        return Map.of("primaryCode",primary,"materialCode",material,"sourceMappingCode",generator.generateMoldCode(parsed.productSpecificCode(),material,sourcePhone,hole),"mappingCode",generator.generateMoldCode(parsed.productSpecificCode(),material,targetPhone,hole));
    }
    @Transactional public Map<String,Object> previewEntityMapping(EntityMappingInput input){
        access.requireEngineeringMaintenance();var plan=entityPlan(input);return Map.of("holeType",plan.hole(),"codes",plan.codes());
    }
    @Transactional public Map<String,Object> confirmEntityMapping(EntityMappingInput input,HttpServletRequest request){
        var actor=access.requireEngineeringMaintenance();require(input!=null&&input.productLineId()>0&&input.sourceRelationId()>0&&input.sourceRelationVersion()>0&&input.targetPhoneModelId()>0,"请选择产品、已开模基准关系和目标机型");required(input.evidence(),2000,"工程确认依据");required(input.reason(),2000,"确认原因");
        access.requireProduct(input.productLineId());ProductMoldLifecycleService.requireActive(jdbc,"product",input.productLineId());jdbc.execute("select pg_advisory_xact_lock(66817399)");jdbc.execute("select pg_advisory_xact_lock(610131102)");jdbc.execute("select pg_advisory_xact_lock(610131110)");
        var plan=entityPlan(input);var relation=plan.relation();
        long referencePhone=number(relation.get("phoneModelId"));
        long item=number(relation.get("itemId")),set=number(relation.get("moldSetId"));access.requireItem(item);access.requireMold(set);String hole=plan.hole(),structure=plan.structure();
        var materials=new HashSet<String>();var result=new ArrayList<Map<String,Object>>();
        for(var part:plan.codes()){String material=text(part.get("materialCode")),primary=text(part.get("primaryCode"));materials.add(material);long catalogId=number(part.get("catalogCodeId"));String alias=text(part.get("mappingCode"));
            long entity=jdbc.queryForObject("insert into plm_mold_entity_identity(product_line_id,item_id,mold_set_id,material_code,hole_type,structure_description,primary_product_mold_code_id,primary_code,evidence,created_by) values(?,?,?,?,?,?,?,?,?,?) on conflict(product_line_id,mold_set_id,material_code,hole_type,structure_description) do update set primary_code=plm_mold_entity_identity.primary_code returning entity_id",Long.class,input.productLineId(),item,set,material,hole,structure,catalogId,primary,input.evidence().strip(),actor.username());
            var existing=jdbc.queryForList("select primary_code,primary_product_mold_code_id from plm_mold_entity_identity where entity_id=? and status='active' for update",entity);
            require(existing.size()==1&&primary.equals(existing.get(0).get("primary_code"))&&catalogId==number(existing.get(0).get("primary_product_mold_code_id")),"该实物套已停用或绑定不同实体主码，需人工核查");
            String sourceAlias=text(part.get("sourceMappingCode"));if(!sourceAlias.equals(primary))saveEntityAlias(entity,input,referencePhone,material,hole,structure,sourceAlias,actor.username());
            long mappingId=saveEntityAlias(entity,input,input.targetPhoneModelId(),material,hole,structure,alias,actor.username());result.add(Map.of("mappingCodeId",mappingId,"entityId",entity,"mappingCode",alias,"primaryCode",primary,"phoneModelId",input.targetPhoneModelId(),"referencePhoneModelId",referencePhone,"materialCode",material,"itemId",item,"moldSetId",set));
        }
        require(materials.containsAll(Set.of(ProductBusinessCodeGenerator.PC_MATERIAL_CODE,ProductBusinessCodeGenerator.TPU_MATERIAL_CODE)),"共模映射必须核对完整PC/TPU实物部件");
        if(!hole.equals(text(relation.get("holeType"))))ensureEntityRelation(input,referencePhone,item,set,hole,structure,actor.username());
        ensureEntityRelation(input,input.targetPhoneModelId(),item,set,hole,structure,actor.username());
        var detail=Map.<String,Object>of("mappings",result,"reason",input.reason().strip(),"sourceRelationId",input.sourceRelationId());audit.logRequired(OperationLogCreateCommand.builder().action("MOLD_ENTITY_MAPPING_CONFIRM").businessType("MOLD_ENTITY_MAPPING").businessId(input.productLineId()+":"+set+":"+input.targetPhoneModelId()).businessName("共模实体映射工程确认").detailJson(encode(detail)).request(request).build(),actor);return Map.of("status","active","mappings",result);
    }
    private long saveEntityAlias(long entity,EntityMappingInput input,long phone,String material,String hole,String structure,String alias,String username){
        var same=jdbc.queryForList("select mapping_code_id from plm_mold_entity_code_mapping where entity_id=? and phone_model_id=? and material_code=? and hole_type=? and structure_description=? and mapping_code=? and status='active'",entity,phone,material,hole,structure,alias);if(same.size()==1)return number(same.get(0).get("mapping_code_id"));
        Long occupied=jdbc.queryForObject("select (select count(*) from plm_product_mold_code where upper(mold_code)=? and deleted_flag=0)+(select count(*) from plm_mold_code_reservation r cross join lateral jsonb_array_elements(coalesce(r.response->'moldCodes','[]'::jsonb)) as reserved_codes(code_row) where upper(reserved_codes.code_row->>'code')=?)+(select count(*) from plm_mold_application where deleted_flag=0 and upper(coalesce(generated_codes,'')) like '%'||?||'%')",Long.class,alias,alias,alias);require(occupied!=null&&occupied==0,"映射码已被模具主档、预留或申请记录占用，需工程人工核查");
        require(jdbc.queryForObject("select count(*) from plm_mold_entity_code_mapping where upper(mapping_code)=upper(?) and entity_id<>?",Long.class,alias,entity)==0,"映射编码已属于其他实体，需工程人工核查");
        Long id=jdbc.queryForObject("insert into plm_mold_entity_code_mapping(entity_id,product_line_id,phone_model_id,material_code,hole_type,structure_description,mapping_code,source_manufacturing_relation_id,evidence,created_by) values(?,?,?,?,?,?,?,?,?,?) on conflict(entity_id,phone_model_id,material_code,hole_type,structure_description) do update set mapping_code=plm_mold_entity_code_mapping.mapping_code where plm_mold_entity_code_mapping.mapping_code=excluded.mapping_code and plm_mold_entity_code_mapping.status='active' returning mapping_code_id",Long.class,entity,input.productLineId(),phone,material,hole,structure,alias,input.sourceRelationId(),input.evidence().strip(),username);require(id!=null,"该机型已有不同或停用的映射编码，请人工核查");return id;
    }
    private void ensureEntityRelation(EntityMappingInput input,long phone,long item,long set,String hole,String structure,String username){
        var existing=jdbc.queryForList("select item_id,mold_set_id from plm_manufacturing_relation where product_line_id=? and phone_model_id=? and hole_type=? and structure_description=? and status='active'",input.productLineId(),phone,hole,structure);
        require(existing.isEmpty()||(existing.size()==1&&number(existing.get(0).get("item_id"))==item&&number(existing.get(0).get("mold_set_id"))==set),"该机型规格已有指向其他实物的制造关系，不能覆盖");if(!existing.isEmpty())return;
        String note="基础资料工程确认："+input.evidence().strip();String hash=digest(input.productLineId()+"|"+phone+"|"+set+"|"+note);
        jdbc.update("insert into plm_manufacturing_relation(product_line_id,item_id,mold_set_id,phone_model_id,hole_type,structure_description,shared_material,manufacturing_product_id,sku_ids,mold_evidence,material_evidence,compatibility_digest,status,created_by,updated_by) values(?,?,?,?,?,?,false,null,'[]'::jsonb,?,null,?,'active',?,?)",input.productLineId(),item,set,phone,hole,structure,note,hash,username,username);
    }
    public Map<String,Object> resolveEntityCode(String code){read();var rows=jdbc.queryForList("select m.mapping_code,e.primary_code,e.entity_id,e.item_id,e.mold_set_id,m.product_line_id,m.phone_model_id,m.material_code,m.hole_type,m.status from plm_mold_entity_code_mapping m join plm_mold_entity_identity e on e.entity_id=m.entity_id where m.mapping_code=? and m.status='active' and e.status='active' and "+access.visibleSqlClause("product","m.product_line_id")+" and "+access.visibleSqlClause("item","e.item_id")+" and "+access.visibleSqlClause("mold","e.mold_set_id")+" order by m.mapping_code_id",code);require(!rows.isEmpty()&&rows.stream().map(row->number(row.get("entity_id"))).distinct().count()==1,"有效共模映射编码不存在、未获授权或指向冲突实体");var result=new LinkedHashMap<>(rows.get(0));result.put("phoneModelIds",rows.stream().map(row->number(row.get("phone_model_id"))).distinct().toList());return result;}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException(error);}}
    private Map<String,Object> decode(Object value){if(value==null)return new LinkedHashMap<>();try{return json.readValue(value.toString(),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception error){throw new IllegalStateException(error);}}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);}}
    private static void required(String value,int max,String label){require(!text(value).isEmpty()&&text(value).length()<=max,"请填写有效的"+label);}
    private static String text(Object value){return Objects.toString(value,"").strip();}
    private static long number(Object value){return ((Number)value).longValue();}
    private static long positive(String value){try{long n=Long.parseLong(value);require(n>0,"编号及页码须为正数");return n;}catch(NumberFormatException error){throw new BusinessException(40001,"编号或页码无效");}}
    private static void require(boolean condition,String message){if(!condition)throw new BusinessException(40901,message);}
}

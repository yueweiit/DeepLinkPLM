package com.yuewei.plm.module.moldsharing;

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
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class ExistingMoldGroupMappingService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PilotAccessService access;
    private final ExistingMoldGroupMappingRepository repository;
    private final PhoneModelReferenceService phones;
    private final ProductBusinessCodeGenerator generator;
    private final ManufacturingIdentityService manufacturing;
    private final FinishedProductMappingService finishedProductMappings;
    private final OperationLogService audit;
    public record Input(long productLineId,long itemId,long moldSetId,long referencePhoneModelId,
        long targetPhoneModelId,String holeType,String evidence,String reason,String sourceDigest,List<String> materialCodes,long sourceProductLineId) {}
    record Plan(Map<String,Object> source,List<Map<String,Object>> codes,String structure,String digest) {}

    public Map<String,Object> groups(long productLineId,String keyword,int page,int size) {
        access.requireDomesticView();access.requirePermission("collaboration:view");access.requireProduct(productLineId);
        require(productLineId>0&&page>0&&size>0&&size<=100&&text(keyword).length()<=128,"产品或分页参数无效");
        return repository.groups(productLineId,text(keyword),page,size);
    }
    @Transactional public Map<String,Object> preview(Input input) {
        access.requireEngineeringMaintenance();var plan=plan(input,false);
        return Map.of("itemId",input.itemId(),"moldSetId",input.moldSetId(),"holeType",input.holeType(),"codes",plan.codes(),"sourceDigest",plan.digest());
    }
    private Plan plan(Input in,boolean lock) {
        require(in!=null&&in.productLineId()>0&&in.itemId()>0&&in.moldSetId()>0&&in.referencePhoneModelId()>0&&in.targetPhoneModelId()>0,"请选择产品、模具组、具体套、基准机型和新增机型");
        require(in.referencePhoneModelId()!=in.targetPhoneModelId(),"新增机型不能与基准机型相同");
        require(Set.of("large","precision","mixed").contains(text(in.holeType())),"请选择大孔、精孔或混合孔型");
        long sourceLine=in.sourceProductLineId()>0?in.sourceProductLineId():in.productLineId();
        access.requireProduct(in.productLineId());access.requireProduct(sourceLine);access.requireItem(in.itemId());access.requireMold(in.moldSetId());
        for(var kind:List.of("product","item","mold_set"))ProductMoldLifecycleService.requireActive(jdbc,kind,kind.equals("product")?in.productLineId():kind.equals("item")?in.itemId():in.moldSetId());
        var source=repository.source(sourceLine,in.itemId(),in.moldSetId(),lock);require(source!=null,"自动匹配的实体模具已变化或资料已删除");
        var models=phones.requireEnabledModels(List.of(in.referencePhoneModelId(),in.targetPhoneModelId()));
        var members=rows(source.get("members"));
        require(members.isEmpty()||members.stream().anyMatch(m->number(m.get("phoneModelId"))==in.referencePhoneModelId()),"请选择该具体套已有成员作为基准机型");
        String prefix=jdbc.queryForObject("select product_specific_code from plm_product where product_id=? and deleted_flag=0",String.class,in.productLineId());
        var entities=rows(source.get("entities")).stream().filter(row->number(row.get("product_line_id"))==in.productLineId()).toList();var relations=rows(source.get("relations")).stream().filter(row->number(row.get("product_line_id"))==in.productLineId()).toList();
        var structures=new HashSet<String>();
        for(var entity:entities){require("active".equals(entity.get("status"))&&in.holeType().equals(entity.get("hole_type")),"该实物套已有停用身份或不同明确孔型，请核对");structures.add(text(entity.get("structure_description")));}
        for(var relation:relations)if("active".equals(relation.get("status"))){String known=text(relation.get("hole_type"));require(!Set.of("large","precision","mixed").contains(known)||known.equals(in.holeType()),"已有制造关系的明确孔型与所选孔型不一致");if(known.equals(in.holeType()))structures.add(text(relation.get("structure_description")));}
        require(structures.size()<=1,"该实体套存在不同结构范围，请工程核对后维护");String structure=structures.isEmpty()?"":structures.iterator().next();
        var selected=materials(in.materialCodes());var codes=new ArrayList<Map<String,Object>>();var types=new HashSet<String>();
        for(var part:rows(source.get("parts"))) {
            String type=text(part.get("part_type")),material=type.equals("PC")?"20":type.equals("TPU")?"10":"";
            if(!selected.contains(material))continue;
            require(!material.isEmpty()&&types.add(type),"实物部件材质重复或不支持");
            String original=text(part.get("original_mold_code")),catalog=number(part.get("catalog_product_id"))==in.productLineId()?text(part.get("catalog_code")):"";
            if(number(part.get("product_mold_code_id"))>0&&number(part.get("catalog_product_id"))==in.productLineId())require(!catalog.isEmpty()&&"active".equals(part.get("catalog_status"))&&number(part.get("catalog_deleted_flag"))==0,"部件关联的正式码已停用或删除");
            var identity=entities.stream().filter(e->material.equals(e.get("material_code"))).toList();require(identity.size()<=1,"该部件有多个实体身份，不能自动选主码");
            String referencePhoneCode=text(models.get(in.referencePhoneModelId()).get("phoneModelCode"));
            String primary=choosePrimary(generator,prefix,material,in.holeType(),referencePhoneCode,original,catalog,identity.isEmpty()?"":text(identity.get(0).get("primary_code")));
            requireOriginalReference(primary,referencePhoneCode);
            if(catalog.isEmpty())require(sourceLine!=in.productLineId()||"pending".equals(part.get("status")),"仅待补编码的pending部件可在此登记；其他状态请先在模具管理核对");
            var code=new LinkedHashMap<String,Object>();code.put("materialCode",material);code.put("primaryCode",primary);code.put("originalCode",original);
            code.put("sourceMappingCode",generator.generateMoldCode(prefix,material,text(models.get(in.referencePhoneModelId()).get("phoneModelCode")),in.holeType()));
            code.put("mappingCode",generator.generateMoldCode(prefix,material,text(models.get(in.targetPhoneModelId()).get("phoneModelCode")),in.holeType()));
            code.put("catalogCodeId",number(part.get("product_mold_code_id")));code.put("entityId",identity.isEmpty()?0L:number(identity.get(0).get("entity_id")));code.put("action",catalog.isEmpty()?"补码":identity.isEmpty()?"新建身份":"沿用");
            checkCode(primary,in,material,true);checkCode(text(code.get("sourceMappingCode")),in,material,primary.equals(code.get("sourceMappingCode")));checkCode(text(code.get("mappingCode")),in,material,false);
            codes.add(code);
        }
        require(types.equals(selected.stream().map(value->value.equals("10")?"TPU":"PC").collect(java.util.stream.Collectors.toSet())),"所选材质缺少对应实体模具部件，请先在模具管理补齐");
        for(long phone:List.of(in.referencePhoneModelId(),in.targetPhoneModelId()))checkRelation(in,phone,structure);
        var signature=new TreeMap<String,Object>();signature.put("source",source);signature.put("codes",codes);signature.put("models",models);signature.put("hole",in.holeType());signature.put("structure",structure);signature.put("reference",in.referencePhoneModelId());signature.put("target",in.targetPhoneModelId());
        return new Plan(source,codes,structure,digest(signature));
    }
    static String choosePrimary(ProductBusinessCodeGenerator generator,String prefix,String material,String hole,String phone,String original,String catalog,String existing) {
        String primary=existing.isBlank()?catalog:existing;
        if(primary.isBlank()&&ProductBusinessCodeGenerator.isCompleteMoldCode(original))primary=original;
        if(primary.isBlank())primary=generator.generateMoldCode(prefix,material,phone,hole);
        generator.parseMoldCode(primary,prefix,List.of(material),null);
        String marker=ProductBusinessCodeGenerator.moldHoleMarker(primary);
        require(marker.isEmpty()||marker.equals(com.yuewei.plm.common.util.ManufacturingHole.moldMarker(hole)),"原模具编码孔型与选择不一致");
        require(catalog.isBlank()||catalog.equals(primary),"实体主码与部件正式码矛盾，不能覆盖");
        require(!ProductBusinessCodeGenerator.isCompleteMoldCode(original)||original.equals(primary),"完整历史原码与实体主码矛盾，不能覆盖");
        return primary;
    }
    static void requireOriginalReference(String primaryCode,String referencePhoneCode) {
        require(ProductBusinessCodeGenerator.moldPhoneCode(primaryCode).equals(referencePhoneCode),
            "共模基准必须选择原模具主码对应的机型，不能从已映射目标机型继续派生");
    }
    private void checkCode(String code,Input in,String material,boolean primary) {
        var catalogs=jdbc.queryForList("select product_mold_code_id,product_id,status,deleted_flag from plm_product_mold_code where upper(mold_code)=upper(?)",code);
        if(!catalogs.isEmpty()) {
            require(primary&&catalogs.size()==1,"编码 "+code+" 已被其他正式码占用");var catalog=catalogs.get(0);
            require("active".equals(catalog.get("status"))&&number(catalog.get("deleted_flag"))==0,"编码 "+code+" 已停用或删除");
            long owner=number(catalog.get("product_id"));access.requireProduct(owner);ProductMoldLifecycleService.requireActive(jdbc,"product",owner);
            require(owner==in.productLineId()||jdbc.queryForObject("select count(*) from plm_product where product_id=? and parent_product_id=? and deleted_flag=0",Long.class,owner,in.productLineId())==1,"编码 "+code+" 不属于本产品");
            require(jdbc.queryForObject("select count(*) from plm_collaboration_mold_part where product_mold_code_id=? and mold_set_id<>?",Long.class,catalog.get("product_mold_code_id"),in.moldSetId())==0,"编码 "+code+" 已绑定其他实物套");
        } else {
            require(jdbc.queryForObject("select (select count(*) from plm_mold_code_reservation r cross join lateral jsonb_array_elements(coalesce(r.response->'moldCodes','[]'::jsonb)) codes(code) where upper(codes.code->>'code')=upper(?))+(select count(*) from plm_mold_application where deleted_flag=0 and upper(coalesce(generated_codes,'')) like '%'||upper(?)||'%')",Long.class,code,code)==0,"编码 "+code+" 已被预留或申请占用");
        }
        var aliases=jdbc.queryForList("select e.mold_set_id,e.material_code,m.status,m.phone_model_id from plm_mold_entity_code_mapping m join plm_mold_entity_identity e using(entity_id) where upper(m.mapping_code)=upper(?)",code);
        for(var alias:aliases)require(number(alias.get("mold_set_id"))==in.moldSetId()&&material.equals(alias.get("material_code"))&&"active".equals(alias.get("status")),"编码 "+code+" 已属于其他实体或停用映射");
        require(jdbc.queryForObject("select count(*) from plm_mold_entity_identity where upper(primary_code)=upper(?) and mold_set_id<>?",Long.class,code,in.moldSetId())==0,"主码 "+code+" 已属于其他实物套");
    }
    private List<Map<String,Object>> checkRelation(Input in,long phone,String structure) {
        var found=jdbc.queryForList("select * from plm_manufacturing_relation where product_line_id=? and phone_model_id=? and hole_type=? and structure_description=? and status='active'",in.productLineId(),phone,in.holeType(),structure);
        require(found.size()<=1&&found.stream().allMatch(r->number(r.get("item_id"))==in.itemId()&&number(r.get("mold_set_id"))==in.moldSetId()),"机型规格已指向其他实物套，不能覆盖");return found;
    }
    @Transactional public Map<String,Object> confirm(Input in,HttpServletRequest request) {
        var actor=access.requireEngineeringMaintenance();require(in!=null,"请填写共模映射");required(in.evidence(),"工程确认依据");required(in.reason(),"确认原因");require(text(in.sourceDigest()).matches("[a-f0-9]{64}"),"请先预览编码");
        jdbc.execute("select pg_advisory_xact_lock(66817399)");jdbc.execute("select pg_advisory_xact_lock(610131102)");jdbc.execute("select pg_advisory_xact_lock(610131110)");
        var plan=plan(in,true);
        if(!plan.digest().equals(in.sourceDigest())) {
            // A lost HTTP response may be retried, but a new target must always refresh its preview.
            require(isCompleted(in,plan),"来源资料或编码已变化，请重新预览后确认");
            return responseWithFinishedMappings(in,plan,request);
        }
        var codes=new ArrayList<Map<String,Object>>();
        for(var code:plan.codes()) {
            String primary=text(code.get("primaryCode")),material=text(code.get("materialCode"));long catalog=number(code.get("catalogCodeId"));
            if(catalog==0) {
                var existing=jdbc.queryForList("select product_mold_code_id from plm_product_mold_code where mold_code=? and deleted_flag=0",primary);
                catalog=existing.isEmpty()?jdbc.queryForObject("insert into plm_product_mold_code(product_id,mold_code,mold_prefix,product_code_prefix,product_specific_code,mold_name,status,source_file,created_by,updated_by,deleted_flag) values(?,?,?,?,?,?,'active','existing-group-engineering',?,?,0) returning product_mold_code_id",Long.class,in.productLineId(),primary,primary.substring(0,3),"N"+primary.substring(1,3),primary.substring(1,3),text(plan.source().get("mold_name")),actor.username(),actor.username()):number(existing.get(0).get("product_mold_code_id"));
                if((in.sourceProductLineId()<=0||in.sourceProductLineId()==in.productLineId()))jdbc.update("update plm_collaboration_mold_part set product_mold_code_id=? where mold_set_id=? and part_type=? and product_mold_code_id is null",catalog,in.moldSetId(),material.equals("10")?"TPU":"PC");
            }
            long entity=number(code.get("entityId"));
            if(entity==0)entity=jdbc.queryForObject("insert into plm_mold_entity_identity(product_line_id,item_id,mold_set_id,material_code,hole_type,structure_description,primary_product_mold_code_id,primary_code,evidence,created_by) values(?,?,?,?,?,?,?,?,?,?) returning entity_id",Long.class,in.productLineId(),in.itemId(),in.moldSetId(),material,in.holeType(),plan.structure(),catalog,primary,in.evidence().strip(),actor.username());
            var saved=new LinkedHashMap<>(code);saved.put("entityId",entity);codes.add(saved);
        }
        for(long phone:List.of(in.referencePhoneModelId(),in.targetPhoneModelId())) {
            boolean pc=materials(in.materialCodes()).contains("20"),tpu=materials(in.materialCodes()).contains("10");
            jdbc.update("insert into plm_collaboration_phone_member(item_id,phone_model_id,mold_set_ids,pc_confirmed,tpu_confirmed,evidence,created_by,updated_by) values(?,?,array[?]::bigint[],?,?,?,?,?) on conflict(item_id,phone_model_id) do update set mold_set_ids=(select array_agg(distinct v order by v) from unnest(plm_collaboration_phone_member.mold_set_ids||excluded.mold_set_ids) v),pc_confirmed=plm_collaboration_phone_member.pc_confirmed or excluded.pc_confirmed,tpu_confirmed=plm_collaboration_phone_member.tpu_confirmed or excluded.tpu_confirmed,updated_by=excluded.updated_by",in.itemId(),phone,in.moldSetId(),pc,tpu,in.evidence().strip(),actor.username(),actor.username());
            jdbc.update("insert into plm_collaboration_phone_set_compatibility(item_id,phone_model_id,mold_set_id,pc_confirmed,tpu_confirmed,pc_evidence,tpu_evidence,created_by,updated_by) values(?,?,?,?,?,?,?,?,?) on conflict(item_id,phone_model_id,mold_set_id) do update set pc_confirmed=plm_collaboration_phone_set_compatibility.pc_confirmed or excluded.pc_confirmed,tpu_confirmed=plm_collaboration_phone_set_compatibility.tpu_confirmed or excluded.tpu_confirmed,pc_evidence=case when excluded.pc_confirmed then excluded.pc_evidence else plm_collaboration_phone_set_compatibility.pc_evidence end,tpu_evidence=case when excluded.tpu_confirmed then excluded.tpu_evidence else plm_collaboration_phone_set_compatibility.tpu_evidence end,updated_by=excluded.updated_by,updated_at=now()",in.itemId(),phone,in.moldSetId(),pc,tpu,pc?in.evidence().strip():null,tpu?in.evidence().strip():null,actor.username(),actor.username());
            var existing=checkRelation(in,phone,plan.structure());
            long relation=existing.isEmpty()?jdbc.queryForObject("insert into plm_manufacturing_relation(product_line_id,item_id,mold_set_id,phone_model_id,hole_type,structure_description,shared_material,manufacturing_product_id,sku_ids,mold_evidence,compatibility_digest,status,created_by,updated_by) values(?,?,?,?,?,?,false,null,'[]'::jsonb,?,?,'active',?,?) returning relation_id",Long.class,in.productLineId(),in.itemId(),in.moldSetId(),phone,in.holeType(),plan.structure(),in.evidence().strip(),digest(List.of(in.itemId(),in.moldSetId(),phone,in.evidence())),actor.username(),actor.username()):number(existing.get(0).get("relation_id"));
            for(var code:codes) {
                String alias=text(code.get(phone==in.referencePhoneModelId()?"sourceMappingCode":"mappingCode"));
                var found=jdbc.queryForList("select * from plm_mold_entity_code_mapping where entity_id=? and phone_model_id=? and material_code=? and hole_type=? and structure_description=?",code.get("entityId"),phone,code.get("materialCode"),in.holeType(),plan.structure());
                require(found.isEmpty()||found.size()==1&&alias.equals(found.get(0).get("mapping_code"))&&"active".equals(found.get(0).get("status")),"已有机型映射停用或编码不同，请核对");
                if(found.isEmpty())jdbc.update("insert into plm_mold_entity_code_mapping(entity_id,product_line_id,phone_model_id,material_code,hole_type,structure_description,mapping_code,source_manufacturing_relation_id,evidence,created_by) values(?,?,?,?,?,?,?,?,?,?)",code.get("entityId"),in.productLineId(),phone,code.get("materialCode"),in.holeType(),plan.structure(),alias,relation,in.evidence().strip(),actor.username());
            }
            if(pc&&tpu)manufacturing.requireResolved(new ManufacturingIdentityService.ResolveInput(in.productLineId(),phone,in.holeType(),plan.structure(),relation,null));
        }
        var result=responseWithFinishedMappings(in,new Plan(plan.source(),codes,plan.structure(),plan.digest()),request);
        jdbc.update("update plm_collaboration_item set master_data_version=master_data_version+1,updated_at=now(),updated_by=? where item_id=?",actor.username(),in.itemId());
        jdbc.update("update plm_collaboration_mold_set set master_data_version=master_data_version+1,updated_at=now(),updated_by=? where mold_set_id=?",actor.username(),in.moldSetId());
        jdbc.update("insert into plm_collaboration_history(item_id,entity_type,entity_id,field_name,before_value,after_value,reason,actor_id,actor_name) values(?,'mold_set',?,'existingGroupMapping',?::jsonb,?::jsonb,?,?,?)",in.itemId(),in.moldSetId(),encode(plan.source()),encode(result),in.reason().strip(),actor.userId(),actor.displayName());
        audit.logRequired(OperationLogCreateCommand.builder().action("MOLD_EXISTING_GROUP_MAPPING_CONFIRM").businessType("MOLD_ENTITY_MAPPING").businessId(in.itemId()+":"+in.moldSetId()).businessName("已有模具组共模映射").detailJson(encode(Map.of("input",in,"result",result))).request(request).build(),actor);
        return result;
    }
    private Map<String,Object> responseWithFinishedMappings(Input in,Plan plan,HttpServletRequest request) {
        var result=new LinkedHashMap<>(response(in,plan));
        result.put("finishedProductMappings",finishedProductMappings.createForMoldShare(in.productLineId(),
            in.referencePhoneModelId(),in.targetPhoneModelId(),in.holeType(),in.moldSetId(),in.evidence(),in.reason(),request));
        return result;
    }
    private boolean isCompleted(Input in,Plan plan) {
        for(var code:plan.codes())for(long phone:List.of(in.referencePhoneModelId(),in.targetPhoneModelId())) {
            String alias=text(code.get(phone==in.referencePhoneModelId()?"sourceMappingCode":"mappingCode"));
            if(jdbc.queryForObject("select count(*) from plm_mold_entity_code_mapping where entity_id=? and phone_model_id=? and mapping_code=? and status='active' and evidence=?",Long.class,code.get("entityId"),phone,alias,text(in.evidence()))!=1)return false;
            var selected=materials(in.materialCodes());if(jdbc.queryForObject("select count(*) from plm_collaboration_phone_set_compatibility where item_id=? and mold_set_id=? and phone_model_id=? and (?=false or pc_confirmed) and (?=false or tpu_confirmed)",Long.class,in.itemId(),in.moldSetId(),phone,selected.contains("20"),selected.contains("10"))!=1)return false;
            var relations=checkRelation(in,phone,plan.structure());if(relations.size()!=1)return false;
            if(selected.size()==2)manufacturing.requireResolved(new ManufacturingIdentityService.ResolveInput(in.productLineId(),phone,in.holeType(),plan.structure(),number(relations.get(0).get("relation_id")),null));
        }
        return true;
    }
    private Map<String,Object> response(Input in,Plan plan) {
        return Map.of("status","active","itemId",in.itemId(),"moldSetId",in.moldSetId(),"mappings",plan.codes().stream().map(code->{var row=new LinkedHashMap<>(code);row.put("phoneModelId",in.targetPhoneModelId());row.put("referencePhoneModelId",in.referencePhoneModelId());row.put("itemId",in.itemId());row.put("moldSetId",in.moldSetId());return row;}).toList());
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String digest(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static void required(String value,String field){require(!text(value).isEmpty()&&text(value).length()<=2000,"请填写有效的"+field);}
    private static String text(Object value){return Objects.toString(value,"").strip();}
    private static long number(Object value){return value instanceof Number n?n.longValue():0;}
    private static Set<String> materials(List<String> values){var result=values==null||values.isEmpty()?Set.of("10","20"):new LinkedHashSet<>(values);require(!result.isEmpty()&&Set.of("10","20").containsAll(result),"请选择有效材质");return result;}
    private static void require(boolean ok,String message){if(!ok)throw new BusinessException(40901,message);}
}

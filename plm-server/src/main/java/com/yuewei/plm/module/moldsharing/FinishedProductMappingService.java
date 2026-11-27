package com.yuewei.plm.module.moldsharing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.util.ManufacturingHole;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import com.yuewei.plm.module.masterdata.ProductMoldLifecycleService;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.product.service.ResearchProductCodingService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps shared finished-product mappings separate from ordinary SKU identities and inventory. */
@Service @RequiredArgsConstructor
public class FinishedProductMappingService {
    private static final List<String> MATERIALS = List.of("10", "20");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PilotAccessService access;
    private final PhoneModelReferenceService phones;
    private final ProductBusinessCodeGenerator codes;
    private final ResearchProductCodingService productCoding;
    private final OperationLogService audit;

    public record PreviewInput(long productLineId, long referencePhoneModelId, List<Long> targetPhoneModelIds,
                               String holeType, Long moldSetId) {}
    public record CreateInput(long productLineId, long referencePhoneModelId, List<Long> targetPhoneModelIds,
                              String holeType, Long moldSetId, List<String> colorCodes,
                              String evidence, String reason) {}
    public record DeactivateInput(String reason) {}
    record MoldBasis(long mappingCodeId, long entityId, long itemId, long moldSetId, long phoneModelId,
                     String phoneModelName, String phoneModelCode, String materialCode, String mappingCode,
                     String primaryCode, String structureDescription, String moldName, long setNo) {}
    record MoldSetCandidate(long moldSetId, long itemId, String physicalCode, String moldName,
                            long setNo, String structureDescription, List<MoldBasis> bases) {}
    private record Sku(long productId, String name, String code, long phoneModelId, String phoneModelCode,
                       String phoneModelName, String colorCode, String colorName, String operationCode) {}
    private record ColorOption(String colorCode, String colorName, String operationCode, String mappingCode,
                               boolean ready, List<String> issues, List<Sku> skus) {}
    private record SavedHeader(long mappingId, boolean created) {}
    private record PreviewData(long productLineId, String productLineName, String productSpecificCode,
                               long referencePhoneModelId, String referencePhoneName, String referencePhoneCode,
                               List<Long> phoneModelIds, String holeType, Long selectedMoldSetId,
                               List<MoldSetCandidate> moldSets, MoldSetCandidate selectedMoldSet,
                               List<ColorOption> colors, List<String> messages) {}

    private void read() { access.requireDomesticView(); access.requirePermission("collaboration:view"); }

    @Transactional
    public Map<String,Object> preview(PreviewInput input) {
        read();require(input!=null,"请填写产品和共模机型");access.requireProduct(input.productLineId());
        var result=inspect(input);
        var response=new LinkedHashMap<String,Object>();
        response.put("productLineId",result.productLineId());response.put("productLineName",result.productLineName());
        response.put("productSpecificCode",result.productSpecificCode());
        response.put("referencePhoneModelId",result.referencePhoneModelId());response.put("referencePhoneName",result.referencePhoneName());response.put("referencePhoneCode",result.referencePhoneCode());
        response.put("targetPhoneModelIds",result.phoneModelIds().stream().filter(id->id!=result.referencePhoneModelId()).toList());
        response.put("holeType",result.holeType());response.put("selectedMoldSetId",result.selectedMoldSet()==null?null:result.selectedMoldSet().moldSetId());
        response.put("moldSets",result.moldSets().stream().map(this::moldSetView).toList());
        response.put("colors",result.colors().stream().map(this::colorView).toList());
        response.put("messages",result.messages());
        response.put("canWrite",access.canMaintainEngineeringData());
        response.put("canSave",result.selectedMoldSet()!=null&&result.colors().stream().anyMatch(ColorOption::ready));
        return response;
    }

    public Map<String,Object> page(Map<String,String> query) {
        read();
        int page=positive(query.getOrDefault("page","1")),size=positive(query.getOrDefault("size","20"));
        require(size<=100,"每页最多100条");
        String status=text(query.getOrDefault("status","active"));require(Set.of("active","inactive","all").contains(status),"映射状态无效");
        String where=" where "+access.visibleSqlClause("product","m.product_line_id");var args=new ArrayList<Object>();
        if(!text(query.get("productLineId")).isEmpty()){where+=" and m.product_line_id=?";args.add(positive(query.get("productLineId")));}
        if(!status.equals("all")){where+=" and m.status=?";args.add(status);}
        if(!text(query.get("holeType")).isEmpty()){where+=" and m.hole_type=?";args.add(ManufacturingHole.canonicalHole(query.get("holeType")));}
        String keyword=text(query.get("keyword"));if(!keyword.isEmpty()){where+=" and (concat_ws(' ',m.mapping_code,p.product_name,m.operation_code,m.color_code,ref.model_name,ref_code.code_value) ilike ? or exists(select 1 from plm_finished_product_mapping_sku x where x.mapping_id=m.mapping_id and ("+access.visibleSqlClause("product","x.product_id")+") and concat_ws(' ',x.finished_product_code,x.phone_model_code) ilike ?))";args.add("%"+keyword+"%");args.add("%"+keyword+"%");}
        String from=" from plm_finished_product_mapping m join plm_product p on p.product_id=m.product_line_id join plm_phone_model_member ref on ref.id=m.reference_phone_model_id left join plm_code_item ref_code on ref_code.code_item_id=ref.code_item_id";
        long total=Objects.requireNonNullElse(jdbc.queryForObject("select count(*)"+from+where,Long.class,args.toArray()),0L);
        args.add(size);args.add((long)(page-1)*size);
        var rows=jdbc.queryForList("select m.mapping_id as id,m.product_line_id as \"productLineId\",p.product_name as \"productLineName\",m.product_specific_code as \"productSpecificCode\",m.operation_code as \"operationCode\",m.hole_type as \"holeType\",m.reference_phone_model_id as \"referencePhoneModelId\",ref.model_name as \"referencePhoneName\",m.reference_phone_model_code as \"referencePhoneCode\",m.color_code as \"colorCode\",m.mapping_code as \"mappingCode\",m.status,m.version,m.evidence,m.updated_at as \"updatedAt\""+from+where+" order by m.updated_at desc,m.mapping_id desc limit ? offset ?",args.toArray());
        var ids=rows.stream().map(row->number(row.get("id"))).toList();
        var skus=skuLinks(ids);var bases=moldLinks(ids);
        for(var row:rows){long id=number(row.get("id"));row.put("skus",skus.getOrDefault(id,List.of()));row.put("moldMappings",bases.getOrDefault(id,List.of()));}
        return Map.of("content",rows,"totalElements",total,"page",page,"size",size,"canWrite",access.canMaintainEngineeringData());
    }

    @Transactional
    public Map<String,Object> create(CreateInput input,HttpServletRequest request) {
        var actor=access.requireEngineeringMaintenance();
        require(input!=null,"请填写共用成品映射");
        required(input.evidence(),2000,"工程确认依据");required(input.reason(),2000,"确认原因");
        var requestedColors=new TreeSet<String>();
        if(input.colorCodes()!=null)for(String color:input.colorCodes())requestedColors.add(text(color).toUpperCase(Locale.ROOT));
        require(!requestedColors.isEmpty()&&requestedColors.size()<=100,"请选择需要建立映射的颜色");
        var preview=inspect(new PreviewInput(input.productLineId(),input.referencePhoneModelId(),input.targetPhoneModelIds(),input.holeType(),input.moldSetId()));
        require(preview.selectedMoldSet()!=null,String.join("；",preview.messages().isEmpty()?List.of("请先选择一套完整有效的共模实体"):preview.messages()));
        var colorsByCode=new LinkedHashMap<String,ColorOption>();preview.colors().forEach(color->colorsByCode.put(color.colorCode(),color));
        for(String color:requestedColors){var option=colorsByCode.get(color);require(option!=null&&option.ready(),option==null?"颜色 "+color+" 不在所有机型共同的有效 SKU 范围内":String.join("；",option.issues()));}

        var sortedOptions=requestedColors.stream().map(colorsByCode::get).sorted(Comparator.comparing(ColorOption::mappingCode)).toList();
        var lockKeys=new TreeSet<String>();for(var option:sortedOptions){lockKeys.add("finished-product-mapping:"+option.mappingCode());for(var sku:option.skus())lockKeys.add("finished-product-sku:"+sku.productId()+":"+preview.holeType());}
        for(String key:lockKeys)jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))",key);
        var saved=new ArrayList<Map<String,Object>>();
        boolean anyChanges=false;
        for(var option:sortedOptions){
            var header=saveHeader(preview,option,input.evidence().strip(),actor.username());long mappingId=header.mappingId();
            boolean optionChanged=header.created();
            var linkedSkus=new ArrayList<String>();
            for(var sku:option.skus()){
                access.requireProduct(sku.productId());
                Long conflicting=jdbc.queryForObject("select count(*) from plm_finished_product_mapping_sku s join plm_finished_product_mapping m on m.mapping_id=s.mapping_id where s.product_id=? and m.hole_type=? and m.status='active' and m.mapping_id<>?",Long.class,sku.productId(),preview.holeType(),mappingId);
                require(conflicting==null||conflicting==0,"SKU "+sku.code()+" 已关联到另一条有效的同孔型共用映射，请先核对原映射");
                var existingSku=jdbc.queryForList("select phone_model_id,phone_model_code,finished_product_code from plm_finished_product_mapping_sku where mapping_id=? and product_id=? for update",mappingId,sku.productId());
                if(existingSku.isEmpty()){jdbc.update("insert into plm_finished_product_mapping_sku(mapping_id,product_id,phone_model_id,phone_model_code,finished_product_code) values(?,?,?,?,?)",mappingId,sku.productId(),sku.phoneModelId(),sku.phoneModelCode(),sku.code());optionChanged=true;}
                else require(number(existingSku.get(0).get("phone_model_id"))==sku.phoneModelId()&&text(existingSku.get(0).get("phone_model_code")).equals(sku.phoneModelCode())&&text(existingSku.get(0).get("finished_product_code")).equals(sku.code()),"普通 SKU 身份或原码已变化，请重新预览");
                linkedSkus.add(sku.code());
            }
            for(var basis:preview.selectedMoldSet().bases()){
                var existingBasis=jdbc.queryForList("select entity_id,mapping_code_id,mold_set_id,mapping_code,primary_code from plm_finished_product_mapping_mold_basis where mapping_id=? and phone_model_id=? and material_code=? and mold_set_id=? for update",mappingId,basis.phoneModelId(),basis.materialCode(),basis.moldSetId());
                if(existingBasis.isEmpty()){jdbc.update("insert into plm_finished_product_mapping_mold_basis(mapping_id,entity_id,mapping_code_id,phone_model_id,material_code,mold_set_id,mapping_code,primary_code) values(?,?,?,?,?,?,?,?)",mappingId,basis.entityId(),basis.mappingCodeId()==0?null:basis.mappingCodeId(),basis.phoneModelId(),basis.materialCode(),basis.moldSetId(),basis.mappingCode(),basis.primaryCode());optionChanged=true;}
                else require(number(existingBasis.get(0).get("entity_id"))==basis.entityId()&&number(existingBasis.get(0).get("mapping_code_id"))==basis.mappingCodeId()&&number(existingBasis.get(0).get("mold_set_id"))==basis.moldSetId()&&text(existingBasis.get(0).get("mapping_code")).equals(basis.mappingCode())&&text(existingBasis.get(0).get("primary_code")).equals(basis.primaryCode()),"模具共模依据已变化或指向另一套实物，请重新核对");
            }
            if(optionChanged&&!header.created())jdbc.update("update plm_finished_product_mapping set version=version+1,updated_by=?,updated_at=now() where mapping_id=?",actor.username(),mappingId);
            anyChanges|=optionChanged;
            saved.add(Map.of("mappingId",mappingId,"mappingCode",option.mappingCode(),"colorCode",option.colorCode(),"skuCodes",linkedSkus));
        }
        if(anyChanges)audit.logRequired(OperationLogCreateCommand.builder().action("FINISHED_PRODUCT_MAPPING_CREATE").businessType("FINISHED_PRODUCT_MAPPING")
            .businessId(input.productLineId()+":"+String.join(",",saved.stream().map(row->row.get("mappingId").toString()).toList()))
            .businessName("共用成品映射").detailJson(encode(Map.of("input",input,"moldSetId",preview.selectedMoldSet().moldSetId(),"result",saved)))
            .request(request).build(),actor);
        return Map.of("status","active","moldSetId",preview.selectedMoldSet().moldSetId(),"mappings",saved);
    }

    @Transactional
    public Map<String,Object> createForMoldShare(long productLineId,long referencePhoneModelId,long targetPhoneModelId,
                                                  String holeType,long moldSetId,String evidence,String reason,
                                                  HttpServletRequest request) {
        var preview=inspect(new PreviewInput(productLineId,referencePhoneModelId,List.of(targetPhoneModelId),holeType,moldSetId));
        var pendingColors=preview.colors().stream().filter(color->!color.ready())
            .map(color->Map.of("colorCode",color.colorCode(),"issues",color.issues())).toList();
        if(preview.selectedMoldSet()==null) {
            return Map.of("status","pending","mappings",List.of(),"pendingColors",pendingColors,
                "messages",preview.messages().isEmpty()?List.of("模具共模依据尚未齐全"):preview.messages());
        }
        var readyColors=preview.colors().stream().filter(ColorOption::ready).toList();
        if(readyColors.isEmpty()) {
            var messages=pendingColors.isEmpty()?List.of("尚无可关联的普通成品 SKU"):List.of("普通 SKU 或最终工序资料未齐，成品映射待补");
            return Map.of("status","pending","mappings",List.of(),"pendingColors",pendingColors,"messages",messages);
        }
        var created=create(new CreateInput(productLineId,referencePhoneModelId,List.of(targetPhoneModelId),holeType,
            moldSetId,readyColors.stream().map(ColorOption::colorCode).toList(),evidence,reason),request);
        return Map.of("status","created","mappings",created.get("mappings"),"pendingColors",pendingColors,
            "messages",pendingColors.isEmpty()?List.of():List.of("部分颜色缺少一致且有效的普通 SKU，已保留为待补"));
    }

    @Transactional
    public Map<String,Object> deactivate(long id,DeactivateInput input,HttpServletRequest request) {
        var actor=access.requireEngineeringMaintenance();required(input==null?null:input.reason(),2000,"停用原因");
        var rows=jdbc.queryForList("select mapping_id,mapping_code,status,version,product_line_id from plm_finished_product_mapping where mapping_id=? and "+access.visibleSqlClause("product","product_line_id")+" for update",id);
        require(rows.size()==1,"共用成品映射不存在或未获授权");var row=rows.get(0);access.requireProduct(number(row.get("product_line_id")));
        require("active".equals(text(row.get("status"))),"该映射已停用");
        for(var basis:jdbc.queryForList("select e.item_id,e.mold_set_id from plm_finished_product_mapping_mold_basis b join plm_mold_entity_identity e on e.entity_id=b.entity_id where b.mapping_id=?",id)){access.requireItem(number(basis.get("item_id")));access.requireMold(number(basis.get("mold_set_id")));}
        jdbc.update("update plm_finished_product_mapping set status='inactive',version=version+1,updated_by=?,updated_at=now() where mapping_id=?",actor.username(),id);
        audit.logRequired(OperationLogCreateCommand.builder().action("FINISHED_PRODUCT_MAPPING_DEACTIVATE").businessType("FINISHED_PRODUCT_MAPPING")
            .businessId(Long.toString(id)).businessCode(text(row.get("mapping_code"))).businessName("停用共用成品映射")
            .detailJson(encode(Map.of("reason",text(input.reason()),"beforeStatus",row.get("status"),"afterStatus","inactive"))).request(request).build(),actor);
        return Map.of("id",id,"status","inactive","mappingCode",text(row.get("mapping_code")));
    }

    private PreviewData inspect(PreviewInput input) {
        require(input!=null&&input.productLineId()>0&&input.referencePhoneModelId()>0,"请选择产品和共模基准机型");
        access.requireProduct(input.productLineId());ProductMoldLifecycleService.requireActive(jdbc,"product",input.productLineId());
        var lineRows=jdbc.queryForList("select product_id as id,product_name as name,upper(product_specific_code) as \"productSpecificCode\",master_data_status as \"masterDataStatus\" from plm_product where product_id=? and product_type='product_line' and deleted_flag=0",input.productLineId());
        require(lineRows.size()==1,"产品线不存在");var line=lineRows.get(0);String specific=text(line.get("productSpecificCode"));require(specific.matches("[A-Z]{2}"),"产品线缺少有效的两位产品特定码");
        String hole=ManufacturingHole.canonicalHole(input.holeType());require(!ManufacturingHole.moldMarker(hole).isEmpty(),"请选择大孔、精孔或混合孔型");
        var targetIds=new TreeSet<Long>();if(input.targetPhoneModelIds()!=null)for(Long id:input.targetPhoneModelIds())if(id!=null&&id>0)targetIds.add(id);
        require(!targetIds.isEmpty()&&targetIds.size()<=20,"请至少选择一个、最多选择20个目标机型");require(!targetIds.contains(input.referencePhoneModelId()),"目标机型不能重复包含基准机型");
        var allPhoneIds=new ArrayList<Long>();allPhoneIds.add(input.referencePhoneModelId());allPhoneIds.addAll(targetIds);
        var modelRows=phones.requireEnabledModels(allPhoneIds);var modelInfo=new LinkedHashMap<Long,Map<String,Object>>();
        for(Long id:allPhoneIds){var row=modelRows.get(id);require(row!=null,"所选机型不存在或未启用");String phoneCode=text(row.get("phoneModelCode"));require(phoneCode.matches("[0-9]{4}"),"机型缺少有效的四位编码");modelInfo.put(id,row);}
        var moldRows=loadMoldMappings(input.productLineId(),input.referencePhoneModelId(),allPhoneIds,hole);
        var moldSets=groupMoldSets(moldRows,allPhoneIds,hole,specific,text(modelInfo.get(input.referencePhoneModelId()).get("phoneModelCode")),codes);
        MoldSetCandidate selected=null;
        if(input.moldSetId()!=null){selected=moldSets.stream().filter(row->row.moldSetId()==input.moldSetId()).findFirst().orElse(null);if(selected==null)throw invalid("所选实物套没有完整、有效的基准及目标机型模具映射");validateMoldSetAccess(selected);}
        else if(moldSets.size()==1){selected=moldSets.get(0);validateMoldSetAccess(selected);}
        var skuRows=loadSkus(input.productLineId(),allPhoneIds);
        var finalOperationCodes=productCoding.finishedOperationCodes(input.productLineId());
        var colorNames=colorNames();
        var colorCodes=new TreeSet<String>();skuRows.forEach(row->{if(!row.colorCode().isEmpty())colorCodes.add(row.colorCode());});
        var options=new ArrayList<ColorOption>();
        for(String color:colorCodes){
            var issues=new ArrayList<String>();var chosen=new ArrayList<Sku>();String operation=null;
            var codeName=colorNames.get(color);if(codeName==null)issues.add("颜色码 "+color+" 不在启用的颜色主数据中");
            for(Long phoneId:allPhoneIds){var sameColor=skuRows.stream().filter(row->row.phoneModelId()==phoneId&&row.colorCode().equals(color)).toList();
                var matching=sameColor.stream().filter(row->finalOperationCodes.contains(row.operationCode())).toList();
                if(matching.size()!=1){issues.add(text(modelInfo.get(phoneId).get("phoneModelCode"))+"："+(matching.isEmpty()?(sameColor.isEmpty()?"缺少该颜色的普通 SKU":"没有使用已配置最终工序的普通 SKU"):"该颜色存在多个最终工序 SKU"));continue;}
                Sku sku=matching.get(0);var valid=validateSku(sku,specific,text(modelInfo.get(phoneId).get("phoneModelCode")),color,finalOperationCodes);
                if(!valid.isEmpty()){issues.addAll(valid);continue;}
                if(operation==null)operation=sku.operationCode();else if(!operation.equals(sku.operationCode()))issues.add("各机型普通 SKU 的工序码不一致");
                chosen.add(sku);
            }
            String mappingCode="";
            if(issues.isEmpty()&&operation!=null)mappingCode=codes.generateSharedProductMappingCode(specific,operation,hole,text(modelInfo.get(input.referencePhoneModelId()).get("phoneModelCode")),color);
            options.add(new ColorOption(color,Objects.toString(codeName,""),Objects.toString(operation,""),mappingCode,issues.isEmpty()&&chosen.size()==allPhoneIds.size(),List.copyOf(issues),List.copyOf(chosen)));
        }
        var messages=new ArrayList<String>();
        if(moldSets.isEmpty())messages.add("没有找到基准机型和所有目标机型均已确认适配的同一套PC/TPU实物模具");
        else if(selected==null)messages.add("找到多套完整共模实物，请选择实际使用的一套");
        if(options.isEmpty())messages.add("所选机型没有可映射的普通 SKU 颜色");
        return new PreviewData(input.productLineId(),text(line.get("name")),specific,input.referencePhoneModelId(),text(modelInfo.get(input.referencePhoneModelId()).get("modelName")),text(modelInfo.get(input.referencePhoneModelId()).get("phoneModelCode")),List.copyOf(allPhoneIds),hole,input.moldSetId(),List.copyOf(moldSets),selected,List.copyOf(options),List.copyOf(messages));
    }

    private void validateMoldSetAccess(MoldSetCandidate selected) {
        access.requireItem(selected.itemId());access.requireMold(selected.moldSetId());
        ProductMoldLifecycleService.requireActive(jdbc,"item",selected.itemId());
        ProductMoldLifecycleService.requireActive(jdbc,"mold_set",selected.moldSetId());
    }

    private List<MoldBasis> loadMoldMappings(long productLineId,long referencePhoneId,List<Long> phoneIds,String hole) {
        var targetIds=phoneIds.stream().filter(id->id!=referencePhoneId).toList();String marks=String.join(",",Collections.nCopies(targetIds.size(),"?"));var args=new ArrayList<Object>();
        args.add(productLineId);args.add(hole);args.add(hole);args.add(productLineId);args.addAll(targetIds);
        args.add(referencePhoneId);args.add(productLineId);args.add(hole);
        String visible=" and "+access.visibleSqlClause("product","c.product_line_id")+" and "+access.visibleSqlClause("item","e.item_id")+" and "+access.visibleSqlClause("mold","e.mold_set_id");
        String baseVisible=" and "+access.visibleSqlClause("product","e.product_line_id")+" and "+access.visibleSqlClause("item","e.item_id")+" and "+access.visibleSqlClause("mold","e.mold_set_id");
        return jdbc.query("""
            select c.mapping_code_id as "mappingCodeId",e.entity_id as "entityId",e.item_id as "itemId",e.mold_set_id as "moldSetId",
              c.phone_model_id as "phoneModelId",pm.model_name as "phoneModelName",phone_code.code_value as "phoneModelCode",
              c.material_code as "materialCode",c.mapping_code as "mappingCode",e.primary_code as "primaryCode",
              c.structure_description as "structureDescription",i.mold_name as "moldName",s.set_no as "setNo",
              r.phone_model_id as "referencePhoneModelId"
            from plm_mold_entity_code_mapping c
            join plm_mold_entity_identity e on e.entity_id=c.entity_id and e.status='active'
            join plm_manufacturing_relation r on r.relation_id=c.source_manufacturing_relation_id and r.status='active'
             join plm_collaboration_item i on i.item_id=e.item_id and i.deleted_flag=0 and i.master_data_status='active'
             join plm_collaboration_mold_set s on s.mold_set_id=e.mold_set_id and s.item_id=e.item_id and s.deleted_flag=0 and s.master_data_status='active'
            join plm_phone_model_member pm on pm.id=c.phone_model_id
            left join plm_code_item phone_code on phone_code.code_item_id=pm.code_item_id and phone_code.code_type='phone_model' and phone_code.deleted_flag=0
            where c.product_line_id=? and c.status='active' and c.hole_type=? and e.hole_type=? and r.product_line_id=?
              and r.phone_model_id=c.phone_model_id and r.mold_set_id=e.mold_set_id and r.hole_type=c.hole_type
              and r.structure_description=c.structure_description and e.structure_description=c.structure_description
              and c.phone_model_id in (%s)%s
            union all
            select coalesce(base_mapping.mapping_code_id,0) as "mappingCodeId",e.entity_id as "entityId",e.item_id as "itemId",e.mold_set_id as "moldSetId",
              ref.id as "phoneModelId",ref.model_name as "phoneModelName",ref_code.code_value as "phoneModelCode",
              e.material_code as "materialCode",coalesce(base_mapping.mapping_code,e.primary_code) as "mappingCode",e.primary_code as "primaryCode",
              e.structure_description as "structureDescription",i.mold_name as "moldName",s.set_no as "setNo",r.phone_model_id as "referencePhoneModelId"
            from plm_mold_entity_identity e
            join plm_manufacturing_relation r on r.product_line_id=e.product_line_id and r.mold_set_id=e.mold_set_id and r.status='active'
              and r.phone_model_id=? and r.hole_type=e.hole_type and r.structure_description=e.structure_description
             join plm_collaboration_item i on i.item_id=e.item_id and i.deleted_flag=0 and i.master_data_status='active'
             join plm_collaboration_mold_set s on s.mold_set_id=e.mold_set_id and s.item_id=e.item_id and s.deleted_flag=0 and s.master_data_status='active'
            join plm_phone_model_member ref on ref.id=r.phone_model_id
            left join plm_code_item ref_code on ref_code.code_item_id=ref.code_item_id and ref_code.code_type='phone_model' and ref_code.deleted_flag=0
            left join plm_mold_entity_code_mapping base_mapping on base_mapping.entity_id=e.entity_id and base_mapping.phone_model_id=ref.id and base_mapping.material_code=e.material_code and base_mapping.hole_type=e.hole_type and base_mapping.structure_description=e.structure_description and base_mapping.status='active'
            where e.product_line_id=? and e.status='active' and e.hole_type=? and e.material_code in ('10','20')
              %s
            order by "moldSetId","phoneModelId","materialCode","mappingCodeId"
            """.formatted(marks,visible,baseVisible), (rs,n)->new MoldBasis(rs.getLong("mappingCodeId"),rs.getLong("entityId"),rs.getLong("itemId"),rs.getLong("moldSetId"),rs.getLong("phoneModelId"),rs.getString("phoneModelName"),rs.getString("phoneModelCode"),rs.getString("materialCode"),rs.getString("mappingCode"),rs.getString("primaryCode"),rs.getString("structureDescription"),rs.getString("moldName"),rs.getLong("setNo")),args.toArray());
    }

    static List<MoldSetCandidate> groupMoldSets(List<MoldBasis> rows,List<Long> phoneIds,String hole,
                                                String productSpecificCode,String referencePhoneModelCode,
                                                ProductBusinessCodeGenerator codes) {
        var grouped=new TreeMap<Long,List<MoldBasis>>();rows.forEach(row->grouped.computeIfAbsent(row.moldSetId(),ignored->new ArrayList<>()).add(row));
        var result=new ArrayList<MoldSetCandidate>();String marker=ManufacturingHole.moldMarker(hole);
        for(var entry:grouped.entrySet()){
            var bases=entry.getValue();boolean complete=true;var structures=new HashSet<String>();
            for(long phone:phoneIds)for(String material:MATERIALS){var matches=bases.stream().filter(row->row.phoneModelId()==phone&&row.materialCode().equals(material)).toList();if(matches.size()!=1){complete=false;continue;}var row=matches.get(0);structures.add(row.structureDescription());
                try {
                    codes.parseMoldCode(row.mappingCode(),productSpecificCode,List.of(material),row.phoneModelCode());
                    codes.parseMoldCode(row.primaryCode(),productSpecificCode,List.of(material),referencePhoneModelCode);
                    if(!ProductBusinessCodeGenerator.moldHoleMarker(row.primaryCode()).equals(marker)
                        ||!ProductBusinessCodeGenerator.moldHoleMarker(row.mappingCode()).equals(marker))complete=false;
                } catch(RuntimeException invalidCode) { complete=false; }
            }
            if(bases.size()!=phoneIds.size()*MATERIALS.size()||structures.size()!=1)complete=false;
            if(!complete)continue;
            var first=bases.get(0);result.add(new MoldSetCandidate(entry.getKey(),first.itemId(),"MS-"+String.format(Locale.ROOT,"%06d",entry.getKey()),first.moldName(),first.setNo(),first.structureDescription(),List.copyOf(bases)));
        }
        return List.copyOf(result);
    }

    private List<Sku> loadSkus(long productLineId,List<Long> phoneIds) {
        String marks=String.join(",",Collections.nCopies(phoneIds.size(),"?"));var args=new ArrayList<Object>();args.add(productLineId);args.addAll(phoneIds);
        String visible=" and "+access.visibleSqlClause("product","sku.product_id")+" and "+access.visibleSqlClause("product","line.product_id");
        return jdbc.query("""
            select sku.product_id as id,sku.product_name as name,sku.finished_product_code as code,
              coalesce(sku.phone_model_member_id,parent.phone_model_member_id) as "phoneModelId",
              coalesce(nullif(sku.phone_model_code,''),nullif(parent.phone_model_code,''),'') as "phoneModelCode",
              pm.model_name as "phoneModelName",coalesce(sku.color_code,'') as "colorCode",coalesce(sku.color,'') as color
            from plm_product sku join plm_product parent on parent.product_id=sku.parent_product_id and parent.deleted_flag=0
              and coalesce(to_jsonb(parent)->>'master_data_status','active')='active' and parent.status not in ('archived','abandoned')
              and coalesce(parent.lock_status,'') not in ('frozen','abandoned')
            join plm_product line on line.product_id=case when parent.product_type='product_line' then parent.product_id else parent.parent_product_id end
              and line.product_type='product_line' and line.deleted_flag=0 and coalesce(to_jsonb(line)->>'master_data_status','active')='active'
            join plm_phone_model_member pm on pm.id=coalesce(sku.phone_model_member_id,parent.phone_model_member_id)
            where sku.product_type='sku' and sku.deleted_flag=0 and coalesce(to_jsonb(sku)->>'master_data_status','active')='active'
              and sku.status not in ('archived','abandoned') and coalesce(sku.lock_status,'') not in ('frozen','abandoned') and line.product_id=?
              and coalesce(sku.phone_model_member_id,parent.phone_model_member_id) in (%s)%s
            order by coalesce(sku.phone_model_member_id,parent.phone_model_member_id),sku.color_code,sku.product_id
            """.formatted(marks,visible),(rs,n)->new Sku(rs.getLong("id"),rs.getString("name"),rs.getString("code"),rs.getLong("phoneModelId"),rs.getString("phoneModelCode"),rs.getString("phoneModelName"),rs.getString("colorCode"),rs.getString("color"),""),args.toArray()).stream().map(sku->{
                try {var parts=codes.parseProductStateCode(sku.code());return new Sku(sku.productId(),sku.name(),sku.code(),sku.phoneModelId(),sku.phoneModelCode(),sku.phoneModelName(),sku.colorCode(),sku.colorName(),parts.operationCode());}
                catch(RuntimeException invalid) {return sku;}
            }).toList();
    }

    private List<String> validateSku(Sku sku,String productSpecificCode,String phoneCode,String colorCode,Set<String> finalOperationCodes) {
        var issues=new ArrayList<String>();
        try {var parts=codes.parseProductStateCode(sku.code());
            if(!parts.productSpecificCode().equals(productSpecificCode))issues.add(sku.code()+"：产品特定码与产品线不一致");
            if(!parts.phoneModelCode().equals(phoneCode)||!sku.phoneModelCode().equals(phoneCode))issues.add(sku.code()+"：手机型号码与所选标准机型不一致");
            if(!parts.colorCode().equals(colorCode))issues.add(sku.code()+"：编码颜色段与SKU颜色字段不一致");
            if(!finalOperationCodes.contains(parts.operationCode()))issues.add(sku.code()+"：工序码不是系统已配置的最终工序");
        } catch(RuntimeException invalid){issues.add(sku.name()+"：普通 SKU 编码无法按现行规则解析");}
        return issues;
    }

    private Map<String,String> colorNames() {
        var result=new LinkedHashMap<String,String>();
        for(var row:jdbc.queryForList("select code_value,coalesce(nullif(code_name_zh,''),code_name) as code_name from plm_code_item where code_type='color' and deleted_flag=0 and status='enabled'"))result.put(text(row.get("code_value")),text(row.get("code_name")));
        return result;
    }

    private SavedHeader saveHeader(PreviewData preview,ColorOption color,String evidence,String actor) {
        var existing=jdbc.queryForList("select mapping_id,status,product_line_id,operation_code,hole_type,reference_phone_model_id,color_code from plm_finished_product_mapping where mapping_code=? for update",color.mappingCode());
        if(!existing.isEmpty()){
            var row=existing.get(0);require("active".equals(text(row.get("status"))),"映射码 "+color.mappingCode()+" 已停用，不能静默复用");
            require(number(row.get("product_line_id"))==preview.productLineId()&&text(row.get("operation_code")).equals(color.operationCode())&&text(row.get("hole_type")).equals(preview.holeType())&&number(row.get("reference_phone_model_id"))==preview.referencePhoneModelId()&&text(row.get("color_code")).equals(color.colorCode()),"生成的共用成品映射码已被另一身份占用");
            return new SavedHeader(number(row.get("mapping_id")),false);
        }
        return new SavedHeader(jdbc.queryForObject("insert into plm_finished_product_mapping(product_line_id,product_specific_code,operation_code,hole_type,reference_phone_model_id,reference_phone_model_code,color_code,mapping_code,evidence,created_by,updated_by) values(?,?,?,?,?,?,?,?,?,?,?) returning mapping_id",Long.class,preview.productLineId(),preview.productSpecificCode(),color.operationCode(),preview.holeType(),preview.referencePhoneModelId(),preview.referencePhoneCode(),color.colorCode(),color.mappingCode(),evidence,actor,actor),true);
    }

    private Map<Long,List<Map<String,Object>>> skuLinks(List<Long> ids) {
        var result=new HashMap<Long,List<Map<String,Object>>>();if(ids.isEmpty())return result;String marks=String.join(",",Collections.nCopies(ids.size(),"?"));
        var rows=jdbc.queryForList("select l.mapping_id as \"mappingId\",l.product_id as \"productId\",l.phone_model_id as \"phoneModelId\",l.phone_model_code as \"phoneModelCode\",l.finished_product_code as \"finishedProductCode\",p.product_name as \"productName\",pm.model_name as \"phoneModelName\" from plm_finished_product_mapping_sku l join plm_product p on p.product_id=l.product_id left join plm_phone_model_member pm on pm.id=l.phone_model_id where l.mapping_id in ("+marks+") and ("+access.visibleSqlClause("product","p.product_id")+") order by l.mapping_id,l.phone_model_code,l.finished_product_code",ids.toArray());
        for(var row:rows)result.computeIfAbsent(number(row.get("mappingId")),ignored->new ArrayList<>()).add(row);return result;
    }

    private Map<Long,List<Map<String,Object>>> moldLinks(List<Long> ids) {
        var result=new HashMap<Long,List<Map<String,Object>>>();if(ids.isEmpty())return result;String marks=String.join(",",Collections.nCopies(ids.size(),"?"));
        var rows=jdbc.queryForList("select b.mapping_id as \"mappingId\",b.phone_model_id as \"phoneModelId\",pm.model_name as \"phoneModelName\",b.material_code as \"materialCode\",b.mapping_code as \"moldMappingCode\",b.primary_code as \"primaryCode\",b.mold_set_id as \"moldSetId\" from plm_finished_product_mapping_mold_basis b join plm_phone_model_member pm on pm.id=b.phone_model_id join plm_mold_entity_identity e on e.entity_id=b.entity_id where b.mapping_id in ("+marks+") and ("+access.visibleSqlClause("product","e.product_line_id")+") and ("+access.visibleSqlClause("item","e.item_id")+") and ("+access.visibleSqlClause("mold","e.mold_set_id")+") order by b.mapping_id,b.phone_model_id,b.material_code,b.mold_set_id",ids.toArray());
        for(var row:rows)result.computeIfAbsent(number(row.get("mappingId")),ignored->new ArrayList<>()).add(row);return result;
    }

    private Map<String,Object> moldSetView(MoldSetCandidate set){return Map.of("moldSetId",set.moldSetId(),"itemId",set.itemId(),"physicalCode",set.physicalCode(),"moldName",set.moldName(),"setNo",set.setNo(),"structureDescription",set.structureDescription());}
    private Map<String,Object> colorView(ColorOption color){var result=new LinkedHashMap<String,Object>();result.put("colorCode",color.colorCode());result.put("colorName",color.colorName());result.put("operationCode",color.operationCode());result.put("mappingCode",color.mappingCode());result.put("ready",color.ready());result.put("issues",color.issues());result.put("skus",color.skus().stream().map(sku->Map.of("productId",sku.productId(),"productName",sku.name(),"phoneModelId",sku.phoneModelId(),"phoneModelCode",sku.phoneModelCode(),"phoneModelName",sku.phoneModelName(),"finishedProductCode",sku.code())).toList());return result;}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException(error);}}
    private static void required(String value,int max,String label){require(!text(value).isEmpty()&&text(value).length()<=max,"请填写有效的"+label);}
    private static String text(Object value){return Objects.toString(value,"").strip();}
    private static long number(Object value){return value instanceof Number n?n.longValue():0;}
    private static int positive(String value){try{int n=Integer.parseInt(value);require(n>0,"编号及页码须为正数");return n;}catch(NumberFormatException error){throw invalid("编号或页码无效");}}
    private static BusinessException invalid(String message){return new BusinessException(40001,message);}
    private static void require(boolean condition,String message){if(!condition)throw new BusinessException(40901,message);}
}

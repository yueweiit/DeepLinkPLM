package com.yuewei.plm.module.manufacturing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only handoff: the actual sales-order owner persists this evidence with its order line. */
@Service @RequiredArgsConstructor
public class ManufacturingOrderSnapshotService {
    private final ManufacturingIdentityService identities;
    private final PilotAccessService access;
    private final ObjectMapper json;

    public record Input(long productLineId,long customerPhoneModelId,String holeType,String structureDescription,
        long relationId,long relationVersion,long manufacturingSkuId,String externalSource,
        String externalOrderId,String externalLineId,String externalSpecId,String customerChoiceText) {}

    @Transactional
    public Map<String,Object> preview(Input input) {
        access.requirePermission("project:view");
        if(access.isExternal())throw invalid(40103,"制造订单映射仅向获授权的国内人员开放");
        validate(input);
        var relation=identities.requireResolved(new ManufacturingIdentityService.ResolveInput(input.productLineId(),
            input.customerPhoneModelId(),input.holeType(),input.structureDescription(),input.relationId(),input.relationVersion()));
        if(!Boolean.TRUE.equals(relation.get("sharedMaterial")))throw invalid(40901,"该关系仅确认共模，未确认共用生产物料，不能据此下生产单或发货");
        var sku=identities.resolvedSkus(relation).stream().filter(row->number(row.get("id"))==input.manufacturingSkuId())
            .findFirst().orElseThrow(()->invalid(40901,"所选制造SKU不在本次已确认范围，请重新核对销售规格"));
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("schemaVersion",1);
        snapshot.put("externalSource",input.externalSource().trim());
        snapshot.put("externalOrderId",input.externalOrderId().trim());
        snapshot.put("externalLineId",input.externalLineId().trim());
        snapshot.put("externalSpecId",input.externalSpecId().trim());
        snapshot.put("customerChoiceText",input.customerChoiceText()==null?"":input.customerChoiceText());
        snapshot.put("customerPhone",fields(map(relation.get("businessPhone")),List.of("phoneModelId","modelName","displayName","phoneModelCode")));
        snapshot.put("holeType",relation.get("holeType"));
        snapshot.put("structureDescription",relation.get("structureDescription"));
        snapshot.put("relation",fields(relation,List.of("relationId","relationVersion","productLineId","itemId","moldSetId",
            "manufacturingProductId","manufacturingPhoneCode","sharedMaterial","moldEvidence","materialEvidence","parts")));
        snapshot.put("manufacturingSku",fields(sku,List.of("id","code","name","phoneModelCode","colorCode","operationCode")));
        String payload=canonical(snapshot);
        // A mapping is not an order save, stock reservation, shipment or externally published listing.
        return Map.of("status","ready","externalListingConnected",false,"productionWriteEnabled",false,
            "orderSaved",false,"generatedAt",OffsetDateTime.now().toString(),"snapshotSha256",digest(payload),
            "snapshot",json.valueToTree(snapshot),"snapshotJson",payload);
    }

    private void validate(Input input) {
        if(input==null||input.productLineId()<1||input.customerPhoneModelId()<1||input.relationId()<1
            ||input.relationVersion()<1||input.manufacturingSkuId()<1)throw invalid(40001,"请提供真实手机、制造SKU及已确认关系版本");
        bounded(input.externalSource(),100,"来源系统");bounded(input.externalOrderId(),200,"外部订单标识");
        bounded(input.externalLineId(),200,"外部订单行标识");bounded(input.externalSpecId(),200,"外部销售规格标识");
        if(input.customerChoiceText()!=null&&input.customerChoiceText().length()>2000)throw invalid(40001,"客户原始选择文字过长");
    }
    private static void bounded(String value,int limit,String field) {
        if(value==null||value.isBlank()||value.length()>limit)throw invalid(40001,"请填写有效的"+field);
    }
    private String canonical(Map<String,Object> snapshot) {
        try{return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(snapshot);}
        catch(JsonProcessingException failure){throw new IllegalStateException("制造订单依据无法序列化",failure);}
    }
    private static String digest(String value) {
        try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }
    private static Map<String,Object> fields(Map<String,Object> source,List<String> keys) {
        var result=new LinkedHashMap<String,Object>();for(String key:keys)if(source.containsKey(key))result.put(key,source.get(key));return result;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    private static long number(Object value){return ((Number)value).longValue();}
    private static BusinessException invalid(int code,String message){return new BusinessException(code,message);}
}

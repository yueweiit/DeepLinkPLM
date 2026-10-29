package com.yuewei.plm.module.pilotdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.constant.ErrorCodeConstants;
import com.yuewei.plm.module.bom.entity.ProductBom;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class BomReleaseDeliveryService {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final PilotDeliveryQueue queue;private final PilotAccessService access;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.springframework.core.env.Environment environment;
    @Transactional public void released(ProductBom bom) {
        access.requirePermission("bom:publish");access.requireProduct(bom.getProductId());
        // Engineering confirmation is not permission to write a source system or queue future QA deliveries.
        if(com.yuewei.plm.module.bom.service.BomStructureService.structured(bom)||environment!=null&&environment.getProperty("PLM_LOCAL_NO_OUTBOUND",Boolean.class,false))return;
        if(!"released".equals(bom.getStatus())||"test".equals(bom.getBomType())||!"formal".equals(bom.getBomScope()))
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,"只有审核发布的正式 BOM 可进入 ERP 同步队列");
        var data=new LinkedHashMap<String,Object>();data.put("bomId",bom.getProductBomId());data.put("productId",bom.getProductId());data.put("bomCode",bom.getBomCode());data.put("versionNo",bom.getVersionNo());data.put("currencyCode",bom.getCurrencyCode());
        data.put("routes",jdbc.queryForList("select product_bom_route_id,process_id,route_code,route_name,shared_bom_group_code,route_variant_no,variant_name from plm_product_bom_route where product_bom_id=? and deleted_flag=0 and status='active' order by product_bom_route_id",bom.getProductBomId()));
        data.put("items",jdbc.queryForList("select product_bom_route_id,line_no,inventory_id,item_code,item_name,quantity,uom_code,loss_rate,unit_cost_snapshot,currency_code from plm_product_bom_item where product_bom_id=? and deleted_flag=0 order by product_bom_item_id",bom.getProductBomId()));
        data.put("colors",jdbc.queryForList("select product_bom_route_id,color_code,color_name from plm_product_bom_route_color where product_bom_id=? and deleted_flag=0 and status='active' order by product_bom_route_color_id",bom.getProductBomId()));
        String encoded=queue.encode(data),hash=hash(encoded),event="plm-bom:"+bom.getProductBomId()+":"+bom.getVersionNo();
        jdbc.update("insert into plm_pilot_bom_release(bom_id,version_no,event_id,snapshot,fingerprint,actor_id) values(?,?,?,?::jsonb,?,?) on conflict(bom_id,version_no) do nothing",bom.getProductBomId(),bom.getVersionNo(),event,encoded,hash,access.actorId());
        String original=jdbc.queryForObject("select fingerprint from plm_pilot_bom_release where bom_id=? and version_no=?",String.class,bom.getProductBomId(),bom.getVersionNo());
        if(!hash.equals(original)){
            String snapshot=jdbc.queryForObject("select snapshot::text from plm_pilot_bom_release where bom_id=? and version_no=?",String.class,bom.getProductBomId(),bom.getVersionNo());
            if(!sameNumericContent(snapshot,encoded))throw new BusinessException(ErrorCodeConstants.VERSION_FROZEN,"已发布版本内容已固定，请复制生成新版本后发布");
        }
        queue.enqueue("erp_bom",event,data,access.actorId());
    }
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static boolean sameNumericContent(String first,String second){try{var mapper=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);return normalizeNumbers(mapper.readTree(first)).equals(normalizeNumbers(mapper.readTree(second)));}catch(Exception invalid){return false;}}
    private static com.fasterxml.jackson.databind.JsonNode normalizeNumbers(com.fasterxml.jackson.databind.JsonNode value){
        if(value.isNumber())return com.fasterxml.jackson.databind.node.DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());
        if(value.isObject()){var object=(com.fasterxml.jackson.databind.node.ObjectNode)value;var keys=new ArrayList<String>();object.fieldNames().forEachRemaining(keys::add);keys.forEach(k->object.set(k,normalizeNumbers(object.get(k))));}
        if(value.isArray()){var array=(com.fasterxml.jackson.databind.node.ArrayNode)value;for(int index=0;index<array.size();index++)array.set(index,normalizeNumbers(array.get(index)));}return value;
    }
}

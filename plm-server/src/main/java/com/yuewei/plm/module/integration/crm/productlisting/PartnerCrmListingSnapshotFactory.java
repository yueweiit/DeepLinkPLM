package com.yuewei.plm.module.integration.crm.productlisting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.partner.PartnerListingService;
import com.yuewei.plm.module.partner.PartnerStore;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static com.yuewei.plm.module.partner.PartnerValues.*;

@Component
@RequiredArgsConstructor
public class PartnerCrmListingSnapshotFactory {
    private final PartnerStore store;
    private final PartnerListingService listings;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PilotAccessService access;

    public Map<String,Object> options(long listingId,boolean lock) {
        var listing=store.load(listingId,"listing",lock);
        var product=store.load(number(listing.get("productId")),"product",lock);
        var basis=map(listing.get("basis"));
        var reasons=new ArrayList<String>();
        if(!"not_connected".equals(text(listing.get("status")))) reasons.add("本记录不在待上架范围");
        if("inactive".equals(text(product.get("status")))) reasons.add("经营档案已停用");
        if(!CrmListingDigest.of(json,basis).equals(text(listing.get("basisHash")))) reasons.add("上架依据摘要不一致，请重新确认");
        if(text(basis.get("introduction")).isEmpty()) reasons.add("缺少商品介绍");
        var photos=new ArrayList<Map<String,Object>>();
        for(var ref:maps(basis.get("photos"))) {
            long id=number(ref.get("id")); access.requireFile(id);
            var files=jdbc.queryForList("select attachment_id as id,version_no as version,checksum from plm_attachment where attachment_id=? and deleted_flag=0 and content_type like 'image/%'"+(lock?" for share":""),id);
            if(files.size()!=1||!text(files.get(0).get("version")).equals(text(ref.get("version")))) reasons.add("商品图片已变化或不可用");
            else photos.add(files.get(0));
        }
        if(photos.isEmpty()) reasons.add("缺少有效商品图片");
        var candidates=new ArrayList<Map<String,Object>>();
        var links=maps(listing.containsKey("pairs")?listing.get("pairs"):basis.get("specs"));
        var seen=new HashSet<Long>();
        for(var link:links) {
            long specId=number(link.get("specId"));
            var spec=maps(product.get("specs")).stream().filter(s->number(s.get("id"))==specId).findFirst().orElse(Map.of());
            var option=new LinkedHashMap<String,Object>();
            option.put("specId",specId); option.put("specName",text(spec.getOrDefault("name",link.get("specName"))));
            option.put("supplierCode",text(listing.get("supplierCode")));
            option.put("evidence",link);
            long skuId=number(spec.get("internalSkuId")); option.put("internalSkuId",skuId);
            String reason="";
            try {
                require(skuId>0,"经营规格尚未关联正式 SKU，请先维护合作产品规格");
                require(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_partner_spec where spec_id=? and record_id=? and deleted_flag=0 and data->>'internalSkuId'=?)",Boolean.class,specId,number(product.get("id")),Long.toString(skuId))),"经营规格已删除或关联已变化");
                access.requireProduct(skuId);
                var rows=jdbc.queryForList("select product_id as \"internalSkuId\",product_code as \"skuCode\",product_name as \"skuName\",phone_model_member_id as \"phoneModelId\",phone_model_code as \"phoneModelCode\",color,color_code as \"colorCode\",master_data_version as \"masterDataVersion\" from plm_product where product_id=? and product_type='sku' and deleted_flag=0 and master_data_status='active'"+(lock?" for share":""),skuId);
                require(rows.size()==1,"正式 SKU 不存在、已删除或停用");option.putAll(rows.get(0));
                var ancestors=jdbc.queryForList("with recursive parents as (select product_id,parent_product_id,deleted_flag,master_data_status,array[product_id] as path from plm_product where product_id=? union all select p.product_id,p.parent_product_id,p.deleted_flag,p.master_data_status,a.path||p.product_id from plm_product p join parents a on p.product_id=a.parent_product_id where not p.product_id=any(a.path)) select product_id from parents where deleted_flag<>0 or master_data_status<>'active'",skuId);
                require(ancestors.isEmpty(),"正式 SKU 所属产品已删除或停用");
                require(!text(option.get("skuCode")).isEmpty(),"正式 SKU 编码缺失");
                long phone=number(option.get("phoneModelId"));
                require(phoneIds(spec.get("phoneModelIds")).contains(phone),"正式 SKU 手机型号与经营规格不一致");
                if(number(link.get("phoneModelId"))>0) require(phone==number(link.get("phoneModelId")),"正式 SKU 不属于本次选择的手机型号");
                var phones=jdbc.queryForList("select m.model_name as name,c.code_value as code from plm_phone_model_member m join plm_phone_brand b on b.id=m.brand_id join plm_code_item c on c.code_item_id=m.code_item_id where m.id=? and m.status='enabled' and b.status='enabled' and c.deleted_flag=0",phone);
                require(phones.size()==1&&!text(phones.get(0).get("code")).isEmpty()&&text(phones.get(0).get("code")).equals(text(option.get("phoneModelCode"))),"手机型号编码缺失、不一致或型号已停用");
                option.put("phoneModelName",phones.get(0).get("name"));
                require(!text(option.get("colorCode")).isEmpty()&&!text(option.get("color")).isEmpty(),"正式 SKU 颜色或颜色编码缺失");
                listings.validateCrmPushEvidence(listing,link,spec);
                require(seen.add(skuId),"同一正式 SKU 被多个经营规格关联，请先核实");
            } catch(BusinessException e) { reason=e.getCode()==40103?"正式 SKU 或依据未获授权":e.getMessage(); }
            option.put("eligible",reason.isEmpty()); option.put("reason",reason); candidates.add(option);
        }
        if(candidates.isEmpty()) reasons.add("没有本次已确认的经营规格");
        for(var candidate:candidates)if(number(candidate.get("internalSkuId"))>0&&candidates.stream().filter(s->number(s.get("internalSkuId"))==number(candidate.get("internalSkuId"))).count()>1){candidate.put("eligible",false);candidate.put("reason","同一正式 SKU 被多个经营规格关联，请先核实");}
        var data=new LinkedHashMap<String,Object>();
        data.put("listingId",listingId);data.put("listingVersion",listing.get("version"));data.put("productVersion",product.get("version"));
        data.put("product",Map.of("productId",product.get("id"),"productCode",text(product.get("code")),"name",text(basis.get("name")),"catalogStyleCode",text(listing.get("catalogStyleCode")),"introduction",text(basis.get("introduction")),"photos",photos));
        data.put("skus",candidates);data.put("reasons",reasons);data.put("snapshotVersion",CrmListingDigest.of(json,data));
        return data;
    }
}

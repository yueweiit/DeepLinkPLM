package com.yuewei.plm.module.productionmetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.moldreference.PhoneIdentityMatcher;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceRepository;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceTypes.Patch;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Only metadata changes: never enable/disable a code or advance/freeze/archive a product. */
@Service @RequiredArgsConstructor
public class ProductionMetadataService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PhoneCodeMetadataRepository phones;
    private final PhoneModelReferenceRepository references;
    private final PilotAccessService access;
    private final OperationLogService audit;
    @org.springframework.beans.factory.annotation.Autowired
    private com.yuewei.plm.module.moldreference.PhoneModelReferenceService phoneReferences;
    public record Item(@NotNull @Positive Long id,@NotEmpty Map<String,JsonNode> changes,@NotNull Map<String,JsonNode> original) {}
    public record Batch(@NotEmpty @Size(max=100) List<@Valid Item> items,@NotBlank @Size(max=1000) String reason) {}
    public record BrandReviewItem(@NotNull @Positive Long id,@NotNull @Size(max=128) String codeValue,
                                 @NotNull @Size(max=512) String codeName,@NotNull @Size(max=100) List<@NotNull @Positive Long> originalBrandIds,
                                 @NotEmpty @Size(max=100) List<@NotNull @Positive Long> brandIds) {}
    public record BrandReviewBatch(@NotEmpty @Size(max=10000) List<@Valid BrandReviewItem> items,@NotBlank @Size(max=1000) String reason) {}
    public Map<String,Object> options(){return Map.of("canMaintain",access.canMaintainEngineeringData());}

    public Map<String,Object> phoneBrandReview() {
        access.requireEngineeringMaintenance();var brands=references.allBrands();var items=new ArrayList<Map<String,Object>>();int ready=0;
        var names=new HashMap<Long,String>();for(var brand:brands)names.put(((Number)brand.get("brandId")).longValue(),Objects.toString(brand.get("name"),""));
        for(var phone:phones.unclassifiedPhones()) {
            var review=PhoneIdentityMatcher.reviewBrand(Objects.toString(phone.get("codeName"),""),brands);
            var item=new LinkedHashMap<>(phone);item.put("originalBrandIds",List.of());item.put("brandIds",review.brandIds());
            item.put("brandNames",review.brandIds().stream().map(names::get).toList());item.put("state",review.state());item.put("reason",review.reason());item.put("evidence",review.evidence());items.add(item);
            if("ready".equals(review.state()))ready++;
        }
        return Map.of("total",items.size(),"candidateCount",ready,"unresolvedCount",items.size()-ready,"items",items);
    }
    /** One transaction for the entire preview selection, using the normal metadata writer and required audit. */
    @Transactional public Map<String,Object> applyPhoneBrandReview(BrandReviewBatch batch,HttpServletRequest request) {
        var actor=access.requireEngineeringMaintenance();
        if(batch==null||batch.items()==null||batch.items().isEmpty()||batch.items().size()>10000)throw invalid("品牌核对批次须为1至10000条");
        String why=reason(batch.reason());var seen=new HashSet<Long>();
        for(var item:batch.items())if(item==null||item.id()==null||item.id()<1||!seen.add(item.id())||item.originalBrandIds()==null||!item.originalBrandIds().isEmpty()||item.brandIds()==null||item.brandIds().isEmpty()||item.brandIds().size()>100)throw invalid("品牌核对对象无效、重复或不是未归类记录");
        // Maintained rules cannot change halfway through an atomic confirmation.
        jdbc.execute("select pg_advisory_xact_lock(66817399)");
        jdbc.queryForList("select id from plm_phone_brand order by id for share");
        var brands=references.allBrands();var ordered=batch.items().stream().sorted(Comparator.comparing(BrandReviewItem::id)).toList();
        var decisions=new LinkedHashMap<Long,PhoneIdentityMatcher.BrandReview>();
        for(var item:ordered) {
            var before=phones.phone(item.id(),true);
            if(before==null)throw new BusinessException(40901,"编码组已删除，请重新预览",Map.of("id",item.id()));
            var decision=PhoneIdentityMatcher.reviewBrand(Objects.toString(before.get("codeName"),""),brands);
            if(!Objects.equals(before.get("codeName"),item.codeName())||!Objects.equals(before.get("codeValue"),item.codeValue())||!Objects.equals(before.get("brandIds"),item.originalBrandIds())||!"ready".equals(decision.state())||!Objects.equals(decision.brandIds(),item.brandIds()))
                throw new BusinessException(40901,"品牌、原型号或匹配规则已变更，请重新预览后核对",Map.of("id",item.id(),"reason",decision.reason()));
            decisions.put(item.id(),decision);
        }
        var result=new ArrayList<Map<String,Object>>();
        for(var item:ordered) {
            var patch=new Patch(Map.of("brandIds",json.valueToTree(item.brandIds())),Map.of("brandIds",json.valueToTree(item.originalBrandIds())),why);
            var decision=decisions.get(item.id());
            result.add(update(true,item.id(),patch,actor,request,Map.of("method","explicit-brand-prefix","evidence",decision.evidence())));
        }
        return Map.of("appliedCount",result.size(),"items",result);
    }

    @Transactional public Map<String,Object> phone(long id,Patch patch,HttpServletRequest request){return update(true,id,patch,access.requireEngineeringMaintenance(),request);}
    @Transactional public Map<String,Object> product(long id,Patch patch,HttpServletRequest request){return update(false,id,patch,access.requireEngineeringMaintenance(),request);}
    /** Joins the original research transaction; the same metadata writer owns catalog state and audit. */
    @Transactional public Map<String,Object> admitResearchProduct(long id,long releaseId,HttpServletRequest request){
        var actor=access.requireEngineeringMaintenance();access.requirePermission("documents:publish");access.requireProduct(id);
        var releases=jdbc.queryForList("""
            select p.product_id from plm_product p
            join plm_product_development_state s on s.product_id=p.product_id
            join plm_product_development_release r on r.product_id=p.product_id and r.release_id=?
            where p.product_id=? and p.product_type='product_line' and p.development_mode='launch_v1'
              and p.status='released' and p.deleted_flag=0 and coalesce(p.lock_status,'') not in ('frozen','abandoned')
              and (s.data->>'currentReleaseId')::bigint=r.release_id
            for update of p
            """,releaseId,id);
        if(releases.size()!=1)throw invalid("请使用本新品当前正式资料包确认上市");
        var before=productRow(id,true);if(before==null)throw invalid("新品已不存在");
        var patch=new Patch(Map.of("salesStatus",json.valueToTree("on_sale")),Map.of("salesStatus",json.valueToTree(before.get("salesStatus"))),"新品首次正式上市，自动纳入在售产品目录");
        return update(false,id,patch,actor,request,Map.of("releaseId",releaseId,"productId",id),"researchLaunch");
    }
    @Transactional public List<Map<String,Object>> phoneBatch(Batch batch,HttpServletRequest request){return batch(true,batch,request);}
    @Transactional public List<Map<String,Object>> productBatch(Batch batch,HttpServletRequest request){return batch(false,batch,request);}
    private List<Map<String,Object>> batch(boolean phone,Batch batch,HttpServletRequest request){
        var actor=access.requireEngineeringMaintenance();
        if(batch==null||batch.items()==null||batch.items().isEmpty()||batch.items().size()>100)throw invalid("每批须为1至100条");
        reason(batch.reason());var seen=new HashSet<Long>();
        for(var item:batch.items())if(item==null||item.id()==null||item.id()<1||!seen.add(item.id()))throw invalid("批量对象无效或重复");
        var ordered=batch.items().stream().sorted(Comparator.comparing(Item::id)).toList();var result=new ArrayList<Map<String,Object>>();
        for(var item:ordered)result.add(update(phone,item.id(),new Patch(item.changes(),item.original(),batch.reason()),actor,request));
        return result;
    }
    private Map<String,Object> update(boolean phone,long id,Patch patch,CurrentUser actor,HttpServletRequest request){
        return update(phone,id,patch,actor,request,Map.of());
    }
    private Map<String,Object> update(boolean phone,long id,Patch patch,CurrentUser actor,HttpServletRequest request,Map<String,Object> evidence){
        return update(phone,id,patch,actor,request,evidence,"brandReview");
    }
    private Map<String,Object> update(boolean phone,long id,Patch patch,CurrentUser actor,HttpServletRequest request,Map<String,Object> evidence,String evidenceKey){
        if(id<1||patch==null||patch.changes()==null||patch.changes().isEmpty()||patch.original()==null)throw invalid("须提交修改字段和原值");
        String why=reason(patch.reason());if(!phone)access.requireProduct(id);
        if(phone&&patch.changes().containsKey("brandIds")&&phoneReferences!=null)phoneReferences.lockCodeSelectionMaintenance();
        var before=phone?phones.phone(id,true):productRow(id,true);
        if(before==null)throw new BusinessException(40401,phone?"手机编码组不存在":"产品不存在");
        var values=new LinkedHashMap<String,Object>();
        for(var entry:patch.changes().entrySet()){
            String field=entry.getKey();
            if(!field.equals("productionStatus")&&!(phone&&field.equals("brandIds"))&&!(!phone&&Set.of("salesStatus","applicationAliases").contains(field)))throw invalid("不可维护字段："+field);
            if(!patch.original().containsKey(field))throw invalid("缺少字段原值："+field);
            Object pending=metadataValue(field,entry.getValue());
            Object original=metadataValue(field,patch.original().get(field));
            if(!Objects.equals(before.get(field),original))throw new BusinessException(40901,"生产状态或品牌已被修改，请刷新核对",Map.of("id",id,"field",field,"current",before.get(field),"pending",pending));
            values.put(field,pending);
        }
        if(values.containsKey("brandIds"))validateBrands(id,(List<Long>)values.get("brandIds"),(List<Long>)before.get("brandIds"));
        if(values.entrySet().stream().noneMatch(e->!Objects.equals(before.get(e.getKey()),e.getValue()))){if(phone&&values.containsKey("brandIds")&&phoneReferences!=null)phoneReferences.syncCodeSelectionAfterMaintenance(id,request);return before;}
        if(values.containsKey("productionStatus"))jdbc.update("update "+(phone?"plm_code_item":"plm_product")+" set production_status=?,updated_by=?,updated_at=now() where "+(phone?"code_item_id":"product_id")+"=?",values.get("productionStatus"),actor.username(),id);
        if(!phone&&values.keySet().stream().anyMatch(k->Set.of("salesStatus","applicationAliases").contains(k))){if(!"product_line".equals(before.get("productType")))throw invalid("在售范围和审批别名只在产品平台维护");if(values.containsKey("salesStatus"))jdbc.update("update plm_product set sales_status=?,updated_at=now(),updated_by=? where product_id=?",values.get("salesStatus"),actor.username(),id);if(values.containsKey("applicationAliases"))jdbc.update("update plm_product set application_aliases=array(select jsonb_array_elements_text(?::jsonb)),updated_at=now(),updated_by=? where product_id=?",array((List<String>)values.get("applicationAliases")),actor.username(),id);}
        if(values.containsKey("brandIds")){
            var derived=phones.brandIds(id,true);jdbc.update("delete from plm_phone_code_brand where code_item_id=?",id);
            for(long brand:(List<Long>)values.get("brandIds"))if(!derived.contains(brand))jdbc.update("insert into plm_phone_code_brand(code_item_id,brand_id,created_by) values(?,?,?)",id,brand,actor.username());
        }
        var after=phone?phones.phone(id,false):productRow(id,false);
        var detail=new LinkedHashMap<String,Object>();detail.put("before",before);detail.put("after",after);detail.put("reason",why);if(!evidence.isEmpty())detail.put(evidenceKey,evidence);
        try{audit.logRequired(OperationLogCreateCommand.builder().action("PRODUCTION_METADATA_UPDATE").businessType(phone?"PHONE_CODE_GROUP":"PRODUCT").businessId(String.valueOf(id)).businessName(Objects.toString(before.get(phone?"codeName":"productName"),"生产状态")).detailJson(json.writeValueAsString(detail)).request(request).build(),actor);}
        catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException("生产状态审计序列化失败",failure);}
        if(phone&&values.containsKey("brandIds")&&phoneReferences!=null)phoneReferences.syncCodeSelectionAfterMaintenance(id,request);
        return after;
    }
    private Map<String,Object> productRow(long id,boolean lock){
        var rows=jdbc.queryForList("select product_id as id,product_name as \"productName\",product_type as \"productType\",sales_status as \"salesStatus\",to_json(application_aliases)::text as \"applicationAliasesJson\",production_status as \"productionStatus\" from plm_product where product_id=? and deleted_flag=0"+(lock?" for update":""),id);if(rows.isEmpty())return null;var row=rows.get(0);try{row.put("applicationAliases",json.readValue(Objects.toString(row.remove("applicationAliasesJson")),List.class));}catch(Exception e){throw new IllegalStateException(e);}return row;
    }
    public List<Map<String,Object>> salesCatalog(){access.requireDomesticView();access.requirePermission("product:view");return jdbc.queryForList("select p.product_id from plm_product p where p.product_type='product_line' and p.deleted_flag=0 and ("+access.visibleSqlClause("product","p.product_id")+") order by p.product_id").stream().map(r->productRow(((Number)r.get("product_id")).longValue(),false)).toList();}
    private Object metadataValue(String field,JsonNode node){if("brandIds".equals(field))return ids(node);if("salesStatus".equals(field)){if(node==null||!Set.of("on_sale","off_sale","unconfirmed").contains(node.asText()))throw invalid("在售状态无效");return node.asText();}if("applicationAliases".equals(field)){if(node==null||!node.isArray()||node.size()>100)throw invalid("审批款式别名无效");var list=new TreeSet<String>();for(var n:node){if(!n.isTextual()||n.asText().isBlank()||n.asText().length()>255)throw invalid("别名须为255字以内文字");list.add(n.asText().strip());}return List.copyOf(list);}return state(node);}
    private String array(List<String> values){try{return json.writeValueAsString(values);}catch(Exception e){throw new IllegalStateException(e);}}

    private void validateBrands(long code,List<Long> selected,List<Long> old){
        if(!selected.containsAll(phones.brandIds(code,true)))throw invalid("标准手机成员已确认的品牌不可从编码组移除，请先在成员资料更正");
        for(long id:selected){var rows=jdbc.queryForList("select status from plm_phone_brand where id=?",id);if(rows.isEmpty())throw invalid("品牌不存在");if(!old.contains(id)&&!"enabled".equals(rows.get(0).get("status")))throw invalid("新增分类只能选择启用品牌");}
    }
    private List<Long> ids(JsonNode node){
        if(node==null||!node.isArray()||node.size()>100)throw invalid("品牌列表无效");var values=new TreeSet<Long>();
        for(var id:node){if(!id.isIntegralNumber()||!id.canConvertToLong()||id.longValue()<1)throw invalid("品牌ID无效");values.add(id.longValue());}return List.copyOf(values);
    }
    private String state(JsonNode node){if(node==null||!node.isTextual())throw invalid("生产状态须为文字");return ProductionStatus.normalize(node.textValue());}
    private String reason(String value){if(value==null||value.isBlank()||value.strip().length()>1000)throw invalid("请填写变更原因（最多1000字）");return value.strip();}
    private static BusinessException invalid(String value){return new BusinessException(40001,value);}
}

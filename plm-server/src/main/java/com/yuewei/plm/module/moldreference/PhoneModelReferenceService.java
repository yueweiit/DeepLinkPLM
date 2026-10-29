package com.yuewei.plm.module.moldreference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.vo.PageVO;
import com.yuewei.plm.module.operationlog.dto.OperationLogQueryDTO;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.moldreference.PhoneModelReferenceTypes.*;

/** Standards are independent phone identities; a business code may identify several standards. */
@Service @RequiredArgsConstructor
public class PhoneModelReferenceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PhoneModelReferenceRepository repository;
    private final PilotAccessService access;
    private final OperationLogService audit;
    @org.springframework.beans.factory.annotation.Autowired private com.yuewei.plm.module.code.service.CodeLifecycleService lifecycle;
    private static final Map<String,String> BRAND_FIELDS=Map.of("name","name","aliases","aliases","prefixes","prefixes","status","status");
    private static final Map<String,String> PHONE_FIELDS=Map.of("brandId","brand_id","modelName","model_name","aliases","aliases","codeItemId","code_item_id","status","status");

    public PageVO<Map<String,Object>> brands(Query query) { read();validateQuery(query);return repository.brands(query); }
    public List<Map<String,Object>> selectionBrands() { access.requirePhoneSelectionRead();return repository.selectionBrands(); }
    public PageVO<Map<String,Object>> phones(Query query) { access.requirePhoneSelectionRead();validateQuery(query);var page=repository.phones(query);selectionFields(page.getContent());return page; }
    public Map<String,Object> brand(long id) { read();return require(repository.brand(id,false),"品牌"); }
    public Map<String,Object> phone(long id) { read();return require(repository.phone(id,false),"手机型号"); }
    public List<Map<String,Object>> options(List<Long> ids) { access.requirePhoneSelectionRead();var rows=members(ids,true);var selected=ids.stream().distinct().map(rows::get).toList();selectionFields(selected);return selected; }
    public List<Map<String,Object>> compatibleMolds(long phoneModelId,long productLineId) {
        read();phone(phoneModelId);access.requireProduct(productLineId);return compatibilityOptions(productLineId,null,phoneModelId);
    }
    /** Shared by project selection and mold-application preview; no name/code guess can create a group. */
    public List<Map<String,Object>> compatibilityOptions(long productLineId,Long codeItemId,Long phoneModelId) {
        read();access.requireProduct(productLineId);
        var args=new ArrayList<Object>();args.add(productLineId);String filter="";
        if(codeItemId!=null){filter+=" and pm.code_item_id=?";args.add(codeItemId);}
        if(phoneModelId!=null){filter+=" and pm.id=?";args.add(phoneModelId);}
        String sql="""
            select i.item_id as "itemId",i.mold_name as "moldName",i.style_name as "styleName",i.engraved_code as "engravedCode",
              s.mold_set_id as "moldSetId",s.set_no as "setNo",('MS-'||lpad(s.mold_set_id::text,greatest(6,length(s.mold_set_id::text)),'0')) as "physicalCode",
              pm.id as "phoneModelId",pm.model_name as "modelName",pm.code_item_id as "codeItemId",c.code_value as "phoneModelCode",
              pm.status as "phoneStatus",b.status as "brandStatus",c.status as "codeStatus",c.deleted_flag as "codeDeletedFlag",
              true as "compatibilityConfirmed",cm.pc_confirmed as "pcConfirmed",cm.tpu_confirmed as "tpuConfirmed",
              cm.pc_evidence as "pcEvidence",cm.tpu_evidence as "tpuEvidence",concat_ws('；',cm.pc_evidence,cm.tpu_evidence) evidence,
              coalesce(pc.original_mold_code,pcm.mold_code) as "pcCode",coalesce(tpu.original_mold_code,tpum.mold_code) as "tpuCode",
              pc.physical_status as "pcPhysicalStatus",tpu.physical_status as "tpuPhysicalStatus",pc.status as "pcStatus",tpu.status as "tpuStatus",
              pc.factory_arrival_date as "pcArrival",tpu.factory_arrival_date as "tpuArrival",
              pc.acceptance_status as "pcAcceptanceStatus",tpu.acceptance_status as "tpuAcceptanceStatus",pc.acceptance_date as "pcAcceptanceDate",tpu.acceptance_date as "tpuAcceptanceDate",
              jsonb_build_array(jsonb_build_object('partType','PC','code',coalesce(pc.original_mold_code,pcm.mold_code),'catalogCodeId',pc.product_mold_code_id,'inventoryId',pc.inventory_id,'originalCode',pc.original_mold_code,'catalogCode',pcm.mold_code),
                jsonb_build_object('partType','TPU','code',coalesce(tpu.original_mold_code,tpum.mold_code),'catalogCodeId',tpu.product_mold_code_id,'inventoryId',tpu.inventory_id,'originalCode',tpu.original_mold_code,'catalogCode',tpum.mold_code))::text as "partsJson"
            from plm_collaboration_phone_set_compatibility cm join plm_phone_model_member pm on pm.id=cm.phone_model_id
              join plm_phone_brand b on b.id=pm.brand_id
              left join plm_code_item c on c.code_item_id=pm.code_item_id and c.code_type='phone_model'
              join plm_collaboration_item i on i.item_id=cm.item_id and i.deleted_flag=0
                and coalesce(to_jsonb(i)->>'master_data_status','active')='active'
              join plm_product product on product.product_id=i.product_line_id and product.deleted_flag=0
                and coalesce(to_jsonb(product)->>'master_data_status','active')='active'
              join plm_collaboration_mold_set s on s.item_id=i.item_id and s.mold_set_id=cm.mold_set_id and s.deleted_flag=0
                and coalesce(to_jsonb(s)->>'master_data_status','active')='active' 
              left join plm_collaboration_mold_part pc on pc.mold_set_id=s.mold_set_id and pc.part_type='PC'
              left join plm_collaboration_mold_part tpu on tpu.mold_set_id=s.mold_set_id and tpu.part_type='TPU'
              left join plm_product_mold_code pcm on pcm.product_mold_code_id=pc.product_mold_code_id and pcm.deleted_flag=0
              left join plm_product_mold_code tpum on tpum.product_mold_code_id=tpu.product_mold_code_id and tpum.deleted_flag=0
            where i.product_line_id=? and coalesce((to_jsonb(pm)->>'deleted_flag')::int,0)=0
              and coalesce((to_jsonb(b)->>'deleted_flag')::int,0)=0
            """+filter+" and "+access.visibleSqlClause("item","i.item_id")+" and "+access.visibleSqlClause("mold","s.mold_set_id")+" order by i.item_id,s.mold_set_id,pm.id";
        var rows=jdbc.queryForList(sql,args.toArray());
        for(var row:rows) {try {row.put("parts",json.readTree(row.remove("partsJson").toString()));}catch(Exception failure){throw new IllegalStateException("兼容实物部件解析失败",failure);}row.put("compatibilityDigest",compatibilityDigest(row));}
        return rows.stream().filter(MoldCompatibilityPolicy::reusable).toList();
    }
    public Map<String,Object> requireCompatibility(long productLineId,long phoneModelId,long itemId,long moldSetId,String expectedDigest) {
        read();access.requireItem(itemId);access.requireMold(moldSetId);access.requireProduct(productLineId);
        com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(jdbc,"product",productLineId);
        com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(jdbc,"item",itemId);
        com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(jdbc,"mold_set",moldSetId);
        requireEnabledModels(List.of(phoneModelId));
        // The shared active guards retain FOR SHARE until the existing adoption transaction finishes.
        jdbc.queryForList("select id from plm_phone_model_member where id=? for share",phoneModelId);
        var match=compatibilityOptions(productLineId,null,phoneModelId).stream().filter(row->number(row.get("itemId"))==itemId&&number(row.get("moldSetId"))==moldSetId).findFirst().orElseThrow(()->invalid("所选手机与PC/TPU实物套尚未完整确认适配，不能复用原码"));
        if(expectedDigest==null||!Objects.equals(expectedDigest,match.get("compatibilityDigest")))throw conflict("兼容关系或原部件编码已变化，请重新核对后确认");return match;
    }
    private String compatibilityDigest(Map<String,Object> row) {
        var decision=new LinkedHashMap<String,Object>();for(String field:List.of("itemId","moldSetId","phoneModelId","codeItemId","phoneModelCode","engravedCode","parts","pcConfirmed","tpuConfirmed","pcEvidence","tpuEvidence","pcPhysicalStatus","tpuPhysicalStatus","pcArrival","tpuArrival","pcAcceptanceStatus","tpuAcceptanceStatus","pcAcceptanceDate","tpuAcceptanceDate"))decision.put(field,row.get(field));
        try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(decision)));}
        catch(Exception failure){throw new IllegalStateException("兼容关系摘要失败",failure);}
    }

    @Transactional public Map<String,Object> createBrand(CreateBrand dto,HttpServletRequest request) {
        CurrentUser actor=write();String name=name(dto.name(),128,"品牌");
        jdbc.execute("select pg_advisory_xact_lock(66817399)");
        List<String> names=aliases(dto.aliases(),name);var prefixes=prefixes(json.valueToTree(dto.prefixes()));ensureBrandUnique(name,null);ensureBrandAliases(name,names,null);
        long id;
        try { id=Objects.requireNonNull(jdbc.queryForObject("insert into plm_phone_brand(name,aliases,prefixes,created_by,updated_by) values(?,?::text[],?::jsonb,?,?) returning id",Long.class,name,array(names),json.valueToTree(prefixes).toString(),actor.username(),actor.username())); }
        catch(DuplicateKeyException duplicate) { throw conflict("品牌名称已存在"); }
        var row=repository.brand(id,false);log(actor,"brand",id,"CREATE",null,row,null,request);return row;
    }
    @Transactional public Map<String,Object> createPhone(CreatePhone dto,HttpServletRequest request) {
        CurrentUser actor=write();jdbc.execute("select pg_advisory_xact_lock(66817399)");validateBrand(dto.brandId());String model=name(dto.modelName(),256,"手机型号");
        List<String> aliases=aliases(dto.aliases(),model);validateCode(dto.codeItemId());ensurePhoneUnique(dto.brandId(),model,null);
        long id;
        try { id=Objects.requireNonNull(jdbc.queryForObject("insert into plm_phone_model_member(brand_id,model_name,aliases,code_item_id,created_by,updated_by) values(?,?,?::text[],?,?,?) returning id",Long.class,dto.brandId(),model,array(aliases),dto.codeItemId(),actor.username(),actor.username())); }
        catch(DuplicateKeyException duplicate) { throw conflict("该品牌下手机型号名称已存在"); }
        var row=repository.phone(id,false);log(actor,"phone",id,"CREATE",null,row,null,request);return row;
    }
    /** Register the original code group, never split it or infer physical molds/fit from its name. */
    @Transactional public List<Map<String,Object>> linkCodeSelection(CodeSelectionBatch batch,HttpServletRequest request) {
        write();
        if(batch==null||batch.items()==null||batch.items().isEmpty()||batch.items().size()>1000||batch.reason()==null||batch.reason().isBlank())throw invalid("请提供编码核对清单和依据");
        if(batch.items().stream().anyMatch(i->i==null||i.codeItemId()==null||i.codeItemId()<1)||batch.items().stream().map(CodeSelectionLink::codeItemId).distinct().count()!=batch.items().size())throw invalid("编码核对对象无效或重复");
        jdbc.execute("select pg_advisory_xact_lock(66817399)");
        var out=new ArrayList<Map<String,Object>>();
        for(var item:batch.items().stream().sorted(Comparator.comparing(CodeSelectionLink::codeItemId)).toList())out.add(linkCode(item,request,batch.reason().strip()));
        return out;
    }
    /** Existing code/brand maintenance owns this transaction and its authorization; readers never write. */
    public void lockCodeSelectionMaintenance() {
        if(access.canMaintainEngineeringData())jdbc.execute("select pg_advisory_xact_lock(66817399)");
    }
    @Transactional public void syncCodeSelectionAfterMaintenance(long codeItemId,HttpServletRequest request) {
        if(!access.canMaintainEngineeringData())return;
        jdbc.execute("select pg_advisory_xact_lock(66817399)");
        linkCode(new CodeSelectionLink(codeItemId,null,null),request,"手机编码／品牌维护后同步原编码组选项");
    }
    private Map<String,Object> linkCode(CodeSelectionLink expected,HttpServletRequest request,String reason) {
        long codeId=expected.codeItemId();
        var codes=jdbc.queryForList("select code_value,code_name,status from plm_code_item where code_item_id=? and code_type='phone_model' and deleted_flag=0 for update",codeId);
        if(codes.isEmpty())throw conflict("手机编码已删除，请重新核对");
        var code=codes.get(0);String value=Objects.toString(code.get("code_value"),""),raw=Objects.toString(code.get("code_name"),"");
        if(expected.codeValue()!=null&&(!expected.codeValue().equals(value)||!Objects.equals(expected.codeName(),raw)))throw conflict("手机编码或原名称已变更，请重新核对");
        var existing=jdbc.queryForList("select id from plm_phone_model_member where code_item_id=? order by id",codeId);
        if(!existing.isEmpty())return Map.of("codeItemId",codeId,"status","linked","phoneModelIds",existing.stream().map(r->r.get("id")).toList());
        if(!"enabled".equals(code.get("status"))||!value.matches("[0-9]{4}"))return codeReview(codeId,"编码停用或不是四位原码");
        var brands=repository.allBrands();
        var brandIds=new TreeSet<>(jdbc.queryForList("select brand_id from plm_phone_code_brand_effective where code_item_id=? order by brand_id",Long.class,codeId));
        if(brandIds.isEmpty()) {
            var decision=PhoneIdentityMatcher.reviewBrand(raw,brands);
            if(!"ready".equals(decision.state()))return codeReview(codeId,decision.reason());
            brandIds.addAll(decision.brandIds());
        }
        for(long brand:brandIds) {
            if(brands.stream().noneMatch(b->Objects.equals(number(b.get("brandId")),brand)&&"enabled".equals(b.get("status"))))return codeReview(codeId,"品牌已停用");
            if(jdbc.queryForObject("select count(*) from plm_phone_model_member where brand_id=? and lower(btrim(model_name))=lower(btrim(?))",Long.class,brand,raw)>0)return codeReview(codeId,"同品牌原名称已关联其他编码；请在手机编码／历史关系核对中处理");
        }
        if(raw.isBlank()||raw.strip().length()>256)return codeReview(codeId,"原型号名称为空或超长，请在手机编码维护");
        var ids=new ArrayList<Long>();
        for(long brand:brandIds) {
            var row=createPhone(new CreatePhone(brand,raw,List.of(),codeId),request);long id=number(row.get("id"));
            // Keep the exact original, including combined models, hole descriptions and line breaks.
            var evidence=Map.of("method","existing-phone-code-group","codeItemId",codeId,"codeValue",value,"originalCodeName",raw,"reason",reason,"combinedOriginalPreserved",!PhoneIdentityMatcher.isSingleModel(raw));
            jdbc.update("update plm_phone_model_member set source_snapshot=?::jsonb where id=?",json.valueToTree(evidence).toString(),id);
            log(write(),"phone",id,"LINK_CODE",null,repository.phone(id,false),reason,request);ids.add(id);
        }
        return Map.of("codeItemId",codeId,"status","created","phoneModelIds",ids);
    }
    private Map<String,Object> codeReview(long id,String reason) {return Map.of("codeItemId",id,"status","review","reason",reason);}
    @Transactional public Map<String,Object> patchBrand(long id,Patch patch,HttpServletRequest request) { return patch("brand",id,patch,request); }
    @Transactional public Map<String,Object> patchPhone(long id,Patch patch,HttpServletRequest request) { return patch("phone",id,patch,request); }

    private Map<String,Object> patch(String kind,long id,Patch patch,HttpServletRequest request) {
        CurrentUser actor=write();boolean brand=kind.equals("brand");jdbc.execute("select pg_advisory_xact_lock(66817399)");Map<String,String> fields=brand?BRAND_FIELDS:PHONE_FIELDS;
        var before=require(brand?repository.brand(id,true):repository.phone(id,true),brand?"品牌":"手机型号");
        if(patch.changes()==null||patch.changes().isEmpty()||patch.original()==null) throw invalid("请提交修改字段及原值");
        if(((Number)before.get("deletedFlag")).intValue()!=0)throw invalid("资料已删除");
        var values=new LinkedHashMap<String,Object>();var conflicts=new ArrayList<Map<String,Object>>();
        for(var entry:patch.changes().entrySet()) {
            String field=entry.getKey();if(!fields.containsKey(field)) throw invalid("不可维护字段："+field);
            if(!patch.original().containsKey(field)) throw invalid("缺少字段原值："+field);
            JsonNode saved=json.valueToTree(before.get(field));JsonNode original=patch.original().get(field);
            if(!sameValue(saved,original)) {
                var item=new LinkedHashMap<String,Object>();item.put("field",field);item.put("current",before.get(field));item.put("pending",entry.getValue());conflicts.add(item);
            }
            values.put(field,normalize(field,entry.getValue()));
        }
        if(patch.changes().containsKey("status")&&(patch.reason()==null||patch.reason().isBlank()))throw invalid("启停请填写原因");
        if(!conflicts.isEmpty()) return Map.of("status","conflict","conflicts",conflicts,"current",before);
        var merged=new LinkedHashMap<>(before);merged.putAll(values);
        if(brand) {ensureBrandUnique((String)merged.get("name"),id);ensureBrandAliases((String)merged.get("name"),(List<String>)merged.get("aliases"),id);}
        else {
            Long oldCode=number(before.get("codeItemId")),code=number(merged.get("codeItemId"));
            if(oldCode!=null&&!Objects.equals(oldCode,code)) throw invalid("已关联的原手机编码固定保留，不能换码或清空");
            if(values.containsKey("codeItemId")&&oldCode==null) validateCode(code);
            Long oldBrand=number(before.get("brandId")),newBrand=number(merged.get("brandId"));
            if(!Objects.equals(oldBrand,newBrand)||"enabled".equals(values.get("status"))) validateBrand(newBrand);
            if(values.containsKey("aliases")) values.put("aliases",aliases((List<?>)merged.get("aliases"),(String)merged.get("modelName")));
            ensurePhoneUnique(newBrand,(String)merged.get("modelName"),id);
        }
        boolean changed=values.entrySet().stream().anyMatch(e->!Objects.equals(before.get(e.getKey()),e.getValue()));
        if(!changed) return Map.of("status","saved","row",before);
        var sets=new ArrayList<String>();var args=new ArrayList<Object>();
        for(var entry:values.entrySet()) {
            boolean arr=entry.getKey().equals("aliases"),rules=entry.getKey().equals("prefixes");sets.add(fields.get(entry.getKey())+"=?"+(arr?"::text[]":rules?"::jsonb":""));args.add(arr?array((List<String>)entry.getValue()):rules?json.valueToTree(entry.getValue()).toString():entry.getValue());
        }
        sets.add("version=version+1");sets.add("updated_by=?");args.add(actor.username());sets.add("updated_at=now()");args.add(id);
        try { jdbc.update("update "+(brand?"plm_phone_brand":"plm_phone_model_member")+" set "+String.join(",",sets)+" where id=?",args.toArray()); }
        catch(DuplicateKeyException duplicate) { throw conflict(brand?"品牌名称已存在":"该品牌下手机型号名称已存在"); }
        var after=brand?repository.brand(id,false):repository.phone(id,false);
        log(actor,kind,id,"UPDATE",before,after,optional(patch.reason()),request);
        return Map.of("status","saved","row",after);
    }

    /** One batch read for compatible memberships; disabled historic references remain resolvable. */
    public Map<Long,Map<String,Object>> members(List<Long> ids,boolean allowDisabled) {
        if(ids!=null&&(ids.size()>100||ids.stream().anyMatch(id->id==null||id<1))) throw invalid("手机型号成员无效");
        List<Long> safe=ids==null?List.of():ids.stream().distinct().sorted().toList();
        if(!allowDisabled&&!safe.isEmpty()&&org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()&&!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            String placeholders=String.join(",",Collections.nCopies(safe.size(),"?"));
            jdbc.queryForList("select b.id from plm_phone_brand b where b.id in (select brand_id from plm_phone_model_member where id in ("+placeholders+")) order by b.id for share",safe.toArray());
            jdbc.queryForList("select id from plm_phone_model_member where id in ("+placeholders+") order by id for share",safe.toArray());
            jdbc.queryForList("select code_item_id from plm_code_item where code_item_id in (select code_item_id from plm_phone_model_member where id in ("+placeholders+")) order by code_item_id for share",safe.toArray());
        }
        var rows=repository.members(safe);if(rows.size()!=safe.size()) throw invalid("手机型号成员不存在");
        if(!allowDisabled) for(var row:rows.values()) {
            if(Objects.equals(row.get("deletedFlag"),1)||!"enabled".equals(row.get("status"))||!"enabled".equals(row.get("brandStatus"))) throw invalid("新增关联只能使用启用的品牌和手机型号");
            // Unknown code is deliberate and does not prevent maintaining compatibility progress.
            if(row.get("codeItemId")!=null&&!"linked".equals(row.get("codeLinkStatus"))) throw invalid("新增关联的手机编码已停用或删除，请人工核对");
        }
        return rows;
    }
    public Map<Long,Map<String,Object>> requireEnabledModels(List<Long> ids) { return members(ids,false); }
    public Map<String,Object> requireSelectedPhone(long id,Long expectedCodeItemId,String expectedCode,String model) {
        read();var member=requireEnabledModels(List.of(id)).get(id);
        if(member.get("codeItemId")==null||!"linked".equals(member.get("codeLinkStatus")))throw invalid("所选标准手机原编码待核对，不自动分配编码");
        if(expectedCodeItemId!=null&&number(member.get("codeItemId")).longValue()!=expectedCodeItemId||expectedCode!=null&&!Objects.equals(expectedCode,member.get("phoneModelCode")))throw invalid("选择的标准手机与原编码不一致，不能用模具刻码替换");
        var selectedBrand=repository.brand(((Number)member.get("brandId")).longValue(),false);
        String normalized=PhoneIdentityMatcher.modelKey(model,selectedBrand);
        var names=new ArrayList<String>();names.add(member.get("modelName").toString());names.add(member.get("displayName").toString());names.addAll((List<String>)member.get("aliases"));
        if(names.stream().map(name->PhoneIdentityMatcher.modelKey(name,selectedBrand)).noneMatch(normalized::equals))throw invalid("型号名称与明确选择的标准手机不一致，请核对资料");return member;
    }
    public Map<String,Object> validateBrand(Long id) {
        if(id==null||id<1) throw invalid("请选择品牌");
        // Write transactions must not attach a new member while an engineering merge retires its brand.
        jdbc.queryForList("select id from plm_phone_brand where id=? for share",id);
        var row=require(repository.brand(id,false),"品牌");
        if(Objects.equals(row.get("deletedFlag"),1)||!"enabled".equals(row.get("status"))) throw invalid("品牌已停用或删除");return row;
    }
    public String normalizedDisplay(List<Long> ids) {
        var rows=members(ids,true);return ids==null?"":ids.stream().distinct().map(id->Objects.toString(rows.get(id).get("displayName"))).reduce((a,b)->a+" / "+b).orElse("");
    }
    public Object history(String kind,long id,Query q) {
        read();validateQuery(q);if(kind.equals("brand")) brand(id);else if(kind.equals("phone")) phone(id);else throw invalid("主资料类型无效");
        var query=new OperationLogQueryDTO();query.setPage(q.getPage());query.setSize(q.getSize());query.setBusinessType(kind.equals("brand")?"PHONE_BRAND":"PHONE_MODEL_MEMBER");query.setBusinessId(String.valueOf(id));
        return audit.page(query);
    }
    private void selectionFields(List<Map<String,Object>> rows) {
        if(access.hasPermission("collaboration:view"))return;
        var allowed=Set.of("phoneModelId","id","brandId","brandName","brandAliases","searchTexts","aliases","modelName","displayName","phoneModelCode","status","brandStatus","codeStatus","codeLinkStatus","productionStatus");
        rows.forEach(row->row.keySet().retainAll(allowed));
    }
    private void read() { access.requireDomesticView();access.requirePermission("collaboration:view"); }
    private CurrentUser write() {
        return access.requireEngineeringMaintenance();
    }
    private void validateCode(Long id) {
        if(id==null)return;
        jdbc.queryForList("select code_item_id from plm_code_item where code_item_id=? for share",id);
        var codes=jdbc.queryForList("select code_type,code_value,status,deleted_flag from plm_code_item where code_item_id=?",id);
        if(codes.isEmpty())throw invalid("原手机编码不存在");var code=codes.get(0);
        if(!"phone_model".equals(code.get("code_type"))||!Objects.toString(code.get("code_value"),"").matches("[0-9]{4}"))throw invalid("请选择原有四位手机编码，保留前导零");
        if(!"enabled".equals(code.get("status"))||!Objects.equals(code.get("deleted_flag"),0))throw invalid("新增关联的原手机编码必须启用");
    }
    private void ensureBrandUnique(String name,Long except) {
        if(jdbc.queryForObject("select count(*) from plm_phone_brand where lower(btrim(name))=lower(?) and (?::bigint is null or id<>?)",Long.class,name,except,except)>0)throw conflict("品牌名称已存在（忽略大小写及首尾空格）");
    }
    private void ensureBrandAliases(String name,List<String> names,Long except) {
        var requested=new HashSet<String>();requested.add(PhoneIdentityMatcher.normalize(name));names.forEach(n->requested.add(PhoneIdentityMatcher.normalize(n)));
        for(var brand:repository.allBrands())if(!Objects.equals(brand.get("brandId"),except)&&PhoneIdentityMatcher.brandNames(brand).stream().anyMatch(requested::contains))throw conflict("品牌名称或别名与其他品牌重复，请核对");
    }
    public List<Map<String,Object>> matchingBrands() {return repository.allBrands();}
    public List<Map<String,Object>> matchingPhones() {return PhoneProductEvidence.matchingPhones(jdbc,repository.allPhones(),repository.allBrands());}
    private void ensurePhoneUnique(Long brand,String model,Long except) {
        if(jdbc.queryForObject("select count(*) from plm_phone_model_member where brand_id=? and lower(btrim(model_name))=lower(?) and (?::bigint is null or id<>?)",Long.class,brand,model,except,except)>0)throw conflict("该品牌下手机型号名称已存在（忽略大小写及首尾空格）");
    }
    private Object normalize(String field,JsonNode input) {
        return switch(field) {
            case "name" -> name(text(input),128,"品牌");
            case "modelName" -> name(text(input),256,"手机型号");
            case "status" -> status(text(input));
            case "brandId" -> positive(input,false);
            case "codeItemId" -> positive(input,true);
            case "aliases" -> {if(input==null||input.isNull())yield List.of();if(!input.isArray())throw invalid("别名列表无效");yield aliases(json.convertValue(input,List.class),null);}
            case "prefixes" -> prefixes(input);
            default -> throw invalid("不可维护字段："+field);
        };
    }
    private List<Map<String,Object>> prefixes(JsonNode input) {
        if(input==null||input.isNull())return List.of();if(!input.isArray()||input.size()>30)throw invalid("品牌系列前缀最多30条");
        var keys=new HashSet<String>();var result=new ArrayList<Map<String,Object>>();
        for(var entry:input){if(!entry.isObject()||entry.size()!=2||!entry.path("prefix").isTextual()||!entry.path("replacement").isTextual())throw invalid("系列规则须包含前缀与替换文字");String prefix=name(entry.path("prefix").asText(),128,"系列前缀"),replacement=entry.path("replacement").asText().strip();if(replacement.length()>128||!PhoneIdentityMatcher.isSingleModel(prefix)||(!replacement.isBlank()&&!PhoneIdentityMatcher.isSingleModel(replacement)))throw invalid("系列前缀不能包含组合型号");if(!keys.add(PhoneIdentityMatcher.normalize(prefix)))throw invalid("系列前缀重复");result.add(Map.of("prefix",prefix,"replacement",replacement));}
        return List.copyOf(result);
    }
    private Long positive(JsonNode input,boolean nullable) {
        if((input==null||input.isNull())&&nullable)return null;
        if(input==null||!input.isIntegralNumber()||!input.canConvertToLong()||input.longValue()<1)throw invalid("主资料关联ID无效");return input.longValue();
    }
    private static String text(JsonNode input) {if(input==null||!input.isTextual())throw invalid("名称或状态必须为文字");return input.textValue();}
    private static String name(String input,int max,String field) {if(input==null||input.isBlank())throw invalid(field+"不能为空");String value=input.strip();if(value.length()>max)throw invalid(field+"过长");return value;}
    private static String status(String value) {String normal=name(value,16,"状态").toLowerCase(Locale.ROOT);if(!Set.of("enabled","disabled").contains(normal))throw invalid("状态无效");return normal;}
    private static List<String> aliases(List<?> input,String model) {
        if(input==null)return List.of();if(input.size()>30)throw invalid("别名最多30条");var normalized=new LinkedHashMap<String,String>();
        for(Object raw:input) {if(!(raw instanceof String))throw invalid("别名必须为文字");String value=name((String)raw,256,"别名"),key=value.toLowerCase(Locale.ROOT);if(model==null||!key.equals(model.toLowerCase(Locale.ROOT)))normalized.putIfAbsent(key,value);}
        return List.copyOf(normalized.values());
    }
    private static String array(List<String> values) {return "{"+values.stream().map(s->"\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"").reduce((a,b)->a+","+b).orElse("")+"}";}
    private static Long number(Object value) {return value==null?null:((Number)value).longValue();}
    private static String optional(String value) {return value==null||value.isBlank()?null:value.strip();}
    private void validateQuery(Query q) {if(q.getPage()<1||q.getSize()<1||q.getSize()>100)throw invalid("分页参数无效");if(q.getStatus()!=null&&!q.getStatus().isBlank())q.setStatus(status(q.getStatus()));}
    private Map<String,Object> require(Map<String,Object> row,String type) {if(row==null||Objects.equals(row.get("deletedFlag"),1))throw new BusinessException(40401,type+"不存在");return row;}
    private void log(CurrentUser actor,String kind,long id,String action,Object before,Object after,String reason,HttpServletRequest request) {
        var details=new LinkedHashMap<String,Object>();details.put("before",before);details.put("after",after);details.put("reason",reason);
        try {audit.logRequired(OperationLogCreateCommand.builder().action(action).businessType(kind.equals("brand")?"PHONE_BRAND":"PHONE_MODEL_MEMBER").businessId(String.valueOf(id)).businessName(kind.equals("brand")?"手机品牌":"标准手机型号").detailJson(json.writeValueAsString(details)).request(request).build(),actor);}
        catch(com.fasterxml.jackson.core.JsonProcessingException failure) {throw new IllegalStateException("主资料审计序列化失败",failure);}
    }
    private boolean sameValue(JsonNode saved,JsonNode original) {
        try {return json.readTree(json.writeValueAsString(saved)).equals(json.readTree(json.writeValueAsString(original)));}
        catch(com.fasterxml.jackson.core.JsonProcessingException failure) {throw new IllegalStateException("原值校验序列化失败",failure);}
    }
    private static BusinessException invalid(String message) {return new BusinessException(40001,message);}
    private static BusinessException conflict(String message) {return new BusinessException(40901,message);}
}

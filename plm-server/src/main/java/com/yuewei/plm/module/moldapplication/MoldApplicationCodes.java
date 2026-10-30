package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.*;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceRepository;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.moldapplication.MoldApplicationForms.*;

@Service
public class MoldApplicationCodes {
    private final MoldApplicationRepository repo;private final MoldApplicationProperties props;
    private final ProductBusinessCodeGenerator generator;private final ObjectMapper json;
    private final PhoneModelReferenceService references;
    @Autowired public MoldApplicationCodes(MoldApplicationRepository repo,MoldApplicationProperties props,ProductBusinessCodeGenerator generator,ObjectMapper json,PhoneModelReferenceService references) {this.repo=repo;this.props=props;this.generator=generator;this.json=json;this.references=references;}
    public MoldApplicationCodes(MoldApplicationRepository repo,MoldApplicationProperties props,ProductBusinessCodeGenerator generator,ObjectMapper json) {this(repo,props,generator,json,new PhoneModelReferenceService(repo.jdbc(),json,new PhoneModelReferenceRepository(repo.jdbc()),new PilotAccessService(repo.jdbc(),json),null));}
    PhoneModelReferenceService references() {return references;}
    public record Request(@com.fasterxml.jackson.annotation.JsonAlias("request_id") String requestId,String tipo,@com.fasterxml.jackson.annotation.JsonAlias("phone_model") String model,Long phoneCodeItemId,Long applicationId,
                          Long phoneModelId,Long itemId,Long moldSetId,String compatibilityDigest) {
        public Request(String requestId,String tipo,String model,Long phoneCodeItemId,Long applicationId) {this(requestId,tipo,model,phoneCodeItemId,applicationId,null,null,null,null);}
    }
    private record LegacyRequest(String requestId,String tipo,String model,Long phoneCodeItemId,Long applicationId) {}
    public Map<String,Object> config(String tipo) {
        var rows=repo.jdbc().queryForList("select tipo,product_line_id as \"productLineId\",product_specific_code as \"productSpecificCode\",material_codes::text as \"materialsJson\",version_no as version from plm_mold_application_config where tipo=? and active=true",tipo);
        if(rows.size()!=1)throw invalid("此 Tipo 尚未配置产品线和材质编码，请管理员核对配置");
        var result=rows.get(0);var materials=repo.node(result.remove("materialsJson"));
        for(var material:materials)requireMaterialPart(material.path("code").asText(),material.path("partType").asText());
        result.put("materials",materials);return result;
    }
    public Map<String,Object> researchMaterials(long project){
        Set<String> configured=null;
        for(var row:repo.jdbc().queryForList("select material_codes::text as materials from plm_mold_application_config where product_line_id=? and active=true",project)){
            var current=new TreeSet<String>();
            for(var material:repo.node(row.get("materials"))){String code=material.path("code").asText();try{requireMaterialPart(code,material.path("partType").asText());}catch(BusinessException invalid){return Map.of("materialCodes",List.of(),"conflict","原产品开模材质配置待核对");}current.add(code);}
            if(current.isEmpty()||configured!=null&&!configured.equals(current))return Map.of("materialCodes",List.of(),"conflict","原产品存在不一致的开模材质配置，请核对后自动续办");configured=current;
        }
        var products=repo.jdbc().queryForList("select material from plm_product where product_id=? and deleted_flag=0",project);var declared=new TreeSet<String>();
        if(products.size()==1&&text(products.get(0).get("material"))!=null){var tokens=text(products.get(0).get("material")).toUpperCase(Locale.ROOT).split("[\\s+＋/、,，;；|&-]+");if(Arrays.stream(tokens).allMatch(value->Set.of("PC","TPU").contains(value)))for(String value:tokens)declared.add("PC".equals(value)?"20":"10");}
        if(configured!=null&&!declared.isEmpty()&&!configured.equals(declared))return Map.of("materialCodes",List.of(),"conflict","原产品材质与开模材质配置冲突，请核对后自动续办");
        return Map.of("materialCodes",new ArrayList<>(configured==null?declared:configured),"conflict","");
    }
    public List<Map<String,Object>> configs(){return repo.jdbc().queryForList("select c.tipo,c.product_line_id as \"productLineId\",p.product_name as \"productName\",c.product_specific_code as \"productSpecificCode\",c.material_codes::text as \"materialsJson\",c.version_no as version from plm_mold_application_config c join plm_product p on p.product_id=c.product_line_id where c.active=true order by c.tipo").stream().peek(r->r.put("materials",repo.node(r.remove("materialsJson")))).toList();}
    void requireOnSaleProduct(Long line,String tipo) {
        var ids=new TreeSet<Long>();if(line!=null)ids.add(line);
        if(text(tipo)!=null)ids.addAll(repo.jdbc().queryForList("select product_line_id from plm_mold_application_config where tipo=? and active=true",Long.class,tipo));
        if(ids.isEmpty())return;
        new com.yuewei.plm.module.productionmetadata.ProductSalesPolicy(repo.jdbc()).requireMoldDevelopmentEligible(ids);
    }
    @Transactional
    public void saveConfig(String tipo,long line,String productCode,List<Map<String,String>> materials,Long version,String actor) {
        if(text(tipo)==null||tipo.length()>255||productCode==null||!productCode.matches("[A-Z]{2}"))throw invalid("Tipo 或产品特定编码无效");
        var rows=repo.jdbc().queryForList("select product_specific_code from plm_product where product_id=? and product_type='product_line' and deleted_flag=0",line);
        if(rows.size()!=1||!productCode.equals(text(rows.get(0).get("product_specific_code"))))throw invalid("产品特定编码须与所选产品线一致");
        if(materials==null||materials.isEmpty()||materials.size()>8)throw invalid("请配置材质编码和对应部件");
        Set<String> codes=new HashSet<>(),parts=new HashSet<>();
        for(var m:materials){if(m==null)throw invalid("材质配置无效");String code=m.get("code"),part=m.get("partType");requireMaterialPart(code,part);if(!codes.add(code)||!parts.add(part))throw invalid("材质须为两位编码，PC/TPU 部件不可重复");}
        repo.jdbc().queryForObject("select pg_advisory_xact_lock(610131101)",(rs,n)->true);
        var existing=repo.jdbc().queryForList("select version_no from plm_mold_application_config where tipo=? for update",tipo);
        if(!existing.isEmpty()&&(version==null||((Number)existing.get(0).get("version_no")).longValue()!=version))throw new BusinessException(40901,"配置已更新，请刷新后确认");
        repo.jdbc().update("""
            insert into plm_mold_application_config(tipo,product_line_id,product_specific_code,material_codes,updated_by)
            values(?,?,?,?::jsonb,?) on conflict(tipo) do update set product_line_id=excluded.product_line_id,
            product_specific_code=excluded.product_specific_code,material_codes=excluded.material_codes,version_no=plm_mold_application_config.version_no+1,
            updated_by=excluded.updated_by,updated_at=now()
            """,tipo,line,productCode,repo.json(materials),actor);
    }
    private static void requireMaterialPart(String code,String part) {
        if(part==null||!part.equals(ProductBusinessCodeGenerator.moldPartType(code)))throw invalid("材质编码须统一为 TPU=10、PC=20，请核对开模配置");
    }
    @Transactional
    public Map<String,Object> reserve(Request request,String actor) {return reserve(request,actor,null);}
    @Transactional
    public Map<String,Object> reserve(Request request,String actor,Long newSetItemId) {
        return reserveConfigured(request,actor,newSetItemId,config(request.tipo()),false);
    }
    @Transactional
    public Map<String,Object> reserveResearch(Request request,long project,List<String> materialCodes,String actor,Long newSetItemId) {
        if(request.phoneModelId()==null||request.phoneCodeItemId()==null)throw invalid("标准手机及原编码待补齐，不能自动分配新编号");
        references.requireSelectedPhone(request.phoneModelId(),request.phoneCodeItemId(),null,request.model());
        var products=repo.jdbc().queryForList("select product_specific_code from plm_product where product_id=? and product_type='product_line' and development_mode='launch_v1' and deleted_flag=0",project);
        if(products.size()!=1)throw invalid("原新品项目不存在");String prefix=text(products.get(0).get("product_specific_code"));
        if(prefix==null||!prefix.matches("[A-Z]{2}"))throw invalid("产品特定编码待补齐");
        if(materialCodes==null||materialCodes.isEmpty())throw invalid("请选择开模材质");
        List<Map<String,String>> materials=new ArrayList<>();for(String code:new TreeSet<>(materialCodes)){String part=ProductBusinessCodeGenerator.moldPartType(code);requireMaterialPart(code,part);materials.add(Map.of("code",code,"partType",part));}
        return reserveConfigured(request,actor,newSetItemId,Map.of("productLineId",project,"productSpecificCode",prefix,"materials",json.valueToTree(materials)),true);
    }
    private Map<String,Object> reserveConfigured(Request request,String actor,Long newSetItemId,Map<String,Object> config,boolean research) {
        if(request.requestId()==null||!request.requestId().matches("[A-Za-z0-9:_-]{8,128}"))throw invalid("请提供有效请求ID");
        if(text(request.model())==null||request.model().length()>255)throw invalid("请填写型号");
        repo.jdbc().execute("select pg_advisory_xact_lock(66817399)");
        repo.jdbc().queryForObject("select pg_advisory_xact_lock(610131102)",(rs,n)->true);
        boolean reuse=request.itemId()!=null||request.moldSetId()!=null||request.compatibilityDigest()!=null;
        Object fingerprint=reuse||request.phoneModelId()!=null?request:new LegacyRequest(request.requestId(),request.tipo(),request.model(),request.phoneCodeItemId(),request.applicationId());
        if(newSetItemId!=null)fingerprint=Map.of("request",fingerprint,"newSetItemId",newSetItemId);
        if(research)fingerprint=Map.of("request",fingerprint,"config",config);
        String hash=MoldApplicationMedia.hash(repo.json(fingerprint).getBytes(StandardCharsets.UTF_8));
        requireOnSaleProduct(((Number)config.get("productLineId")).longValue(),null);
        var prior=repo.jdbc().queryForList("select request_hash,response::text response from plm_mold_code_reservation where request_id=?",request.requestId());
        if(!prior.isEmpty()) {
            if(!hash.equals(prior.get(0).get("request_hash")))throw new BusinessException(40901,"请求ID已对应另一份编码申请");
            return json.convertValue(repo.node(prior.get(0).get("response")),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        }
        Long phoneId=request.phoneCodeItemId();Map<String,Object> phone;
        Map<String,Object> selectedPhone=null;
        if(request.phoneModelId()!=null){if(phoneId==null)throw invalid("标准手机原编码待核对，不能自动分配新编号");selectedPhone=references.requireSelectedPhone(request.phoneModelId(),phoneId,null,request.model());}
        if(reuse) {
            if(request.phoneModelId()==null||request.itemId()==null||request.moldSetId()==null||phoneId==null)throw invalid("请明确选择标准手机、共模档案和完整实物套");
            var match=references.requireCompatibility(((Number)config.get("productLineId")).longValue(),request.phoneModelId(),request.itemId(),request.moldSetId(),request.compatibilityDigest());
            if(((Number)match.get("codeItemId")).longValue()!=phoneId)throw invalid("所选手机原编码记录与共模成员不一致，不能替换原刻码");
            var parts=(JsonNode)match.get("parts");var originals=new ArrayList<Map<String,Object>>();
            for(var part:parts) {
                String code=part.path("code").asText();String partType=part.path("partType").asText();
                boolean configured=false;for(var material:(JsonNode)config.get("materials"))if(material.path("partType").asText().equals(partType)&&material.path("code").asText().equals(code.substring(3,5)))configured=true;
                if(!code.substring(1,3).equals(config.get("productSpecificCode"))||!configured)throw invalid("原PC/TPU刻码与申请款式材质配置不一致，不能复用");
                originals.add(Map.of("code",code,"materialCode",code.substring(3,5),"partType",partType));
            }
            var result=new LinkedHashMap<String,Object>();result.put("phoneCodeItemId",phoneId);result.put("phoneModelCode",match.get("phoneModelCode"));result.put("productLineId",config.get("productLineId"));result.put("productSpecificCode",config.get("productSpecificCode"));result.put("moldCodes",originals);result.put("generatedCodes",String.join(" / ",originals.stream().map(part->part.get("code").toString()).toList()));result.put("reuseExisting",true);result.put("compatibility",match);
            saveReservation(request,hash,phoneId,result,actor);return result;
        }
        if(phoneId==null) {
            var matches=repo.jdbc().queryForList("select code_item_id,code_value,code_name,status,deleted_flag from plm_code_item c where code_type='phone_model' and (lower(regexp_replace(btrim(code_name),'\\s+',' ','g'))=lower(regexp_replace(btrim(?),'\\s+',' ','g')) or exists(select 1 from plm_mold_model_alias a where lower(regexp_replace(a.normalized_name,'\\s+',' ','g'))=lower(regexp_replace(btrim(?),'\\s+',' ','g')) and a.phone_code=c.code_value))",request.model(),request.model());
            if(matches.size()>1)throw invalid("型号名称匹配多个编码，请先核对主数据");
            if(matches.size()==1){phone=matches.get(0);if(((Number)phone.get("deleted_flag")).intValue()!=0)throw invalid("原手机编码已删除，请先人工核对，不另分新编号");}
            else {
                if(!props.isCentralCodeEnabled())throw invalid("中央编码服务尚未启用，本地须选择已有手机型号编码");
                if(repo.jdbc().queryForObject("select count(*) from plm_mold_model_alias where lower(regexp_replace(normalized_name,'\\s+',' ','g'))=lower(regexp_replace(btrim(?),'\\s+',' ','g'))",Integer.class,request.model())>0)throw invalid("服务器已有此型号，但本地主数据存在冲突，请管理员关联；不可另分新编号");
                Integer highest=repo.jdbc().queryForObject("select greatest(last_value,(select coalesce(max(code_value::integer),0) from plm_code_item where code_type='phone_model' and code_value~'^[0-9]{4}$'),(select coalesce(max(phone_code::integer),0) from plm_mold_model_alias)) from plm_mold_code_sequence where sequence_key='phone_model' for update",Integer.class);
                if(highest==null||highest>=9999)throw invalid("四位手机型号编码已用尽");
                Integer next=highest+1;repo.jdbc().update("update plm_mold_code_sequence set last_value=? where sequence_key='phone_model'",next);
                String value=String.format("%04d",next);
                phoneId=repo.jdbc().queryForObject("insert into plm_code_item(code_type,code_value,code_name,status,sort_order,created_by,updated_by) values('phone_model',?,?,'enabled',0,?,?) returning code_item_id",Long.class,value,request.model().trim().replaceAll("\\s+"," "),actor,actor);
                phone=Map.of("code_item_id",phoneId,"code_value",value,"status","enabled");
            }
        } else {
            var matches=repo.jdbc().queryForList("select code_item_id,code_value,code_name,status from plm_code_item where code_item_id=? and code_type='phone_model' and deleted_flag=0",phoneId);
            if(matches.size()!=1)throw invalid("请选择有效手机型号编码");phone=matches.get(0);
        }
        if(!"enabled".equals(phone.get("status")))throw invalid("手机型号编码已停用");
        phoneId=((Number)phone.get("code_item_id")).longValue();String phoneCode=phone.get("code_value").toString();
        if(!phoneCode.matches("\\d{4}"))throw invalid("手机型号编码必须为四位数字");
        Map<String,Object> newSetMaster=null;String moldCodePhoneSegment=phoneCode;
        if(newSetItemId!=null){
            var master=repo.jdbc().queryForList("select item_id as \"itemId\",product_line_id as \"productLineId\",coalesce(nullif(engraved_code,''),phone_model_code) as \"engravedCode\" from plm_collaboration_item where item_id=? and deleted_flag=0",newSetItemId);
            if(master.size()!=1||!Objects.equals(master.get(0).get("productLineId"),config.get("productLineId")))throw invalid("加开主档与产品线配置不一致");
            moldCodePhoneSegment=Objects.toString(master.get(0).get("engravedCode"),"");if(!moldCodePhoneSegment.matches("[0-9]{4}"))throw invalid("已有主档原刻码待核对，请先完善原刻码再加开");newSetMaster=master.get(0);
        }
        List<Map<String,Object>> generated=new ArrayList<>();for(var material:(JsonNode)config.get("materials")) {
            String value=generator.generateMoldCode(config.get("productSpecificCode").toString(),material.path("code").asText(),moldCodePhoneSegment);
            generated.add(Map.of("code",value,"materialCode",material.path("code").asText(),"partType",material.path("partType").asText()));
        }
        var result=new LinkedHashMap<String,Object>();result.put("phoneCodeItemId",phoneId);result.put("phoneModelCode",phoneCode);result.put("productLineId",config.get("productLineId"));result.put("productSpecificCode",config.get("productSpecificCode"));result.put("moldCodes",generated);result.put("generatedCodes",String.join(" / ",generated.stream().map(g->g.get("code").toString()).toList()));if(selectedPhone!=null){result.put("phoneModelId",request.phoneModelId());result.put("phoneSnapshot",selectedPhone);}
        if(newSetMaster!=null)result.put("newSetMaster",newSetMaster);saveReservation(request,hash,phoneId,result,actor);
        return result;
    }
    private void saveReservation(Request request,String hash,Long phoneId,Map<String,Object> result,String actor) {repo.jdbc().update("insert into plm_mold_code_reservation(request_id,request_hash,application_id,phone_code_item_id,response,created_by) values(?,?,?,?,?::jsonb,?)",request.requestId(),hash,request.applicationId(),phoneId,repo.json(result),actor);}
    public Map<String,Object> reservedCompatibility(long applicationId,String generatedCodes) {
        var response=reservedSelection(applicationId,generatedCodes);Object value=response.get("compatibility");return value instanceof Map<?,?>?json.convertValue(value,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}):null;
    }
    public Map<String,Object> reservedNewSet(long applicationId,String generatedCodes){var result=reservedSelection(applicationId,generatedCodes).get("newSetMaster");return result instanceof Map<?,?>?json.convertValue(result,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}):null;}
    public Long reservedPhoneModelId(long applicationId,String generatedCodes) {
        var response=reservedSelection(applicationId,generatedCodes);Object value=response.get("phoneModelId");if(value==null&&response.get("compatibility") instanceof Map<?,?> compatible)value=compatible.get("phoneModelId");return value==null?null:((Number)value).longValue();
    }
    private Map<String,Object> reservedSelection(long applicationId,String generatedCodes) {var rows=repo.jdbc().queryForList("select response::text as response from plm_mold_code_reservation where application_id=? and response->>'generatedCodes'=? order by created_at desc,request_id desc limit 1",applicationId,generatedCodes);return rows.isEmpty()?Map.of():json.convertValue(repo.node(rows.get(0).get("response")),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}
}

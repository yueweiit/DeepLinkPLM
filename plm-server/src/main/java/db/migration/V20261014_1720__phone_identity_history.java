package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.moldreference.PhoneIdentityHistory;
import com.yuewei.plm.module.moldreference.PhoneIdentityMatcher;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceRepository;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Existing immutable code anchors become standard IDs; this never creates stock, molds or fit claims. */
public class V20261014_1720__phone_identity_history extends BaseJavaMigration {
    private static final String ACTOR="phone-history-migration";
    @Override public void migrate(Context context) throws Exception {
        migrate(new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(),true)));
    }
    /** Exposed for rollback integration checks against the same production migration. */
    @SuppressWarnings("unchecked") public Map<String,Object> migrate(JdbcTemplate jdbc) throws Exception {
        jdbc.execute("select pg_advisory_xact_lock(66817394)");
        jdbc.execute("lock table plm_phone_brand,plm_phone_model_member in share row exclusive mode");
        jdbc.execute("lock table plm_code_item in share mode");
        var json=new ObjectMapper();var repository=new PhoneModelReferenceRepository(jdbc);
        var originalBrands=repository.allBrands();
        for(var brand:originalBrands)if(brand.get("sourceSnapshot") instanceof String value)brand.put("sourceSnapshot",json.readValue(value,Map.class));
        var brands=PhoneIdentityHistory.withVerifiedSourceAliases(originalBrands);var aliasChanges=new ArrayList<Map<String,Object>>();
        for(var brand:brands) {
            var original=originalBrands.stream().filter(b->Objects.equals(b.get("brandId"),brand.get("brandId"))).findFirst().orElseThrow();
            if(!Objects.equals(original.get("aliases"),brand.get("aliases"))) {
                jdbc.update("update plm_phone_brand set aliases=?::text[],updated_by=?,updated_at=now() where id=?",array((List<String>)brand.get("aliases")),ACTOR,brand.get("brandId"));
                aliasChanges.add(Map.of("brandId",brand.get("brandId"),"originalAliases",original.get("aliases"),"aliases",brand.get("aliases"),"sourceRecordId",brand.get("sourceRecordId")));
            }
        }
        var phones=repository.allPhones();
        var codes=jdbc.queryForList("select code_item_id as \"codeItemId\",code_value as \"codeValue\",code_name as \"codeName\",status from plm_code_item where code_type='phone_model' and deleted_flag=0 order by code_item_id");
        var preview=PhoneIdentityHistory.preview(brands,phones,codes);var created=new ArrayList<Map<String,Object>>();
        for(var decision:preview.decisions())if(decision.action().equals("initialize")) {
            var snapshot=Map.of("migration","20261014.1720","codeItemId",decision.codeItemId(),"codeValue",decision.codeValue(),"originalName",decision.originalName(),"identityKey",decision.identityKey(),"reason","唯一启用原编码与完整型号；未推断兼容实物");
            long id=jdbc.queryForObject("insert into plm_phone_model_member(brand_id,model_name,aliases,code_item_id,source_snapshot,created_by,updated_by) values(?,?,?::text[],?,?::jsonb,?,?) returning id",Long.class,decision.brandId(),decision.originalName(),array(List.of()),decision.codeItemId(),json.writeValueAsString(snapshot),ACTOR,ACTOR);
            created.add(Map.of("phoneModelId",id,"codeItemId",decision.codeItemId(),"codeValue",decision.codeValue(),"modelName",decision.originalName()));
        }
        phones=repository.allPhones();Map<String,Object> outcome=new LinkedHashMap<>();outcome.put("preview",preview);outcome.put("brandAliases",aliasChanges);outcome.put("created",created);
        var projects=new ArrayList<Map<String,Object>>();var retiredCodes=new HashSet<>(jdbc.queryForList("select code_value from plm_code_item where code_type='phone_model' and deleted_flag<>0",String.class));
        for(var project:jdbc.queryForList("select product_id,model,phone_model_code from plm_product where deleted_flag=0 and product_type='model_variant' and phone_model_member_id is null and phone_model_code is not null and model is not null")) {
            String originalCode=Objects.toString(project.get("phone_model_code"));Long phone=retiredCodes.contains(originalCode.strip())?null:uniqueOriginalPhone(originalCode,Objects.toString(project.get("model")),brands,phones);
            if(phone!=null) {jdbc.update("update plm_product set phone_model_member_id=? where product_id=? and phone_model_member_id is null",phone,project.get("product_id"));projects.add(Map.of("projectId",project.get("product_id"),"phoneModelId",phone,"originalModel",project.get("model"),"originalCode",project.get("phone_model_code")));}
        }
        var applications=new ArrayList<Map<String,Object>>();
        for(var application:jdbc.queryForList("select a.application_id,a.model,a.phone_code_item_id,c.code_value,a.generated_codes from plm_mold_application a left join plm_code_item c on c.code_item_id=a.phone_code_item_id and c.code_type='phone_model' and c.deleted_flag=0 where a.deleted_flag=0 and a.phone_model_id is null and a.model is not null")) {
            String code=Objects.toString(application.get("code_value"),"");Long explicitCode=application.get("phone_code_item_id")==null?null:((Number)application.get("phone_code_item_id")).longValue();
            if(explicitCode!=null&&code.isBlank())continue;
            if(code.isBlank())code=uniqueFormalPhoneCode(Objects.toString(application.get("generated_codes"),""));
            Long phone=code==null||(explicitCode==null&&retiredCodes.contains(code))?null:uniqueOriginalPhone(code,Objects.toString(application.get("model")),brands,phones);
            if(phone!=null&&explicitCode!=null) {long selected=phone;var member=phones.stream().filter(p->((Number)p.get("phoneModelId")).longValue()==selected).findFirst().orElseThrow();if(member.get("codeItemId")==null||((Number)member.get("codeItemId")).longValue()!=explicitCode)phone=null;}
            if(phone!=null) {jdbc.update("update plm_mold_application set phone_model_id=? where application_id=? and phone_model_id is null",phone,application.get("application_id"));applications.add(Map.of("applicationId",application.get("application_id"),"phoneModelId",phone,"originalModel",application.get("model"),"originalCode",code));}
        }
        outcome.put("projectLinks",projects);outcome.put("applicationLinks",applications);
        // A system migration has no human operator identity. Preserve its decisions in the existing audit store.
        var columns=new ArrayList<String>(List.of("request_id","operator_user_name","business_type","business_id","business_name","action","detail_json","created_by","updated_by"));
        var values=new ArrayList<Object>(List.of("phone-history-20261014-1720",ACTOR,"PHONE_IDENTITY_HISTORY","20261014.1720","原手机编码历史补链","INITIALISE",json.writeValueAsString(outcome),ACTOR,ACTOR));
        if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=current_schema() and table_name='plm_operation_log' and column_name='operation_log_id'",Long.class)>0){columns.add("operation_log_id");values.add(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());}
        jdbc.update("insert into plm_operation_log("+String.join(",",columns)+") values("+String.join(",",Collections.nCopies(values.size(),"?"))+")",values.toArray());
        return outcome;
    }
    @SuppressWarnings("unchecked") private static Long uniqueOriginalPhone(String code,String model,List<Map<String,Object>> brands,List<Map<String,Object>> phones) {
        String codeValue=code.strip();if(!codeValue.matches("[0-9]{4}")||!PhoneIdentityMatcher.isSingleModel(model))return null;
        var matches=phones.stream().filter(p->codeValue.equals(p.get("phoneModelCode"))&&"enabled".equals(p.get("status"))&&"linked".equals(p.get("codeLinkStatus")))
            .filter(p->{var brand=brands.stream().filter(b->Objects.equals(b.get("brandId"),p.get("brandId"))).findFirst().orElseThrow();var names=new ArrayList<String>();names.add(Objects.toString(p.get("modelName")));names.addAll((List<String>)p.get("aliases"));String key=PhoneIdentityMatcher.modelKey(model,brand);return names.stream().filter(PhoneIdentityMatcher::isSingleModel).anyMatch(n->PhoneIdentityMatcher.modelKey(n,brand).equals(key));}).toList();
        return matches.size()==1?((Number)matches.get(0).get("phoneModelId")).longValue():null;
    }
    private static String uniqueFormalPhoneCode(String codes) {
        if(!com.yuewei.plm.module.moldapplication.MoldApplicationForms.hasValidCodes(codes))return null;
        var values=new HashSet<String>();for(String code:com.yuewei.plm.module.moldapplication.MoldApplicationForms.codes(codes))values.add(code.substring(5));return values.size()==1?values.iterator().next():null;
    }
    private static String array(List<String> values) {return "{"+String.join(",",values.stream().map(s->"\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"").toList())+"}";}
}

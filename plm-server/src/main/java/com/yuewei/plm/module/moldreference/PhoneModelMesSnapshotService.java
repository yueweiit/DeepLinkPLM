package com.yuewei.plm.module.moldreference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
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
import static com.yuewei.plm.module.moldreference.PhoneModelReferenceTypes.*;

/** No public upload/controller. Only a trusted local bootstrap supplies read-only MES responses. */
@Service @RequiredArgsConstructor
public class PhoneModelMesSnapshotService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PhoneModelReferenceService references;
    private final PilotAccessService access;
    private final OperationLogService audit;
    public record Snapshot(String sourceUrl,List<JsonNode> brands,List<JsonNode> phones) {}
    public record Confirmation(String previewDigest,List<Long> brandSourceIds,List<Long> phoneSourceIds,
                               Map<Long,String> brandDisplayNames,String reason) {}

    public Map<String,Object> preview(Snapshot snapshot,Map<Long,String> brandDisplayNames) {
        access.requireAdministrator();return analyse(snapshot,brandDisplayNames==null?Map.of():brandDisplayNames);
    }
    @Transactional public Map<String,Object> confirm(Snapshot snapshot,Confirmation confirmation,HttpServletRequest request) {
        access.requireAdministrator();if(confirmation.reason()==null||confirmation.reason().isBlank())throw invalid("初始化确认须说明核对依据");
        jdbc.execute("select pg_advisory_xact_lock(66817394)");
        var names=confirmation.brandDisplayNames()==null?Map.<Long,String>of():confirmation.brandDisplayNames();var preview=analyse(snapshot,names);
        if(!Objects.equals(confirmation.previewDigest(),preview.get("previewDigest")))throw new BusinessException(40901,"主数据已变化，请重新预览核对");
        var selectedBrands=ids(confirmation.brandSourceIds());var selectedPhones=ids(confirmation.phoneSourceIds());
        var brandRows=(List<Map<String,Object>>)preview.get("brands");var phoneRows=(List<Map<String,Object>>)preview.get("phones");
        validateSelected(brandRows,selectedBrands);validateSelected(phoneRows,selectedPhones);
        var brandTargets=new LinkedHashMap<Long,Long>();var phoneTargets=new LinkedHashMap<Long,Long>();
        for(var row:brandRows) {
            long source=number(row.get("sourceId"));if(row.get("existingId")!=null)brandTargets.put(source,number(row.get("existingId")));
            else if(selectedBrands.contains(source)) {
                var brand=references.createBrand(new CreateBrand(row.get("displayName").toString()),request);long target=number(brand.get("id"));
                attach("plm_phone_brand",target,source,(JsonNode)row.get("snapshot"),confirmation.reason(),request);brandTargets.put(source,target);
            }
        }
        for(var row:phoneRows)if(selectedPhones.contains(number(row.get("sourceId")))) {
            long source=number(row.get("sourceId"));
            if(row.get("existingId")!=null) {phoneTargets.put(source,number(row.get("existingId")));continue;}
            Long brand=brandTargets.get(number(row.get("brandSourceId")));if(brand==null)throw invalid("所选型号的品牌尚未确认初始化");
            var raw=(JsonNode)row.get("snapshot");String shortCode=text(raw,"model_short_code");
            var phone=references.createPhone(new CreatePhone(brand,text(raw,"model_name"),shortCode==null?List.of():List.of(shortCode),number(row.get("codeItemId"))),request);
            long target=number(phone.get("id"));attach("plm_phone_model_member",target,source,raw,confirmation.reason(),request);phoneTargets.put(source,target);
        }
        var result=Map.of("brandTargets",brandTargets,"phoneTargets",phoneTargets,"sourceDigest",preview.get("sourceDigest"),"previewDigest",preview.get("previewDigest"));
        var detail=new LinkedHashMap<String,Object>();detail.putAll(result);detail.put("reason",confirmation.reason().strip());detail.put("sourceUrl",snapshot.sourceUrl());
        audit.logRequired(OperationLogCreateCommand.builder().action("INITIALISE").businessType("PHONE_REFERENCE_SOURCE").businessId(preview.get("sourceDigest").toString()).businessName("MES核对初始化").detailJson(encode(detail)).request(request).build(),access.actor());
        return result;
    }
    private Map<String,Object> analyse(Snapshot s,Map<Long,String> displayNames) {
        if(s==null||!"https://lemos-case.com/mes/master-data/phone-model-codes".equals(s.sourceUrl())||s.brands()==null||s.phones()==null||s.brands().size()>500||s.phones().size()>10000)throw invalid("仅接收已核对的MES只读主数据快照");
        ensureSourceIds(s.brands());ensureSourceIds(s.phones());
        var brandByCode=new LinkedHashMap<String,List<JsonNode>>();for(var raw:s.brands())brandByCode.computeIfAbsent(Objects.toString(text(raw,"brand_code"),""),key->new ArrayList<>()).add(raw);
        var brandRows=new ArrayList<Map<String,Object>>();var phoneRows=new ArrayList<Map<String,Object>>();
        for(var raw:s.brands()) {
            var row=row(raw);long source=number(row.get("sourceId"));String label=displayNames.getOrDefault(source,first(raw,"brand_name_en","brand_name_es","brand_name_cn"));row.put("displayName",label);
            var existing=existing("plm_phone_brand",source);boolean exact=existing!=null&&Objects.equals(existing.get("source_snapshot"),canonical(raw));
            if(existing!=null) {row.put("existingId",existing.get("id"));row.put("state",exact?"existing":"review");if(!exact)row.put("issue","来源品牌已变化，不覆盖本地维护值");}
            else if(!"enabled".equals(text(raw,"status")))review(row,"来源品牌未启用，留待人工核对");
            else if(label==null||label.isBlank()||brandByCode.get(Objects.toString(text(raw,"brand_code"),"")).size()!=1)review(row,"品牌名称或来源缩写不明确");
            else if(jdbc.queryForObject("select count(*) from plm_phone_brand where lower(btrim(name))=lower(?)",Long.class,label.strip())>0)review(row,"同名本地品牌已存在，需人工关联");
            else row.put("state","ready");brandRows.add(row);
        }
        for(var raw:s.phones()) {
            var row=row(raw);long source=number(row.get("sourceId"));String name=text(raw,"model_name"),code=text(raw,"model_code"),brandCode=text(raw,"brand_code");
            var existing=existing("plm_phone_model_member",source);
            if(existing!=null) {row.put("existingId",existing.get("id"));if(Objects.equals(existing.get("source_snapshot"),canonical(raw)))row.put("state","existing");else review(row,"来源型号已变化，不覆盖本地维护值");phoneRows.add(row);continue;}
            if(!"enabled".equals(text(raw,"status")))review(row,"来源型号未启用，留待人工核对");
            else if(name==null||name.isBlank()||name.contains("/")||name.contains("\\")||name.contains(";")||name.contains("|"))review(row,"组合或异常型号保留原文，需人工核对成员");
            else if(brandCode==null||brandByCode.getOrDefault(brandCode,List.of()).size()!=1)review(row,"来源品牌未明确，不从型号文字猜测品牌");
            else if(code==null||!code.matches("[0-9]{4}"))review(row,"原四位编码不明确，不自动分配");
            else {
                long brandSource=sourceId(brandByCode.get(brandCode).get(0));row.put("brandSourceId",brandSource);row.put("normalizedName",name.strip().toLowerCase(Locale.ROOT));
                var brand=brandRows.stream().filter(b->number(b.get("sourceId"))==brandSource).findFirst().orElseThrow();
                var codes=jdbc.queryForList("select code_item_id from plm_code_item where code_type='phone_model' and code_value=? and lower(btrim(code_name))=lower(?) and status='enabled' and deleted_flag=0",code,name.strip());
                if(codes.size()!=1)review(row,"原编码与名称未精确匹配唯一启用注册记录");
                else if("review".equals(brand.get("state")))review(row,"所属品牌需先核对");
                else if(phoneRows.stream().anyMatch(p->Objects.equals(p.get("brandSourceId"),brandSource)&&Objects.equals(p.get("normalizedName"),name.strip().toLowerCase(Locale.ROOT))))review(row,"同品牌同名存在重复来源行，不自动合并");
                else {row.put("codeItemId",codes.get(0).get("code_item_id"));
                    if(brand.get("existingId")!=null&&jdbc.queryForObject("select count(*) from plm_phone_model_member where brand_id=? and lower(btrim(model_name))=lower(?)",Long.class,brand.get("existingId"),name.strip())>0)review(row,"同名本地手机已存在，需人工关联来源");else row.put("state","ready");}
            }
            phoneRows.add(row);
        }
        // Duplicate identities are all reviewed, not first-row-wins.
        for(var row:brandRows)if(row.get("displayName")!=null&&brandRows.stream().filter(p->p.get("displayName")!=null&&p.get("displayName").toString().strip().equalsIgnoreCase(row.get("displayName").toString().strip())).count()>1)review(row,"多个来源品牌同名，不自动合并");
        for(var row:phoneRows)if(row.get("brandSourceId")!=null&&brandRows.stream().anyMatch(b->Objects.equals(b.get("sourceId"),row.get("brandSourceId"))&&"review".equals(b.get("state"))))review(row,"所属品牌需先核对");
        for(var row:phoneRows)if(row.get("normalizedName")!=null&&phoneRows.stream().filter(p->Objects.equals(p.get("brandSourceId"),row.get("brandSourceId"))&&Objects.equals(p.get("normalizedName"),row.get("normalizedName"))).count()>1)review(row,"同品牌同名存在重复来源行，不自动合并");
        var result=new LinkedHashMap<String,Object>();result.put("brands",brandRows);result.put("phones",phoneRows);result.put("sourceDigest",digest(encode(s)));result.put("previewDigest",digest(encode(result)));return result;
    }
    private Map<String,Object> existing(String table,long source) {
        var rows=jdbc.queryForList("select id,source_snapshot::text as source_snapshot from "+table+" where source_system='mes' and source_record_id=?",String.valueOf(source));
        if(rows.isEmpty())return null;var row=rows.get(0);try {row.put("source_snapshot",json.readTree(row.get("source_snapshot").toString()));}catch(Exception e){throw invalid("既有来源快照无效");}return row;
    }
    private void attach(String table,long target,long source,JsonNode raw,String reason,HttpServletRequest request) {
        jdbc.update("update "+table+" set source_system='mes',source_record_id=?,source_snapshot=?::jsonb where id=?",String.valueOf(source),encode(raw),target);
        var before=new LinkedHashMap<String,Object>();before.put("sourceSystem","plm");before.put("sourceRecordId",null);
        var detail=Map.of("before",before,"after",Map.of("sourceSystem","mes","sourceRecordId",String.valueOf(source),"sourceSnapshot",raw),"reason",reason);
        audit.logRequired(OperationLogCreateCommand.builder().action("LINK_SOURCE").businessType(table.equals("plm_phone_brand")?"PHONE_BRAND":"PHONE_MODEL_MEMBER").businessId(String.valueOf(target)).businessName("MES核对来源").detailJson(encode(detail)).request(request).build(),access.actor());
    }
    private Map<String,Object> row(JsonNode raw) {var row=new LinkedHashMap<String,Object>();row.put("sourceId",sourceId(raw));row.put("snapshot",raw);return row;}
    private static void review(Map<String,Object> row,String issue) {row.put("state","review");row.put("issue",issue);}
    private static void ensureSourceIds(List<JsonNode> rows) {var ids=new HashSet<Long>();for(var row:rows)if(!ids.add(sourceId(row)))throw invalid("快照重复来源ID，请先去重同一GET结果");}
    private static long sourceId(JsonNode raw) {var id=raw.get("id");if(id==null||!id.isIntegralNumber()||id.longValue()<1)throw invalid("来源行缺少稳定真实ID");var resource=raw.get("resource_id");if(resource!=null&&!resource.isNull()&&(!resource.isIntegralNumber()||resource.longValue()!=id.longValue()))throw invalid("来源ID与资源ID矛盾");return id.longValue();}
    private static long number(Object id) {return ((Number)id).longValue();}
    private static String text(JsonNode raw,String key) {var value=raw.get(key);return value==null||!value.isTextual()||value.textValue().isBlank()?null:value.textValue().strip();}
    private static String first(JsonNode raw,String... keys) {for(String key:keys){String value=text(raw,key);if(value!=null)return value;}return null;}
    private static Set<Long> ids(List<Long> values) {if(values==null)return Set.of();if(values.stream().anyMatch(id->id==null||id<1))throw invalid("确认来源ID无效");return new LinkedHashSet<>(values);}
    private static void validateSelected(List<Map<String,Object>> rows,Set<Long> selected) {for(Long id:selected){var row=rows.stream().filter(r->number(r.get("sourceId"))==id).findFirst().orElseThrow(()->invalid("所选来源记录不存在"));if("review".equals(row.get("state")))throw invalid("所选来源记录仍待人工核对");}}
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("来源快照序列化失败",e);}}
    private JsonNode canonical(JsonNode raw) {try{return json.readTree(encode(raw));}catch(Exception e){throw new IllegalStateException("来源快照转换失败",e);}}
    private static String digest(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static BusinessException invalid(String message) {return new BusinessException(40001,message);}
}

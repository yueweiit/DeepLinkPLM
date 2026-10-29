package com.yuewei.plm.module.moldreference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.code.dto.CodeItemSaveDTO;
import com.yuewei.plm.module.code.dto.PhoneModelReviewConfirmDTO;
import com.yuewei.plm.module.code.dto.PhoneModelReviewQueryDTO;
import com.yuewei.plm.module.code.service.CodeItemService;
import com.yuewei.plm.module.code.service.PhoneModelReviewService;
import com.yuewei.plm.module.code.vo.PhoneModelReviewVO;
import com.yuewei.plm.module.operationlog.service.*;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.productionmetadata.PhoneCodeMetadataRepository;
import com.yuewei.plm.module.productionmetadata.ProductionMetadataService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.moldreference.PhoneModelReferenceTypes.Patch;

/** A live projection over existing reference data and required audit; not another identity ledger. */
@Service @RequiredArgsConstructor
public class PhoneIdentityIssuesService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PhoneModelReferenceRepository repository;
    private final PhoneModelReferenceService references;
    private final PhoneCodeMetadataRepository metadataRepository;
    private final ProductionMetadataService metadata;
    private final CodeItemService codes;
    private final PhoneModelReviewService history;
    private final PilotAccessService access;
    private final OperationLogService audit;

    @Data public static class Query {
        @Min(1) private int page=1;
        @Min(1) @Max(100) private int size=50;
        @Size(max=256) private String keyword;
        @Pattern(regexp="pending|ready|resolved|not_phone|all") private String state="pending";
    }
    public record Selection(@NotBlank @Size(max=384) String key,@NotBlank @Pattern(regexp="[0-9a-f]{32}|[0-9a-f]{64}") String sourceDigest) {}
    public record Resolve(@NotEmpty @Size(max=1000) List<@Valid Selection> items,@NotBlank @Size(max=1000) String reason) {}
    public record CodeEdit(@Positive long codeItemId,@NotBlank String sourceDigest,@NotBlank @Size(max=128) String codeName,
                           @NotNull @Size(max=100) List<@NotNull @Positive Long> brandIds,@NotBlank @Size(max=1000) String reason) {}
    public record BrandMerge(@Positive long sourceBrandId,@Positive long targetBrandId,String sourceDigest,@Size(max=1000) String reason) {}
    private record Snapshot(List<Map<String,Object>> brands,List<Map<String,Object>> phones,List<Map<String,Object>> codes,Map<Long,List<Long>> effective,Map<Long,String> productLines) {}

    @Transactional(readOnly=true) public Map<String,Object> page(Query query) {
        read();if(query.getPage()<1||query.getSize()<1||query.getSize()>100||!Set.of("pending","ready","resolved","not_phone","all").contains(query.getState()))throw invalid("资料问题筛选无效");
        String keyword=Objects.toString(query.getKeyword(),"").strip().toLowerCase(Locale.ROOT);
        var all=issues(snapshot()).stream().map(this::publicIssue).filter(r->keyword.isBlank()||encode(r).toLowerCase(Locale.ROOT).contains(keyword)).toList();
        var summary=new LinkedHashMap<String,Long>();for(String state:List.of("pending","ready","resolved","not_phone"))summary.put(state,all.stream().filter(r->state.equals(r.get("state"))).count());
        summary.put("needsAction",summary.get("pending")+summary.get("ready"));
        var filtered=all.stream().filter(r->switch(query.getState()){case "all"->true;case "pending"->Set.of("pending","ready").contains(r.get("state"));default->query.getState().equals(r.get("state"));}).toList();
        long offset=(query.getPage()-1L)*query.getSize();var rows=filtered.stream().skip(offset).limit(query.getSize()).toList();
        var ready=all.stream().filter(r->"ready".equals(r.get("state"))&&Boolean.TRUE.equals(r.get("canResolve"))).map(r->Map.of("key",r.get("key"),"sourceDigest",r.get("sourceDigest"))).toList();
        return Map.of("content",rows,"page",query.getPage(),"size",query.getSize(),"totalElements",filtered.size(),"totalPages",(filtered.size()+query.getSize()-1)/query.getSize(),"summary",summary,"readyItems",ready,"canMaintain",access.canMaintainEngineeringData());
    }
    @Transactional public List<Map<String,Object>> resolve(Resolve input,HttpServletRequest request) {
        access.requireEngineeringMaintenance();reason(input.reason());
        if(input.items()==null||input.items().isEmpty()||input.items().size()>1000||input.items().stream().map(Selection::key).distinct().count()!=input.items().size())throw invalid("请选择1至1000条不同的明确项");
        lockReferences();var all=byKey(issues(snapshot()));
        for(var selected:input.items()) {
            var existing=all.get(selected.key());
            if(existing!=null&&Set.of("resolved","not_phone").contains(existing.get("state"))&&Objects.equals(existing.get("sourceDigest"),selected.sourceDigest()))continue;
            requireIssue(all,selected.key(),selected.sourceDigest(),true);
        }
        var result=new ArrayList<Map<String,Object>>();
        for(var selected:input.items()) {
            var before=all.get(selected.key());String action=text(before,"autoAction");
            if(Set.of("resolved","not_phone").contains(before.get("state"))){result.add(Map.of("key",selected.key(),"status","already_resolved"));continue;}
            if("confirm_history".equals(action)) {
                history.confirm(new PhoneModelReviewConfirmDTO(text(before,"historyKind"),text(before,"historyKey"),text(before,"sourceDigest"),"same_phone_alias",input.reason()+"；"+text(before,"reason"),List.of(),List.of()),request);
            } else if("align_code".equals(action)) {
                updateCode(number(before.get("codeItemId")),text(before,"suggestedName"),longs(before.get("suggestedBrandIds")),input.reason(),request);
                record(before,"resolved",input.reason(),request);
            } else if("align_member".equals(action)) {
                for(long duplicate:longs(before.get("retireMemberIds"))) {
                    var old=references.phone(duplicate);var target=references.brand(number(before.get("targetBrandId")));String archivedName=withoutPrefixes(text(old,"modelName"),target,target);var aliases=new LinkedHashSet<String>((List<String>)old.get("aliases"));aliases.add(text(old,"modelName"));
                    saved(references.patchPhone(duplicate,new Patch(Map.of("status",json.valueToTree("disabled"),"modelName",json.valueToTree(archivedName),"aliases",json.valueToTree(aliases)),Map.of("status",json.valueToTree("enabled"),"modelName",json.valueToTree(old.get("modelName")),"aliases",json.valueToTree(old.get("aliases"))),input.reason()+"；停用无业务引用的同码同机本地重复，原名称留别名，保留原ID"),request));
                }
                long memberId=number(before.get("focusPhoneModelId"));var prior=references.phone(memberId);var aliases=new LinkedHashSet<String>((List<String>)prior.get("aliases"));aliases.add(text(prior,"modelName"));
                Map<String,JsonNode> changes=Map.of("brandId",json.valueToTree(before.get("targetBrandId")),"modelName",json.valueToTree(before.get("suggestedName")),"aliases",json.valueToTree(aliases));
                Map<String,JsonNode> original=Map.of("brandId",json.valueToTree(prior.get("brandId")),"modelName",json.valueToTree(prior.get("modelName")),"aliases",json.valueToTree(prior.get("aliases")));
                saved(references.patchPhone(memberId,new Patch(changes,original,input.reason()),request));
                long codeId=number(before.get("memberCodeItemId"));var current=metadataRepository.phone(codeId,false);
                metadata.phone(codeId,new Patch(Map.of("brandIds",json.valueToTree(List.of(before.get("targetBrandId")))),Map.of("brandIds",json.valueToTree(current.get("brandIds"))),input.reason()),request);
                before.put("after",references.phone(memberId));record(before,"resolved",input.reason(),request);
            } else if("classify_non_phone".equals(action))record(before,"not_phone",input.reason(),request);
            else throw conflict("依据已变化，请刷新后核对");
            result.add(Map.of("key",selected.key(),"status","resolved","action",action));
        }
        return result;
    }
    @Transactional public Map<String,Object> editCode(CodeEdit input,HttpServletRequest request) {
        access.requireEngineeringMaintenance();reason(input.reason());lockReferences();
        var before=requireIssue(byKey(issues(snapshot())),"code:"+input.codeItemId(),input.sourceDigest(),false);
        if(input.brandIds().isEmpty())throw invalid("请先选择品牌，再补充完整型号；配件请使用非手机归类");
        updateCode(input.codeItemId(),input.codeName(),input.brandIds(),input.reason(),request);record(before,"resolved",input.reason(),request);
        return metadataRepository.phone(input.codeItemId(),false);
    }
    private void updateCode(long id,String name,List<Long> brands,String why,HttpServletRequest request) {
        var row=jdbc.queryForMap("select code_type,code_value,code_name_zh,sort_order,version from plm_code_item where code_item_id=? and deleted_flag=0 for update",id);
        var input=new CodeItemSaveDTO();input.setCodeType(row.get("code_type").toString());input.setCodeValue(row.get("code_value").toString());input.setCodeName(name);input.setCodeNameZh((String)row.get("code_name_zh"));input.setSortOrder(((Number)row.get("sort_order")).intValue());input.setVersion(((Number)row.get("version")).longValue());input.setReason(why);codes.update(id,input);
        var current=metadataRepository.phone(id,false);
        metadata.phone(id,new Patch(Map.of("brandIds",json.valueToTree(brands)),Map.of("brandIds",json.valueToTree(current.get("brandIds"))),why),request);
    }

    /** Explicit engineering choice of two real brands; names or shared codes alone never merge brands. */
    @Transactional(readOnly=true) public Map<String,Object> previewBrandMerge(BrandMerge input) {
        access.requireEngineeringMaintenance();return mergePlan(input.sourceBrandId(),input.targetBrandId());
    }
    @Transactional public Map<String,Object> mergeBrands(BrandMerge input,HttpServletRequest request) {
        access.requireEngineeringMaintenance();reason(input.reason());lockReferences();
        var plan=mergePlan(input.sourceBrandId(),input.targetBrandId());if(!Objects.equals(input.sourceDigest(),plan.get("sourceDigest")))throw conflict("品牌或关联在预览后变化，请重新预览");
        if(!((List<?>)plan.get("blockers")).isEmpty())throw invalid("存在同名成员或来源冲突，请先处理预览中的缺项");
        long from=input.sourceBrandId(),to=input.targetBrandId();var source=repository.brand(from,true);var target=repository.brand(to,true);
        jdbc.queryForList("select id from plm_phone_model_member where brand_id in (?,?) order by id for update",from,to);
        var ids=longs(plan.get("phoneModelIds"));
        for(long id:longs(plan.get("retiredDuplicatePhoneModelIds")))saved(references.patchPhone(id,new Patch(Map.of("status",json.valueToTree("disabled")),Map.of("status",json.valueToTree("enabled")),input.reason()+"；同码同名且无业务引用的旧本地成员停用，ID和原文保留"),request));
        for(long id:ids)saved(references.patchPhone(id,new Patch(Map.of("brandId",json.valueToTree(to)),Map.of("brandId",json.valueToTree(from)),input.reason()),request));
        jdbc.update("insert into plm_phone_code_brand(code_item_id,brand_id,created_by,created_at) select code_item_id,?,created_by,created_at from plm_phone_code_brand where brand_id=? on conflict do nothing",to,from);
        jdbc.update("delete from plm_phone_code_brand where brand_id=?",from);
        jdbc.update("update plm_collaboration_item set brand_id=?,updated_by=?,updated_at=now() where brand_id=?",to,access.actor().username(),from);
        jdbc.update("update plm_master_data_source set object_id=? where object_type='phone_brand' and object_id=?",to,from);
        var alias=new LinkedHashSet<String>((List<String>)target.getOrDefault("aliases",List.of()));alias.add(source.get("name").toString());alias.addAll((List<String>)source.getOrDefault("aliases",List.of()));
        var rules=new ArrayList<Object>((List<?>)target.getOrDefault("prefixes",List.of()));for(var rule:(List<?>)source.getOrDefault("prefixes",List.of()))if(!rules.contains(rule))rules.add(rule);
        String retired="已并入品牌 #"+to+" · "+source.get("name");
        saved(references.patchBrand(from,new Patch(Map.of("name",json.valueToTree(retired),"aliases",json.valueToTree(List.of()),"prefixes",json.valueToTree(List.of()),"status",json.valueToTree("disabled")),Map.of("name",json.valueToTree(source.get("name")),"aliases",json.valueToTree(source.get("aliases")),"prefixes",json.valueToTree(source.get("prefixes")),"status",json.valueToTree(source.get("status"))),input.reason()),request));
        saved(references.patchBrand(to,new Patch(Map.of("aliases",json.valueToTree(alias),"prefixes",json.valueToTree(rules)),Map.of("aliases",json.valueToTree(target.get("aliases")),"prefixes",json.valueToTree(target.get("prefixes"))),input.reason()),request));
        // Preserve the legacy MES lookup as well as current CRM source mappings.
        if(source.get("legacySourceRecordId")!=null) {
            jdbc.update("update plm_phone_brand set source_system='plm',source_record_id=null where id=?",from);
            jdbc.update("update plm_phone_brand set source_system=?,source_record_id=?,source_snapshot=?::jsonb where id=?",source.get("legacySourceSystem"),source.get("legacySourceRecordId"),source.get("legacySourceSnapshot"),to);
        }
        var after=Map.of("source",repository.brand(from,false),"target",repository.brand(to,false),"phoneModelIds",ids,"retiredDuplicatePhoneModelIds",plan.get("retiredDuplicatePhoneModelIds"));
        var issue=new LinkedHashMap<String,Object>();issue.put("key","brand:"+from+":"+to);issue.put("category","brand_merge");issue.put("title",source.get("name")+" → "+target.get("name"));issue.put("modelNames",List.of(source.get("name"),target.get("name")));issue.put("phoneCodes",plan.get("phoneCodes"));issue.put("sourceDigest",plan.get("sourceDigest"));issue.put("reason",input.reason());issue.put("evidence",List.of("保留手机成员、编码和全部业务ID；来源关系统一到品牌 #"+to));issue.put("before",plan);issue.put("after",after);
        record(issue,"resolved",input.reason(),request);return after;
    }
    private Map<String,Object> mergePlan(long from,long to) {
        if(from<1||to<1||from==to)throw invalid("请选择两个不同品牌");
        var source=references.brand(from);var target=references.brand(to);if(!"enabled".equals(source.get("status"))||!"enabled".equals(target.get("status")))throw invalid("合并预览只接受启用品牌");
        var members=jdbc.queryForList("select id,brand_id,model_name,code_item_id,status from plm_phone_model_member where brand_id in (?,?) order by id",from,to);
        var links=jdbc.queryForList("select code_item_id,brand_id,created_by from plm_phone_code_brand where brand_id in (?,?) order by code_item_id,brand_id",from,to);
        var items=jdbc.queryForList("select item_id,brand_id from plm_collaboration_item where brand_id in (?,?) order by item_id",from,to);for(var item:items)access.requireItem(number(item.get("item_id")));
        var sources=jdbc.queryForList("select source_key,source_system,source_id,source_hash,object_id from plm_master_data_source where object_type='phone_brand' and object_id in (?,?) order by source_key",from,to);
        var blockers=new ArrayList<String>();var retired=new ArrayList<Long>();
        for(var member:members)if(number(member.get("brand_id"))==from) {
            var same=members.stream().filter(other->number(other.get("brand_id"))==to&&PhoneIdentityMatcher.normalize(text(member,"model_name")).equals(PhoneIdentityMatcher.normalize(text(other,"model_name")))).findFirst();
            if(same.isPresent()) {
                var original=repository.phone(number(member.get("id")),false);var targetMember=same.get();
                // No business IDs are rewired. Only unused local duplicates can be retired.
                if(member.get("code_item_id")!=null&&Objects.equals(member.get("code_item_id"),targetMember.get("code_item_id"))&&"enabled".equals(member.get("status"))&&"enabled".equals(targetMember.get("status"))&&"plm".equals(original.get("sourceSystem"))&&original.get("sourceRecordId")==null&&unusedMember(number(member.get("id"))))retired.add(number(member.get("id")));
                else blockers.add("目标品牌已有同名成员："+member.get("model_name")+"；需核对原编码、业务引用及来源，不能覆盖引用");
            }
        }
        if(source.get("legacySourceRecordId")!=null&&target.get("legacySourceRecordId")!=null)blockers.add("两品牌各有独立旧来源身份，请先核对来源映射");
        var result=new LinkedHashMap<String,Object>();result.put("source",source);result.put("target",target);result.put("members",members);result.put("codeLinks",links);result.put("items",items);result.put("sourceLinks",sources);
        result.put("phoneModelIds",members.stream().filter(m->number(m.get("brand_id"))==from&&!retired.contains(number(m.get("id")))).map(m->number(m.get("id"))).toList());result.put("retiredDuplicatePhoneModelIds",retired);
        var codeIds=new TreeSet<Long>();members.forEach(m->{if(m.get("code_item_id")!=null)codeIds.add(number(m.get("code_item_id")));});links.forEach(m->codeIds.add(number(m.get("code_item_id"))));
        result.put("phoneCodes",codeIds.isEmpty()?List.of():jdbc.queryForList("select code_value from plm_code_item where code_item_id in ("+String.join(",",Collections.nCopies(codeIds.size(),"?"))+") order by code_value desc",String.class,codeIds.toArray()));result.put("blockers",blockers);result.put("sourceDigest",digest(result));return result;
    }
    private boolean unusedMember(long id) {
        var columns=jdbc.queryForList("select table_name,column_name from information_schema.columns where table_schema=current_schema() and column_name in ('phone_model_id','phone_model_member_id') and data_type='bigint'");
        for(var column:columns) {
            String table=column.get("table_name").toString(),name=column.get("column_name").toString();
            if(!table.matches("[a-z0-9_]+")||!name.matches("[a-z0-9_]+"))throw new IllegalStateException("手机引用字段无效");
            if(jdbc.queryForObject("select exists(select 1 from "+table+" where "+name+"=?)",Boolean.class,id))return false;
        }
        if(jdbc.queryForObject("select exists(select 1 from plm_master_data_source where object_type='phone_model' and object_id=?)",Boolean.class,id))return false;
        for(String field:List.of("plm_product_development_state.data","plm_partner_record.data","plm_partner_spec.data","plm_partner_import.data","plm_mold_application.form_data","plm_mold_code_reservation.response","plm_collaboration_mold_set.development_data","plm_pilot_trial.baseline")) {
            String[] parts=field.split("\\.");String path="$.** ? (@.phoneModelId == $phone || @.phoneModelMemberId == $phone || exists(@.phoneModelIds[*] ? (@ == $phone)))";
            if(jdbc.queryForObject("select exists(select 1 from "+parts[0]+" where jsonb_path_exists("+parts[1]+",?::jsonpath,jsonb_build_object('phone',?::bigint)))",Boolean.class,path,id))return false;
        }
        return true;
    }

    private Snapshot snapshot() {
        var effective=new LinkedHashMap<Long,List<Long>>();for(var row:jdbc.queryForList("select code_item_id,brand_id from plm_phone_code_brand_effective order by code_item_id,brand_id"))effective.computeIfAbsent(number(row.get("code_item_id")),k->new ArrayList<>()).add(number(row.get("brand_id")));
        var lines=new LinkedHashMap<Long,String>();for(var row:jdbc.queryForList("select p.product_id,coalesce(case when p.product_type='product_line' then p.product_name when parent.product_type='product_line' then parent.product_name when grand.product_type='product_line' then grand.product_name end,p.product_name) as product_line_name from plm_product p left join plm_product parent on parent.product_id=p.parent_product_id left join plm_product grand on grand.product_id=parent.parent_product_id where p.deleted_flag=0"))lines.put(number(row.get("product_id")),text(row,"product_line_name"));
        return new Snapshot(repository.allBrands(),repository.allPhones(),jdbc.queryForList("select code_item_id as \"codeItemId\",code_value as \"phoneModelCode\",code_name as \"modelName\",status,updated_at::text as \"updatedAt\" from plm_code_item where code_type='phone_model' and deleted_flag=0 order by code_value desc,code_item_id"),effective,lines);
    }
    private List<Map<String,Object>> issues(Snapshot snapshot) {
        boolean writable=access.canMaintainEngineeringData();var out=new ArrayList<Map<String,Object>>();var decisions=decisions();
        for(var code:snapshot.codes()) {
            long id=number(code.get("codeItemId"));var members=snapshot.phones().stream().filter(p->Objects.equals(p.get("codeItemId"),id)).toList();var brands=snapshot.effective().getOrDefault(id,List.of());var actual=new TreeSet<Long>();members.forEach(p->actual.add(number(p.get("brandId"))));
            boolean missing=members.isEmpty()&&brands.isEmpty(),conflict=!members.isEmpty()&&!actual.containsAll(brands);
            if(!missing&&!conflict)continue;
            var row=new LinkedHashMap<String,Object>();row.put("key","code:"+id);row.put("category",missing?"missing_identity":"brand_conflict");row.put("title",code.get("modelName"));row.put("codeItemId",id);row.put("phoneCodes",List.of(code.get("phoneModelCode")));row.put("modelNames",members.isEmpty()?List.of(code.get("modelName")):members.stream().map(p->p.get("modelName")).toList());row.put("brandIds",brands);row.put("phoneModelIds",members.stream().map(p->p.get("phoneModelId")).toList());row.put("state","pending");row.put("canResolve",writable);
            row.put("missingFields",missing?List.of("明确品牌","完整手机型号或非手机用途说明"):List.of("编码品牌与标准成员的来源结论"));row.put("reason",missing?"未找到已确认的品牌和标准成员；不按未知文字猜手机":"编码组品牌与已有标准成员不同，须以明确来源核对");row.put("evidence",List.of());row.put("editTab","groups");
            String nonPhone=PhoneIdentityMatcher.nonPhoneEvidence(text(code,"modelName"));
            if(missing&&nonPhone!=null){row.put("state","ready");row.put("autoAction","classify_non_phone");row.put("reason",nonPhone);row.put("evidence",List.of(nonPhone));}
            if(conflict&&actual.size()==1) {
                long brand=actual.first();var b=snapshot.brands().stream().filter(v->number(v.get("brandId"))==brand).findFirst().orElseThrow();
                var keys=members.stream().map(p->PhoneIdentityMatcher.identityKey(text(p,"modelName"),b)).distinct().toList();
                if(keys.size()==1&&members.stream().allMatch(p->trusted(p,code,b))) {
                    row.put("state","ready");row.put("autoAction","align_code");row.put("suggestedName",members.get(0).get("modelName"));row.put("suggestedBrandIds",List.of(brand));row.put("reason","可信 CRM 来源的原编码、品牌及完整型号一致；保留原码，修正编码名称和额外品牌分类");row.put("evidence",members.stream().map(p->"CRM #"+p.get("sourceRecordId")+"："+p.get("modelName")+" · "+p.get("phoneModelCode")).toList());
                }
            }
            row.put("sourceDigest",digest(List.of(code,brands,members.stream().map(p->Arrays.asList(p.get("phoneModelId"),p.get("brandId"),p.get("modelName"),p.get("status"),p.get("sourceSystem"),p.get("sourceRecordId"),p.get("sourceSnapshot"))).toList())));
            var decision=decisions.get(row.get("key"));if(decision!=null&&Objects.equals(decision.get("sourceDigest"),row.get("sourceDigest"))&&"not_phone".equals(decision.get("state"))){row.put("state","not_phone");row.put("reviewedAt",decision.get("reviewedAt"));row.put("reviewedBy",decision.get("reviewedBy"));row.put("reviewReason",decision.get("reviewReason"));}
            out.add(row);
        }
        out.addAll(memberIssues(snapshot,writable));
        for(String kind:List.of("code_many_names","name_many_codes")) {
            var q=new PhoneModelReviewQueryDTO();q.setKind(kind);q.setSize(100);
            for(long page=1;;page++) {
                q.setPage(page);var rows=history.page(q);
                for(var group:rows.getContent())out.add(historyIssue(group,kind,snapshot,writable));
                if(page>=rows.getTotalPages())break;
            }
        }
        var keys=new HashSet<>();out.forEach(r->keys.add(r.get("key")));
        for(var row:decisions.values())if(!keys.contains(row.get("key"))&&"resolved".equals(row.get("state")))out.add(row);
        return out;
    }
    private List<Map<String,Object>> memberIssues(Snapshot snapshot,boolean writable) {
        var out=new ArrayList<Map<String,Object>>();
        for(var phone:snapshot.phones()) {
            if(!"enabled".equals(phone.get("status"))||!"enabled".equals(phone.get("brandStatus"))||phone.get("codeItemId")==null||!PhoneIdentityMatcher.isSingleModel(text(phone,"modelName")))continue;
            var review=PhoneIdentityMatcher.reviewBrand(text(phone,"modelName"),snapshot.brands());
            if(review.brandIds().size()!=1||review.brandIds().get(0)==number(phone.get("brandId")))continue;
            long targetId=review.brandIds().get(0);var target=snapshot.brands().stream().filter(b->number(b.get("brandId"))==targetId).findFirst().orElseThrow();var source=snapshot.brands().stream().filter(b->Objects.equals(b.get("brandId"),phone.get("brandId"))).findFirst().orElseThrow();
            String fullBrand=PhoneIdentityMatcher.normalize(text(target,"name"));
            if(!(fullBrand.length()>=4&&PhoneIdentityMatcher.normalize(text(phone,"modelName")).startsWith(fullBrand+" "))&&review.evidence().stream().noneMatch(e->e.startsWith("完整品牌“")))continue;
            var code=snapshot.codes().stream().filter(c->Objects.equals(c.get("codeItemId"),phone.get("codeItemId"))).findFirst().orElse(null);if(code==null)continue;
            String name=withoutPrefixes(text(phone,"modelName"),source,target),key=PhoneIdentityMatcher.normalize(name).replace(" ","");
            var duplicates=snapshot.phones().stream().filter(p->!Objects.equals(p.get("phoneModelId"),phone.get("phoneModelId"))&&Objects.equals(p.get("codeItemId"),phone.get("codeItemId"))).toList();
            boolean clear=PhoneIdentityMatcher.isSingleModel(text(code,"modelName"))&&PhoneIdentityMatcher.normalize(withoutPrefixes(text(code,"modelName"),source,target)).replace(" ","").equals(key)&&!name.isBlank();
            var retired=new ArrayList<Long>();for(var duplicate:duplicates) {
                if(!Objects.equals(duplicate.get("brandId"),targetId)||!PhoneIdentityMatcher.identityKey(text(duplicate,"modelName"),target).equals(key)){clear=false;continue;}
                if("enabled".equals(duplicate.get("status"))) {
                    if("plm".equals(duplicate.get("sourceSystem"))&&duplicate.get("sourceRecordId")==null&&unusedMember(number(duplicate.get("phoneModelId"))))retired.add(number(duplicate.get("phoneModelId")));else clear=false;
                }
            }
            if(retired.size()>1)clear=false;
            final String clean=name;name=target.get("name")+" "+name;
            final String chosen=name;if(snapshot.phones().stream().anyMatch(p->Objects.equals(p.get("brandId"),targetId)&&!retired.contains(number(p.get("phoneModelId")))&&PhoneIdentityMatcher.normalize(text(p,"modelName")).equals(PhoneIdentityMatcher.normalize(chosen))))clear=false;
            if(!retired.isEmpty()&&snapshot.phones().stream().anyMatch(p->Objects.equals(p.get("brandId"),targetId)&&!retired.contains(number(p.get("phoneModelId")))&&PhoneIdentityMatcher.normalize(text(p,"modelName")).equals(PhoneIdentityMatcher.normalize(clean))))clear=false;
            var row=new LinkedHashMap<String,Object>();row.put("key","member:"+phone.get("phoneModelId"));row.put("category","member_brand_conflict");row.put("title",phone.get("modelName"));row.put("phoneCodes",List.of(phone.get("phoneModelCode")));row.put("modelNames",List.of(phone.get("modelName"),target.get("name")+" "+withoutPrefixes(text(phone,"modelName"),source,target)));row.put("phoneModelIds",List.of(phone.get("phoneModelId")));row.put("focusPhoneModelId",phone.get("phoneModelId"));row.put("editTab","phones");row.put("canResolve",writable);row.put("state",clear?"ready":"pending");row.put("missingFields",List.of("原成员品牌与型号内完整品牌的对应结论；同码成员的业务引用"));row.put("evidence",review.evidence());row.put("reason","完整品牌、原编码及完整型号一致时修正父品牌归类；不拆组合，不推断适配");
            row.put("targetBrandId",targetId);row.put("suggestedName",name);row.put("sourceBrandId",phone.get("brandId"));row.put("sourceModelName",phone.get("modelName"));row.put("memberCodeItemId",phone.get("codeItemId"));row.put("retireMemberIds",retired);row.put("before",phone);row.put("sourceDigest",digest(Arrays.asList(phone,code,duplicates,target)));if(clear)row.put("autoAction","align_member");out.add(row);
        }
        return out;
    }
    private String withoutPrefixes(String name,Map<String,Object> source,Map<String,Object> target) {
        String value=name.strip().replaceAll("[\\s\\p{Zs}]+"," ");
        for(var brand:List.of(source,target))for(String prefix:PhoneIdentityMatcher.brandPrefixes(brand).stream().sorted(Comparator.comparingInt(String::length).reversed()).toList())if(!prefix.isBlank()&&value.toLowerCase(Locale.ROOT).startsWith(prefix+" ")){value=value.substring(prefix.length()).strip();break;}
        return value;
    }
    private Map<String,Object> historyIssue(PhoneModelReviewVO group,String kind,Snapshot snapshot,boolean writable) {
        var row=new LinkedHashMap<String,Object>();row.put("key","history:"+kind+":"+group.key());row.put("category","history");row.put("title",String.join(" / ",group.modelNames()));row.put("phoneCodes",group.codeValues());row.put("modelNames",group.modelNames());row.put("phoneModelIds",group.references().stream().map(PhoneModelReviewVO.Reference::phoneModelId).filter(Objects::nonNull).distinct().toList());row.put("productNames",group.references().stream().map(r->snapshot.productLines().getOrDefault(r.projectId(),r.productName())).filter(Objects::nonNull).distinct().toList());row.put("referenceCount",group.sourceCount());row.put("sourceDigest",group.sourceDigest());row.put("historyKind",kind);row.put("historyKey",group.key());row.put("editTab","review");row.put("canResolve",writable&&group.canReview());row.put("state","confirmed".equals(group.reviewState())?"resolved":"pending");
        row.put("missingFields",historyMissing(group,kind));row.put("reason","仅有同码或名称相近不能确认同一手机，也不等于实物适配；在历史关系核对中维护明确结论");row.put("evidence",List.of("来源产品／SKU "+group.sourceCount()+" 条；分组展示，不需逐SKU核对"));
        if(group.reviewedAt()!=null){row.put("reviewedAt",group.reviewedAt());row.put("reviewedBy",Objects.toString(group.reviewedBy(),""));row.put("reviewReason",Objects.toString(group.reviewReason(),""));}
        if("code_many_names".equals(kind)&&group.canReview()&&!"confirmed".equals(group.reviewState())&&group.modelNames().stream().allMatch(PhoneIdentityMatcher::isSingleModel)) {
            var brands=snapshot.phones().stream().filter(p->group.codeValues().contains(p.get("phoneModelCode"))).map(p->number(p.get("brandId"))).distinct().toList();
            if(brands.size()==1) {
                var brand=snapshot.brands().stream().filter(b->number(b.get("brandId"))==brands.get(0)).findFirst().orElseThrow();
                if(group.modelNames().stream().map(n->PhoneIdentityMatcher.identityKey(n,brand)).distinct().count()==1){row.put("state","ready");row.put("autoAction","confirm_history");row.put("reason","同一原编码、同一品牌，原名称仅有明确前缀、大小写或空格差异；保留网络、代数、孔型与地区");}
            }
        }
        return row;
    }
    private List<String> historyMissing(PhoneModelReviewVO group,String kind) {
        if("name_many_codes".equals(kind))return List.of("原编码 "+String.join("／",group.codeValues())+" 的分配依据：不同版本、共码或共模关系");
        var fields=new ArrayList<String>();
        if(group.modelNames().stream().anyMatch(n->!PhoneIdentityMatcher.isSingleModel(n)))fields.add("组合型号与单型号的适用范围／共码依据");
        if(group.modelNames().stream().map(n->n.toLowerCase(Locale.ROOT).matches(".*[45]\\s*g\\b.*")).distinct().count()>1)fields.add("未注明网络的记录对应4G还是5G");
        if(group.modelNames().stream().anyMatch(n->n.toLowerCase(Locale.ROOT).contains("europe")))fields.add("Europe与未注明地区记录的版本对应依据");
        if(fields.isEmpty())fields.add("保留代数、网络和孔型后，多个原名称是否为同一手机或不同手机的明确结论");
        return fields;
    }
    private boolean trusted(Map<String,Object> phone,Map<String,Object> code,Map<String,Object> brand) {
        if(!"crm".equals(phone.get("sourceSystem"))||!"crm".equals(brand.get("sourceSystem"))||!"enabled".equals(phone.get("status"))||!"enabled".equals(brand.get("status"))||!"enabled".equals(code.get("status")))return false;
        try{var source=json.readTree(phone.get("sourceSnapshot").toString());return "ACTIVE".equals(source.path("status").asText())&&Objects.equals(source.path("modelCode").asText(),code.get("phoneModelCode"))&&Objects.equals(source.path("phoneBrandId").asText(),brand.get("sourceRecordId"))&&PhoneIdentityMatcher.identityKey(source.path("modelName").asText(),brand).equals(PhoneIdentityMatcher.identityKey(text(phone,"modelName"),brand));}catch(Exception failure){return false;}
    }
    private Map<String,Object> publicIssue(Map<String,Object> row) {
        var fields=Set.of("key","category","title","codeItemId","phoneCodes","modelNames","brandIds","phoneModelIds","focusPhoneModelId","productNames","referenceCount","missingFields","reason","evidence","editTab","sourceDigest","state","canResolve","autoAction","suggestedName","suggestedBrandIds","historyKind","historyKey","reviewedAt","reviewedBy","reviewReason");
        var result=new LinkedHashMap<String,Object>();row.forEach((k,v)->{if(fields.contains(k))result.put(k,v);});return result;
    }
    private Map<String,Map<String,Object>> decisions() {
        var out=new LinkedHashMap<String,Map<String,Object>>();
        for(var row:jdbc.queryForList("select distinct on(detail_json::jsonb->>'key') detail_json,operator_user_name,created_at::text as reviewed_at from plm_operation_log where business_type='PHONE_IDENTITY_REVIEW' and result='success' and deleted_flag=0 order by detail_json::jsonb->>'key',log_id desc"))try{
            var detail=json.readValue(row.get("detail_json").toString(),LinkedHashMap.class);var issue=new LinkedHashMap<String,Object>((Map<String,Object>)detail.get("issue"));issue.put("state",detail.get("state"));issue.put("sourceDigest",detail.get("sourceDigest"));issue.put("reviewedBy",row.get("operator_user_name"));issue.put("reviewedAt",row.get("reviewed_at"));issue.put("reviewReason",detail.get("reason"));issue.put("canResolve",false);out.put(issue.get("key").toString(),issue);
        }catch(Exception failure){throw new IllegalStateException("手机资料核对审计读取失败",failure);}
        return out;
    }
    private void record(Map<String,Object> issue,String state,String why,HttpServletRequest request) {
        var detail=new LinkedHashMap<String,Object>();detail.put("key",issue.get("key"));detail.put("sourceDigest",issue.get("sourceDigest"));detail.put("state",state);detail.put("reason",why);detail.put("issue",issue);
        audit.logRequired(OperationLogCreateCommand.builder().action("RESOLVE_IDENTITY").businessType("PHONE_IDENTITY_REVIEW").businessId(issue.get("key").toString()).businessName("手机资料集中核对").detailJson(encode(detail)).request(request).build(),access.actor());
    }
    private Map<String,Object> requireIssue(Map<String,Map<String,Object>> all,String key,String digest,boolean automatic) {
        var row=all.get(key);if(row==null||!Objects.equals(row.get("sourceDigest"),digest))throw conflict("资料或来源已变化，请刷新后重试");
        if(!Boolean.TRUE.equals(row.get("canResolve"))||automatic&&!"ready".equals(row.get("state")))throw invalid("此项仍缺明确依据，不能自动确认");return row;
    }
    private void lockReferences(){jdbc.execute("select pg_advisory_xact_lock(66817399)");jdbc.queryForList("select id from plm_phone_brand order by id for update");jdbc.queryForList("select code_item_id from plm_code_item where code_type='phone_model' and deleted_flag=0 order by code_item_id for update");}
    private void read(){access.requireDomesticView();access.requirePermission("collaboration:view");}
    private void reason(String value){if(value==null||value.isBlank()||value.length()>1000)throw invalid("请填写核对依据（最多1000字）");}
    private static Map<String,Map<String,Object>> byKey(List<Map<String,Object>> rows){var out=new LinkedHashMap<String,Map<String,Object>>();rows.forEach(r->out.put(r.get("key").toString(),r));return out;}
    private static List<Long> longs(Object value){return value==null?List.of():((List<?>)value).stream().map(PhoneIdentityIssuesService::number).distinct().sorted().toList();}
    private static long number(Object value){return ((Number)value).longValue();}
    private static String text(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
    private void saved(Map<String,Object> result){if(!"saved".equals(result.get("status")))throw conflict("引用或品牌资料已变化，请刷新重试");}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException("手机资料证据序列化失败",failure);}}
    private String digest(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception failure){throw new IllegalStateException(failure);}}
    private static BusinessException invalid(String value){return new BusinessException(40001,value);}
    private static BusinessException conflict(String value){return new BusinessException(40901,value);}
}

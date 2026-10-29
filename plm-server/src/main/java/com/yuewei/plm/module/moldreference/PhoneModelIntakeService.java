package com.yuewei.plm.module.moldreference;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.operationlog.service.*;
import com.yuewei.plm.module.partner.PartnerCatalogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** A source-backed engineering inbox, not a phone/code master or another supplier ledger. */
@Service @RequiredArgsConstructor
public class PhoneModelIntakeService {
    private static final Set<String> REASONS=Set.of("","name","variant","shared_group","incomplete","code");
    private static final List<String> ORIGINAL=List.of("state","classification","reviewReason","brandId","componentText","resolvedPhoneModelIds");
    private static final List<String> RAW=List.of("rawBrandGroup","rawModelText","rawStatus","rawNote");
    private static final List<String> MODEL_INPUT=List.of("supplierIndex","rawBrandGroup","rawModelText","rawStatus","rawNote","matchText","matchNote","phoneModelIds");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PhoneModelReferenceService references;
    private final PhoneModelReferenceRepository repository;
    private final PartnerCatalogService catalog;
    private final PilotAccessService access;
    private final OperationLogService audit;

    private void read(){access.requireDomesticView();access.requirePermission("collaboration:view");}
    private void write(){read();access.requireEngineeringMaintenance();}
    private String visibleSources(){return access.hasPermission("partner:view")
        ? "r.deleted_flag=0 and p.deleted_flag=0 and ("+access.visibleSqlClause("partner","r.record_id")+") and ("+access.visibleSqlClause("supplier_profile","p.supplier_id")+")" : "false";}
    private String sourceFrom(){return " from plm_phone_model_intake_source s join plm_partner_record r on r.record_id=s.record_id join plm_supplier_profile p on p.supplier_code=s.supplier_code ";}
    private String visibleCandidate(){return "exists(select 1"+sourceFrom()+"where s.intake_id=c.id and "+visibleSources()+")";}
    private boolean allSourcesVisible(long id){return jdbc.queryForObject("select count(*) from plm_phone_model_intake_source where intake_id=?",Long.class,id).equals(jdbc.queryForObject("select count(*)"+sourceFrom()+"where s.intake_id=? and "+visibleSources(),Long.class,id));}
    private boolean candidateVisible(long id){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_phone_model_intake c where c.id=? and "+visibleCandidate()+")",Boolean.class,id));}
    private String base(){return "select c.*,b.name as brand_name,array_to_json(c.resolved_phone_ids)::text as resolved_json from plm_phone_model_intake c left join plm_phone_brand b on b.id=c.brand_id ";}
    private Map<String,Object> load(long id,boolean lock){
        read();require(id>0,"待补型号标识无效");
        var rows=jdbc.queryForList(base()+"where c.id=? and "+visibleCandidate()+(lock?" for update of c":""),id);
        require(rows.size()==1,"待补型号不存在或来源无权访问");return rows.get(0);
    }
    private List<Long> ids(Object raw){
        if(raw==null)return List.of();if(raw instanceof String value)try{return ids(json.readValue(value,new TypeReference<List<Object>>(){}));}catch(Exception e){throw invalid("手机关联身份无法读取");}
        require(raw instanceof Collection<?>,"手机关联须为已有型号列表");return ((Collection<?>)raw).stream().map(v->number(v)).peek(v->require(v>0,"手机型号身份无效")).distinct().sorted().toList();
    }
    private Map<String,Object> publicCandidate(Map<String,Object> row){
        long id=number(row.get("id"));long visible=jdbc.queryForObject("select count(*)"+sourceFrom()+"where s.intake_id=? and "+visibleSources(),Long.class,id),total=jdbc.queryForObject("select count(*) from plm_phone_model_intake_source where intake_id=?",Long.class,id);
        String reason=text(row.get("reason"));if(visible!=total){var current=jdbc.queryForList("select s.source_snapshot::text"+sourceFrom()+"where s.intake_id=? and "+visibleSources()+" order by s.id desc limit 1",id);reason=current.isEmpty()?"仅显示当前可见来源":text(decode(json,current.get(0).get("source_snapshot")).getOrDefault("intakeReason","按当前可见来源核对"));}
        return publicCandidate(row,visible,total,reason);
    }
    private Map<String,Object> publicCandidate(Map<String,Object> row,long visible,long total,String reason){
        var out=new LinkedHashMap<String,Object>();out.put("id",row.get("id"));out.put("componentText",row.get("component_text"));out.put("modelName",row.get("component_text"));out.put("brandId",row.get("brand_id"));out.put("brandName",text(row.get("brand_name")));
        out.put("classification",row.get("classification"));out.put("reviewReason",row.get("review_reason"));out.put("reason",reason);out.put("state",row.get("state"));out.put("version",row.get("version"));out.put("resolvedPhoneModelIds",ids(row.get("resolved_json")));
        out.put("sourceCount",visible);out.put("canMaintain",access.canMaintainEngineeringData()&&visible==total);
        out.put("createdAt",text(row.get("created_at")));out.put("updatedAt",text(row.get("updated_at")));return out;
    }
    /** Batch results are summaries: source details are paged on demand, never resolved once per candidate. */
    private List<Map<String,Object>> candidateSummaries(Collection<Long> requested){
        if(requested.isEmpty())return List.of();var ordered=new LinkedHashSet<>(requested);String array="{"+String.join(",",ordered.stream().map(String::valueOf).toList())+"}";
        var rows=jdbc.queryForList(base()+"where c.id=any(?::bigint[]) and "+visibleCandidate(),array);var byId=new HashMap<Long,Map<String,Object>>();for(var row:rows)byId.put(number(row.get("id")),row);
        require(byId.size()==ordered.size(),"原提交涉及当前无法完整核对的来源，请恢复授权后查看原结果");
        var stats=jdbc.queryForList("select s.intake_id,count(*) as total_count,count(*) filter(where "+visibleSources()+") as visible_count from plm_phone_model_intake_source s left join plm_partner_record r on r.record_id=s.record_id left join plm_supplier_profile p on p.supplier_code=s.supplier_code where s.intake_id=any(?::bigint[]) group by s.intake_id",array);var bySource=new HashMap<Long,Map<String,Object>>();for(var stat:stats)bySource.put(number(stat.get("intake_id")),stat);
        var summaries=new ArrayList<Map<String,Object>>();for(long id:ordered){var row=byId.get(id);var stat=bySource.getOrDefault(id,Map.of());long visible=number(stat.get("visible_count")),total=number(stat.get("total_count"));require(visible>0&&visible==total,"原提交涉及当前无法完整核对的来源，请恢复授权后查看原结果");summaries.add(publicCandidate(row,visible,total,text(row.get("reason"))));}return summaries;
    }
    @Transactional(readOnly=true) public Map<String,Object> page(Map<String,String> query){
        read();long page=pageNumber(query),size=pageSize(query);String where=visibleCandidate();var args=new ArrayList<Object>();
        String keyword=text(query.get("keyword"));if(!keyword.isEmpty()){where+=" and (c.component_text ilike ? or b.name ilike ?)";args.add("%"+keyword+"%");args.add("%"+keyword+"%");}
        String state=text(query.getOrDefault("state","pending")),classification=text(query.getOrDefault("classification","all"));
        require(Set.of("pending","resolved","deferred","all").contains(state)&&Set.of("missing","review","all").contains(classification),"待补型号筛选无效");
        if(!classification.equals("all")){where+=" and c.classification=?";args.add(classification);}if(!text(query.get("brandId")).isEmpty()){where+=" and c.brand_id=?";args.add(number(query.get("brandId")));}
        var summary=new LinkedHashMap<String,Long>();for(String value:List.of("pending","resolved","deferred","missing","review")){String field=Set.of("missing","review").contains(value)?"classification":"state";var countArgs=new ArrayList<>(args);countArgs.add(value);summary.put(value,jdbc.queryForObject("select count(*) from plm_phone_model_intake c left join plm_phone_brand b on b.id=c.brand_id where "+where+" and c."+field+"=?",Long.class,countArgs.toArray()));}
        if(!state.equals("all")){where+=" and c.state=?";args.add(state);}long total=jdbc.queryForObject("select count(*) from plm_phone_model_intake c left join plm_phone_brand b on b.id=c.brand_id where "+where,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
        var content=jdbc.queryForList(base()+"where "+where+" order by c.updated_at desc,c.id desc limit ? offset ?",args.toArray()).stream().map(this::publicCandidate).toList();var out=pageResult(content,total,page,size);out.put("summary",summary);out.put("canMaintain",access.canMaintainEngineeringData());return out;
    }
    @Transactional(readOnly=true) public Map<String,Object> detail(long id){var out=publicCandidate(load(id,false));out.put("sources",sources(id,Map.of()).get("content"));return out;}
    @Transactional(readOnly=true) public Map<String,Object> sources(long id,Map<String,String> query){
        load(id,false);long page=pageNumber(query),size=pageSize(query);long total=jdbc.queryForObject("select count(*)"+sourceFrom()+"where s.intake_id=? and "+visibleSources(),Long.class,id);
        var cache=new HashMap<String,Map<String,Object>>();var rows=jdbc.queryForList("select s.*"+sourceFrom()+"where s.intake_id=? and "+visibleSources()+" order by s.id desc limit ? offset ?",id,size,(page-1)*size);
        return pageResult(rows.stream().map(row->publicSource(row,cache)).toList(),total,page,size);
    }
    private Map<String,Object> sourceDetail(Map<String,Object> identity,boolean version){
        var detail=catalog.detail(required(identity.get("sourceKind"),"来源类型"),number(identity.get("recordId")),required(identity.get("rowId"),"来源款式"),required(identity.get("supplierCode"),"供应商"));
        if(version)require(identity.containsKey("rowVersion")&&identity.containsKey("recordVersion")&&number(identity.get("rowVersion"))==number(detail.get("rowVersion"))&&number(identity.get("recordVersion"))==number(detail.get("recordVersion")),"供应商资料版本已变化，请重新核对预览");
        return detail;
    }
    private String sourceKey(Map<String,Object> source){return text(source.get("sourceKind"))+":"+number(source.get("recordId"))+":"+text(source.get("rowId"))+":"+text(source.get("supplierCode"));}
    private Map<String,Object> publicSource(Map<String,Object> row,Map<String,Map<String,Object>> cache){
        var snapshot=decode(json,row.get("source_snapshot"));Map<String,Object> current;
        try{current=cache.computeIfAbsent(sourceKey(snapshot),key->sourceDetail(snapshot,false));}catch(BusinessException unavailable){var out=new LinkedHashMap<>(snapshot);out.put("id",row.get("id"));out.put("createdAt",text(row.get("created_at")));out.put("sourceDigest",row.get("source_digest"));out.put("modelSheetRefs",List.of());out.put("modelSheetFileDetails",List.of());out.put("sourceUnavailable",true);return out;}
        var out=new LinkedHashMap<>(snapshot);out.put("id",row.get("id"));out.put("createdAt",text(row.get("created_at")));out.put("sourceDigest",row.get("source_digest"));
        var visibleIds=new HashSet<Long>();for(var file:maps(current.get("modelSheetFileDetails")))visibleIds.add(number(file.get("id")));
        out.put("modelSheetRefs",maps(snapshot.get("modelSheetRefs")).stream().filter(ref->visibleIds.contains(number(ref.get("id")))).toList());out.put("modelSheetFileDetails",maps(current.get("modelSheetFileDetails")).stream().filter(file->idsOfRefs(snapshot.get("modelSheetRefs")).contains(number(file.get("id")))).toList());return out;
    }
    private Set<Long> idsOfRefs(Object refs){var out=new LinkedHashSet<Long>();for(var ref:maps(refs))out.add(number(ref.get("id")));return out;}
    private Map<String,Object> sourceSnapshot(Map<String,Object> detail,Map<String,Object> entry,String component){
        var out=new LinkedHashMap<String,Object>();for(String key:List.of("supplierCode","supplierName","sourceKind","recordId","rowId","rowVersion","recordVersion","sourceCode","sourceName"))out.put(key,detail.get(key));
        out.put("styleName",detail.get("name"));
        out.put("supplierIndex",number(entry.get("supplierIndex")));out.put("componentText",component);for(String key:RAW)out.put(key,Objects.toString(entry.get(key),""));
        out.put("modelSheetRefs",maps(detail.get("modelSheetRefs")).stream().map(ref->Map.<String,Object>of("id",number(ref.get("id")))).toList());return out;
    }
    private String evidence(Map<String,Object> snapshot){var safe=new LinkedHashMap<String,Object>();for(String key:List.of("supplierCode","sourceKind","recordId","rowId","supplierIndex","rawBrandGroup","rawModelText","rawStatus","rawNote","modelSheetRefs"))safe.put(key,snapshot.get(key));return digest(safe);}
    private Map<String,Object> classification(Map<String,Object> input){
        String component=bounded(required(input.get("componentText"),"待补型号片段"),256,"待补型号"),kind=text(input.get("classification")),why=text(input.get("reviewReason"));
        require(Set.of("missing","review").contains(kind)&&REASONS.contains(why)&&(!kind.equals("review")||!why.isEmpty()),"请区分真正缺档与名称／版本／共模／原文／编码待核对");
        Long brand=nullable(input.get("brandId"));Map<String,Object> actual=brand==null?Map.of():references.brand(brand);require(brand==null||"enabled".equals(actual.get("status")),"品牌已停用，请先核对");
        String normalized=brand==null?PhoneIdentityMatcher.normalize(component):PhoneIdentityMatcher.identityKey(component,actual);
        var out=new LinkedHashMap<String,Object>();out.put("componentText",component);out.put("brandId",brand);out.put("brandName",actual.getOrDefault("name",""));out.put("classification",kind);out.put("reviewReason",why);out.put("reason",bounded(required(input.get("reason"),"整理依据"),1000,"整理依据"));out.put("identityKey",digest(Arrays.asList(brand,normalized)));return out;
    }
    @Transactional(readOnly=true) public Map<String,Object> sourcePreview(Map<String,Object> input){write();return sourcePlan(input,true);}
    private Map<String,Object> sourcePlan(Map<String,Object> input,boolean version){
        var detail=sourceDetail(input,version);require(input.get("entries") instanceof List<?> && !((List<?>)input.get("entries")).isEmpty(),"请选择明确的待补或待核对片段，不能提交空清单");
        var sourceEntries=new HashMap<Long,Map<String,Object>>();for(var entry:maps(detail.get("supplierModelEntries")))sourceEntries.put(number(entry.get("supplierIndex")),entry);
        var rows=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();var newIdentities=new HashSet<String>();var blockers=new ArrayList<String>();long fresh=0,append=0,reused=0;
        for(Object raw:(List<?>)input.get("entries")){
            require(raw instanceof Map<?,?>,"待补型号条目格式无效");var item=map(raw);var candidate=classification(item);long index=number(item.get("supplierIndex"));var source=sourceEntries.get(index);require(source!=null,"原型号表不存在该供方序号，请核对原件而不是补造来源");
            var snapshot=sourceSnapshot(detail,source,text(candidate.get("componentText")));snapshot.put("intakeReason",candidate.get("reason"));String hash=evidence(snapshot),componentKey=text(candidate.get("identityKey"));
            require(seen.add(index+":"+componentKey),"同一次提交含重复的来源型号片段");var existing=jdbc.queryForList("select id from plm_phone_model_intake where identity_key=?",candidate.get("identityKey"));String action="new";
            if(!existing.isEmpty()){
                long id=number(existing.get(0).get("id"));
                if(!candidateVisible(id)||!allSourcesVisible(id)){action="blocked";blockers.add("同一型号已有当前无法完整授权核对的依据，请管理员处理；本次不会公开其身份或来源");}
                else{candidate.put("candidateId",id);String state=jdbc.queryForObject("select state from plm_phone_model_intake where id=?",String.class,id);candidate.put("existingState",state);long found=jdbc.queryForObject("select count(*) from plm_phone_model_intake_source where intake_id=? and source_kind=? and record_id=? and row_id=?::uuid and supplier_index=? and component_key=? and source_digest=?",Long.class,id,detail.get("sourceKind"),detail.get("recordId"),detail.get("rowId"),index,componentKey,hash);
                    action=found==0?"append":"unchanged";if(found==0){append++;if("resolved".equals(state))blockers.add("已处理候选追加新来源前，请先明确重新打开，再按正常处理补关联；不能将新来源假定为已完成");}else reused++;}
            }else if(newIdentities.add(text(candidate.get("identityKey"))))fresh++;
            candidate.put("supplierIndex",index);candidate.put("source",snapshot);candidate.put("sourceDigest",hash);candidate.put("componentKey",componentKey);candidate.put("action",action);candidate.put("messages",List.of("人工整理的型号片段；不会生成编码或改写来源原文／状态"));rows.add(candidate);
        }
        var out=new LinkedHashMap<String,Object>();out.put("entries",rows);out.put("summary",Map.of("entryCount",rows.size(),"newCount",fresh,"appendCount",append,"reusedCount",reused));out.put("messages",blockers.isEmpty()?List.of("仅登记待补手机资料与原始依据，不自动创建手机、编码、产品或样品。"):blockers.stream().distinct().toList());out.put("canSave",blockers.isEmpty());out.put("previewToken",digest(out));return out;
    }
    @Transactional public Map<String,Object> collect(Map<String,Object> input,HttpServletRequest request){
        write();String requestId=clientRequest(input);var replay=replay(requestId,"collect",input);if(replay!=null){sourceDetail(input,false);return replay;}
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended('phone-intake-collection',0))");var initial=sourcePlan(input,true);
        var existingIds=maps(initial.get("entries")).stream().map(item->number(item.get("candidateId"))).filter(id->id>0).distinct().sorted().toList();
        for(long id:existingIds)jdbc.queryForList("select id from plm_phone_model_intake where id=? for update",id);
        jdbc.queryForList("select record_id from plm_partner_record where record_id=? for update",number(input.get("recordId")));
        var plan=sourcePlan(input,true);require(Boolean.TRUE.equals(plan.get("canSave")),String.join("；",strings(plan.get("messages"))));requirePreview(input,plan);var resultIds=new LinkedHashSet<Long>();var sameBatch=new HashMap<String,Long>();var evidenceChanges=new ArrayList<Map<String,Object>>();long added=0;
        for(var item:maps(plan.get("entries"))){String identity=text(item.get("identityKey"));long id=sameBatch.getOrDefault(identity,number(item.get("candidateId")));if(id==0){id=jdbc.queryForObject("insert into plm_phone_model_intake(identity_key,component_text,brand_id,classification,review_reason,reason,created_by,updated_by) values(?,?,?,?,?,?,?,?) returning id",Long.class,item.get("identityKey"),item.get("componentText"),item.get("brandId"),item.get("classification"),item.get("reviewReason"),item.get("reason"),access.actor().username(),access.actor().username());}sameBatch.put(identity,id);
            var source=map(item.get("source"));int changed=jdbc.update("insert into plm_phone_model_intake_source(intake_id,supplier_code,source_kind,record_id,row_id,supplier_index,component_key,source_digest,source_snapshot,created_by) values(?,?,?,?,?::uuid,?,?,?,?::jsonb,?) on conflict do nothing",id,source.get("supplierCode"),source.get("sourceKind"),source.get("recordId"),source.get("rowId"),item.get("supplierIndex"),item.get("componentKey"),item.get("sourceDigest"),encode(json,source),access.actor().username());
            added+=changed;if(changed>0){jdbc.update("update plm_phone_model_intake set version=version+1,updated_at=now(),updated_by=? where id=?",access.actor().username(),id);evidenceChanges.add(Map.of("candidateId",id,"supplierIndex",item.get("supplierIndex"),"componentKey",item.get("componentKey"),"sourceDigest",item.get("sourceDigest")));}resultIds.add(id);
        }
        var result=new LinkedHashMap<String,Object>();result.put("items",candidateSummaries(resultIds));result.put("addedSourceCount",added);result.put("replayed",false);var sourceIdentity=new LinkedHashMap<String,Object>();for(String key:List.of("supplierCode","sourceKind","recordId","rowId","rowVersion","recordVersion"))sourceIdentity.put(key,input.get(key));record("collect",requestId,input,Map.of(),Map.of("candidateIds",resultIds,"sourceIdentity",sourceIdentity,"evidenceChanges",evidenceChanges,"summary",plan.get("summary")),result,request);return result;
    }

    @Transactional(readOnly=true) public Map<String,Object> resolutionPreview(long id,Map<String,Object> input){write();return resolutionPlan(load(id,false),input);}
    private Map<String,Object> resolutionPlan(Map<String,Object> stored,Map<String,Object> input){
        var before=publicCandidate(stored);require(input.containsKey("version")&&number(input.get("version"))==number(before.get("version")),"待补型号已变化，请重新载入预览");var original=map(input.get("original"));for(String field:ORIGINAL)require(original.containsKey(field)&&signature(json,original.get(field)).equals(signature(json,before.get(field))),"原值已变化或不完整："+field);
        String action=text(input.get("action"));require(Set.of("create","link","defer","reopen","revise").contains(action),"待补型号处理动作无效");bounded(required(input.get("reason"),"处理依据"),1000,"处理依据");
        String state=text(before.get("state"));require(!state.equals("resolved")||action.equals("reopen"),"已处理项请先重新打开，不能改写原处理记录");
        var after=new LinkedHashMap<>(before);var selected=new ArrayList<Long>();var blockers=new ArrayList<String>();Map<String,Object> proposed=null;
        if(!allSourcesVisible(number(before.get("id"))))blockers.add("部分来源当前无法完整授权核对，本次不能修改候选或跳过来源标记完成");
        if(action.equals("revise")){
            var changes=new LinkedHashMap<String,Object>();for(String key:List.of("componentText","brandId","classification","reviewReason"))changes.put(key,input.containsKey(key)?input.get(key):before.get(key));changes.put("reason",input.get("reason"));var updated=classification(changes);
            var conflicts=jdbc.queryForList("select id from plm_phone_model_intake where identity_key=? and id<>?",updated.get("identityKey"),before.get("id"));if(!conflicts.isEmpty())blockers.add("此品牌与完整型号已有独立待办，请人工核对两项来源，不能静默合并或覆盖");after.putAll(updated);after.put("modelName",updated.get("componentText"));
        }else if(action.equals("create")){
            if(!"missing".equals(before.get("classification")))blockers.add("名称、版本、共模或原文疑点请先核对修订为真正缺档，不能直接新建");
            Long brand=nullable(input.getOrDefault("brandId",before.get("brandId")));String model=bounded(required(input.getOrDefault("modelName",before.get("componentText")),"标准手机名称"),256,"标准手机名称");
            if(brand==null)blockers.add("请先明确品牌");else if(!"enabled".equals(references.brand(brand).get("status")))blockers.add("所选品牌已停用");
            if(!PhoneIdentityMatcher.isSingleModel(model))blockers.add("新建标准手机须为明确单一型号；组合或共模原文不能自动拆档");
            var aliases=strings(input.get("aliases"));require(aliases.size()<=30,"别名最多30项");
            if(brand!=null){var actual=references.brand(brand);String identity=PhoneIdentityMatcher.identityKey(model,actual);if(references.matchingPhones().stream().anyMatch(phone->number(phone.get("brandId"))==brand&&modelNames(phone).stream().anyMatch(name->identity.equals(PhoneIdentityMatcher.identityKey(name,actual)))))blockers.add("现有资料已有该品牌与型号或明确别名，请改用关联已有手机，不能重复建档");var explicitBrands=PhoneIdentityMatcher.sourceBrands("",model,repository.allBrands());if(!explicitBrands.isEmpty()&&explicitBrands.stream().noneMatch(value->number(value.get("brandId"))==brand))blockers.add("拟标准名称明确指向其他品牌，请先核对品牌选择");}
            var confirmed=classification(new LinkedHashMap<>(valuesForClassification(model,brand,"missing",text(before.get("reviewReason")),text(input.get("reason")))));
            if(!jdbc.queryForList("select id from plm_phone_model_intake where identity_key=? and id<>?",confirmed.get("identityKey"),before.get("id")).isEmpty())blockers.add("确认后的品牌与完整型号已有独立待办，请人工核对，不能静默合并");
            after.putAll(confirmed);after.put("modelName",model);proposed=new LinkedHashMap<>();proposed.put("brandId",brand);proposed.put("modelName",model);proposed.put("aliases",aliases);proposed.put("codeItemId",null);after.put("state","resolved");
        }else if(action.equals("link")){
            selected.addAll(ids(input.get("phoneModelIds")));require(!selected.isEmpty(),"请选择明确的已有手机型号");references.requireEnabledModels(selected);after.put("state","resolved");after.put("resolvedPhoneModelIds",selected);
        }else after.put("state",action.equals("defer")?"deferred":"pending");
        var sourceViews=new ArrayList<Map<String,Object>>();long changed=0;
        if(Set.of("create","link").contains(action)){
            long all=jdbc.queryForObject("select count(*) from plm_phone_model_intake_source where intake_id=?",Long.class,before.get("id"));long visible=number(before.get("sourceCount"));if(all!=visible)blockers.add("部分来源当前无法授权核对，本次不能跳过来源并标记完成");
            // Confirmed newer evidence supersedes its own old snapshot for processing; history remains immutable.
            var rows=jdbc.queryForList("select distinct on(s.source_kind,s.record_id,s.row_id,s.supplier_index) s.*"+sourceFrom()+"where s.intake_id=? and "+visibleSources()+" order by s.source_kind,s.record_id,s.row_id,s.supplier_index,s.id desc",before.get("id"));var cache=new HashMap<String,Map<String,Object>>();var seen=new HashSet<String>();
            for(var source:rows){var view=publicSource(source,cache);try{
                require(!Boolean.TRUE.equals(view.get("sourceUnavailable")),"当前来源无法授权读取或款式归属有变，本次不能跳过处理");var current=cache.get(sourceKey(view));require(Boolean.TRUE.equals(current.get("canMaintainModelSheet")),"来源已冻结或没有当前款式维护权限");var entry=findEntry(current,number(view.get("supplierIndex")));require(evidence(sourceSnapshot(current,entry,text(view.get("componentText")))).equals(text(source.get("source_digest"))),"来源原型号、状态、备注或型号表图已有变更，请先重新整理依据");
                var old=ids(entry.get("phoneModelIds"));var added=selected.stream().filter(phone->!old.contains(phone)).toList();boolean will=action.equals("create")||!added.isEmpty();view.put("currentRowVersion",current.get("rowVersion"));view.put("currentRecordVersion",current.get("recordVersion"));view.put("addedPhoneModelIds",added);view.put("alreadyLinkedPhoneModelIds",selected.stream().filter(old::contains).toList());view.put("requiresNewPhone",action.equals("create"));view.put("willUpdate",will);String key=sourceKey(view)+":"+view.get("supplierIndex");if(will&&seen.add(key))changed++;
            }catch(BusinessException failure){blockers.add(failure.getMessage());view.put("willUpdate",false);}sourceViews.add(view);}
        }
        var out=new LinkedHashMap<String,Object>();out.put("action",action);out.put("before",before);out.put("after",after);if(proposed!=null)out.put("proposedPhone",proposed);out.put("sources",sourceViews);out.put("summary",Map.of("sourceCount",sourceViews.size(),"changedSourceCount",changed));out.put("blockers",blockers.stream().distinct().toList());out.put("messages",List.of("只补当前明确来源款式的关联；原型号、现货／开模／试模、图片和既有选品快照不变，不分配手机编码。"));out.put("canSave",blockers.isEmpty());out.put("previewToken",digest(out));return out;
    }
    @Transactional public Map<String,Object> resolve(long id,Map<String,Object> input,HttpServletRequest request){
        write();String requestId=clientRequest(input);var replay=replay(requestId,"resolve:"+id,input);var stored=load(id,true);if(replay!=null)return replay;
        jdbc.execute("select pg_advisory_xact_lock(66817399)");var plan=resolutionPlan(stored,input);require(Boolean.TRUE.equals(plan.get("canSave")),String.join("；",strings(plan.get("blockers"))));requirePreview(input,plan);
        String action=text(input.get("action"));var selected=new ArrayList<Long>(ids(map(plan.get("after")).get("resolvedPhoneModelIds")));Long created=null;
        if(action.equals("create")){var proposed=map(plan.get("proposedPhone"));var phone=references.createPhone(new PhoneModelReferenceTypes.CreatePhone(nullable(proposed.get("brandId")),text(proposed.get("modelName")),strings(proposed.get("aliases")),null),request);created=number(phone.get("id"));selected=new ArrayList<>(List.of(created));}
        long updated=0;if(Set.of("create","link").contains(action))updated=applySources(number(stored.get("id")),maps(plan.get("sources")),selected,text(input.get("reason")),requestId);
        var after=map(plan.get("after"));String identity=text(after.getOrDefault("identityKey",stored.get("identity_key")));
        try{require(jdbc.update("update plm_phone_model_intake set identity_key=?,component_text=?,brand_id=?,classification=?,review_reason=?,reason=?,state=?,resolved_phone_ids=?::bigint[],version=version+1,updated_at=now(),updated_by=? where id=? and version=?",identity,after.get("componentText"),after.get("brandId"),after.get("classification"),after.get("reviewReason"),input.get("reason"),after.get("state"),"{"+String.join(",",selected.stream().map(String::valueOf).toList())+"}",access.actor().username(),id,stored.get("version"))==1,"待补型号已被其他人处理，请重新预览");}catch(org.springframework.dao.DuplicateKeyException duplicate){throw invalid("品牌与完整型号已有独立待办，不能静默合并");}
        var result=new LinkedHashMap<String,Object>();result.put("item",detail(id));result.put("replayed",false);result.put("updatedSourceCount",updated);if(created!=null)result.put("createdPhoneModelId",created);record("resolve:"+id,requestId,input,map(plan.get("before")),detail(id),result,request);return result;
    }
    private long applySources(long intake,List<Map<String,Object>> sources,List<Long> selected,String why,String request){
        var grouped=new LinkedHashMap<String,List<Map<String,Object>>>();for(var source:sources)grouped.computeIfAbsent(sourceKey(source),key->new ArrayList<>()).add(source);long updated=0;
        var records=sources.stream().map(source->number(source.get("recordId"))).distinct().sorted().toList();for(long record:records)jdbc.queryForList("select record_id from plm_partner_record where record_id=? for update",record);
        var originals=new HashMap<String,Map<String,Object>>();var headerVersions=new HashMap<Long,Long>();
        for(var group:grouped.values()){var first=group.get(0);var current=sourceDetail(first,false);require(number(current.get("rowVersion"))==number(first.get("currentRowVersion"))&&number(current.get("recordVersion"))==number(first.get("currentRecordVersion")),"来源已在预览后变化，整次处理未生效，请重新核对");originals.put(sourceKey(first),current);headerVersions.put(number(current.get("recordId")),number(current.get("recordVersion")));}
        for(var group:grouped.values()){
            var first=group.get(0);var current=originals.get(sourceKey(first));
            var indexes=new HashSet<Long>();for(var source:group)indexes.add(number(source.get("supplierIndex")));var entries=new ArrayList<Map<String,Object>>();boolean changed=false;
            for(var entry:maps(current.get("supplierModelEntries"))){var next=new LinkedHashMap<String,Object>();for(String field:MODEL_INPUT)if(entry.containsKey(field))next.put(field,entry.get(field));if(indexes.contains(number(entry.get("supplierIndex")))){var phones=new LinkedHashSet<Long>(ids(entry.get("phoneModelIds")));int before=phones.size();phones.addAll(selected);if(phones.size()!=before){changed=true;next.put("phoneModelIds",new ArrayList<>(phones));next.put("matchNote",bounded((text(entry.get("matchNote"))+"；待补手机资料 #"+intake+" 工程确认："+why).replaceFirst("^；",""),12000,"匹配依据"));}}entries.add(next);}
            if(!changed)continue;var command=new LinkedHashMap<String,Object>();for(String field:List.of("supplierCode","sourceKind","recordId","rowId","rowVersion","recordVersion"))command.put(field,current.get(field));command.put("recordVersion",headerVersions.get(number(current.get("recordId"))));command.put("entries",entries);command.put("modelSheetRefs",maps(current.get("modelSheetRefs")).stream().map(ref->Map.of("id",ref.get("id"))).toList());var preview=catalog.modelSheetPreview(command);require(Boolean.TRUE.equals(preview.get("canSave")),"当前来源型号表不能安全补关联");command.put("previewToken",preview.get("previewToken"));command.put("requestId","intake-"+digest(Arrays.asList(request,sourceKey(first))).substring(0,48));var saved=catalog.saveModelSheet(command);headerVersions.put(number(current.get("recordId")),number(map(saved.get("style")).get("recordVersion")));updated++;
        }return updated;
    }
    private Map<String,Object> findEntry(Map<String,Object> source,long index){return maps(source.get("supplierModelEntries")).stream().filter(entry->number(entry.get("supplierIndex"))==index).findFirst().orElseThrow(()->invalid("当前原型号表已缺少该序号，不能猜补来源"));}
    private List<String> modelNames(Map<String,Object> phone){var names=new ArrayList<String>();names.add(text(phone.get("modelName")));for(String field:List.of("aliases","matchingAliases"))for(String value:strings(phone.get(field)))if(PhoneIdentityMatcher.isSingleModel(value))names.add(value);return names;}
    private Map<String,Object> valuesForClassification(String component,Long brand,String kind,String review,String reason){var out=new LinkedHashMap<String,Object>();out.put("componentText",component);out.put("brandId",brand);out.put("classification",kind);out.put("reviewReason",review);out.put("reason",reason);return out;}
    private void requirePreview(Map<String,Object> input,Map<String,Object> plan){require(text(plan.get("previewToken")).equals(required(input.get("previewToken"),"请先预览")),"待补项、手机资料或来源已变化，请重新预览");}
    private String clientRequest(Map<String,Object> input){String key=required(input.get("requestId"),"提交标识");require(key.length()<=80,"提交标识过长");return key;}
    private Map<String,Object> replay(String request,String operation,Map<String,Object> input){
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))","phone-intake:"+access.actorId()+":"+request);
        var saved=jdbc.queryForList("select detail_json from plm_operation_log where business_type='PHONE_MODEL_INTAKE' and operator_user_id=? and deleted_flag=0 and result='success' and detail_json::jsonb->>'requestId'=? order by log_id desc limit 1",access.actorId(),request);
        if(saved.isEmpty())return null;var record=decode(json,saved.get(0).get("detail_json"));require(operation.equals(record.get("operation"))&&digest(input).equals(record.get("fingerprint")),"提交标识已用于其他待补型号操作");var result=new LinkedHashMap<>(map(record.get("result")));
        if(result.containsKey("item")){long id=number(map(result.get("item")).get("id"));load(id,false);require(allSourcesVisible(id),"原提交涉及当前无法完整核对的来源，请恢复授权后查看原结果");result.put("item",detail(id));}
        if(result.containsKey("items"))result.put("items",candidateSummaries(maps(result.get("items")).stream().map(item->number(item.get("id"))).toList()));result.put("replayed",true);return result;
    }
    private void record(String operation,String requestId,Map<String,Object> input,Map<String,Object> before,Map<String,Object> after,Map<String,Object> result,HttpServletRequest request){
        var details=new LinkedHashMap<String,Object>();details.put("operation",operation);details.put("requestId",requestId);details.put("fingerprint",digest(input));details.put("before",before);details.put("after",after);var savedResult=new LinkedHashMap<>(result);if(operation.equals("collect"))savedResult.put("items",maps(result.get("items")).stream().map(item->Map.of("id",item.get("id"))).toList());details.put("result",savedResult);
        audit.logRequired(OperationLogCreateCommand.builder().action("PHONE_INTAKE_"+(operation.startsWith("resolve")?"RESOLVE":"COLLECT")).businessType("PHONE_MODEL_INTAKE").businessId(operation.startsWith("resolve:")?operation.substring(8):"collection").businessName("待补手机型号").detailJson(encode(json,details)).request(request).build(),access.actor());
    }
    private String digest(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signature(json,value).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}}
    private static Long nullable(Object value){return value==null||text(value).isEmpty()?null:number(value);}
    private static String bounded(String value,int max,String name){require(value.length()<=max,name+"过长");return value;}
    private static List<String> strings(Object value){require(value==null||value instanceof List<?>,"文字清单格式无效");return value==null?List.of():((List<?>)value).stream().map(v->text(v)).toList();}
    private static long pageNumber(Map<String,String> query){long page=number(query.getOrDefault("page","1"));require(page>0&&page<=Integer.MAX_VALUE,"页码无效");return page;}
    private static long pageSize(Map<String,String> query){long size=number(query.getOrDefault("size","30"));require(size>0&&size<=100,"每页须为1至100条");return size;}
    private static Map<String,Object> pageResult(Object content,long total,long page,long size){var out=new LinkedHashMap<String,Object>();out.put("content",content);out.put("page",page);out.put("size",size);out.put("totalElements",total);out.put("totalPages",(total+size-1)/size);return out;}
}

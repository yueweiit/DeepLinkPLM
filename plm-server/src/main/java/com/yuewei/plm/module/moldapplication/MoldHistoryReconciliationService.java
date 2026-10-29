package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.util.ProductCodeGenerator;
import com.yuewei.plm.module.moldreference.*;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.workflow.service.WorkflowTemplateService;
import com.yuewei.plm.repository.ProductRepository;
import com.yuewei.plm.repository.entity.Product;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.yuewei.plm.module.moldapplication.MoldApplicationForms.*;

/** Evidence-based metadata repair of the existing ledger. Never calls live OA or provisions physical sets. */
@Service @RequiredArgsConstructor
public class MoldHistoryReconciliationService {
    private final MoldApplicationRepository repo;
    private final MoldApplicationService service;
    private final MoldApplicationConversion conversion;
    private final PhoneModelReferenceService references;
    private final PilotAccessService access;
    private final ProductRepository products;
    private final ProductCodeGenerator codes;
    private final WorkflowTemplateService templates;
    private final PlatformTransactionManager transactions;
    public record Selection(@Positive long applicationId,@Positive long version,@NotBlank String fingerprint,Long phoneModelId) {public Selection(long id,long version,String fingerprint){this(id,version,fingerprint,null);}}
    public record Apply(@NotEmpty @Size(max=1000) List<@Valid Selection> items,@NotBlank @Size(max=1000) String reason) {}
    private record Context(List<Map<String,Object>> brands,List<Map<String,Object>> phones,List<Map<String,Object>> lines,List<Map<String,Object>> items,List<Map<String,Object>> sets,Map<String,Set<String>> networks) {}

    private Context context() {
        var lines=repo.jdbc().queryForList("select p.product_id as \"productLineId\",p.product_name as \"productName\",p.product_specific_code as prefix,to_json(p.application_aliases)::text as aliases from plm_product p where p.deleted_flag=0 and p.product_type='product_line' and ("+access.visibleSqlClause("product","p.product_id")+")");
        var items=repo.jdbc().queryForList("select i.item_id as \"itemId\",i.product_line_id as \"productLineId\",i.compatible_models as model,i.style_name as \"styleName\",i.phone_model_code as code,i.phone_model_code_item_id as \"codeItemId\",i.mold_name as \"moldName\",i.engraved_code as \"engravedCode\" from plm_collaboration_item i where i.deleted_flag=0 and ("+access.visibleSqlClause("item","i.item_id")+")");
        var sets=repo.jdbc().queryForList("""
            select s.item_id as "itemId",s.mold_set_id as "moldSetId",s.set_no as "setNo",s.opened_date as "openedDate",s.actual_ship_date as "actualShipDate",
              r.batch_id as "importBatchId",r.sheet_name as "sheetName",r.row_no as "rowNo",b.file_name as "fileName",b.file_hash as "fileHash"
            from plm_collaboration_mold_set s join plm_collaboration_item i on i.item_id=s.item_id and i.deleted_flag=0
            left join plm_collaboration_import_row r on r.mold_set_id=s.mold_set_id and r.item_id=s.item_id
            left join plm_collaboration_import_batch b on b.batch_id=r.batch_id
            where s.deleted_flag=0 and (%s) and (%s)
            order by s.mold_set_id,r.row_no
            """.formatted(access.visibleSqlClause("mold","s.mold_set_id"),access.visibleSqlClause("item","i.item_id")));
        var networks=new HashMap<String,Set<String>>();
        for(var row:repo.jdbc().queryForList("select a.model,a.form_data->>'networkType' network from plm_mold_application a where a.deleted_flag=0 and a.source_system='dingtalk' and ("+service.visible()+")")) {
            String model=PhoneIdentityMatcher.normalize(Objects.toString(row.get("model"),""));
            String network=network(Objects.toString(row.get("network"),""));
            if(!network.isBlank())networks.computeIfAbsent(model,k->new TreeSet<>()).add(network);
        }
        return new Context(references.matchingBrands(),references.matchingPhones(),lines,items,sets,networks);
    }
    public Map<String,Object> preview(){
        service.convertPermission();var c=context();var rows=new ArrayList<Map<String,Object>>();
        for(long id:repo.jdbc().queryForList("select a.application_id from plm_mold_application a where a.deleted_flag=0 and a.source_system='dingtalk' and ("+service.visible()+") order by a.application_id",Long.class))rows.add(decide(service.require(id,false),c));
        return report(rows);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> report(List<Map<String,Object>> rows){
        var groups=new LinkedHashMap<String,Map<String,Object>>();
        for(var row:rows){String key=Objects.toString(row.get("phoneModelId"),PhoneIdentityMatcher.normalize(Objects.toString(row.get("model"),"")))+":"+Objects.toString(row.get("phoneCode"),"");
            var group=groups.computeIfAbsent(key,k->{var g=new LinkedHashMap<String,Object>();g.put("groupKey",k);g.put("phoneModelId",row.get("phoneModelId"));g.put("phoneName",row.get("phoneName")!=null?row.get("phoneName"):row.get("model"));g.put("phoneCode",row.get("phoneCode"));g.put("applications",new ArrayList<Map<String,Object>>());return g;});
            ((List<Map<String,Object>>)group.get("applications")).add(row);
        }
        return Map.of("total",rows.size(),"actionable",rows.stream().filter(r->Boolean.TRUE.equals(r.get("actionable"))).count(),"linked",rows.stream().filter(r->"linked".equals(r.get("state"))).count(),"groups",List.copyOf(groups.values()),"items",rows);
    }
    private Map<String,Object> decide(Map<String,Object> app,Context c){
        var out=new LinkedHashMap<String,Object>();for(String key:List.of("applicationId","approvalNo","instanceId","version","model","tipo","businessStatus","phoneModelId","productLineId","projectId","itemId","moldSetId"))out.put(key,app.get(key));
        var gaps=new ArrayList<Map<String,Object>>();var evidence=new ArrayList<Object>();
        String model=Objects.toString(app.get("model"),""),tipo=Objects.toString(app.get("tipo"),"");
        JsonNode form=(JsonNode)app.get("form");
        var raw=repo.jdbc().queryForObject("select raw_snapshot::text from plm_mold_application where application_id=?",String.class,app.get("applicationId"));
        var authority=approvalEvidence(repo.node(raw));boolean authorized=authority.taskAuthorized()&&Boolean.TRUE.equals(app.get("approvalGranted"));
        out.put("authorized",authorized);out.put("authorization",authority.authorization());
        boolean ended=Set.of("rejected","withdrawn","terminated").contains(Objects.toString(app.get("businessStatus"),""));
        boolean test=model.matches("(?is).*(测试|test|demo|演示|11111).*" );
        var fullCodes=hasValidCodes(text(app.get("generatedCodes")))?MoldApplicationForms.codes(text(app.get("generatedCodes"))):List.<String>of();
        var codeValues=new TreeSet<String>();var prefixes=new TreeSet<String>();for(String value:fullCodes){codeValues.add(value.substring(5));prefixes.add(value.substring(1,3));}
        String code=codeValues.size()==1?codeValues.iterator().next():null;out.put("phoneCode",code);out.put("rawCodes",app.get("generatedCodes"));
        String variant=model.toLowerCase(Locale.ROOT).contains("复制模")||model.toLowerCase(Locale.ROOT).contains("copia")?"复制模 copia":"";if(tipo.contains("磨砂"))variant+=(variant.isEmpty()?"":" · ")+"磨砂款";
        String identityModel=!variant.contains("复制模")?model:model.replaceAll("(?i)\\s*[（(]复制模\\s*copia[）)]","").strip();out.put("variant",variant);
        String formNetwork=network(form.path("networkType").asText());
        boolean networkConflict=!identityModel.toLowerCase(Locale.ROOT).matches(".*[45]g.*")&&c.networks().getOrDefault(PhoneIdentityMatcher.normalize(model),Set.of()).size()>1;
        String qualifiedModel=!formNetwork.isBlank()&&!identityModel.toLowerCase(Locale.ROOT).matches(".*[45]g.*")?identityModel+" "+formNetwork:identityModel;
        var phoneMatch=PhoneIdentityMatcher.match("",qualifiedModel,c.brands(),c.phones());
        if(phoneMatch.phoneModelId()==null&&!networkConflict)phoneMatch=PhoneIdentityMatcher.match("",identityModel,c.brands(),c.phones());
        Long phone=id(app.get("phoneModelId"));if(phone==null)phone=phoneMatch.phoneModelId();
        if(code!=null&&PhoneIdentityMatcher.isSingleModel(identityModel)){
            final String expectedCode=code;var byCode=c.phones().stream().filter(p->expectedCode.equals(p.get("phoneModelCode"))).filter(p->samePhone(qualifiedModel,p,c.brands())||samePhone(identityModel,p,c.brands())).toList();if(byCode.size()==1)phone=id(byCode.get(0).get("phoneModelId"));
        }
        if(networkConflict){phone=id(app.get("phoneModelId"));gap(gaps,"network_conflict","同一原型号的审批同时填写4G与5G；须一次明确标准成员和对应申请范围","/phone-model-codes",Map.of("tab","history","keyword",model));}
        Map<String,Object> member=null;for(var p:c.phones())if(Objects.equals(phone,id(p.get("phoneModelId")))){member=p;break;}
        if(member!=null&&code!=null&&!code.equals(member.get("phoneModelCode"))){boolean exactIdentity=Objects.equals(phoneMatch.phoneModelId(),phone);gap(gaps,exactIdentity?"original_mold_code_differs":"code_conflict",exactIdentity?"手机身份已明确；审批预留刻码与手机编码不同，保留原文，主档及适用套仍须有独立依据":"审批刻码与标准手机不一致且身份依据不足，请先核对",exactIdentity?"/molds":"/phone-model-codes",Map.of("keyword",code));if(!exactIdentity){phone=null;member=null;}}
        var brandMatches=PhoneIdentityMatcher.sourceBrands("",qualifiedModel,c.brands());
        boolean createPhone=phone==null&&!networkConflict&&authorized&&!ended&&!test&&PhoneIdentityMatcher.isSingleModel(qualifiedModel)&&brandMatches.size()==1&&phoneMatch.candidateIds().isEmpty()&&code==null;
        out.put("createPhone",createPhone);out.put("identityName",qualifiedModel);out.put("identityBrandId",brandMatches.size()==1?brandMatches.get(0).get("brandId"):null);
        out.put("phoneCandidates",phoneMatch.candidateIds());out.put("phoneModelId",phone);out.put("phoneName",member==null?null:member.get("modelName"));
        if(member!=null){out.put("phoneCode",member.get("phoneModelCode"));evidence.add(Map.of("kind","standard_phone","id",phone,"model",member.get("modelName")));}
        if(phone==null&&!test&&!createPhone)gap(gaps,"phone_identity",PhoneIdentityMatcher.isSingleModel(identityModel)?"缺少唯一标准手机关系；按明确品牌别名、编码和网络版本维护一次后重试":"组合型号保留原文；须明确成员及共用关系，不能拆分猜配","/phone-model-codes",Map.of("tab","history","keyword",code==null?model:code));
        var lines=new ArrayList<Map<String,Object>>();
        for(var line:c.lines()){
            var names=new ArrayList<String>();names.add(Objects.toString(line.get("productName"),""));repo.node(line.get("aliases")).forEach(a->names.add(a.asText()));
            if(names.stream().anyMatch(n->PhoneIdentityMatcher.normalize(n).equals(PhoneIdentityMatcher.normalize(tipo)))||(prefixes.size()==1&&line.get("prefix")!=null&&prefixes.contains(line.get("prefix"))))lines.add(line);
        }
        Long line=id(app.get("productLineId"));if(line==null&&lines.size()==1)line=id(lines.get(0).get("productLineId"));
        out.put("productLineId",line);out.put("productLineName",lines.size()==1?lines.get(0).get("productName"):tipo);
        if(line==null&&!test)gap(gaps,"product_mapping","原款式缺少唯一产品映射；在产品在售及审批别名维护一次后批量重试","/market-watch",Map.of("panel","products"));
        List<Map<String,Object>> candidates=new ArrayList<>();
        if(member!=null&&line!=null&&variant.isEmpty())for(var item:c.items())if(Objects.equals(id(item.get("productLineId")),line)&&samePhone(Objects.toString(item.get("model"),""),member,c.brands())&&(item.get("code")==null||Objects.toString(item.get("code")).isBlank()||Objects.equals(item.get("code"),member.get("phoneModelCode"))))candidates.add(item);
        out.put("itemCandidates",candidates);
        Long item=id(app.get("itemId"));if(item==null&&candidates.size()==1)item=id(candidates.get(0).get("itemId"));out.put("itemId",item);
        Long set=id(app.get("moldSetId"));var setCandidates=new ArrayList<Map<String,Object>>();
        if(item!=null)for(var s:c.sets())if(Objects.equals(id(s.get("itemId")),item))setCandidates.add(s);
        var distinctSets=new TreeSet<Long>();for(var s:setCandidates)distinctSets.add(id(s.get("moldSetId")));
        // A unique imported set plus exact phone/product identity and real dates establishes a historical source link.
        if(set==null&&distinctSets.size()==1&&setCandidates.stream().anyMatch(s->s.get("importBatchId")!=null&&s.get("fileHash")!=null&&(s.get("openedDate")!=null||s.get("actualShipDate")!=null)))set=distinctSets.iterator().next();
        out.put("moldSetId",set);out.put("setCandidates",setCandidates);
        if(item!=null)evidence.add(Map.of("kind","archive","itemId",item,"basis","相同标准手机、正式产品及明确款式"));
        if(set!=null)evidence.addAll(setCandidates.stream().filter(s->Objects.equals(s.get("moldSetId"),out.get("moldSetId"))).toList());
        if(item==null&&!test)gap(gaps,"archive",candidates.size()>1?"存在多个主档，须按原款式和刻码确认目标":"尚无同身份同款式的明确主档；先登记项目，不补造模具","/molds",Map.of("keyword",code==null?model:code));
        if(set==null&&!test)gap(gaps,"set",distinctSets.size()>1?"主档有多套，缺少申请到具体套的来源依据":"缺少唯一具体套及导入／开工依据；不新增历史实物套","/molds",item==null?Map.of("keyword",code==null?model:code):Map.of("itemId",item));
        Long project=id(app.get("projectId"));boolean create=createPhone&&line!=null;
        if(project==null&&phone!=null&&line!=null&&authorized&&!ended&&!test&&!networkConflict){
            var projects=repo.jdbc().queryForList("select p.product_id as \"projectId\" from plm_product p where p.parent_product_id=? and p.phone_model_member_id=? and p.project_kind='model_extension' and p.mold_development_variant=? and p.deleted_flag=0",line,phone,variant);
            if(projects.size()==1){long target=id(projects.get(0).get("projectId"));if(repo.jdbc().queryForObject("select count(*) from plm_product p where p.product_id=? and ("+access.visibleSqlClause("product","p.product_id")+")",Integer.class,target)>0)project=target;else gap(gaps,"project_permission","已有项目未获授权，请工程管理员处理；不返回其详情","/market-watch",Map.of());}
            else if(projects.isEmpty())create=true;else gap(gaps,"project_conflict","同身份同款式有多个正式项目，请工程合并确认","/projects",Map.of());
        }
        out.put("projectId",project);out.put("createProject",create);
        if(!authorized&&!ended&&!test)gap(gaps,"authorization","缺少周汉琴真实账号的完整任务同意证据；可同步审批后整组重试","/mold-applications",Map.of("applicationId",app.get("applicationId")));
        if(ended)gap(gaps,"ended","已拒绝、撤回或终止；保留事实关联，不启动新项目和执行","/mold-applications",Map.of("applicationId",app.get("applicationId")));
        if(test)gap(gaps,"test","测试或占位型号，排除自动项目登记","/mold-applications",Map.of("applicationId",app.get("applicationId")));
        boolean changed=(phone!=null||createPhone)&&line!=null&&!test&&(create||!Objects.equals(phone,app.get("phoneModelId"))||!Objects.equals(line,app.get("productLineId"))||!Objects.equals(project,app.get("projectId"))||!Objects.equals(item,app.get("itemId"))||!Objects.equals(set,app.get("moldSetId")));
        out.put("actionable",changed);out.put("state",test?"test":ended?"ended":changed?(gaps.isEmpty()?"ready":"partial"):gaps.isEmpty()&&project!=null&&item!=null&&set!=null?"linked":"needs_information");out.put("gaps",gaps);out.put("evidence",evidence);
        out.put("fingerprint",MoldApplicationMedia.hash(repo.json(Arrays.asList(app.get("applicationId"),app.get("version"),app.get("phoneModelId"),member!=null&&member.get("codeItemId")!=null?phone:qualifiedModel,out.get("identityBrandId"),line,item,set,variant,authorized,ended,test,networkConflict)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return out;
    }
    public Map<String,Object> apply(Apply input,HttpServletRequest request){
        service.convertPermission();if(input==null||input.items()==null||input.items().isEmpty()||input.items().size()>1000||text(input.reason())==null)throw invalid("请提交预检记录及原因");
        var ids=new HashSet<Long>();for(var s:input.items())if(!ids.add(s.applicationId()))throw invalid("补链申请重复");
        var results=new ArrayList<Map<String,Object>>();
        for(var choice:input.items()){
            try{results.add(new TransactionTemplate(transactions).execute(tx->{
                repo.jdbc().execute("select pg_advisory_xact_lock(610131103)");var app=service.require(choice.applicationId(),true);
                var decision=decide(app,context());
                if(id(app.get("version"))!=choice.version()||!choice.fingerprint().equals(decision.get("fingerprint"))||choice.phoneModelId()!=null&&!Objects.equals(choice.phoneModelId(),decision.get("phoneModelId")))throw new BusinessException(40901,"资料或预检依据已变化，请重新预检后重试");
                if(!Boolean.TRUE.equals(decision.get("actionable")))return Map.of("applicationId",choice.applicationId(),"status","unchanged");
                Long phoneId=id(decision.get("phoneModelId"));
                if(Boolean.TRUE.equals(decision.get("createPhone"))){var member=references.createPhone(new PhoneModelReferenceTypes.CreatePhone(id(decision.get("identityBrandId")),decision.get("identityName").toString(),List.of(app.get("model").toString()),null),request);phoneId=id(member.get("phoneModelId"));decision.put("phoneModelId",phoneId);decision.put("phoneName",member.get("modelName"));}
                long phone=phoneId,line=id(decision.get("productLineId"));service.lockPhones(phone);
                Long project=id(decision.get("projectId"));if(Boolean.TRUE.equals(decision.get("createProject"))){project=createProject(app,decision,request);if(decision.get("itemId")!=null)repo.jdbc().update("insert into plm_collaboration_item_project(item_id,project_id,linked_by) values(?,?,?) on conflict(project_id) do nothing",decision.get("itemId"),project,access.actor().username());}
                var selection=new MoldApplicationConversion.Association(choice.version(),phone,line,project,id(decision.get("itemId")),id(decision.get("moldSetId")),input.reason()+"；证据："+repo.json(decision.get("evidence")),!Objects.equals(app.get("phoneModelId"),phone)&&app.get("phoneModelId")!=null);
                conversion.associateHistory(choice.applicationId(),selection,request);
                return Map.of("applicationId",choice.applicationId(),"status","saved","links",Map.of("phoneModelId",phone,"productLineId",line),"projectId",project==null?0:project,"itemId",decision.get("itemId")==null?0:decision.get("itemId"),"moldSetId",decision.get("moldSetId")==null?0:decision.get("moldSetId"));
            }));}catch(BusinessException e){results.add(Map.of("applicationId",choice.applicationId(),"status","conflict","message",e.getMessage()));}
        }
        return Map.of("saved",results.stream().filter(r->"saved".equals(r.get("status"))).count(),"results",results,"remaining",preview());
    }
    private long createProject(Map<String,Object> app,Map<String,Object> d,HttpServletRequest request){
        long line=id(d.get("productLineId")),phone=id(d.get("phoneModelId"));access.requireProduct(line);String variant=Objects.toString(d.get("variant"),"");
        var existing=repo.jdbc().queryForList("select product_id from plm_product where parent_product_id=? and phone_model_member_id=? and project_kind='model_extension' and mold_development_variant=? and deleted_flag=0",Long.class,line,phone,variant);
        if(existing.size()==1){access.requireProduct(existing.get(0));return existing.get(0);}if(!existing.isEmpty())throw invalid("已有多个正式项目，须先处理冲突");
        Product parent=products.selectById(line);String name=parent.getProductName()+" · "+d.get("phoneName")+(variant.isEmpty()?"":" · "+variant);
        String productCode=codes.generate(name);
        Product p=new Product();p.setParentProductId(line);p.setProductCode(productCode);p.setProductName(name);p.setProductType("model_variant");p.setProductSpecificCode(parent.getProductSpecificCode());p.setSeriesName(parent.getSeriesName());p.setModel(Objects.toString(d.get("phoneName")));p.setPhoneModelMemberId(phone);p.setPhoneModelCode(text(d.get("phoneCode")));p.setVersionNo("A");p.setStatus("developing");p.setLockStatus("unlocked");p.setCurrentStepNo(1);p.setTimelineCurrentConfirmed(false);p.setSourceSystem("dingtalk");p.setSourceInstanceId(text(app.get("instanceId")));p.setSourceFormUrl(text(((JsonNode)app.get("form")).path("website")));
        p.setOwnerUserId((Long)app.get("applicantId"));String expected=text(((JsonNode)app.get("form")).path("expectedDeliveryDate"));if(expected!=null)try{p.setExpectedDeliveryDate(LocalDate.parse(expected));}catch(java.time.format.DateTimeParseException ignored){}
        var template=templates.findActiveTemplate("model_variant");if(template!=null){p.setWorkflowTemplateId(template.getWorkflowTemplateId());p.setWorkflowTemplateVersionNo(template.getVersionNo());}
        String actor=access.actor().username();p.setCreatedBy(actor);p.setUpdatedBy(actor);p.setCreatedAt(LocalDateTime.now());p.setUpdatedAt(LocalDateTime.now());p.setDeletedFlag(0);products.insert(p);
        repo.jdbc().update("update plm_product set project_kind='model_extension',project_registered_at=now(),mold_development_variant=? where product_id=?",variant,p.getProductId());
        service.audit("MOLD_HISTORY_PROJECT_REGISTER",id(app.get("applicationId")),Map.of("projectId",p.getProductId(),"authorization",d.get("authorization"),"sourceInstanceId",app.get("instanceId"),"evidence",d.get("evidence")),request,access.actor());return p.getProductId();
    }
    @SuppressWarnings("unchecked") private static boolean samePhone(String name,Map<String,Object> phone,List<Map<String,Object>> brands){
        if(!PhoneIdentityMatcher.isSingleModel(name))return false;
        var brand=brands.stream().filter(b->Objects.equals(b.get("brandId"),phone.get("brandId"))).findFirst().orElse(null);if(brand==null)return false;
        var inferred=PhoneIdentityMatcher.sourceBrands("",name,brands);if(inferred.size()!=1||!Objects.equals(inferred.get(0).get("brandId"),phone.get("brandId")))return false;
        String key=PhoneIdentityMatcher.identityKey(name,brand);var names=new ArrayList<String>();names.add(Objects.toString(phone.get("modelName"),""));names.addAll((List<String>)phone.getOrDefault("aliases",List.of()));names.addAll((List<String>)phone.getOrDefault("matchingAliases",List.of()));
        return names.stream().filter(PhoneIdentityMatcher::isSingleModel).anyMatch(n->PhoneIdentityMatcher.identityKey(n,brand).equals(key));
    }
    private static void gap(List<Map<String,Object>> gaps,String code,String message,String path,Map<String,Object> query){gaps.add(Map.of("code",code,"message",message,"path",path,"query",query));}
    private static String network(String value){return value.toUpperCase(Locale.ROOT).contains("4G")?"4G":value.toUpperCase(Locale.ROOT).contains("5G")?"5G":"";}
    private static Long id(Object value){return value instanceof Number n?n.longValue():null;}
}

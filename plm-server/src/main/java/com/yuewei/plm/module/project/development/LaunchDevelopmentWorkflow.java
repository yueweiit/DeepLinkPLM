package com.yuewei.plm.module.project.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.collaboration.MoldScheduleService;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import com.yuewei.plm.module.development.DevelopmentFinanceService;
import com.yuewei.plm.module.development.DevelopmentTypes;
import com.yuewei.plm.repository.entity.Product;
import com.yuewei.plm.service.vo.ProductReleaseGateMissingItemVO;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static com.yuewei.plm.module.project.development.DevelopmentWorkflowStore.*;
import static com.yuewei.plm.module.project.development.DevelopmentRules.*;

/** Launch-specific evidence policy. Existing product, round, document, finance and mold writers own the facts. */
@Component @RequiredArgsConstructor
public class LaunchDevelopmentWorkflow {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PilotAccessService access;
    private final DevelopmentWorkflowStore store;
    private final MoldScheduleService molds;
    private final DevelopmentFinanceService finance;
    private final LaunchReviewWorkflow reviews;
    private final PhoneModelReferenceService phones;
    @org.springframework.beans.factory.annotation.Autowired
    private ResearchLaunchService salesLaunch;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy
    private com.yuewei.plm.module.moldapplication.MoldApplicationService moldApplications;
    public boolean handles(String action){return Set.of("plan_draft","launch_draft","mold_link","mold_unlink","mold_node","mold_restart","no_mold","cost","promotion","presale","launch","shipment_confirm").contains(action);}
    private int stage(Product p,Map<String,Object>s){var r=map(s.get("revision"));return r.isEmpty()?Objects.requireNonNullElse(p.getCurrentStepNo(),1):(int)number(r.get("stage"));}
    private List<Map<String,Object>> records(Object rows){if(!(rows instanceof List<?> list))return new ArrayList<>();return new ArrayList<>(list.stream().map(DevelopmentWorkflowStore::map).toList());}
    public void enrich(long id,Map<String,Object> detail){
        var state=store.load(id);detail.put("plan",map(detail.get("workflow")).get("plan"));
        if(salesLaunch!=null)salesLaunch.enrich(id,detail);
        if(access.hasPermission("schedule:read")){
            List<Long> ids=new ArrayList<>();for(var link:records(state.get("moldLinks"))){long selected=number(link.get("moldSetId"));if(selected==0&&number(link.get("applicationId"))>0){var linked=jdbc.queryForList("select mold_set_id from plm_mold_application where application_id=? and deleted_flag=0 and (project_id=? or product_line_id=?)",Long.class,number(link.get("applicationId")),id,id);if(!linked.isEmpty())selected=number(linked.get(0));}if(selected>0)ids.add(selected);}
            detail.put("molds",ids.isEmpty()?List.of():molds.projectRows(ids));
        }else detail.put("molds",List.of());
        detail.put("applications",access.hasPermission("collaboration:view")&&moldApplications!=null?moldApplications.researchApplications(id):List.of());
        detail.put("unitCostOptions",records(state.get("unitCostVersions")).stream().map(c->{var option=new LinkedHashMap<String,Object>();for(String key:List.of("id","title","bomId","bomVersion","basisType","confirmedAt","confirmedBy","voidReason"))if(c.containsKey(key))option.put(key,c.get(key));return option;}).toList());
        detail.put("canFinanceWrite",access.hasPermission("finance:write")&&access.hasPermission("finance:read"));
        detail.put("canScheduleWrite",access.hasPermission("schedule:write")&&access.hasPermission("schedule:read"));
    }
    public void action(long id,Product p,Map<String,Object>s,String action,Map<String,Object>d,ProductDevelopmentService service){
        boolean parallel=Set.of("cost","promotion","presale").contains(action);
        if(!parallel&&!"launch".equals(action))service.requireDevelopmentEditable(id);
        if(action.equals("plan_draft")){
            require(stage(p,s)==1||stage(p,s)==2||stage(p,s)==5||stage(p,s)==7,"开发计划请在立项或画3D产品图维护");var old=map(s.get("plan"));
            if(stage(p,s)>=5)require(Objects.equals(LaunchReviewWorkflow.planBasis(Map.of("plan",old)),LaunchReviewWorkflow.planBasis(Map.of("plan",d))),"模具设计不能改变已确认的代表型号或开发路径，请返回画图步骤调整");
            var selectedPhones=ids(d.get("phoneModelIds"));var priorPhones=new LinkedHashSet<>(ids(map(s.get("planDraft")).get("phoneModelIds")));priorPhones.addAll(ids(old.get("phoneModelIds")));d.put("phones",phoneSnapshots(selectedPhones,new ArrayList<>(priorPhones)));
            require(Set.of("own_mold","supplier","direct").contains(text(d.get("method")))||text(d.get("method")).isBlank(),"开发方式无效");
            if(s.containsKey("stage2Basis")&&!old.isEmpty()&&!Objects.equals(LaunchReviewWorkflow.planBasis(Map.of("plan",old)),LaunchReviewWorkflow.planBasis(Map.of("plan",d))))filled(d.get("reason"),"计划调整原因");
            validatePlanDates(d);
            d.put("references",service.snapshotReferences(id,service.refs(d.get("references"))));
            s.put("planDraft",d);s.remove("finalized");
        }else if(action.equals("launch_draft")){
            atStage(p.getCurrentStepNo(),s,stage(p,s));
            int st=stage(p,s);require(number(d.get("stage"))==st,"只能保存当前阶段草稿");require(st>=2&&st<=8,"请在当前阶段维护资料草稿");
            var drafts=new LinkedHashMap<>(map(s.get("stageDrafts")));d.put("references",service.snapshotReferences(id,service.refs(d.get("references"))));drafts.put(String.valueOf(st),d);s.put("stageDrafts",drafts);
        }else if(action.equals("mold_link")){
            atStage(p.getCurrentStepNo(),s,5);long moldId=number(d.get("moldSetId")),appId=number(d.get("applicationId"));
            if(appId>0){var apps=jdbc.queryForList("select application_id,mold_set_id,phone_model_id from plm_mold_application where application_id=? and deleted_flag=0 and (project_id=? or product_line_id=?)",appId,id,id);require(!apps.isEmpty(),"请选择属于本项目的原开模申请");var app=apps.get(0);require(ids(map(s.get("plan")).get("phoneModelIds")).contains(number(app.get("phone_model_id"))),"申请机型不在当前计划内");moldId=number(app.get("mold_set_id"));d.put("applicationSnapshot",app);}
            require(moldId>0||appId>0,"请选择模具或原开模申请");
            if(moldId>0){var row=molds.snapshotForDevelopment(moldId);verifyMoldScope(moldId,s);if(Boolean.TRUE.equals(d.get("reuseConfirmed"))){filled(d.get("reason"),"已有模具采用依据");require("ready".equals(row.get("pcStatus"))&&"ready".equals(row.get("tpuStatus")),"已有模具PC／TPU尚未确认可用");requireReusableMold(id,moldId,s);}d.put("moldSetId",moldId);d.put("moldSnapshot",row);}
            var links=records(s.get("moldLinks"));final long selected=moldId;
            require(links.stream().noneMatch(m->selected>0?number(m.get("moldSetId"))==selected:appId>0&&number(m.get("applicationId"))==appId),"此模具／申请已经关联");links.add(new LinkedHashMap<>(d));s.put("moldLinks",links);s.remove("noMold");s.remove("finalized");
        }else if(action.equals("mold_unlink")){
            atStage(p.getCurrentStepNo(),s,5);filled(d.get("reason"),"取消关联原因");
            var links=records(s.get("moldLinks"));require(links.removeIf(link->number(link.get("moldSetId"))==number(d.get("moldSetId"))&&number(link.get("applicationId"))==number(d.get("applicationId"))),"关联不存在");s.put("moldLinks",links);s.remove("finalized");
        }else if(action.equals("mold_node")||action.equals("mold_restart")){
            int current=stage(p,s);require(current==6,"请在开模步骤办理制作、试模和改模节点");
            requireMoldLink(id,s,number(d.get("moldSetId")));verifyMoldScope(number(d.get("moldSetId")),s);
            var evidence=new LinkedHashMap<String,Object>();
            var refs=service.snapshotReferences(id,service.refs(d.get("references")));
            if("making".equals(d.get("node"))||"rework".equals(d.get("node"))||action.equals("mold_restart")){var design=reviews.requireDesign(id,s,null,service);evidence.put("designReview",design);if(Boolean.TRUE.equals(d.get("confirm")))service.sameTechnical(design.get("references"),refs);}
            else {var sample=currentSample(id,s,service,false);evidence.put("sample",sample);if(Boolean.TRUE.equals(d.get("confirm"))){service.sameTechnical(sample.get("references"),refs);if("testing".equals(d.get("node"))&&"passed".equals(d.get("result")))reviews.requireT0ForMold(id,s,number(d.get("moldSetId")),sample,service);}}
            evidence.put("requirementVersion",s.get("requirementVersion"));evidence.put("planVersion",map(s.get("plan")).get("planVersion"));evidence.put("projectId",id);evidence.put("references",refs);
            d.put("saved",molds.saveDevelopmentNode(number(d.get("moldSetId")),d,evidence,action.equals("mold_restart")));s.remove("finalized");
        }else if(action.equals("no_mold")){
            require(stage(p,s)==5||stage(p,s)==6,"无需开模依据在模具开发或样品验证阶段核对");require(!Boolean.TRUE.equals(map(s.get("plan")).get("needsMold")),"当前计划涉及开模，不能按无需开模跳过");
            filled(d.get("reason"),"无需开模依据");d.put("references",service.snapshotReferences(id,service.refs(d.get("references"))));d.put("designReviewId",reviews.requireDesign(id,s,null,service).get("id"));d.put("planVersion",map(s.get("plan")).get("planVersion"));d.put("planBasis",LaunchReviewWorkflow.planBasis(s));d.put("revisionNo",LaunchReviewWorkflow.revisionNo(s));s.put("noMold",d);
        }else if(action.equals("shipment_confirm")){
            atStage(p.getCurrentStepNo(),s,8);
            var draft=map(map(s.get("stageDrafts")).get("8"));requireChecks(shipmentChecks(id,s,draft,service));
            var batches=records(s.get("shipmentBatches"));var scope=ids(draft.get("moldSetIds"));
            if(batches.stream().anyMatch(b->ids(b.get("moldSetIds")).stream().anyMatch(scope::contains)))filled(draft.get("reason"),"已确认批次更正原因");
            var batch=new LinkedHashMap<>(draft);batch.put("id",batches.stream().mapToLong(b->number(b.get("id"))).max().orElse(0)+1);
            batch.put("moldSetIds",scope);batch.put("references",stageReferences(id,8,s,service));
            batch.put("molds",scope.stream().map(molds::snapshotForDevelopment).toList());batch.put("confirmedAt",java.time.LocalDateTime.now().toString());batch.put("confirmedBy",access.actor().displayName());
            batches.add(batch);s.put("shipmentBatches",batches);d.put("saved",batch);
        }else if(action.equals("cost"))saveCost(id,d);
        else if(action.equals("promotion")||action.equals("presale")){d.put("references",service.snapshotReferences(id,service.refs(d.get("references"))));saveParallel(id,s,action,d);}
        else if(action.equals("launch")){
            require(salesLaunch!=null,"上市服务暂不可用，请稍后重试");salesLaunch.requireLaunchPermission();
            atStage(p.getCurrentStepNo(),s,8);require("released".equals(p.getStatus())&&s.get("currentReleaseId")!=null,"请先形成正式包再登记实际上市");require(map(s.get("revision")).isEmpty(),"当前有待完成的修订，请完成后再登记新版上市");
            date(d,"actualDate");filled(d.get("channels"),"实际上架／开售渠道");require(number(d.get("releaseId"))==number(s.get("currentReleaseId")),"请选择当前正式包");
            var previous=map(s.get("launched"));
            if(previous.isEmpty()){
                service.validateEngineeringFinal(id,s,service.snapshotReferences(id,service.refs(map(s.get("releaseDraft")).get("references"))));
                salesLaunch.admitFirstLaunch(id,number(s.get("currentReleaseId")));d.put("catalogAdmission",true);
            }else{filled(d.get("reason"),"上市登记调整原因");d.put("catalogAdmission",false);}
            d.put("before",previous);s.put("launched",new LinkedHashMap<>(d));
        }
    }
    public void validateSample(long id,DevelopmentModels.RoundInput input,Map<String,Object>f,Map<String,Object>s){
        var plan=map(s.get("plan"));require(!plan.isEmpty(),"请先确认开发计划");
        var scope=ids(f.get("phoneModelIds"));require(ids(plan.get("phoneModelIds")).containsAll(scope),"样品机型不在当前计划范围内");f.put("planVersion",plan.get("planVersion"));
        if(!input.confirm())return;
        require(input.recordDate()!=null&&!input.recordDate().isAfter(LocalDate.now()),"请填写真实收样日期");filled(f.get("makerName"),"样品来源／制作方");require(!scope.isEmpty(),"请明确实际适配范围");
        if("prototype_3d".equals(f.get("samplePurpose"))){
            require("pending".equals(input.result()),"3D打样登记实际收样，确认结论请在样品确认办理");
            require(input.references()!=null&&!input.references().isEmpty(),"请关联本轮样品图片或附件");
            return;
        }
        require(Set.of("passed","failed").contains(input.result()),"请填写本次通过或需改进结论");
        filled(f.get("designSummary"),"样品方案／供应商商品编号与版本依据");
        if(input.result().equals("passed")){filled(f.get("actualResult"),"实际验证说明");require(!Boolean.TRUE.equals(f.get("blockingIssues")),"阻断问题尚未处理，不能通过");}else filled(input.issues(),"本次样品问题");
    }
    boolean planCurrent(Map<String,Object> state){return !map(state.get("plan")).isEmpty()&&Objects.equals(planBasis(map(state.get("plan"))),planBasis(map(state.get("planDraft"))));}
    List<ReadinessCheck> planChecks(Map<String,Object> state){
        var draft=map(state.get("planDraft"));var old=map(state.get("plan"));
        return List.of(
            check("PLAN_DATES","已填写的计划日期","plan-more",()->validatePlanDates(draft)),
            check("METHOD","开发方式","method",()->{filled(draft.get("method"),"开发方式");require(Set.of("own_mold","supplier","direct").contains(text(draft.get("method"))),"开发方式无效");}),
            check("NEEDS_MOLD","是否需要开模","method",()->{require(draft.get("needsMold") instanceof Boolean,"请明确是否需要开模");if("own_mold".equals(draft.get("method")))require(Boolean.TRUE.equals(draft.get("needsMold")),"自主开模需要模具开发");}),
            check("PLAN_CHANGE","计划调整原因及影响核对","plan-change",()->{if(state.containsKey("stage2Basis")&&!old.isEmpty()&&!Objects.equals(LaunchReviewWorkflow.planBasis(Map.of("plan",old)),LaunchReviewWorkflow.planBasis(Map.of("plan",draft)))){filled(draft.get("reason"),"计划变更原因");require(Boolean.TRUE.equals(draft.get("impactAcknowledged")),"请核对受影响的模具计划、推广和预售承诺");}})
        );
    }
    public void confirmPlan(long id,Map<String,Object>s,ProductDevelopmentService service){
            var draft=new LinkedHashMap<>(map(s.get("planDraft")));require(!draft.isEmpty(),"请先保存开发计划");requireChecks(planChecks(s));draft.put("phones",phoneSnapshots(ids(draft.get("phoneModelIds"))));
            var old=map(s.get("plan"));if(old.isEmpty()||!Objects.equals(planBasis(old),planBasis(draft))){
                draft.put("planVersion",number(old.get("planVersion"))+1);draft.put("confirmedBy",access.actor().displayName());draft.put("confirmedAt",java.time.LocalDateTime.now().toString());
                store.event(id,"plan_confirmed",Map.of("before",old,"after",draft,"impact",impact(s)),null,access.actor().displayName());s.put("plan",draft);
            }else{var current=new LinkedHashMap<>(old);current.put("references",draft.getOrDefault("references",List.of()));s.put("plan",current);}
    }
    public void confirmStage(long id,int stage,Map<String,Object>s,ProductDevelopmentService service){
        requireChecks(stageChecks(id,stage,s,service));
        Map<String,Object> basis=new LinkedHashMap<>();
        if(stage==1){confirmPlan(id,s,service);basis.put("requirement",s.get("requirement"));basis.put("plan",s.get("plan"));}
        else if(stage==2){confirmPlan(id,s,service);basis.put("references",stageReferences(id,2,s,service));basis.put("phoneModelIds",map(s.get("plan")).get("phoneModelIds"));}
        else if(stage==3){var sample=currentPrototype(id,s,service);s.put("prototypeRoundId",sample.get("roundId"));basis.put("sample",sample);}
        else if(stage==4)basis.put("confirmation",reviews.requireDesign(id,s,currentPrototype(id,s,service),service));
        else if(stage==5){confirmPlan(id,s,service);basis.put("plan",s.get("plan"));basis.put("molds",resolvedMolds(id,s));basis.put("references",stageReferences(id,5,s,service));}
        else if(stage==6){basis.put("sample",currentSample(id,s,service));basis.put("molds",moldBasis(id,s,service));s.put("validationBasis",new LinkedHashMap<>(basis));}
        else if(stage==7){basis.put("pilot",reviews.requirePilot(id,s,service));s.put("finalized",s.get("releaseDraft"));}
        else basis.put("shipments",records(s.get("shipmentBatches")));
        s.put("stage"+stage+"Basis",basis);
    }
    public List<Map<String,Object>> stageReferences(long id,int step,Map<String,Object>s,ProductDevelopmentService service){
        return service.snapshotReferences(id,service.refs(map(map(s.get("stageDrafts")).get(String.valueOf(step))).get("references")));
    }
    public Map<String,Object> currentPrototype(long id,Map<String,Object>s,ProductDevelopmentService service){
        long selected=number(s.get("prototypeRoundId"));
        var candidates=service.allRounds(id).stream().filter(r->number(r.get("roundId"))>number(s.get("prototypeAfterRoundId"))&&"sample".equals(r.get("kind"))&&"prototype_3d".equals(map(r.get("details")).get("samplePurpose"))&&"confirmed".equals(r.get("status"))&&!LaunchReviewWorkflow.roundVoided(s,number(r.get("roundId")))).toList();
        var row=candidates.stream().filter(r->selected==0||number(r.get("roundId"))==selected).findFirst().orElseThrow(()->invalid("请登记本次3D打样的实际收样"));
        require(service.compatibleRequirement(s,map(row.get("details")).get("requirementVersion")),"3D样采用的需求已变化，请重新打样确认");
        require(!records(map(s.get("stage2Basis")).get("references")).isEmpty(),"采用的3D图纸依据缺失，请返回画3D产品图确认版本");
        service.sameTechnical(map(s.get("stage2Basis")).get("references"),row.get("references"));
        require(records(row.get("references")).stream().anyMatch(r->"sample_image".equals(r.get("category"))),"请关联本轮实际收样照片");
        require(ids(map(row.get("details")).get("phoneModelIds")).containsAll(ids(map(s.get("plan")).get("phoneModelIds"))),"3D样未覆盖本次代表型号");
        return row;
    }
    private List<Long> resolvedMolds(long id,Map<String,Object>s){return records(s.get("moldLinks")).stream().map(link->reviews.resolveMold(id,link)).filter(m->m>0).distinct().toList();}
    private com.fasterxml.jackson.databind.JsonNode planBasis(Map<String,Object> p){
        var copy=new LinkedHashMap<>(p);for(String key:List.of("reason","planVersion","confirmedBy","confirmedAt","impact","phones","impactAcknowledged","references"))copy.remove(key);
        copy.put("phoneModelIds",ids(p.get("phoneModelIds")));
        copy.entrySet().removeIf(e->e.getValue()==null||e.getValue() instanceof String value&&value.isBlank());
        try{return json.readTree(json.writeValueAsString(copy));}catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException("计划依据比较失败",failure);}
    }
    public Map<String,Object> currentSample(long id,Map<String,Object>s,ProductDevelopmentService service){return currentSample(id,s,service,true);}
    private Map<String,Object> currentSample(long id,Map<String,Object>s,ProductDevelopmentService service,boolean passed){long selected=number(s.get("validationSampleId"));if(selected==0)selected=number(s.get("selectedRoundId"));return sample(id,selected,s,service,passed);}
    private Map<String,Object> sample(long id,long sampleId,Map<String,Object>s,ProductDevelopmentService service,boolean passed){
        var sample=service.confirmedSource(id,sampleId,"sample",passed);var f=map(sample.get("details"));require(service.compatibleRequirement(s,f.get("requirementVersion")),"样品采用旧需求，请重新确认当前方案");
        var plan=map(s.get("plan"));require(ids(f.get("phoneModelIds")).containsAll(ids(plan.get("phoneModelIds"))),"样品未覆盖当前计划机型");
        // Date-only plan changes do not invent a new physical result; method/scope changes need current validation.
        long pv=number(f.get("planVersion"));if(pv!=number(plan.get("planVersion"))){var old=store.events(id).stream().filter(e->"plan_confirmed".equals(e.get("kind"))).map(e->map(map(e.get("data")).get("after"))).filter(p->number(p.get("planVersion"))==pv).findFirst().orElseThrow(()->invalid("样品计划依据待核对"));require(Objects.equals(old.get("method"),plan.get("method"))&&Objects.equals(old.get("needsMold"),plan.get("needsMold"))&&ids(old.get("phoneModelIds")).containsAll(ids(plan.get("phoneModelIds"))),"计划开发方式或范围已变，请补充对应样品验证");}
        var rev=map(s.get("revision"));if(Boolean.TRUE.equals(rev.get("technical")))require(number(f.get("revisionNo"))==number(rev.get("revisionNo")),"技术修订需要本次样品验证");return sample;
    }
    private record Assessment(List<ReadinessCheck> checks,Map<String,Object> basis) {
        Map<String,Object> requireBasis(){requireChecks(checks);return basis;}
    }
    private Assessment moldAssessment(long id,Map<String,Object> state,ProductDevelopmentService service){
        var checks=new ArrayList<ReadinessCheck>();var basis=new LinkedHashMap<String,Object>();var sample=new LinkedHashMap<String,Object>();
        checks.add(check("SAMPLE","当前方案已确认通过的实际样品","sample",()->sample.putAll(currentSample(id,state,service))));
        if(!Boolean.TRUE.equals(map(state.get("plan")).get("needsMold"))){
            checks.add(check("NO_MOLD","当前方案的无需开模依据","no-mold",()->basis.put("noMold",reviews.requireNoMold(state,reviews.requireDesign(id,state,sample.isEmpty()?null:sample,service)))));
            return new Assessment(checks,basis);
        }
        var links=records(state.get("moldLinks"));var confirmed=new ArrayList<Map<String,Object>>();Set<Long> covered=new HashSet<>();
        checks.add(check("MOLD_LINKS","本次采用的实际模具套","mold-links",()->require(!links.isEmpty(),"请关联本次需要开发的模具或原申请")));
        for(var link:links){
            long moldId=reviews.resolveMold(id,link);
            checks.add(check("ACCEPTANCE_"+moldKey(link,moldId),"验模及采用依据 · "+moldLabel(link,moldId),moldTarget(link,moldId),()->{
                require(moldId>0,"开模申请尚未生成真实模具，不能确认完成");
                var row=molds.snapshotForDevelopment(moldId);require(!Boolean.TRUE.equals(row.get("schedulePaused")),"模具执行暂停，请先核对原申请");var dev=map(row.get("development"));
                require("ready".equals(dev.get("node"))&&"ready".equals(row.get("pcStatus"))&&"ready".equals(row.get("tpuStatus")),"仍有模具未实际验模通过，或制作状态已变化");
                if(!sample.isEmpty()){
                    var evidence=map(map(dev.get("last")).get("basis"));require(number(evidence.get("projectId"))==id&&number(map(evidence.get("sample")).get("roundId"))==number(sample.get("roundId")),"验模采用的样品／项目已变化，请重新验模");
                    service.sameTechnical(map(evidence.get("sample")).get("references"),sample.get("references"));require(service.compatibleRequirement(state,evidence.get("requirementVersion")),"验模采用旧需求");
                }
                covered.addAll(jdbc.queryForList("select phone_model_id from plm_collaboration_phone_set_compatibility where mold_set_id=? and pc_confirmed and tpu_confirmed and nullif(btrim(pc_evidence),'') is not null and nullif(btrim(tpu_evidence),'') is not null",Long.class,moldId));confirmed.add(row);
            }));
        }
        if(!links.isEmpty()&&confirmed.size()==links.size())checks.add(check("MOLD_COVERAGE","全部计划机型的 PC／TPU 适配依据","mold-coverage",()->require(covered.containsAll(ids(map(state.get("plan")).get("phoneModelIds"))),"模具验模未覆盖全部计划机型，请核对逐套PC／TPU适配依据")));
        basis.put("molds",confirmed);basis.put("sample",sample);return new Assessment(checks,basis);
    }
    private Map<String,Object> moldBasis(long id,Map<String,Object> state,ProductDevelopmentService service){return moldAssessment(id,state,service).requireBasis();}
    private String moldKey(Map<String,Object> link,long moldId){return moldId>0?"M"+moldId:"A"+number(link.get("applicationId"));}
    private String moldTarget(Map<String,Object> link,long moldId){return moldId>0?"mold:"+moldId:"application:"+number(link.get("applicationId"));}
    private String moldLabel(Map<String,Object> link,long moldId){
        var snapshot=map(link.get("moldSnapshot"));for(String key:List.of("assetNumber","setName"))if(!text(snapshot.get(key)).isBlank())return text(snapshot.get(key));
        var application=map(link.get("applicationSnapshot"));if(!text(application.get("approval_no")).isBlank())return text(application.get("approval_no"));
        return moldId>0?"实物套 #"+moldId:"原申请 #"+number(link.get("applicationId"));
    }
    public List<ReadinessCheck> stageChecks(long id,int step,Map<String,Object> state,ProductDevelopmentService service){
        var checks=new ArrayList<ReadinessCheck>();
        require(step>=1&&step<=8,"步骤无效");
        if(step==1){checks.addAll(requirementChecks(map(state.get("profileDraft")),false));checks.addAll(planChecks(state));checks.add(check("INIT_OWNER_CONFIRMATION","项目负责人确认立项","owner",()->service.requireResponsibleConfirmation(state)));return checks;}
        if(step==2){
            var plan=map(state.get("planDraft"));checks.add(check("REPRESENTATIVE_PHONES","本次代表型号","phones",()->{require(!ids(plan.get("phoneModelIds")).isEmpty(),"请选择本次代表型号");phoneSnapshots(ids(plan.get("phoneModelIds")));}));
            checks.add(check("PRODUCT_3D_DRAWING","采用的3D产品图版本","drawings",()->require(stageReferences(id,2,state,service).stream().anyMatch(r->"drawing".equals(r.get("category"))&&Set.of("step","stp","stl","igs","iges","sldprt","sldasm","x_t","x_b","3dm","obj","3mf","prt").contains(text(r.get("fileExt")).replace(".","").toLowerCase(Locale.ROOT))),"请上传并采用3D产品图版本")));
            return checks;
        }
        if(step==3){
            if(records(map(state.get("stage2Basis")).get("references")).isEmpty())checks.add(check("PROTOTYPE_DRAWING","已采用的3D图纸依据","previous-stage",()->{throw invalid("请返回画3D产品图确认采用版本");}));
            else checks.add(check("PROTOTYPE_RECEIPT","本轮3D样实际收样","sample",()->currentPrototype(id,state,service)));return checks;
        }
        if(step==4){checks.add(check("PROTOTYPE_CONFIRMATION","产品开发负责人确认代表样","review:design",()->reviews.requireDesign(id,state,currentPrototype(id,state,service),service)));return checks;}
        if(step==5){
            if(!Boolean.TRUE.equals(map(state.get("plan")).get("needsMold"))){
                checks.add(noMoldCheck(id,state,service));return checks;
            }
            checks.add(check("CONFIRMED_SAMPLE","已确认的产品样品方案","review:design",()->reviews.requireDesign(id,state,null,service)));
            checks.add(check("MOLD_SUPPLIER","外部开模供应商","supplier",()->service.requireMoldSupplier(state)));
            checks.add(check("MOLD_DESIGN_FILES","本次模具设计资料","mold-design-files",()->{var draft=map(map(state.get("stageDrafts")).get("5"));var refs=service.snapshotReferences(id,service.refs(draft.get("moldDesignReferences")));require(!refs.isEmpty(),"请明确本次模具设计资料版本");}));
            checks.add(check("MOLD_AUTHORIZATION","原开模申请授权及逐套关联","mold-links",()->{var links=records(state.get("moldLinks"));require(!links.isEmpty(),"请关联原开模申请或明确可复用的模具套");for(var link:links){long mold=reviews.resolveMold(id,link);require(mold>0,"原申请尚未授权建套，请在原申请查看审批和建档进度");access.requireMold(mold);var authority=new com.yuewei.plm.module.collaboration.MoldWorkflowRepository(jdbc);require(!authority.isPaused(mold),"原申请已暂停或终止");
                if(number(link.get("applicationId"))>0)require(authority.isAuthorized(number(link.get("applicationId")),id,mold),"原申请尚未达到已授权开工条件");
                else {require(Boolean.TRUE.equals(link.get("reuseConfirmed")),"直接关联已有套需要明确确认复用；新开模请关联原申请");requireReusableMold(id,mold,state);}}}));
            return checks;
        }
        if(step==6){
            checks.addAll(moldAssessment(id,state,service).checks());
            var sample=new LinkedHashMap<String,Object>();checks.add(check("MOLD_SAMPLE","本次试模样","sample",()->sample.putAll(currentSample(id,state,service))));
            if(!sample.isEmpty())checks.addAll(reviews.t0Checks(id,state,sample,service));
            checks.add(check("RELIABILITY","本次实际验证及报告","review:reliability",()->reviews.requireReliability(id,state,service)));
            return checks;
        }
        if(step==7)return finalChecks(id,state,false,service);
        if(!Boolean.TRUE.equals(map(state.get("plan")).get("needsMold"))){checks.add(noMoldCheck(id,state,service));return checks;}
        var linked=resolvedMolds(id,state);
        checks.add(check("SHIPMENT_SCOPE","本次开发关联模具套","mold-links",()->require(!linked.isEmpty(),"请核对本次开发关联套")));
        for(long mold:linked)checks.add(check("SHIP_"+mold,"发模及批次确认 · 套 #"+mold,"shipment:"+mold,()->{
            var row=molds.snapshotForDevelopment(mold);require(row.get("actualShipDate")!=null,"请在原排程登记本套实际发模日期");date(row,"actualShipDate");
            var confirmed=records(state.get("shipmentBatches")).stream().filter(batch->ids(batch.get("moldSetIds")).contains(mold)).reduce((a1,b1)->b1).orElseThrow(()->invalid("此套尚未确认实际发模批次"));
            var snapshot=records(confirmed.get("molds")).stream().filter(m->number(m.get("moldSetId"))==mold).findFirst().orElseThrow(()->invalid("批次缺少实际发模依据"));
            require(Objects.equals(text(snapshot.get("actualShipDate")),text(row.get("actualShipDate"))),"发模日期已更正，请重新确认本套批次");
        }));
        return checks;
    }
    private ReadinessCheck noMoldCheck(long id,Map<String,Object> state,ProductDevelopmentService service){
        return check("NO_MOLD","当前方案无需开模／发模的确认依据","no-mold",()->reviews.requireNoMold(state,reviews.requireDesign(id,state,null,service)));
    }
    private List<ReadinessCheck> shipmentChecks(long id,Map<String,Object> state,Map<String,Object> draft,ProductDevelopmentService service){
        var checks=new ArrayList<ReadinessCheck>();var linked=resolvedMolds(id,state);var selected=ids(draft.get("moldSetIds"));
        checks.add(check("BATCH_SCOPE","本批具体模具套","shipment",()->require(!selected.isEmpty()&&linked.containsAll(selected),"请选择本项目关联的本批实际发模套")));
        checks.add(check("BATCH_RECIPIENT","墨西哥收货地点及物流资料","shipment",()->{filled(draft.get("recipient"),"墨西哥收货地点／接收方");var refs=stageReferences(id,8,state,service);require(!refs.isEmpty()&&refs.stream().allMatch(r->"file".equals(r.get("type"))),"请关联本批物流附件，BOM／工艺不能代替物流资料");}));
        for(long mold:selected)checks.add(check("BATCH_SHIP_"+mold,"本批实际发模 · 套 #"+mold,"shipment:"+mold,()->{require(linked.contains(mold),"模具套不属于本项目");var row=molds.snapshotForDevelopment(mold);require(row.get("actualShipDate")!=null,"请在原排程登记本套实际发模日期");date(row,"actualShipDate");}));
        return checks;
    }
    public void validateFinal(long id,Map<String,Object> state,boolean finalized,ProductDevelopmentService service){requireChecks(finalChecks(id,state,finalized,service));}
    private List<ReadinessCheck> finalChecks(long id,Map<String,Object> state,boolean finalized,ProductDevelopmentService service){
        var checks=new ArrayList<>(moldAssessment(id,state,service).checks());var draft=map(state.get("releaseDraft"));
        checks.add(check("ENGINEERING_BASIS","当前供应商、BOM与工艺方案确认","engineering-basis",()->service.validateEngineeringFinal(id,state,service.snapshotReferences(id,service.refs(draft.get("references"))))));
        checks.add(check("RELEASE_DRAFT","本次待定型清单及修订阶段","release-date",()->{var revision=map(state.get("revision"));if(!revision.isEmpty())require(number(revision.get("stage"))>=7,"请先完成本次修订前序阶段");require(!draft.isEmpty(),"请保存待定型资料清单");}));
        checks.add(check("RELEASE_DATE","本次定型确认日期","release-date",()->date(draft,"confirmationDate")));
        var pilot=new LinkedHashMap<String,Object>();
        checks.add(check("PILOT_REQUIREMENTS","试产计划数量、良率及检验要求","review:setup",()->LaunchReviewWorkflow.requirePilotRequirements(map(state.get("reviewSetup")))));
        checks.add(check("PILOT","实际产量、不良处置及试产通过评审","review:pilot",()->pilot.putAll(reviews.requirePilot(id,state,service))));
        checks.add(check("PILOT_SELECTION","定型采用的试产评审","release-pilot",()->{require(number(draft.get("pilotRoundId"))>0,"请选择本次方案通过的试产评审作为定型依据");if(!pilot.isEmpty()){require(number(draft.get("pilotRoundId"))==number(pilot.get("roundId")),"请选择本次方案通过的试产评审作为定型依据");if(!text(draft.get("confirmationDate")).isBlank()){date(draft,"confirmationDate");LaunchReviewWorkflow.sourceAvailable(draft.get("confirmationDate"),pilot);}}}));
        checks.add(check("RELEASE_SCOPE","清单样品与当前计划的适配范围","release-scope",()->{var sample=currentSample(id,state,service);require(number(draft.get("sampleRoundId"))==number(sample.get("roundId")),"清单样品与本次方案不一致");var scope=ids(draft.get("phoneModelIds"));require(!scope.isEmpty()&&ids(map(sample.get("details")).get("phoneModelIds")).containsAll(scope)&&scope.containsAll(ids(map(state.get("plan")).get("phoneModelIds"))),"正式包范围与当前计划／样品不一致");}));
        var selected=new ArrayList<Map<String,Object>>();
        checks.add(check("RELEASE_MATERIALS","采用资料的具体版本及正式状态","drawings",()->{
            selected.addAll(service.snapshotReferences(id,service.refs(draft.get("references"))));
            if(!pilot.isEmpty())service.validateReleaseVersions(pilot.get("references"),selected,draft);
            Set<Object> families=new HashSet<>();Set<String> types=new HashSet<>();for(var ref:selected){if("file".equals(ref.get("type")))require(families.add(ref.get("familyId")),"同一文件族只采用一个版本");else if(Set.of("bom","process").contains(text(ref.get("type")))){require(types.add(text(ref.get("type"))),"BOM／工艺只能选定一版");require(Set.of("released","frozen","locked").contains(text(ref.get("status"))),"选中的 BOM／工艺版本尚非正式状态");}}
            for(var ref:selected)if("bom".equals(ref.get("type"))&&Set.of("mes_baseline","mes_derivative").contains(text(ref.get("sourceType"))))require(Boolean.TRUE.equals(ref.get("engineeringConfirmed")),"来源有效不等于工程确认，请国内工程确认采用的 BOM 具体版本");
        }));
        checks.add(check("UNIT_COST","采用已确认且与本方案一致的单件成本","release-cost",()->{
            long costId=number(draft.get("unitCostVersionId"));var cost=records(state.get("unitCostVersions")).stream().filter(c->number(c.get("id"))==costId&&!c.containsKey("voidReason")).findFirst().orElseThrow(()->invalid("请选择已确认且有效的单件成本版本"));
            if("supplier_quote".equals(cost.get("basisType")))require("direct".equals(map(state.get("plan")).get("method"))&&Boolean.FALSE.equals(map(state.get("plan")).get("needsMold"))&&!map(cost.get("adoptedQuote")).isEmpty(),"当前方案不能沿用采购成品报价成本，请按本方案重新核算");
            else{require(selected.stream().anyMatch(r->"bom".equals(r.get("type"))&&number(r.get("id"))==number(cost.get("bomId"))),"成本采用的BOM与正式包不同，请重新核算确认");service.sameTechnical(records(cost.get("references")).stream().filter(r->"bom".equals(r.get("type"))).toList(),selected.stream().filter(r->"bom".equals(r.get("type"))).toList());}
        }));
        if(finalized)checks.add(check("FINALIZED","采用资料变化后的重新定型确认","release-date",()->require(Objects.equals(state.get("finalized"),draft),"资料已变化，请重新确认定型")));
        return checks;
    }
    public List<ProductReleaseGateMissingItemVO> releaseGaps(Map<String,Object>s,List<Map<String,Object>>refs){
        boolean direct="direct".equals(map(s.get("plan")).get("method"));var required=new LinkedHashMap<String,String>();required.put("customer_confirm","确认样／签样");required.put("testing","实际验证报告");if(!direct){required.put("bom","BOM");required.put("process","工艺");required.put("drawing","图纸");required.put("production_docs","SOP／SIP");}
        return required.entrySet().stream().filter(e->refs.stream().noneMatch(r->e.getKey().equals(r.get("type"))||e.getKey().equals(r.get("category"))||e.getKey().equals("production_docs")&&Set.of("sop","sip").contains(text(r.get("category"))))).map(e->ProductReleaseGateMissingItemVO.builder().code("RELEASE_"+e.getKey().toUpperCase(Locale.ROOT)).message("未采用"+e.getValue()+"版本").severity("warning").build()).toList();
    }
    private void requireReusableMold(long productId,long moldId,Map<String,Object> state){
        com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(jdbc,"product",productId);
        com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(jdbc,"mold_set",moldId);
        boolean matched=false;
        for(long phoneId:ids(map(state.get("plan")).get("phoneModelIds")))for(var candidate:phones.compatibleMolds(phoneId,productId))if(number(candidate.get("moldSetId"))==moldId){
            phones.requireCompatibility(productId,phoneId,number(candidate.get("itemId")),moldId,text(candidate.get("compatibilityDigest")));matched=true;
        }
        require(matched,"已有模具须按明确的实物及PC／TPU逐套适配依据确认复用");
    }
    private List<Long> verifyMoldScope(long moldId,Map<String,Object>s){access.requireMold(moldId);var rows=jdbc.queryForList("select phone_model_id from plm_collaboration_phone_set_compatibility where mold_set_id=?",Long.class,moldId);require(rows.stream().anyMatch(ids(map(s.get("plan")).get("phoneModelIds"))::contains),"模具与本项目机型没有实际适配关系");return rows;}
    private void requireMoldLink(long id,Map<String,Object>s,long moldId){require(records(s.get("moldLinks")).stream().anyMatch(m->number(m.get("moldSetId"))==moldId||number(m.get("applicationId"))>0&&jdbc.queryForObject("select count(*) from plm_mold_application where application_id=? and mold_set_id=? and deleted_flag=0 and (project_id=? or product_line_id=?)",Long.class,number(m.get("applicationId")),moldId,id,id)>0),"此模具未关联本项目");}
    List<Map<String,Object>> phoneSnapshots(List<Long> selected){return phoneSnapshots(selected,List.of());}
    /** Retained draft selections remain correctable; a new scope or confirmation rechecks active IDs. */
    List<Map<String,Object>> phoneSnapshots(List<Long> selected,List<Long> retained){
        if(selected.isEmpty())return List.of();
        var existing=new HashSet<>(retained);var added=selected.stream().filter(id->!existing.contains(id)).toList();
        if(!added.isEmpty())phones.requireEnabledModels(added);
        var members=phones.members(selected,true);var result=new ArrayList<Map<String,Object>>();
        for(long id:selected){var member=members.get(id);require(member!=null,"所选标准手机不存在，请核对原关联");var row=new LinkedHashMap<String,Object>();for(String key:List.of("phoneModelId","modelName","brandName","status","brandStatus"))row.put(key,member.get(key));result.add(row);}
        return result;
    }

    private void saveCost(long id,Map<String,Object>d){
        access.requireFinanceWrite();String kind=text(d.get("type"));require(Set.of("budget","expense").contains(kind),"报价请使用样品报价／原模具报价，避免形成第二份报价");filled(d.get("name"),"费用名称");java.math.BigDecimal amount;try{amount=new java.math.BigDecimal(text(d.get("amount")));require(amount.signum()>=0,"金额不能为负数");}catch(NumberFormatException e){throw invalid("请填写金额");}
        long recordId=number(d.get("id"));Map<String,Object>row;
        if(recordId>0){row=finance.detail(kind,recordId);require(number(row.get("productLineId"))==id||number(row.get("productId"))==id,"成本记录不属于本新品项目");filled(d.get("reason"),"成本调整原因");require(Objects.equals(text(d.get("expectedUpdatedAt")),text(row.get("updatedAt"))),"原成本台账已变化，请重新载入核对");require(text(d.get("currency")).equals(text(row.get("currencyCode"))),"原币种不能通过修改金额改变");}
        else if(kind.equals("budget"))row=finance.createBudget(new DevelopmentTypes.CreateBudget("platform",text(d.get("name")),id,null,null,null,text(d.get("currency"))),null);
        else row=finance.createExpense(new DevelopmentTypes.CreateExpense(text(d.get("name")),id,null,null,null,text(d.get("currency")),id),null);
        recordId=number(row.get(kind.equals("budget")?"budgetId":"expenseId"));String field=kind.equals("budget")?"budgetAmount":"declaredAmount";
        var changes=new ArrayList<DevelopmentTypes.Change>();String nameField=kind.equals("budget")?"budgetName":"description";changes.add(new DevelopmentTypes.Change(nameField,json.valueToTree(row.get(nameField)),json.valueToTree(text(d.get("name")))));changes.add(new DevelopmentTypes.Change(field,json.valueToTree(row.get(field)),json.valueToTree(amount)));if(kind.equals("expense"))changes.add(new DevelopmentTypes.Change("expenseCategory",json.valueToTree(row.get("expenseCategory")),json.valueToTree(text(d.get("category")))));
        if(kind.equals("expense"))for(String k:List.of("occurredAt","notes"))if(d.containsKey(k))changes.add(new DevelopmentTypes.Change(k,json.valueToTree(row.get(k)),json.valueToTree(d.get(k))));
        var result=finance.save(kind,new DevelopmentTypes.Batch(List.of(new DevelopmentTypes.Patch(recordId,changes,text(d.get("reason")).isBlank()?"新品项目登记":text(d.get("reason"))))),null).get(0);require("saved".equals(result.status()),result.message());d.put("id",recordId);d.put("ledgerSnapshot",result.row());
    }
    private void saveParallel(long id,Map<String,Object>s,String action,Map<String,Object>d){
        var all=records(s.get(action+"s"));long recordId=number(d.get("id"));final long requestedId=recordId;var old=all.stream().filter(r->number(r.get("id"))==requestedId).findFirst().orElse(Map.of());if(recordId>0)require(!old.isEmpty(),"记录不属于本项目");if(!old.isEmpty())filled(d.get("reason"),"调整原因");
        require(d.toString().length()<64000,"单条记录过长，请将大文件放入资料版本库");filled(d.get("channel"),"渠道");filled(d.get("target"),action.equals("promotion")?"具体账号／合作方":"客户");
        if(action.equals("promotion")){
            require(Set.of("ecommerce","mcn","wholesale","chain").contains(text(d.get("channel"))),"推广渠道无效");filled(d.get("action"),"推广动作");date(d,"plannedDate");require(Set.of("pending","working","done","cancelled").contains(text(d.get("status"))),"推广状态无效");if("done".equals(d.get("status")))date(d,"actualDate");
            for(String type:List.of("budget","expense")){long costId=number(d.get(type+"Id"));if(costId>0){var cost=finance.detail(type,costId);require(number(cost.get("productId"))==id||number(cost.get("productLineId"))==id,"推广费用不属于本项目");}}
        }else{
            if(!access.hasPermission("finance:write")){for(String key:List.of("unitPrice","currency","depositNote")){if(d.containsKey(key)&&!Objects.equals(d.get(key),old.get(key)))throw invalid("预售金额维护需要金额权限");if(old.containsKey(key))d.put(key,old.get(key));}}else if(d.get("unitPrice")!=null&&!text(d.get("unitPrice")).isBlank()){try{require(new java.math.BigDecimal(text(d.get("unitPrice"))).signum()>=0,"预售单价不能为负数");require(Set.of("CNY","MXN","USD","EUR").contains(text(d.get("currency"))),"请明确预售原币种");}catch(NumberFormatException e){throw invalid("预售单价格式无效");}}
            long quantity=number(d.get("quantity"));require(quantity>0&&quantity<=1000000000,"预售数量须为正整数");long phone=number(d.get("phoneModelId"));require(ids(map(s.getOrDefault("plan",s.get("requirement"))).get("phoneModelIds")).contains(phone),"预售机型不在项目当前范围，请先核对开发计划");d.put("phoneSnapshot",phoneSnapshots(List.of(phone)).get(0));date(d,"promisedDate");require(Set.of("reserved","confirmed","cancelled","fulfilled").contains(text(d.get("status"))),"预售状态无效");
        }
        if(recordId==0)recordId=all.stream().mapToLong(r->number(r.get("id"))).max().orElse(0)+1;
        d.put("id",recordId);d.put("recordVersion",number(old.get("recordVersion"))+1);d.put("updatedBy",access.actor().displayName());d.put("updatedAt",java.time.LocalDateTime.now().toString());long owner=number(d.get("ownerUserId"));if(owner==0)owner=number(jdbc.queryForObject("select owner_user_id from plm_product where product_id=?",Long.class,id));var ownerRows=jdbc.queryForList("select coalesce(display_name,user_name,username) as name from sys_user where user_id=? and deleted_flag=0 and status='active'",owner);require(!ownerRows.isEmpty(),"请选择有效负责人");d.put("ownerUserId",owner);d.put("ownerName",ownerRows.get(0).get("name"));d.put("planVersion",map(s.get("plan")).get("planVersion"));
        var saved=new LinkedHashMap<>(d);d.put("before",old);final long key=recordId;all.removeIf(r->number(r.get("id"))==key);all.add(saved);s.put(action+"s",all);
    }
    private void validatePlanDates(Map<String,Object> draft){for(String key:List.of("launchDate","pilotDate","sampleDate","moldDate","supplyDate"))if(!text(draft.get(key)).isBlank())date(draft,key);}
    private Object impact(Map<String,Object>s){return Map.of("molds",s.getOrDefault("moldLinks",List.of()),"promotions",s.getOrDefault("promotions",List.of()),"presales",s.getOrDefault("presales",List.of()));}
    private void date(Map<String,Object>d,String key){try{LocalDate.parse(text(d.get(key)));if(Set.of("actualDate","confirmationDate").contains(key))require(!LocalDate.parse(text(d.get(key))).isAfter(LocalDate.now()),"实际日期不能晚于今天");}catch(java.time.format.DateTimeParseException e){throw invalid("请填写有效日期");}}
}

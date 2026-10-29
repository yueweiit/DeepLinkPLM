package com.yuewei.plm.module.project.development;

import com.yuewei.plm.module.pilot.PilotAccessService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static com.yuewei.plm.module.project.development.DevelopmentWorkflowStore.*;
import static com.yuewei.plm.module.project.development.DevelopmentRules.*;

/** Four reviews use the existing project state/events and test/pilot round history. */
@Component
@RequiredArgsConstructor
public class LaunchReviewWorkflow {
    private final JdbcTemplate jdbc;
    private final PilotAccessService access;
    private final DevelopmentWorkflowStore store;

    static List<Map<String,Object>> records(Object value) {
        if (!(value instanceof List<?> rows)) return new ArrayList<>();
        return new ArrayList<>(rows.stream().map(DevelopmentWorkflowStore::map).toList());
    }
    public boolean handles(String action) {
        return Set.of("review_setup", "review_save", "review_delete", "review_void", "round_void").contains(action);
    }
    public List<Map<String,Object>> summary(long id,Map<String,Object> state,ProductDevelopmentService service) {
        var rows=new ArrayList<Map<String,Object>>();
        for(String gate:List.of("design","t0","reliability","pilot")) {
            var row=new LinkedHashMap<String,Object>();row.put("gate",gate);
            try {
                if("design".equals(gate))row.put("recordId",requireDesign(id,state,null,service).get("id"));
                else if("t0".equals(gate)){long selected=number(state.get("validationSampleId"));if(selected==0)selected=number(state.get("selectedRoundId"));require(selected>0,"请先记录实际样品");requireT0(id,state,service.confirmedSource(id,selected,"sample",true),service);}
                else if("reliability".equals(gate))row.put("recordId",requireReliability(id,state,service).get("roundId"));
                else row.put("recordId",requirePilot(id,state,service).get("roundId"));
                row.put("status","passed");row.put("reason","t0".equals(gate)&&!Boolean.TRUE.equals(map(state.get("plan")).get("needsMold"))?"已确认无需开模依据":"当前采用版本已通过");
            }catch(com.yuewei.plm.common.exception.BusinessException pending){row.put("status","pending");row.put("reason",pending.getMessage());}
            rows.add(row);
        }
        return rows;
    }
    public void action(long id, Map<String,Object> state, String action, Map<String,Object> data, ProductDevelopmentService service) {
        var product=service.requireDevelopmentEditable(id);
        int stage=map(state.get("revision")).isEmpty()?Objects.requireNonNullElse(product.getCurrentStepNo(),1):(int)number(map(state.get("revision")).get("stage"));
        if ("review_setup".equals(action)) {
            var setup=stageSetup(stage,state,data);
            state.put("reviewSetup",setup);state.remove("finalized");return;
        }
        if("round_void".equals(action)) {
            var round=service.rawRound(id,number(data.get("roundId")));
            atStage(product.getCurrentStepNo(),state,roundStage(text(round.get("kind")),map(round.get("details"))));
            require("confirmed".equals(round.get("status")),"只有已确认记录需要作废；草稿请直接删除");
            require(!roundVoided(state,number(round.get("roundId"))),"该记录已经作废");
            filled(data.get("reason"),"作废原因");
            var voided=new LinkedHashMap<>(map(state.get("voidedRounds")));
            var reason=new LinkedHashMap<>(data);reason.put("by",access.actor().displayName());reason.put("at",LocalDate.now().toString());
            voided.put(text(round.get("roundId")),reason);state.put("voidedRounds",voided);state.remove("finalized");return;
        }
        var rows=records(state.get("reviewRecords"));
        long selected=number(data.get("id"));
        var old=rows.stream().filter(r->number(r.get("id"))==selected&&!Boolean.TRUE.equals(r.get("deleted"))).findFirst().orElse(null);
        if("review_delete".equals(action)||"review_void".equals(action)) {
            require(old!=null,"评审记录不存在");
            require("design".equals(old.get("gate"))?(stage==4||stage==6):stage==6,"请在评审所属阶段办理；前序历史只读");
            if("review_delete".equals(action)) {require("draft".equals(old.get("status")),"已确认评审请复评或作废");old.put("deleted",true);}
            else {require("confirmed".equals(old.get("status")),"只能作废已确认评审");filled(data.get("reason"),"作废原因");old.put("voidReason",data.get("reason"));old.put("voidedBy",access.actor().displayName());old.put("voidedAt",LocalDate.now().toString());}
            state.put("reviewRecords",rows);state.remove("finalized");return;
        }
        String gate=text(data.get("gate"));require(Set.of("design","t0").contains(gate),"评审类型无效");
        require("design".equals(gate)?(stage==4||stage==6):stage==6,"样品确认办理代表样确认；开模办理T0／复试与设计复核");
        require(selected==0||old!=null,"原评审草稿不存在");
        if(old!=null){require("draft".equals(old.get("status")),"已确认评审保留历史，请新增复评");require(gate.equals(old.get("gate")),"不能改变评审类型");}
        var row=new LinkedHashMap<>(data);row.remove("confirm");
        long reviewId=selected==0?rows.stream().mapToLong(r->number(r.get("id"))).max().orElse(0)+1:selected;
        row.put("id",reviewId);row.put("status",Boolean.TRUE.equals(data.get("confirm"))?"confirmed":"draft");
        row.put("references",service.snapshotReferences(id,service.refs(data.get("references"))));
        if(Boolean.TRUE.equals(data.get("confirm"))) {
            actualDate(row.get("reviewDate"),"实际评审日期");activeReviewer(row);
            require(Set.of("passed","failed","rework").contains(text(row.get("result"))),"请选择评审结论");filled(row.get("summary"),"评审说明");
            if(!"passed".equals(row.get("result")))filled(row.get("issues"),"未通过原因及整改要求");
            require(state.containsKey("requirement")&&!map(state.get("plan")).isEmpty(),"请先确认立项要求与开发计划");
            if("design".equals(gate)&&stage==4){service.requireCurrentDesignBasis(id,state);require(number(map(state.get("requirement")).get("ownerUserId"))==access.actorId(),"请由产品开发负责人确认样品");require(number(row.get("reviewerUserId"))==access.actorId(),"样品确认人必须是当前负责人");row.put("sampleRoundId",service.prototype(id,state).get("roundId"));row.put("samplePurpose","prototype_3d");}
            Map<String,Object> sample=null;
            if("t0".equals(gate)||number(row.get("sampleRoundId"))>0){
                sample="prototype_3d".equals(row.get("samplePurpose"))?service.prototype(id,state):service.confirmedSource(id,number(row.get("sampleRoundId")),"sample","passed".equals(row.get("result")));
                sourceAvailable(row.get("reviewDate"),sample);require(service.compatibleRequirement(state,map(sample.get("details")).get("requirementVersion")),"样品采用旧需求，请重新确认");service.sameTechnical(sample.get("references"),row.get("references"));
            }
            if("passed".equals(row.get("result"))) {
                requireChecks(passingReviewChecks(gate,row,Boolean.TRUE.equals(map(state.get("plan")).get("needsMold"))));
            }
            row.put("requirementVersion",state.get("requirementVersion"));row.put("planBasis",planBasis(state));
            row.put("revisionNo",revisionNo(state));
            row.put("setupVersion",number(map(state.get("reviewSetup")).get("version")));
            if("t0".equals(gate)) {
                requireDesign(id,state,sample,service);
                long moldId=number(row.get("moldSetId"));require(linkedMold(id,state,moldId),"请选择本项目关联的实际模具套");access.requireMold(moldId);
                var mold=moldState(moldId);require(Set.of("testing","ready").contains(text(mold.get("node"))),"请先登记模具制作／改模完成，再进行T0评审");
                row.put("moldCycle",number(mold.get("cycle")));state.put("validationSampleId",row.get("sampleRoundId"));
            }
            row.put("confirmedAt",java.time.LocalDateTime.now().toString());row.put("confirmedBy",access.actor().displayName());
        }
        if(old!=null)rows.remove(old);rows.add(row);state.put("reviewRecords",rows);data.put("saved",row);state.remove("finalized");
    }
    static List<ReadinessCheck> passingReviewChecks(String gate,Map<String,Object> row,boolean needsMold){
        var checks=new ArrayList<ReadinessCheck>();var refs=records(row.get("references"));
        checks.add(check("DESIGN_FEASIBILITY","结构、装配与制造可行性","design-review",()->{
            require(!Boolean.TRUE.equals(row.get("blockingIssues")),"阻断问题未关闭，不能通过评审");
            for(String field:List.of("structure","assembly"))require(Boolean.TRUE.equals(map(row.get("checklist")).get(field)),"请确认结构与装配效果");require(Boolean.TRUE.equals(map(row.get("checklist")).get("appearance"))||Boolean.TRUE.equals(map(row.get("checklist")).get("manufacturability")),"请确认样品外观");
        }));
        if(needsMold){
            checks.add(check("MATERIAL_BASIS","初步用料与部件方案","material-basis",()->{
                if("design".equals(gate))require(refs.stream().anyMatch(r->"bom".equals(r.get("type")))||!text(row.get("materialBasis")).isBlank(),"设计评审请填写初步用料与部件方案，或采用已有设计BOM；不要求量产编码及正式工艺");
                else require(refs.stream().anyMatch(r->"bom".equals(r.get("type")))||!text(row.get("materialBasis")).isBlank(),"请填写本次试模实际用料说明；详细BOM在试产完善");
            }));
            checks.add(check("DESIGN_DRAWING","本次采用的设计图纸版本","drawings",()->require(refs.stream().anyMatch(r->"drawing".equals(r.get("category"))),("design".equals(gate)?"设计":"T0")+"评审请采用具体图纸版本（可为设计草稿）")));
        }
        return checks;
    }
    static Map<String,Object> stageSetup(int stage,Map<String,Object> state,Map<String,Object> data) {
        require(stage==6||stage==7,"测试要求在样品验证阶段办理，试产要求在小批量试产阶段办理");
        var old=map(state.get("reviewSetup"));var setup=new LinkedHashMap<>(old);
        if(stage==6){
            for(String key:List.of("plannedQty","minYieldRate","fullInspection"))
                require(!data.containsKey(key)||Objects.equals(text(data.get(key)),text(old.get(key))),"试产数量、良率及检验要求请在小批量试产阶段维护");
            setup.put("tests",records(data.get("tests")));
        }else {
            require(!data.containsKey("tests")||Objects.equals(records(data.get("tests")),records(old.get("tests"))),"测试要求变化请返回样品验证复核");
            for(String key:List.of("plannedQty","minYieldRate","fullInspection"))if(data.containsKey(key))setup.put(key,data.get(key));
        }
        var tests=records(setup.get("tests"));require(tests.size()<=50,"测试要求最多50项");var codes=new HashSet<String>();
        for(var test:tests){filled(test.get("code"),"测试编号");filled(test.get("name"),"测试名称");filled(test.get("requirement"),"测试要求");require(codes.add(text(test.get("code"))),"测试编号不能重复");}
        if(setup.get("plannedQty")!=null){var qty=decimal(setup.get("plannedQty"),"试产计划数量");require(qty.signum()>0&&qty.stripTrailingZeros().scale()<=0,"试产计划数量必须为正整数");}
        if(setup.get("minYieldRate")!=null)percentage(setup.get("minYieldRate"),"试产良率标准");
        require(setup.get("fullInspection")==null||setup.get("fullInspection") instanceof Boolean,"请明确试产检验要求");
        boolean priorScope=stage==6?!records(old.get("tests")).isEmpty():List.of("plannedQty","minYieldRate","fullInspection").stream().anyMatch(k->old.get(k)!=null);
        boolean testsChanged=!Objects.equals(records(old.get("tests")),tests);
        boolean pilotChanged=List.of("plannedQty","minYieldRate","fullInspection").stream().anyMatch(k->!Objects.equals(text(old.get(k)),text(setup.get(k))));
        if(!testsChanged&&!pilotChanged)return new LinkedHashMap<>(old);
        if(priorScope&&state.containsKey("requirement"))filled(data.get("reason"),"验证要求修改原因");
        setup.put("tests",tests);setup.put("reason",data.get("reason"));setup.put("version",number(old.get("version"))+1);
        setup.put("testVersion",number(old.get("testVersion"))+(testsChanged?1:0));setup.put("pilotVersion",number(old.get("pilotVersion"))+(pilotChanged?1:0));return setup;
    }
    static Map<String,Object> planBasis(Map<String,Object> state) {
        var plan=map(state.get("plan"));var basis=new LinkedHashMap<String,Object>();
        basis.put("phoneModelIds",ids(plan.get("phoneModelIds")));basis.put("method",text(plan.get("method")));basis.put("needsMold",plan.get("needsMold"));return basis;
    }
    private void activeReviewer(Map<String,Object> record) {
        long user=number(record.get("reviewerUserId"));
        var people=jdbc.queryForList("select coalesce(display_name,user_name,username) as name from sys_user where user_id=? and deleted_flag=0 and status='active'",user);
        require(!people.isEmpty(),"请选择有效的实际评审人");record.put("reviewerName",people.get(0).get("name"));
    }
    public Map<String,Object> requireDesign(long id,Map<String,Object> state,Map<String,Object> sample,ProductDevelopmentService service) {
        return requireReview(id,state,"design",0,sample,service);
    }
    private Map<String,Object> requireReview(long id,Map<String,Object> state,String gate,long moldId,Map<String,Object> sample,ProductDevelopmentService service) {
        var candidates=records(state.get("reviewRecords"));Collections.reverse(candidates);
        for(var row:candidates) {
            if(!gate.equals(row.get("gate"))||Boolean.TRUE.equals(row.get("deleted"))||row.containsKey("voidReason")||!"confirmed".equals(row.get("status")))continue;
            if("design".equals(gate)&&"prototype_3d".equals(row.get("samplePurpose"))&&number(row.get("sampleRoundId"))!=number(state.get("prototypeRoundId")))continue;
            if(moldId>0&&(number(row.get("moldSetId"))!=moldId||number(row.get("moldCycle"))!=number(moldState(moldId).get("cycle"))))continue;
            if(!Objects.equals(planBasis(state),planBasis(Map.of("plan",map(row.get("planBasis")))))||!service.compatibleRequirement(state,row.get("requirementVersion"))||("t0".equals(gate)&&sample!=null&&number(row.get("sampleRoundId"))!=number(sample.get("roundId"))))continue;
            if(number(row.get("revisionNo"))!=revisionNo(state))continue;
            require("passed".equals(row.get("result")),("design".equals(gate)?"设计":"试模")+"最新评审未通过，请完成整改复评");
            if(sample!=null){require(!roundVoided(state,number(sample.get("roundId"))),"采用的样品已作废，请重新评审");service.sameTechnical(row.get("references"),service.snapshotReferences(id,service.refs(sample.get("references"))),true);}
            else service.sameTechnical(row.get("references"),service.snapshotReferences(id,service.refs(row.get("references"))));return row;
        }
        throw invalid(("design".equals(gate)?"设计评审":"T0评审（套 "+moldId+"）")+"尚未通过，或采用的样品／技术版本已变化，请完成对应评审");
    }
    public Map<String,Object> requireT0ForMold(long id,Map<String,Object> state,long moldId,Map<String,Object> sample,ProductDevelopmentService service){
        require(linkedMold(id,state,moldId),"请选择本项目关联的实际模具套");
        requireDesign(id,state,sample,service);
        return requireReview(id,state,"t0",moldId,sample,service);
    }
    public List<Map<String,Object>> requireT0(long id,Map<String,Object> state,Map<String,Object> sample,ProductDevelopmentService service) {
        var result=new ArrayList<Map<String,Object>>();requireChecks(t0Checks(id,state,sample,service,result));return result;
    }
    List<ReadinessCheck> t0Checks(long id,Map<String,Object> state,Map<String,Object> sample,ProductDevelopmentService service){return t0Checks(id,state,sample,service,new ArrayList<>());}
    private List<ReadinessCheck> t0Checks(long id,Map<String,Object> state,Map<String,Object> sample,ProductDevelopmentService service,List<Map<String,Object>> result){
        var checks=new ArrayList<ReadinessCheck>();
        checks.add(check("DESIGN_REVIEW","样品采用版本的设计评审","review:design",()->requireDesign(id,state,sample,service)));
        if(!Boolean.TRUE.equals(map(state.get("plan")).get("needsMold"))){
            checks.add(check("NO_MOLD","当前方案的无需开模依据","no-mold",()->requireNoMold(state,requireDesign(id,state,sample,service))));
        }else{
            var links=records(state.get("moldLinks"));
            checks.add(check("T0_MOLDS","逐套 T0 评审的实际模具关联","mold-links",()->require(!links.isEmpty(),"请先关联模具并完成逐套T0评审")));
            for(var link:links){long mold=resolveMold(id,link);String key=mold>0?"M"+mold:"A"+number(link.get("applicationId"));
                checks.add(check("T0_"+key,"T0／复试评审 · "+(mold>0?"实物套 #"+mold:"原申请 #"+number(link.get("applicationId"))),mold>0?"review:t0:"+mold:"application:"+number(link.get("applicationId")),()->{require(mold>0,"原申请尚未形成实际模具套");result.add(requireReview(id,state,"t0",mold,sample,service));}));
            }
        }
        return checks;
    }
    Map<String,Object> requireNoMold(Map<String,Object> state,Map<String,Object> design){
        var basis=map(state.get("noMold"));
        boolean unchangedPlan=basis.containsKey("planBasis")&&Objects.equals(planBasis(state),planBasis(Map.of("plan",map(basis.get("planBasis")))))&&number(basis.get("revisionNo"))==revisionNo(state);
        require(!basis.isEmpty()&&(unchangedPlan||number(basis.get("designReviewId"))==number(design.get("id"))),"请确认当前方案的无需开模依据；路径、机型或技术修订变化后需重新核对");
        return basis;
    }
    long resolveMold(long id,Map<String,Object> link) {
        long mold=number(link.get("moldSetId"));if(mold>0)return mold;
        var rows=jdbc.queryForList("select mold_set_id from plm_mold_application where application_id=? and deleted_flag=0 and (project_id=? or product_line_id=?)",number(link.get("applicationId")),id,id);
        return rows.isEmpty()?0:number(rows.get(0).get("mold_set_id"));
    }
    private boolean linkedMold(long id,Map<String,Object> state,long mold) {return mold>0&&records(state.get("moldLinks")).stream().anyMatch(link->resolveMold(id,link)==mold);}
    private Map<String,Object> moldState(long id) {
        var rows=jdbc.queryForList("select development_data::text as data from plm_collaboration_mold_set where mold_set_id=? and deleted_flag=0",id);
        require(!rows.isEmpty(),"模具套不存在");return map(store.read(rows.get(0).get("data")));
    }
    public void validateRound(long id,DevelopmentModels.RoundInput input,Map<String,Object> facts,Map<String,Object> state,ProductDevelopmentService service) {
        if(!input.confirm())return;
        actualDate(input.recordDate(),"实际验证／试产日期");activeReviewer(facts);
        if("test".equals(input.kind())) {
            var source=service.confirmedSource(id,number(facts.get("sourceRoundId")),"sample",true);
            sourceAvailable(input.recordDate(),source);
            var t0=requireT0(id,state,source,service);facts.put("t0Reviews",t0.stream().map(r->r.get("id")).toList());state.put("validationSampleId",source.get("roundId"));
            if("passed".equals(input.result())){validateTests(map(state.get("reviewSetup")),facts);for(var test:records(facts.get("tests")))require(!LocalDate.parse(text(test.get("date"))).isAfter(input.recordDate()),"评审日期不能早于本次测试结果日期");require(service.snapshotReferences(id,input.references()).stream().anyMatch(ref->"testing".equals(ref.get("category"))),"请采用本次实际测试报告版本");}
        } else {
            var test=requireReliability(id,state,service);sourceAvailable(input.recordDate(),test);
            require(number(test.get("roundId"))==number(facts.get("sourceRoundId")),"试产必须采用本次方案有效的可靠性验证");
            if("passed".equals(input.result()))validatePilot(map(state.get("reviewSetup")),facts);
        }
        facts.put("setupVersion",number(map(state.get("reviewSetup")).get("test".equals(input.kind())?"testVersion":"pilotVersion")));
    }
    public static void validateTests(Map<String,Object> setup,Map<String,Object> facts) {
        var requirements=requireTestRequirements(setup);
        var tests=records(facts.get("tests"));
        for(var target:requirements) {
            var result=tests.stream().filter(t->text(target.get("code")).equals(text(t.get("code")))).findFirst().orElseThrow(()->invalid("缺少测试项目："+target.get("name")));
            require("passed".equals(result.get("result")),target.get("name")+"尚未通过");
            if(target.get("minValue")!=null)require(decimal(result.get("measuredValue"),target.get("name")+"实测值").compareTo(decimal(target.get("minValue"),"测试最低标准"))>=0,target.get("name")+"实测值未达到要求");
            filled(result.get("actual"),target.get("name")+"实际结果");filled(result.get("method"),target.get("name")+"测试方法");filled(result.get("tester"),target.get("name")+"测试人");
            var samples=decimal(result.get("sampleQty"),"测试样本数");require(samples.signum()>0&&samples.stripTrailingZeros().scale()<=0,"测试样本数必须为正整数");actualDate(result.get("date"),"测试日期");
        }
    }
    static List<Map<String,Object>> requireTestRequirements(Map<String,Object> setup){
        var requirements=records(setup.get("tests"));require(!requirements.isEmpty(),"请先在验证要求中明确测试项目及判定标准");return requirements;
    }
    static void requirePilotRequirements(Map<String,Object> setup){
        require(decimal(setup.get("plannedQty"),"试产计划数量").signum()>0,"请先明确试产计划数量");
        require(setup.get("fullInspection") instanceof Boolean,"请先明确试产检验要求");percentage(setup.get("minYieldRate"),"试产良率标准");
    }
    public static void validatePilot(Map<String,Object> setup,Map<String,Object> facts) {
        quantities(facts);requirePilotRequirements(setup);
        var planned=decimal(setup.get("plannedQty"),"试产计划数量");
        var produced=decimal(facts.get("producedQty"),"实际产量");var inspected=decimal(facts.get("inspectedQty"),"检验数量");
        require(produced.compareTo(planned)>=0,"实际试产数量尚未达到计划要求");
        if(Boolean.TRUE.equals(setup.get("fullInspection")))require(produced.compareTo(inspected)==0,"本项目要求全检，检验数量必须等于实际产量");
        var target=percentage(setup.get("minYieldRate"),"试产良率标准");
        var rate=inspected.subtract(decimal(facts.get("defectQty"),"不良数")).multiply(BigDecimal.valueOf(100)).divide(inspected,8,RoundingMode.HALF_UP);
        require(rate.compareTo(target)>=0,"试产良率未达到评审标准");facts.put("yieldRate",rate);facts.put("minYieldRate",target);facts.put("plannedQty",planned);
        filled(facts.get("defectSummary"),"不良分类及处理说明（无不良也需明确记录）");
    }
    public Map<String,Object> requireReliability(long id,Map<String,Object> state,ProductDevelopmentService service) {
        long selected=number(state.get("validationSampleId"));if(selected==0)selected=number(state.get("selectedRoundId"));require(selected>0,"请先记录实际通过的样品");var sample=service.confirmedSource(id,selected,"sample",true);
        var t0=requireT0(id,state,sample,service);
        for(var row:service.allRounds(id)) {
            if(!"test".equals(row.get("kind"))||!"confirmed".equals(row.get("status"))||roundVoided(state,number(row.get("roundId"))))continue;
            var facts=map(row.get("details"));
            if(number(facts.get("sourceRoundId"))!=number(sample.get("roundId"))||!currentFacts(state,facts,service,"test"))continue;
            if(!Objects.equals(facts.get("t0Reviews"),t0.stream().map(r->r.get("id")).toList()))continue;
            require("passed".equals(row.get("result")),"最新可靠性评审未通过，请完成整改复验");if(!ids(facts.get("phoneModelIds")).containsAll(ids(map(state.get("plan")).get("phoneModelIds"))))continue;
            service.sameTechnical(row.get("references"),service.snapshotReferences(id,service.refs(sample.get("references"))),true);validateTests(map(state.get("reviewSetup")),facts);return row;
        }
        throw invalid("可靠性评审尚未通过，或采用的样品／技术版本已变化");
    }
    public Map<String,Object> requirePilot(long id,Map<String,Object> state,ProductDevelopmentService service) {
        var test=requireReliability(id,state,service);
        for(var row:service.allRounds(id)) {
            if(!"pilot".equals(row.get("kind"))||!"confirmed".equals(row.get("status"))||roundVoided(state,number(row.get("roundId"))))continue;
            var facts=map(row.get("details"));
            if(number(facts.get("sourceRoundId"))!=number(test.get("roundId"))||!currentFacts(state,facts,service,"pilot"))continue;
            require("passed".equals(row.get("result")),"最新试产评审未通过，请完成整改复评");if(!ids(facts.get("phoneModelIds")).containsAll(ids(map(state.get("plan")).get("phoneModelIds"))))continue;
            require(!map(state.get("pilotCorrections")).containsKey(text(row.get("roundId"))),"试产数量或检验已更正，请新增试产复评确认良率");
            service.sameTechnical(service.snapshotReferences(id,service.refs(test.get("references"))),row.get("references"),true);validatePilot(map(state.get("reviewSetup")),facts);return row;
        }
        throw invalid("试产评审尚未通过，或数量、良率标准及采用版本待补齐");
    }
    private boolean currentFacts(Map<String,Object> state,Map<String,Object> facts,ProductDevelopmentService service,String kind) {
        return service.compatibleRequirement(state,facts.get("requirementVersion"))&&number(facts.get("setupVersion"))==number(map(state.get("reviewSetup")).get("test".equals(kind)?"testVersion":"pilotVersion"))&&number(facts.get("revisionNo"))==revisionNo(state);
    }
    static void sourceAvailable(Object reviewDate,Map<String,Object> source){
        if(!text(source.get("recordDate")).isBlank())require(!LocalDate.parse(text(reviewDate)).isBefore(LocalDate.parse(text(source.get("recordDate")))),"评审或定型日期不能早于来源收样／评审日期");
    }
    static long revisionNo(Map<String,Object> state){var revision=map(state.get("revision"));return revision.isEmpty()||!Boolean.TRUE.equals(revision.get("technical"))?number(state.get("lastReviewRevisionNo")):number(revision.get("revisionNo"));}
    public static boolean roundVoided(Map<String,Object> state,long id) {return map(state.get("voidedRounds")).containsKey(String.valueOf(id));}
    static BigDecimal decimal(Object value,String label) {
        filled(value,label);try{return new BigDecimal(value.toString());}catch(NumberFormatException ex){throw invalid(label+"必须是有效数值");}
    }
    static BigDecimal percentage(Object value,String label) {var n=decimal(value,label);require(n.signum()>=0&&n.compareTo(BigDecimal.valueOf(100))<=0,label+"必须在0至100之间");return n;}
    static void actualDate(Object value,String label) {try{require(!LocalDate.parse(text(value)).isAfter(LocalDate.now()),label+"不能晚于今天");}catch(java.time.format.DateTimeParseException ex){throw invalid("请填写"+label);}}
}

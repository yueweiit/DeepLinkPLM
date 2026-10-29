package com.yuewei.plm.module.project.development;

import java.math.BigDecimal;
import java.util.*;
import static com.yuewei.plm.module.project.development.DevelopmentWorkflowStore.*;

/** Business validation shared by UI actions and the original timeline/publication entrypoints. */
public final class DevelopmentRules {
    private DevelopmentRules() {}
    public record ReadinessCheck(String code,String title,String target,boolean complete,String message) {}
    /** Both the read-only checklist and the original write gates execute these checks. */
    public static ReadinessCheck check(String code,String title,String target,Runnable validation){
        try{validation.run();return new ReadinessCheck(code,title,target,true,"");}
        catch(com.yuewei.plm.common.exception.BusinessException missing){return new ReadinessCheck(code,title,target,false,missing.getMessage());}
    }
    public static void requireChecks(List<ReadinessCheck> checks){
        checks.stream().filter(c->!c.complete()).findFirst().ifPresent(c->{throw invalid(c.message());});
    }
    public static void require(boolean value,String message){if(!value)throw invalid(message);}
    public static int roundStage(String kind,Map<String,Object> details){
        return switch(kind){case "sample" -> "prototype_3d".equals(details.get("samplePurpose"))?3:6;case "test" -> 6;case "pilot" -> 7;default -> throw invalid("开发记录类型无效");};
    }
    public static int roundStage(String kind){return roundStage(kind,Map.of());}
    public static void atStage(Integer productStage,Map<String,Object> state,int expected){
        var revision=map(state.get("revision"));
        long current=revision.isEmpty()?(productStage==null?1:productStage):number(revision.get("stage"));
        require(current==expected,"当前不在本操作所属阶段，请刷新并前往当前阶段办理；需要修改前序内容时先退回对应阶段");
    }
    public static void filled(Object value,String label){require(!text(value).isBlank(),"请填写"+label);}
    public static List<Long> ids(Object value){
        if(!(value instanceof List<?> list))return List.of();
        require(list.size()<=100,"适配机型不能超过100个");
        return list.stream().map(DevelopmentWorkflowStore::number).peek(id->require(id>0,"机型编号无效")).distinct().sorted().toList();
    }
    public static boolean sameRequirementDraft(Map<String,Object> confirmed,Map<String,Object> draft){
        if(confirmed.isEmpty())return false;
        for(String key:List.of("productName","model","requirements","expectedDeliveryDate","pendingReason","currency","costUnit","notes"))
            if(!text(confirmed.get(key)).equals(text(draft.get(key))))return false;
        if(number(confirmed.get("ownerUserId"))!=number(draft.get("ownerUserId"))||!ids(confirmed.get("phoneModelIds")).equals(ids(draft.get("phoneModelIds"))))return false;
        var left=confirmed.get("targetCost");var right=draft.get("targetCost");
        if(left==null||right==null){if(left!=right)return false;}else if(new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()))!=0)return false;
        return referenceIds(confirmed.get("references")).equals(referenceIds(draft.get("references")));
    }
    private static Set<String> referenceIds(Object value){
        if(!(value instanceof List<?> rows))return Set.of();var result=new TreeSet<String>();
        for(Object item:requirementReferences(rows)){var ref=map(item);result.add(text(ref.get("type"))+":"+number(ref.get("id")));}return result;
    }
    public static boolean technicalReference(Map<String,Object> ref){
        return Set.of("bom","process").contains(text(ref.get("type")))||("file".equals(ref.get("type"))&&Set.of("drawing","sop","sip").contains(text(ref.get("category"))));
    }
    /** Product requirements keep their source documents; design and test versions belong to their reviews. */
    public static List<Map<String,Object>> requirementReferences(Object value){
        if(!(value instanceof List<?> rows))return List.of();
        return rows.stream().map(DevelopmentWorkflowStore::map).filter(r->!technicalReference(r)&&!("file".equals(r.get("type"))&&Set.of("testing","sample_image").contains(text(r.get("category"))))).toList();
    }
    public static void requirement(Map<String,Object> profile){requirement(profile,true);}
    public static void requirement(Map<String,Object> profile,boolean requirePhones){
        requireChecks(requirementChecks(profile,requirePhones));
    }
    public static List<ReadinessCheck> requirementChecks(Map<String,Object> profile,boolean requirePhones){
        var checks=new ArrayList<ReadinessCheck>();
        checks.add(check("PRODUCT_NAME","产品名称","product-name",()->filled(profile.get("productName"),"产品名称")));
        checks.add(check("REQUIREMENTS","开发要求","requirements",()->filled(profile.get("requirements"),"开发要求")));
        if(requirePhones)checks.add(check("REQUIREMENT_PHONES","适配手机型号","phones",()->require(!ids(profile.get("phoneModelIds")).isEmpty()||!text(profile.get("pendingReason")).isBlank(),"请选择适配机型，或填写暂未确定的原因")));
        checks.add(check("OWNER","项目负责人","owner",()->require(number(profile.get("ownerUserId"))>0,"请选择有效负责人")));
        return checks;
    }
    public static void round(DevelopmentModels.RoundInput input,Map<String,Object> facts){
        if(!input.confirm())return;
        require(input.recordDate()!=null,"请填写实际日期");
        require(!ids(facts.get("phoneModelIds")).isEmpty(),"请明确本轮实际适配机型范围");
        require(Set.of("test","pilot").contains(input.kind()),"样品收样与初检请使用样品校验");
        require(number(facts.get("sourceRoundId"))>0,"请选择已确认的来源记录");
        filled(facts.get("actualResult"),"本次实际结果");
        require(Set.of("passed","failed").contains(input.result()),"确认时请选择本轮通过或未通过");
        if("test".equals(input.kind()))filled(facts.get("testContent"),"本次测试内容");
        if("pilot".equals(input.kind())){quantities(facts,"passed".equals(input.result()));if("passed".equals(input.result()))filled(facts.get("inspectionMethod"),"检验方式");}
        if("passed".equals(input.result()))require(!Boolean.TRUE.equals(facts.get("blockingIssues")),"仍有影响后续的问题，不能确认通过");
    }

    public static void quantities(Map<String,Object> facts){quantities(facts,true);}
    private static void quantities(Map<String,Object> facts,boolean completed){
        Long produced=optionalQuantity(facts.get("producedQty"),"生产数量"),inspected=optionalQuantity(facts.get("inspectedQty"),"检验数量"),defect=optionalQuantity(facts.get("defectQty"),"不良数量");
        if(completed){
            require(produced!=null&&produced>0&&inspected!=null&&inspected>0,"实际生产和检验数量必须大于0");
            require(defect!=null,"请填写不良数量");
        }
        if(inspected!=null&&produced!=null)require(inspected<=produced,"检验数量不能超过生产数量");
        if(defect!=null&&inspected!=null)require(defect<=inspected,"不良数量不能超过检验数量");
    }
    private static Long optionalQuantity(Object value,String label){return value==null||text(value).isBlank()?null:quantity(value,label);}
    private static long quantity(Object value,String label){
        require(value!=null&&!text(value).isBlank(),"请填写"+label);
        try{long n=new BigDecimal(value.toString()).longValueExact();require(n>=0,label+"必须为非负整数");return n;}
        catch(ArithmeticException|NumberFormatException failure){throw invalid(label+"必须为非负整数");}
    }
    public static void covers(Map<String,Object> source,Map<String,Object> target){
        require(ids(source.get("phoneModelIds")).containsAll(ids(target.get("phoneModelIds"))),"本轮适配范围超出来源验证范围，请补充对应样品或验证");
    }
}

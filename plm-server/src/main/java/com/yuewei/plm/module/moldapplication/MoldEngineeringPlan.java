package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.yuewei.plm.module.moldapplication.MoldApplicationForms.*;
import static com.yuewei.plm.common.util.ManufacturingHole.*;

/** Intended scope is not engineering proof, nor is a phone identity a manufacturing identity. */
public final class MoldEngineeringPlan {
    private MoldEngineeringPlan() {}
    static JsonNode from(Map<String,Object> row) {
        if(row.get("executionResolution") instanceof JsonNode resolution&&resolution.path("engineeringPlan").isObject())return resolution.path("engineeringPlan");
        return row.get("sourceSnapshot") instanceof JsonNode source ? source.path("engineeringPlan") : null;
    }
    static boolean present(JsonNode plan) {return plan!=null&&plan.isObject();}
    public static JsonNode normalize(ObjectMapper json,JsonNode input) {
        if(input==null||input.isNull()||input.isMissingNode())return null;
        if(!input.isObject())throw invalid("请按申请规格填写工程方案");
        String handling=input.path("handling").asText("pending");
        if(!Set.of("pending","new","new_set","reuse").contains(handling))throw invalid("请选择有效的工程办理方式");
        var result=json.createObjectNode();result.put("handling",handling);var rows=result.putArray("specifications");
        JsonNode specs=input.path("specifications");if(!specs.isArray())throw invalid("请提供本次适用规格");
        var keys=new HashSet<String>();
        for(JsonNode value:specs) {
            if(!value.isObject())throw invalid("申请规格无效");
            String key=value.path("key").asText("").strip(),hole=value.path("holeType").asText("").strip();
            if(key.isBlank()||!keys.add(key))throw invalid("申请规格标识缺失或重复");
            if(!Set.of("","large","precision","other").contains(hole))throw invalid("孔型请选择大孔、精孔或其他");
            String description=value.path("structureDescription").asText("").strip();
            if(description.length()>1000)throw invalid("结构说明不能超过1000字");
            var row=rows.addObject();row.put("key",key);row.put("holeType",hole);row.put("structureDescription",description);
            if(!value.path("phoneModelIds").isArray())throw invalid("请选择本规格的适用手机");
            var phones=new TreeSet<Long>();for(JsonNode phone:value.path("phoneModelIds")) {if(!phone.isIntegralNumber()||phone.asLong()<1)throw invalid("适用手机无效");phones.add(phone.asLong());}
            var members=row.putArray("phoneModelIds");phones.forEach(members::add);
            if(value.hasNonNull("manufacturingRelationId")) {
                if(!value.path("manufacturingRelationId").isIntegralNumber()||value.path("manufacturingRelationId").asLong()<1||!value.path("manufacturingRelationVersion").isIntegralNumber()||value.path("manufacturingRelationVersion").asLong()<1)throw invalid("请重新选择有效的制造关联及版本");
                row.put("manufacturingRelationId",value.path("manufacturingRelationId").asLong());row.put("manufacturingRelationVersion",value.path("manufacturingRelationVersion").asLong());
            }
        }
        if(input.hasNonNull("codePhoneModelId")) {
            long phone=input.path("codePhoneModelId").asLong();if(!input.path("codePhoneModelId").isIntegralNumber()||!phones(result).contains(phone))throw invalid("刻码基准手机必须属于本次适用范围");result.put("codePhoneModelId",phone);
        }
        return result;
    }
    static List<Long> phones(JsonNode plan) {
        var result=new TreeSet<Long>();if(present(plan))for(JsonNode spec:plan.path("specifications"))for(JsonNode id:spec.path("phoneModelIds"))result.add(id.asLong());return new ArrayList<>(result);
    }
    static boolean includesPhone(Map<String,Object> application,long phone) {
        var plan=from(application);return present(plan)?phones(plan).contains(phone):Objects.equals(application.get("phoneModelId"),phone);
    }
    static boolean overlaps(JsonNode plan,Map<String,Object> application) {
        var prior=from(application);
        if(!present(prior)) {
            var phone=application.get("phoneModelId");String hole=application.get("form") instanceof JsonNode form?canonicalHole(form.path("holeType").asText()):"";
            for(JsonNode spec:plan.path("specifications"))if((phone==null||spec.path("phoneModelIds").isEmpty()||phonesOf(spec).contains(((Number)phone).longValue()))&&(hole.isBlank()||spec.path("holeType").asText().isBlank()||hole.equals(spec.path("holeType").asText())))return true;
            return false;
        }
        for(JsonNode spec:plan.path("specifications"))for(JsonNode previous:prior.path("specifications")) {
            boolean samePhones=spec.path("phoneModelIds").isEmpty()||previous.path("phoneModelIds").isEmpty()||!Collections.disjoint(phonesOf(spec),phonesOf(previous));
            boolean sameHole=spec.path("holeType").asText().isBlank()||previous.path("holeType").asText().isBlank()||spec.path("holeType").equals(previous.path("holeType"));
            if(samePhones&&sameHole&&spec.path("structureDescription").equals(previous.path("structureDescription")))return true;
        }
        return false;
    }
    private static Set<Long> phonesOf(JsonNode spec) {var result=new HashSet<Long>();spec.path("phoneModelIds").forEach(phone->result.add(phone.asLong()));return result;}
    static void requireHoles(JsonNode plan,Map<String,Object> form) {
        if(present(plan)) {
            if(plan.path("specifications").isEmpty())throw invalid("请填写本次适用规格及孔型");
            for(JsonNode row:plan.path("specifications")) {
                if(holeLabel(row.path("holeType").asText()).isBlank())throw invalid("请明确每项申请规格的孔型");
                if("other".equals(row.path("holeType").asText())&&row.path("structureDescription").asText().isBlank())throw invalid("其他孔型必须填写结构说明");
            }
            if("reuse".equals(plan.path("handling").asText()))throw invalid("沿用已有模具请由工程确认关联，不发起新的开模审批");
        } else {
            String hole=canonicalHole(text(form.get("holeType")));
            if(hole.isBlank())throw invalid("孔型尚未明确，请补充大孔、精孔或其他结构说明后送审");
            if("other".equals(hole)&&text(form.get("structureDescription"))==null)throw invalid("其他孔型必须填写结构说明");
        }
    }
    static void requireCoding(JsonNode plan) {
        if(!present(plan))return;
        requireHolesForCoding(plan);
        if("pending".equals(plan.path("handling").asText()))throw invalid("工程办理方式待确认，不自动新建模具或生产编码");
        if(phones(plan).isEmpty())throw invalid("适用手机待确认，不自动生成正式编码");
        if(!"reuse".equals(plan.path("handling").asText())&&!plan.hasNonNull("codePhoneModelId"))throw invalid("请工程明确本次制造编码基准手机");
    }
    private static void requireHolesForCoding(JsonNode plan) {
        var check=((ObjectNode)plan).deepCopy();check.put("handling","new");requireHoles(check,Map.of());
    }
    static Map<String,Object> withScope(Map<String,Object> form,JsonNode plan) {
        if(!present(plan))return form;
        var result=new LinkedHashMap<>(form);var holes=new LinkedHashSet<String>();var notes=new ArrayList<String>();
        for(JsonNode row:plan.path("specifications")) {String hole=holeLabel(row.path("holeType").asText());if(!hole.isBlank())holes.add(hole);notes.add(hole+" "+row.path("structureDescription").asText());}
        if(holes.size()==1)result.put("holeType",holes.iterator().next());
        result.put("remark",Objects.toString(result.get("remark"),"")+"\n本次工程规格："+String.join("；",notes)+"；办理方式："+plan.path("handling").asText());return result;
    }
    static void mapTemplateHole(Map<String,Object> form,List<Map<String,Object>> controls) {
        var control=controls.stream().filter(value->"holeType".equals(value.get("key"))).findFirst().orElseThrow(()->invalid("当前钉钉模板尚未映射孔型字段，请先核对模板"));
        String hole=canonicalHole(text(form.get("holeType")));
        if(control.get("options") instanceof List<?> options&&!options.isEmpty()) {
            var matching=options.stream().map(Objects::toString).filter(option->!hole.isBlank()&&hole.equals(canonicalHole(option))).toList();
            if(matching.size()!=1)throw invalid("当前钉钉模板孔型选项未明确映射，请核对真实选项后送审");
            form.put("holeType",matching.get(0));
        }
    }
    public static List<Map<String,Object>> resolveReuse(com.yuewei.plm.module.manufacturing.ManufacturingIdentityService identities,long productLineId,JsonNode plan) {
        if(!present(plan)||!"reuse".equals(plan.path("handling").asText()))throw invalid("请明确沿用工程方案");return resolveReferenced(identities,productLineId,plan);
    }
    public static List<Map<String,Object>> resolveReferenced(com.yuewei.plm.module.manufacturing.ManufacturingIdentityService identities,long productLineId,JsonNode plan) {
        if(!present(plan)||!Set.of("reuse","new_set").contains(plan.path("handling").asText()))throw invalid("请明确沿用或加开原模具的工程方案");requireCoding(plan);
        var result=new ArrayList<Map<String,Object>>();Long mold=null;
        for(JsonNode spec:plan.path("specifications")) {
            if(spec.path("phoneModelIds").size()!=1||!spec.hasNonNull("manufacturingRelationId"))throw invalid("沿用或加开时每项规格须明确一个手机及其工程确认关系");
            var match=identities.requireResolved(new com.yuewei.plm.module.manufacturing.ManufacturingIdentityService.ResolveInput(productLineId,spec.path("phoneModelIds").get(0).asLong(),spec.path("holeType").asText(),spec.path("structureDescription").asText(),spec.path("manufacturingRelationId").asLong(),spec.path("manufacturingRelationVersion").asLong()));
            long selected=((Number)match.get("moldSetId")).longValue();if(mold!=null&&mold!=selected)throw invalid("同一共模执行方案须指向同一实物套，不同套请分别办理");mold=selected;result.add(match);
        }
        return List.copyOf(result);
    }
}

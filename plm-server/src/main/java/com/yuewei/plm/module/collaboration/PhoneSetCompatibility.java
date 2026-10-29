package com.yuewei.plm.module.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuewei.plm.common.exception.BusinessException;
import java.util.*;

/** Canonical per-set records; the old array/flags are a derived wire compatibility projection. */
final class PhoneSetCompatibility {
    private PhoneSetCompatibility() {}
    static List<Map<String,Object>> normalize(JsonNode input) {
        if(input==null||input.isNull())return List.of();
        if(!input.isArray()||input.size()>300)throw invalid("兼容关系须为手机成员数组（最多300条）");
        var members=new TreeMap<Long,Map<String,Object>>();
        for(var entry:input) {
            Set<String> keys=new HashSet<>();entry.fieldNames().forEachRemaining(keys::add);
            boolean modern=entry.has("setCompatibilities");
            var allowed=new HashSet<>(Set.of("phoneModelId","setIds","pcConfirmed","tpuConfirmed","evidence","setCompatibilities"));
            if(!entry.isObject()||!allowed.containsAll(keys)||!entry.has("phoneModelId")||!modern&&!keys.equals(Set.of("phoneModelId","setIds","pcConfirmed","tpuConfirmed","evidence")))throw invalid("兼容关系须包含手机及逐套适配资料");
            long phone=id(entry.get("phoneModelId"));if(members.containsKey(phone))throw invalid("兼容手机ID重复");
            var sets=new TreeMap<Long,Map<String,Object>>();
            JsonNode values=modern?entry.get("setCompatibilities"):entry.get("setIds");
            if(values==null||!values.isArray()||values.isEmpty()||values.size()>300)throw invalid("每款手机须明确至少一套适用模具");
            for(var value:values) {
                long set=id(modern?value.get("moldSetId"):value);if(sets.containsKey(set))throw invalid("同一手机适用套重复");
                if(modern&&(!value.isObject()||value.size()!=5||!value.has("moldSetId")||!value.has("pcConfirmed")||!value.has("tpuConfirmed")||!value.has("pcEvidence")||!value.has("tpuEvidence")))throw invalid("逐套适配须包含套ID、PC／TPU独立确认及依据");
                var row=new LinkedHashMap<String,Object>();row.put("moldSetId",set);
                for(String prefix:List.of("pc","tpu")) {
                    JsonNode confirmed=modern?value.get(prefix+"Confirmed"):entry.get(prefix+"Confirmed");
                    if(confirmed==null||!confirmed.isBoolean())throw invalid("部件适配确认须为布尔值");
                    JsonNode proof=modern?value.get(prefix+"Evidence"):entry.get("evidence");
                    if(proof!=null&&!proof.isNull()&&!proof.isTextual())throw invalid("适配依据须为文字");
                    String evidence=proof==null||proof.isNull()?"":proof.asText().strip();if(evidence.length()>2000||confirmed.asBoolean()&&evidence.isBlank())throw invalid("确认部件适配须填写依据（最多2000字）");
                    row.put(prefix+"Confirmed",confirmed.asBoolean());row.put(prefix+"Evidence",evidence.isBlank()?null:evidence);
                }
                sets.put(set,row);
            }
            var relation=new LinkedHashMap<String,Object>();relation.put("phoneModelId",phone);relation.put("setCompatibilities",List.copyOf(sets.values()));
            relation.put("setIds",List.copyOf(sets.keySet()));
            for(String prefix:List.of("pc","tpu"))relation.put(prefix+"Confirmed",sets.values().stream().allMatch(s->Boolean.TRUE.equals(s.get(prefix+"Confirmed"))));
            relation.put("evidence",String.join("；",sets.values().stream().flatMap(s->java.util.stream.Stream.of(s.get("pcEvidence"),s.get("tpuEvidence"))).filter(Objects::nonNull).map(Object::toString).distinct().sorted().toList()));
            members.put(phone,relation);
        }
        return List.copyOf(members.values());
    }
    private static long id(JsonNode node) {if(node==null||!node.isIntegralNumber()||!node.canConvertToLong()||node.asLong()<1)throw invalid("手机或实物套ID无效");return node.asLong();}
    private static BusinessException invalid(String message){return new BusinessException(40001,message);}
}

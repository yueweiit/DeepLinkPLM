package com.yuewei.plm.module.moldsales;

import com.fasterxml.jackson.databind.*;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** A reviewed sales alias never asserts a new physical fit or merges two mold groups. */
final class MoldSalesReconciliation {
    private final Map<String,JsonNode> decisions=new LinkedHashMap<>();
    private final Set<String> changedSources=new HashSet<>();
    private final Set<String> validCodeBindings=new HashSet<>();
    private static boolean codeBased(JsonNode decision){return "mold_catalog".equals(decision.path("codeBasis").path("kind").asText());}
    private static String key(String style,String phone){return style+":"+phone;}
    static MoldSalesReconciliation load(JdbcTemplate jdbc,ObjectMapper json){
        var result=new MoldSalesReconciliation();
        for(String raw:jdbc.queryForList("select decisions::text from plm_mold_sales_reconciliation order by created_at,batch_id",String.class)){
            try{for(var d:json.readTree(raw))result.decisions.put(key(d.path("styleCode").asText(),d.path("phoneCode").asText()),d);}catch(Exception e){throw new IllegalStateException("关联依据读取失败",e);}
        }
        if(result.decisions.values().stream().anyMatch(MoldSalesReconciliation::codeBased)){
            var codes=new HashMap<Long,String>();
            for(var row:jdbc.queryForList("select source_code_id,mold_code from plm_mold_code_source where not source_disabled"))codes.put(((Number)row.get("source_code_id")).longValue(),Objects.toString(row.get("mold_code"),"").trim().toUpperCase(Locale.ROOT));
            for(var entry:result.decisions.entrySet())if(validCodeBasis(entry.getValue(),codes))result.validCodeBindings.add(entry.getKey());
        }
        if(!result.decisions.isEmpty())for(var row:jdbc.queryForList("select style_code,phone_code,array_to_json(array_agg(distinct coalesce(source_data->>'name','')))::text names from plm_mold_sales_fact where source_kind='crm' and eligibility='eligible' group by style_code,phone_code")){
            String k=key(row.get("style_code").toString(),row.get("phone_code").toString());var d=result.decisions.get(k);if(d==null||codeBased(d))continue;
            try{for(var name:json.readTree(row.get("names").toString()))if(!contains(d.path("sourceNames"),name.asText()))result.changedSources.add(k);}catch(Exception e){throw new IllegalStateException(e);}
        }
        return result;
    }
    private static boolean validCodeBasis(JsonNode decision,Map<Long,String> sources){
        if(!codeBased(decision)||!decision.path("codeBasis").path("codes").isArray()||decision.path("codeBasis").path("codes").isEmpty())return false;
        var parser=new ProductBusinessCodeGenerator();
        for(var source:decision.path("codeBasis").path("codes")){
            String code=source.path("moldCode").asText();
            if(!ProductBusinessCodeGenerator.isCompleteMoldCode(code)||!code.equals(sources.get(source.path("sourceCodeId").asLong())))return false;
            var parts=parser.parseMoldCode(code,null,List.of(),null);
            if(!parts.productSpecificCode().equals(decision.path("styleCode").asText())||!parts.phoneModelCode().equals(decision.path("targetBasis").path("phoneCode").asText()))return false;
        }
        return true;
    }
    private static boolean contains(JsonNode array,String value){for(var n:array)if(n.asText().equals(value))return true;return false;}
    @SuppressWarnings("unchecked") void apply(List<Map<String,Object>> groups){
        Set<String> claimed=new HashSet<>();
        for(var g:groups)for(String code:(Collection<String>)g.get("phoneCodes"))claimed.add(key(g.get("styleCode").toString(),code));
        for(var entry:decisions.entrySet()){
            var d=entry.getValue();if(!d.path("decision").asText().equals("linked")||changedSources.contains(entry.getKey())||claimed.contains(entry.getKey())||(codeBased(d)&&!validCodeBindings.contains(entry.getKey())))continue;
            for(var g:groups){
                var records=(List<Map<String,Object>>)g.get("productionRecords");
                var target=records.stream().filter(r->((Number)r.get("itemId")).longValue()==d.path("targetItemId").asLong()).findFirst();
                if(target.isEmpty()||!basisMatches(d,g,target.get()))continue;
                ((Collection<String>)g.get("phoneCodes")).add(d.path("phoneCode").asText());claimed.add(entry.getKey());
                var aliases=(List<JsonNode>)g.computeIfAbsent("salesAliases",k->new ArrayList<JsonNode>());aliases.add(d);break;
            }
        }
    }
    static boolean basisMatches(JsonNode d,Map<String,Object> group,Map<String,Object> target){
        var b=d.path("targetBasis");return d.path("styleCode").asText().equals(Objects.toString(group.get("styleCode"),""))&&(codeBased(d)||b.path("modelName").asText().equals(Objects.toString(target.get("modelName"),"")))&&b.path("phoneCode").asText().equals(Objects.toString(target.get("phoneCode"),""));
    }
    Set<String> ignoredKeys(){var out=new HashSet<String>();for(var entry:decisions.entrySet())if(entry.getValue().path("decision").asText().equals("ignored")&&!changedSources.contains(entry.getKey()))out.add(entry.getKey());return out;}
    void explain(Map<String,Object> row){
        String k=key(row.get("styleCode").toString(),row.get("phoneCode").toString());var d=decisions.get(k);
        if(d==null){row.put("reviewReason","新增来源，待核对下单系统及现有档案");return;}
        for(String field:List.of("websiteNames","candidateLabels","candidateIds","reason"))if(d.has(field))row.put(field.equals("reason")?"reviewReason":field,d.get(field));
        if(codeBased(d)&&!validCodeBindings.contains(k))row.put("reviewReason","原模具编码资料已停用、缺失或编码归属变更，需复核");
        else if(codeBased(d)&&d.path("decision").asText().equals("linked"))row.put("reviewReason","目标档案的产品或机型编码归属已变更，需复核");
        else if(changedSources.contains(k))row.put("reviewReason","同一来源编码出现新名称，原关联待复核");
        else if(d.path("decision").asText().equals("linked"))row.put("reviewReason","原关联的档案已调整，待复核");
    }
}

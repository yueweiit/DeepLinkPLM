package com.yuewei.plm.module.project.development;
import java.util.*;
/** One recursive policy for project snapshots and their canonical mold history projection. */
public final class FinancialSnapshotRedaction {
    private FinancialSnapshotRedaction() {}
    public static Object scopedMold(Object value,java.util.function.LongPredicate canReadProject) {
        if(value instanceof Map<?,?> map){
            var basis=DevelopmentWorkflowStore.map(map.get("basis"));long project=DevelopmentWorkflowStore.number(basis.get("projectId"));
            if(project>0&&!canReadProject.test(project)){
                var restricted=new LinkedHashMap<String,Object>();restricted.put("restricted",true);
                for(String key:List.of("node","result","actualDate"))if(map.containsKey(key))restricted.put(key,map.get(key));
                return restricted;
            }
            var result=new LinkedHashMap<String,Object>();map.forEach((k,v)->result.put(k.toString(),scopedMold(v,canReadProject)));return result;
        }
        if(value instanceof List<?> list)return list.stream().map(v->scopedMold(v,canReadProject)).toList();return value;
    }
    public static Object withoutMoney(Object value) {
        if (value instanceof Map<?, ?> map) {
            if((Set.of("quote","cost").contains(Objects.toString(map.get("kind"),""))||Objects.toString(map.get("kind"),"").startsWith("unit_cost_"))&&map.containsKey("data")){
                var hidden=new LinkedHashMap<String,Object>();map.forEach((key,child)->hidden.put(key.toString(),"data".equals(key)?Map.of("restricted",true):withoutMoney(child)));return hidden;
            }
            var clean = new LinkedHashMap<String, Object>();
            map.forEach((key, child) -> {
                if ("quoteEventId".equals(key) || !key.toString().toLowerCase(Locale.ROOT).matches(".*(cost|price|amount|total|quote|deposit).*")) clean.put(key.toString(), withoutMoney(child));
            });
            return clean;
        }
        if (value instanceof List<?> list) return list.stream().map(FinancialSnapshotRedaction::withoutMoney).toList();
        return value;
    }

}

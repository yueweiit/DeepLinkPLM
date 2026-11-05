package com.yuewei.plm.module.project.development;

import java.util.*;
import static com.yuewei.plm.module.project.development.DevelopmentWorkflowStore.*;
import static com.yuewei.plm.module.project.development.DevelopmentRules.require;

/** Scope metadata and versions stay inside the original project workflow document. */
public final class DevelopmentCostScopes {
    private DevelopmentCostScopes() {}
    public static List<Map<String,Object>> scopes(Map<String,Object> state){return LaunchReviewWorkflow.records(state.get("costScopes"));}
    public static Map<String,Object> find(Map<String,Object> state,String key,boolean writable){
        var scope=scopes(state).stream().filter(s->key.equals(s.get("id"))).findFirst().orElseThrow(()->invalid("成本组不存在或不属于本项目"));
        if(writable)require(!Boolean.TRUE.equals(scope.get("inactive")),"该成本组已停用，请先恢复");return scope;
    }
    public static Map<String,Object> costState(Map<String,Object> state,String key,boolean writable){return key.isBlank()?state:find(state,key,writable);}
    public static long nextVersionId(Map<String,Object> state){var all=new ArrayList<>(LaunchReviewWorkflow.records(state.get("unitCostVersions")));for(var scope:scopes(state))all.addAll(LaunchReviewWorkflow.records(scope.get("unitCostVersions")));return all.stream().mapToLong(c->number(c.get("id"))).max().orElse(0)+1;}
    public static List<Map<String,Object>> options(Map<String,Object> state){
        var result=new ArrayList<Map<String,Object>>();addOptions(result,state,"","项目原核算");
        for(var scope:scopes(state))addOptions(result,scope,text(scope.get("id")),text(scope.get("name")));return result;
    }
    private static void addOptions(List<Map<String,Object>> result,Map<String,Object> state,String scopeId,String name){
        for(var version:LaunchReviewWorkflow.records(state.get("unitCostVersions"))){var option=new LinkedHashMap<String,Object>();
            for(String key:List.of("id","title","bomId","bomVersion","basisType","confirmedAt","confirmedBy","voidReason"))if(version.containsKey(key))option.put(key,version.get(key));
            option.put("costScopeId",scopeId);option.put("costScopeName",name);option.put("key",scopeId+":"+version.get("id"));option.put("scopeInactive",Boolean.TRUE.equals(state.get("inactive")));result.add(option);
        }
    }
    public static List<Map<String,Object>> releaseCosts(Map<String,Object> state,Map<String,Object> draft,boolean requireAll){
        var keys=draft.get("unitCostKeys") instanceof List<?> list?list.stream().map(DevelopmentWorkflowStore::text).toList():List.<String>of();
        require(new HashSet<>(keys).size()==keys.size(),"同一成本版本不能重复采用");
        if(keys.isEmpty()){
            var selected=LaunchReviewWorkflow.records(state.get("unitCostVersions")).stream().filter(c->number(c.get("id"))==number(draft.get("unitCostVersionId"))&&!c.containsKey("voidReason")).toList();
            if(requireAll)require(selected.size()==1,"请选择已确认且有效的单件成本版本");return selected;
        }
        var phones=DevelopmentRules.ids(draft.get("phoneModelIds"));var selected=new ArrayList<Map<String,Object>>();var selectedScopes=new HashSet<String>();
        for(String key:keys){
            int split=key.lastIndexOf(':');require(split>0,"请选择明确成本组的版本");String scopeId=key.substring(0,split);var scope=find(state,scopeId,false);
            require(!Boolean.TRUE.equals(scope.get("inactive")),"不能采用已停用成本组");require(selectedScopes.add(scopeId),"每个成本组只采用一个版本");
            var relevantPhones=DevelopmentRules.ids(scope.get("phoneModelIds")).stream().filter(phones::contains).toList();require(!relevantPhones.isEmpty(),"所选成本组不在本批定型范围内");
            var cost=LaunchReviewWorkflow.records(scope.get("unitCostVersions")).stream().filter(c->number(c.get("id"))==number(key.substring(split+1))&&!c.containsKey("voidReason")).findFirst().orElseThrow(()->invalid("成本组版本不存在或已作废"));
            require(DevelopmentRules.ids(cost.get("costScopePhoneModelIds")).containsAll(relevantPhones),"成本版本的兼容范围已变化，请核对后保存新版本");selected.add(cost);
        }
        if(requireAll)require(!phones.isEmpty()&&phones.stream().allMatch(phone->selectedScopes.stream().anyMatch(scopeId->DevelopmentRules.ids(find(state,scopeId,false).get("phoneModelIds")).contains(phone))),"本批还有型号未采用对应成本版本");
        return selected;
    }
}

package com.yuewei.plm.module.partner;

import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Pure, shared supply coverage rules. A range is not a declaration of a Cartesian product. */
public final class SupplierSupplyRules {
    private SupplierSupplyRules() {}
    public static final Set<String> STATES=Set.of("available","unavailable","unverified");
    private record Conditions(String state,String price,String currency,String packaging,String terms,String moq,String unit) {}

    public static Map<String,Object> coverage(String supplier,String style,String config,String quoteVersion,
            List<Map<String,Object>> pairings,List<Map<String,Object>> selectedPairs) {
        return coverage(supplier,style,config,quoteVersion,pairings,selectedPairs,"");
    }

    public static Map<String,Object> coverage(String supplier,String style,String config,String quoteVersion,
            List<Map<String,Object>> pairings,List<Map<String,Object>> selectedPairs,String sourceContext) {
        required(supplier,"供应商");
        Map<String,List<Map<String,Object>>> facts=new LinkedHashMap<>();
        List<Map<String,Object>> conflicts=new ArrayList<>();
        for(var raw:pairings) {
            require(supplier.equals(text(raw.get("supplierCode"))),"供货关系不属于当前供应商");
            require(text(style).equals(text(raw.get("styleKey")))&&text(config).equals(text(raw.get("configKey"))),"供货关系的款式／配置不符");
            if(!text(quoteVersion).isEmpty())require(text(quoteVersion).equals(text(raw.get("quoteVersion"))),"供货关系的报价版本不符");
            else require(!text(sourceContext).isEmpty()&&text(raw.get("quoteVersion")).isEmpty()&&sourceContext.equals(text(raw.get("sourceContext"))),"供货关系缺少一致的已冻结来源版本");
            String state=text(raw.get("state"));require(STATES.contains(state),"供货结论须为明确可供、不供或待核实");
            if(!state.equals("unverified"))required(raw.get("evidence"),"供货核对依据");
            String key=key(raw);facts.computeIfAbsent(key,k->new ArrayList<>()).add(raw);
        }
        Set<String> conflictKeys=new HashSet<>();
        for(var entry:facts.entrySet()) {
            // A conflicting price/configuration is not resolved by input order or last-write-wins.
            Set<Conditions> signatures=new HashSet<>();
            for(var fact:entry.getValue())signatures.add(new Conditions(text(fact.get("state")),text(fact.get("unitPrice")),text(fact.get("currency")),text(fact.get("packaging")),text(fact.get("terms")),text(fact.get("moq")),text(fact.get("unit"))));
            if(signatures.size()>1){conflicts.add(Map.of("pair",entry.getKey(),"reason","同一组合存在不同供货结论或报价条件"));conflictKeys.add(entry.getKey());}
        }
        List<Map<String,Object>> available=new ArrayList<>(),unavailable=new ArrayList<>(),unverified=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(var selected:selectedPairs) {
            String key=key(selected);require(seen.add(key),"本次选择包含重复型号／颜色组合");
            List<Map<String,Object>> options=facts.getOrDefault(key,List.of());
            boolean conflict=conflictKeys.contains(key);
            String state=options.isEmpty()||conflict?"unverified":text(options.get(0).get("state"));
            var result=new LinkedHashMap<String,Object>(selected);result.put("state",state);
            if(state.equals("available"))available.add(result);else if(state.equals("unavailable"))unavailable.add(result);else unverified.add(result);
        }
        var relevantConflicts=conflicts.stream().filter(c->seen.contains(text(c.get("pair")))).toList();
        return Map.of("available",available,"unavailable",unavailable,"unverified",unverified,"conflicts",relevantConflicts,"otherConflicts",conflicts.stream().filter(c->!seen.contains(text(c.get("pair")))).toList(),
                "canConfirm",!selectedPairs.isEmpty()&&unavailable.isEmpty()&&unverified.isEmpty()&&relevantConflicts.isEmpty());
    }

    public static Map<String,Object> validate(String supplier,String style,String config,String quoteVersion,
            List<Map<String,Object>> pairings,List<Map<String,Object>> selectedPairs) {
        return validate(supplier,style,config,quoteVersion,pairings,selectedPairs,"");
    }

    public static Map<String,Object> validate(String supplier,String style,String config,String quoteVersion,
            List<Map<String,Object>> pairings,List<Map<String,Object>> selectedPairs,String sourceContext) {
        require(!selectedPairs.isEmpty(),"请明确本次实际选择的型号和颜色组合；范围引用不能代替具体选择");
        var coverage=coverage(supplier,style,config,quoteVersion,pairings,selectedPairs,sourceContext);
        require(Boolean.TRUE.equals(coverage.get("canConfirm")),"本次选择含不供、待核实或冲突组合，请逐项核对，不能自动按型号×颜色推定可供");
        return coverage;
    }

    private static String key(Map<String,Object> pair) {
        long phone=number(pair.get("phoneModelId")),color=number(pair.get("colorId"));
        require(phone>0&&color>0,"供货与选择须明确真实手机成员和供方颜色");return phone+":"+color;
    }
}

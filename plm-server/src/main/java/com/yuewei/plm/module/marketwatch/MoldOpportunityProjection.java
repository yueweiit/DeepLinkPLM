package com.yuewei.plm.module.marketwatch;

import java.util.*;

/** Mutually exclusive business progress. Physical reusability remains owned by MoldCompatibilityPolicy. */
final class MoldOpportunityProjection {
    static final List<String> VIEWS=List.of("opportunities","to_apply","applied","making","partially_molded","molded","not_planned","all");
    private MoldOpportunityProjection() {}
    @SuppressWarnings("unchecked") static void apply(Map<String,Object> row){
        var groups=(List<Map<String,Object>>)row.getOrDefault("coverages",List.of());
        var catalog=(List<Map<String,Object>>)row.getOrDefault("salesProducts",List.of());
        var summaries=new ArrayList<Map<String,Object>>();
        for(var line:catalog){
            Object lineId=line.get("productLineId");var relevant=groups.stream().filter(g->Objects.equals(g.get("productLineId"),lineId)).toList();
            var products=relevant.stream().flatMap(g->records(g,"products").stream()).toList();
            var molds=relevant.stream().flatMap(g->records(g,"molds").stream()).toList();
            var apps=relevant.stream().flatMap(g->records(g,"applications").stream()).toList();
            boolean opened=products.stream().anyMatch(MoldOpportunityProjection::skuEvidence)||molds.stream().anyMatch(MoldOpportunityProjection::completed);
            boolean making=molds.stream().anyMatch(MoldOpportunityProjection::inProduction);
            boolean applied=apps.stream().anyMatch(MoldOpportunityProjection::submitted);
            String state=opened?"molded":making?"making":applied?"applied":"to_apply";
            var summary=new LinkedHashMap<String,Object>();summary.put("productLineId",lineId);summary.put("productLineName",line.get("productName"));summary.put("status",state);
            summary.put("skuCount",products.stream().filter(MoldOpportunityProjection::skuEvidence).count());summary.put("applicationCount",apps.stream().filter(MoldOpportunityProjection::submitted).count());summary.put("making",making);summary.put("reusable",molds.stream().anyMatch(m->Boolean.TRUE.equals(m.get("reusable"))));summaries.add(summary);
        }
        long opened=summaries.stream().filter(g->"molded".equals(g.get("status"))).count();
        long making=summaries.stream().filter(g->Boolean.TRUE.equals(g.get("making"))).count();
        long applied=summaries.stream().filter(g->((Number)g.get("applicationCount")).longValue()>0).count();
        boolean known=row.get("phoneModelId")!=null&&"matched".equals(row.get("matchState"));
        boolean coded=Objects.toString(row.get("phoneModelCode"),"").matches("[0-9]{4}");
        boolean activeExecution=groups.stream().anyMatch(g->records(g,"applications").stream().anyMatch(a->currentExecution(a,records(g,"molds")))||records(g,"molds").stream().anyMatch(m->currentMold(m,records(g,"applications"))));
        String status=!known?"identity_unresolved":!catalog.isEmpty()&&opened==catalog.size()?"molded":"not_planned".equals(row.get("moldPlanStatus"))&&!activeExecution?"not_planned":opened>0?"partially_molded":making>0?"making":coded||applied>0?"applied":"to_apply";
        row.put("wholePhoneStatus",status);row.put("productCoverage",summaries);row.put("coverageTotal",catalog.size());row.put("openedProductCount",opened);row.put("makingProductCount",making);row.put("appliedProductCount",applied);row.put("gapProductCount",summaries.stream().filter(g->"to_apply".equals(g.get("status"))).count());
        if(row.get("selectedProductLineId")!=null){status=summaries.stream().filter(g->Objects.equals(g.get("productLineId"),row.get("selectedProductLineId"))).map(g->g.get("status").toString()).findFirst().orElse("to_apply");if(!known)status="identity_unresolved";}
        row.put("moldStatus",status);row.put("applicationEvidence",coded?"手机编码：历史已申请":applied>0?"正式提交申请":"暂无提交依据");
        var reasons=new LinkedHashSet<String>();if(!known)reasons.add("尚未关联唯一标准手机，先补身份关系");
        for(var group:groups)for(var reason:records(group,"reviewReasons"))reasons.add(Objects.toString(reason.get("message"),""));
        row.put("opportunityReasons",List.copyOf(reasons));row.put("missingInformationCount",reasons.size());row.put("hasActiveExecution",activeExecution);
        row.put("opportunityViews",List.of(status));
    }
    static boolean skuEvidence(Map<String,Object> p){return Boolean.TRUE.equals(p.get("historicalSkuEvidence"));}
    private static boolean completed(Map<String,Object> m){return m.get("actualShipDate")!=null||m.get("actualMexicoArrivalDate")!=null||("confirmed".equals(m.get("pcState"))&&"confirmed".equals(m.get("tpuState")));}
    private static boolean inProduction(Map<String,Object> m){return !completed(m)&&(m.get("openedDate")!=null||"making".equals(m.get("state"))||Set.of("making","confirmed").contains(Objects.toString(m.get("pcState"),""))||Set.of("making","confirmed").contains(Objects.toString(m.get("tpuState"),"")));}
    private static boolean currentExecution(Map<String,Object> app,List<Map<String,Object>> molds){
        if(!submitted(app)||Set.of("paused","terminated").contains(Objects.toString(app.get("executionStatus"),"")))return false;
        if(Boolean.TRUE.equals(app.get("approvalRunning")))return true;
        if(Boolean.TRUE.equals(app.get("historicalAssociationOnly")))return false;
        return app.get("moldSetId")==null||molds.stream().noneMatch(m->Objects.equals(m.get("moldSetId"),app.get("moldSetId"))&&completed(m));
    }
    private static boolean currentMold(Map<String,Object> mold,List<Map<String,Object>> apps){
        if(!inProduction(mold))return false;
        var linked=apps.stream().filter(a->mold.get("moldSetId")!=null&&Objects.equals(a.get("moldSetId"),mold.get("moldSetId"))).toList();
        return linked.isEmpty()||linked.stream().anyMatch(a->submitted(a)&&!Set.of("paused","terminated").contains(Objects.toString(a.get("executionStatus"),"")));
    }
    private static boolean submitted(Map<String,Object> a){return !Set.of("draft","terminated","refused","rejected","withdrawn","paused").contains(Objects.toString(a.getOrDefault("coverageState",a.get("businessStatus")),""))&&!"draft".equals(a.get("status"))&&a.get("instanceId")!=null;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> records(Map<String,Object> m,String key){return (List<Map<String,Object>>)m.getOrDefault(key,List.of());}
    static boolean matches(Map<String,Object> row,String view){
        if(view==null||"all".equals(view))return true;
        if("needs_confirmation".equals(view))return ((Number)row.getOrDefault("missingInformationCount",0)).intValue()>0;
        if("opportunities".equals(view))return Set.of("to_apply","partially_molded").contains(row.get("moldStatus"));
        String normalized="applying".equals(view)?"applied":view;
        return normalized.equals(row.get("moldStatus"));
    }
}

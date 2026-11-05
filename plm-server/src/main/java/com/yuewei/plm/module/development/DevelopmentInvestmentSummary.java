package com.yuewei.plm.module.development;

import java.math.BigDecimal;
import java.util.*;
import static com.yuewei.plm.module.development.DevelopmentFinanceMath.*;

/** Project investment is separate from unit-cost amortisation and financial approval amounts. */
final class DevelopmentInvestmentSummary {
    static final List<String> CATEGORIES=List.of("mold","transport","customs","design","sample","other");
    private DevelopmentInvestmentSummary() {}
    static Map<String,Object> budgets(List<Map<String,Object>> records) {
        var rows=new ArrayList<Map<String,Object>>();var references=new ArrayList<Map<String,Object>>();
        for(var r:records) {
            if("mold_formula".equals(r.get("calculationMode"))) {
                boolean overlap=r.get("moldSetId")==null&&records.stream().anyMatch(other->!Objects.equals(other.get("budgetId"),r.get("budgetId"))&&Objects.equals(other.get("itemId"),r.get("itemId"))&&other.get("moldSetId")!=null&&Objects.equals(other.get("currencyCode"),r.get("currencyCode"))&&Objects.equals(other.get("costScopeKey"),r.get("costScopeKey")));
                if(overlap){var ref=line(r,"budget","unclassified",null);ref.put("note","档案总预算与具体套预算范围重叠，请核对采用范围");references.add(ref);continue;}
                rows.add(line(r,"budget","mold",decimal(r.get("moldCost"))));
                rows.add(line(r,"budget","transport",decimal(r.get("freightTotal"))));
                rows.add(line(r,"budget","customs",decimal(r.get("customsCost"))));
            } else {
                String category=Objects.toString(r.get("costCategory"),"");
                var row=line(r,"budget",CATEGORIES.contains(category)?category:"unclassified",decimal(r.get("budgetAmount")));
                if("project_total".equals(category)||"platform".equals(r.get("budgetKind"))&&category.isBlank()) {
                    row.put("category","project_total");row.put("note","项目总预算仅作参考，不与分项重复相加");references.add(row);
                }else if("platform".equals(r.get("budgetKind"))&&List.of("mold","transport","customs").contains(category)&&records.stream().anyMatch(other->"mold_formula".equals(other.get("calculationMode"))&&Objects.equals(other.get("currencyCode"),r.get("currencyCode"))&&Objects.equals(other.get("costScopeKey"),r.get("costScopeKey")))) {row.put("category","unclassified");row.put("note","项目汇总与具体模具分项范围待核对，未重复加总");references.add(row);}else rows.add(row);
            }
        }
        return result(rows,references);
    }
    static Map<String,Object> expenses(List<Map<String,Object>> records) {
        var rows=new ArrayList<Map<String,Object>>();var references=new ArrayList<Map<String,Object>>();
        for(var r:records) {
            String category=Objects.toString(r.get("expenseCategory"),"other");BigDecimal actual=null;
            if(r.get("actualReplacedById")!=null){var ref=line(r,"expense",category,null);ref.put("note","已更正为 E"+r.get("actualReplacedById")+"，原记录不重复计入");references.add(ref);continue;}
            if("erp".equals(r.get("sourceSystem"))) actual=decimal(r.get("erpPostedAmount"));
            else if("confirmed".equals(r.get("actualStatus"))) actual=decimal(r.get("actualAmount")).subtract(decimal(r.getOrDefault("actualCoveredTotal",BigDecimal.ZERO)));
            boolean pending=r.get("actualAmount")!=null&&"confirmed".equals(r.get("actualStatus"))&&records.stream().anyMatch(other->"erp".equals(other.get("sourceSystem"))&&other.get("actualManualExpenseId")==null&&!"independent".equals(other.get("actualReconciliation"))&&Objects.equals(other.get("currencyCode"),r.get("currencyCode"))&&Objects.equals(other.get("costScopeKey"),r.get("costScopeKey"))&&Objects.equals(other.get("expenseCategory"),r.get("expenseCategory"))&&(r.get("budgetId")!=null&&Objects.equals(r.get("budgetId"),other.get("budgetId"))||List.of("productId","productLineId","itemId","moldSetId").stream().allMatch(k->Objects.equals(r.get(k),other.get(k)))));
            if(pending)actual=null;
            var row=line(r,"expense",category,actual);
            if(pending)row.put("note","存在同核算对象的未核对ERP费用，手工部分暂不重复计入；请核销或明确为独立费用");
            row.put("actualStatus",r.get("actualStatus"));row.put("actualDate",r.get("actualDate"));row.put("actualBasis",r.get("actualBasis"));
            if(r.get("actualCorrectionOfId")!=null&&!"confirmed".equals(r.get("actualStatus"))&&records.stream().anyMatch(old->Objects.equals(old.get("expenseId"),r.get("actualCorrectionOfId"))&&"confirmed".equals(old.get("actualStatus"))&&old.get("actualReplacedById")==null))row.put("aggregateExcluded",true);
            row.put("sourceSystem",r.get("sourceSystem"));row.put("actualAmount",r.get("actualAmount"));row.put("actualCoveredTotal",r.get("actualCoveredTotal"));
            row.put("actualManualExpenseId",r.get("actualManualExpenseId"));row.put("actualCoveredAmount",r.get("actualCoveredAmount"));
            if("promotion".equals(category)){row.put("note","推广费用不计入本页研发投入");references.add(row);}else rows.add(row);
        }
        return result(rows,references);
    }
    private static Map<String,Object> line(Map<String,Object> source,String entity,String category,BigDecimal amount) {
        var row=new LinkedHashMap<String,Object>();row.put("entity",entity);row.put("id",source.get(entity.equals("budget")?"budgetId":"expenseId"));
        row.put("category",category);row.put("currency",source.get("currencyCode"));row.put("amount",amount);
        row.put("name",source.get(entity.equals("budget")?"budgetName":"description"));
        var values=new LinkedHashMap<String,Object>();for(String key:List.of("costScopeKey","budgetId","expenseId","budgetName","description","currencyCode","costCategory","calculationMode","budgetAmount","moldCost","freightAmount","moldWeight","transportRate","fuelRate","customsCost","versionNo","productId","productLineId","itemId","moldSetId","expenseCategory","sourceSystem","actualAmount","actualDate","actualBasis","actualStatus","actualCorrectionOfId","actualReplacedById"))values.put(key,source.get(key));row.put("sourceValues",values);
        for(String key:List.of("costScopeKey","budgetId","budgetKind","calculationMode","actualCorrectionOfId","actualReplacedById","itemId","moldSetId","styleName","compatibleModels","setNo","setName","versionNo"))row.put(key,source.get(key));
        return row;
    }
    private static Map<String,Object> result(List<Map<String,Object>> rows,List<Map<String,Object>> references) {
        var currencies=new TreeSet<String>();rows.forEach(r->currencies.add(Objects.toString(r.get("currency"),"")));references.forEach(r->currencies.add(Objects.toString(r.get("currency"),"")));
        var groups=new ArrayList<Map<String,Object>>();
        for(String currency:currencies) {
            var items=rows.stream().filter(r->currency.equals(r.get("currency"))&&!Boolean.TRUE.equals(r.get("aggregateExcluded"))).toList();
            BigDecimal known=BigDecimal.ZERO;int missing=0;var categories=new ArrayList<Map<String,Object>>();
            for(String category:CATEGORIES) {
                var selected=items.stream().filter(r->category.equals(r.get("category"))).toList();BigDecimal value=BigDecimal.ZERO;int pending=0,knownCount=0;
                for(var r:selected)if(r.get("amount")==null)pending++;else {value=value.add(decimal(r.get("amount")));knownCount++;}
                if(selected.isEmpty())pending=1;
                var part=new LinkedHashMap<String,Object>();part.put("category",category);part.put("knownTotal",knownCount==0?null:value);part.put("missingCount",pending);part.put("count",selected.size());categories.add(part);
                known=known.add(value);missing+=pending;
            }
            missing+=items.stream().filter(r->"unclassified".equals(r.get("category"))).count();
            missing+=references.stream().filter(r->currency.equals(r.get("currency"))&&"unclassified".equals(r.get("category"))).count();
            boolean hasKnown=items.stream().anyMatch(r->CATEGORIES.contains(r.get("category"))&&r.get("amount")!=null);
            var group=new LinkedHashMap<String,Object>();group.put("currency",currency);group.put("knownTotal",hasKnown?known:null);group.put("missingCount",missing);group.put("complete",missing==0);group.put("categories",categories);groups.add(group);
        }
        return Map.of("rows",rows,"references",references,"currencyGroups",groups);
    }
}

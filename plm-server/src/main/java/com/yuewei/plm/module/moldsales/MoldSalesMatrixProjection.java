package com.yuewei.plm.module.moldsales;

import com.yuewei.plm.module.moldreference.PhoneModelCoverageService;
import java.math.BigDecimal;
import java.util.*;

/** The matrix repositions existing groups. A shared quantity is placed in exactly one cell. */
final class MoldSalesMatrixProjection {
    private MoldSalesMatrixProjection(){}
    static Set<Long> ids(Object value){var ids=new TreeSet<Long>();if(value instanceof Collection<?> values)for(var v:values)if(v instanceof Number n)ids.add(n.longValue());return ids;}
    private static long id(Object value){return value instanceof Number n?n.longValue():0;}
    private static BigDecimal quantity(Object v){return v==null?BigDecimal.ZERO:new BigDecimal(v.toString());}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> records(Object v){return v==null?List.of():(List<Map<String,Object>>)v;}
    static String stateLabel(String state){return switch(state){
        case "reusable"->"可复用";case "confirmed"->"已有实物";case "making"->"制作中";case "planned"->"待开工";case "partial"->"部分适配";
        case "applying"->"申请中";case "draft"->"申请草稿";case "catalogued"->"已建产品资料";case "historical"->"历史档案·适配待核对";
        case "needs_review"->"待核对";case "paused"->"已暂停";case "terminated"->"已终止";default->"—";};}
    static boolean matches(Map<String,Object> g,String keyword,String filter){
        String search=List.of("styleName","productName","styleCode","modelNames","canonicalModelNames","phoneCodes","canonicalPhoneCodes","brandNames").stream().map(k->Objects.toString(g.get(k),"")).reduce("",(a,b)->a+" "+b);
        if(!keyword.isBlank()&&!search.toLowerCase(Locale.ROOT).contains(keyword.strip().toLowerCase(Locale.ROOT)))return false;
        var production=g.get("production") instanceof Map<?,?> p?p:Map.of();
        return switch(filter){
            case "withSales"->quantity(g.get("salesQuantity")).signum()>0;
            case "missingCost"->!Boolean.TRUE.equals(g.get("hasCostBasis"))&&!Boolean.TRUE.equals(g.get("hasActualCost"));
            case "unmapped"->Boolean.TRUE.equals(g.get("unmapped"));
            case "withProduction"->List.of("pcTotal","tpuTotal","finishedTotal").stream().anyMatch(k->production.get(k)!=null&&quantity(production.get(k)).signum()>0);
            case "missingProduction"->List.of("pcTotal","tpuTotal","finishedTotal").stream().noneMatch(k->production.get(k)!=null);
            default->true;
        };
    }
    @SuppressWarnings("unchecked")
    static List<Map<String,Object>> rows(List<Map<String,Object>> groups,Map<Long,List<Map<String,Object>>> coverage,List<Map<String,Object>> phones,List<Map<String,Object>> products,List<Long> selected,Long brandId,String keyword,String filter,boolean connected){
        return rows(groups,coverage,phones,products,selected,brandId,keyword,filter,connected,null);
    }
    @SuppressWarnings("unchecked")
    static List<Map<String,Object>> rows(List<Map<String,Object>> groups,Map<Long,List<Map<String,Object>>> coverage,List<Map<String,Object>> phones,List<Map<String,Object>> products,List<Long> selected,Long brandId,String keyword,String filter,boolean connected,List<String> selectedKeys){
        var allowed=new LinkedHashMap<String,Map<String,Object>>();for(var p:products){String key=MoldSalesHistoricalProjection.productKey(p);if(selectedKeys==null?(selected==null||p.get("productLineId")!=null&&selected.contains(id(p.get("productLineId")))):selectedKeys.contains(key))allowed.put(key,p);}
        var rows=new LinkedHashMap<String,Map<String,Object>>();var seenGroups=new HashSet<String>();
        for(var g:groups){long productId=id(g.get("productLineId"));String product=MoldSalesHistoricalProjection.productKey(g),sourceGroupKey=MoldSalesHistoricalProjection.groupKey(g);if(!allowed.containsKey(product)||brandId!=null&&!ids(g.get("brandIds")).contains(brandId)||!matches(g,keyword,filter)||!seenGroups.add(sourceGroupKey))continue;
            String key=Objects.toString(g.get("rowKey"));var row=rows.computeIfAbsent(key,k->row(k,g.get("brandIds"),g.get("brandNames"),g.get("modelNames"),g.get("canonicalPhoneCodes"),g.get("phoneModelIds"),g.get("modelKind")));
            mergeNames(row,"modelNames",g.get("modelNames"));mergeNames(row,"modelNames",g.getOrDefault("canonicalModelNames",List.of()));mergeNames(row,"phoneCodes",g.get("canonicalPhoneCodes"));mergeNames(row,"phoneModelIds",g.get("phoneModelIds"));mergeBrands(row,g);
            var cell=cell(row,product,g.get("productLineId"),connected);MoldSalesRules.mergeOpeningDate(cell,g);MoldSalesRules.mergeOpeningDate(row,g);var cellGroups=records(cell.get("groups"));var sourceGroup=new LinkedHashMap<String,Object>();sourceGroup.put("groupId",g.get("groupId"));sourceGroup.put("sourceGroupKey",sourceGroupKey);sourceGroup.put("readonly",Boolean.TRUE.equals(g.get("readonly")));sourceGroup.put("modelNames",modelNames(g));sourceGroup.put("phoneCodes",g.get("phoneCodes"));sourceGroup.put("salesQuantity",g.get("salesQuantity"));if(g.containsKey("sourceBreakdown"))sourceGroup.put("sourceBreakdown",g.get("sourceBreakdown"));cellGroups.add(sourceGroup);addSources(cell,g);cell.put("historyReviewRows",id(cell.get("historyReviewRows"))+id(g.get("historyReviewRows")));
            if(g.get("groupId")!=null)((Set<Long>)cell.get("groupIds")).add(id(g.get("groupId")));((Set<String>)cell.get("sourceGroupKeys")).add(sourceGroupKey);((Set<Long>)cell.get("itemIds")).addAll(ids(g.get("itemIds")));if(connected)cell.put("salesQuantity",quantity(cell.get("salesQuantity")).add(quantity(g.get("salesQuantity"))));
            var states=(List<String>)cell.get("states");states.add("historical");var registered=new HashMap<Long,Long>();
            for(long phone:Boolean.TRUE.equals(g.get("salesScopeOnly"))?Set.<Long>of():ids(g.get("phoneModelIds")))for(var c:coverage.getOrDefault(phone,List.of()))if(id(c.get("productLineId"))==productId){
                for(var mold:records(c.get("molds")))if(ids(g.get("itemIds")).contains(id(mold.get("itemId")))){states.add(Objects.toString(mold.get("state"),"needs_review"));registered.merge(id(mold.get("itemId")),id(mold.get("registeredSets")),Math::max);}
                for(var app:records(c.get("applications")))if(ids(g.get("itemIds")).contains(id(app.get("itemId")))){states.add(Objects.toString(app.get("coverageState"),"applying"));((Set<Long>)cell.get("applicationIds")).add(id(app.get("applicationId")));}
            }
            cell.put("registeredSets",id(cell.get("registeredSets"))+registered.values().stream().mapToLong(Long::longValue).sum());
        }
        // Coverage has the same ACLs and exact ID links as market-watch. No market source is needed.
        for(var phone:phones){long phoneId=id(phone.get("phoneModelId"));if(brandId!=null&&id(phone.get("brandId"))!=brandId)continue;
            for(var cover:coverage.getOrDefault(phoneId,List.of())){long product=id(cover.get("productLineId"));if(!allowed.containsKey("product:"+product))continue;String key="phone:"+phoneId;
                var linkedGroups=groups.stream().filter(g->id(g.get("productLineId"))==product&&records(cover.get("molds")).stream().anyMatch(m->ids(g.get("itemIds")).contains(id(m.get("itemId"))))).toList();
                // Existing archive coverage is already represented by its complete shared row. Never
                // add a second standard-phone row with a zero quantity pointing at the same orders.
                if(!linkedGroups.isEmpty())continue;
                boolean represented=rows.values().stream().filter(r->ids(r.get("phoneModelIds")).contains(phoneId)).flatMap(r->records(r.get("cells")).stream()).anyMatch(c->id(c.get("productLineId"))==product&&!((Collection<?>)c.get("sourceGroupKeys")).isEmpty());if(represented)continue;
                var pseudo=new HashMap<String,Object>();pseudo.put("modelNames",List.of(phone.get("modelName")));pseudo.put("phoneCodes",List.of(Objects.toString(phone.get("phoneCode"),"")));pseudo.put("brandNames",List.of(phone.get("brandName")));pseudo.put("productName",cover.get("productLineName"));pseudo.put("unmapped",Objects.toString(phone.get("phoneCode"),"").isBlank());if(!matches(pseudo,keyword,filter))continue;
                var row=rows.computeIfAbsent(key,k->row(k,List.of(phone.get("brandId")),List.of(phone.get("brandName")),List.of(phone.get("modelName")),List.of(Objects.toString(phone.get("phoneCode"),"")),List.of(phoneId),"single"));
                var cell=cell(row,"product:"+product,product,connected);((List<String>)cell.get("states")).add(Objects.toString(cover.get("state"),"uncovered"));cell.put("registeredSets",cover.getOrDefault("registeredSets",0));
                for(var app:records(cover.get("applications")))((Set<Long>)cell.get("applicationIds")).add(id(app.get("applicationId")));
                for(var mold:records(cover.get("molds"))){if(id(mold.get("itemId"))>0)((Set<Long>)cell.get("itemIds")).add(id(mold.get("itemId")));var opening=new LinkedHashMap<String,Object>();MoldSalesRules.openingDate(opening,MoldSalesRules.date(mold.get("openedDate")),null);MoldSalesRules.mergeOpeningDate(cell,opening);MoldSalesRules.mergeOpeningDate(row,opening);}

            }
        }
        var result=new ArrayList<Map<String,Object>>();
        for(var row:rows.values()){
            for(var product:allowed.entrySet())cell(row,product.getKey(),product.getValue().get("productLineId"),connected);
            BigDecimal total=BigDecimal.ZERO;for(var cell:records(row.get("cells"))){var states=(List<String>)cell.remove("states");if(states.isEmpty()&&((Collection<?>)cell.get("sourceGroupKeys")).isEmpty()&&ids(cell.get("applicationIds")).isEmpty())cell.put("salesQuantity",null);String state=PhoneModelCoverageService.primaryState(states);if(states.contains("historical")&&(state.equals("uncovered")||state.equals("needs_review")))state="historical";cell.put("state",state);cell.put("stateLabel",stateLabel(state));if(connected)total=total.add(quantity(cell.get("salesQuantity")));}
            row.put("totalSalesQuantity",connected?total:null);for(var cell:records(row.get("cells")))addSources(row,cell);row.put("historyReviewRows",records(row.get("cells")).stream().mapToLong(c->id(c.get("historyReviewRows"))).sum());result.add(row);
        }
        MoldSalesRules.sortRows(result,"quantityDesc",false);return result;
    }
    static List<Map<String,Object>> productTotals(List<Map<String,Object>> rows,boolean connected){
        var totals=new LinkedHashMap<String,Map<String,Object>>();
        for(var row:rows)for(var cell:records(row.get("cells"))){String key=MoldSalesHistoricalProjection.productKey(cell);var total=totals.computeIfAbsent(key,k->{var t=new LinkedHashMap<String,Object>();t.put("productKey",k);t.put("productLineId",cell.get("productLineId"));t.put("salesQuantity",null);return t;});if(cell.get("salesQuantity")!=null)total.put("salesQuantity",quantity(total.get("salesQuantity")).add(quantity(cell.get("salesQuantity"))));addSources(total,cell);}
        if(!connected)totals.values().forEach(t->t.put("salesQuantity",null));return new ArrayList<>(totals.values());
    }
    private static void addSources(Map<String,Object> target,Map<String,Object> source){if(!(source.get("sourceBreakdown") instanceof Map<?,?> added))return;var current=target.get("sourceBreakdown") instanceof Map<?,?> b?b:Map.of();target.put("sourceBreakdown",Map.of("eccount",quantity(current.get("eccount")).add(quantity(added.get("eccount"))),"crm",quantity(current.get("crm")).add(quantity(added.get("crm")))));}
    private static Collection<Object> modelNames(Map<String,Object> group){var names=new LinkedHashSet<Object>();if(group.get("modelNames") instanceof Collection<?> values)names.addAll(values);if(group.get("canonicalModelNames") instanceof Collection<?> values)names.addAll(values);return names;}
    private static Map<String,Object> row(String key,Object brands,Object brandNames,Object names,Object codes,Object phones,Object kind){var row=new LinkedHashMap<String,Object>();row.put("rowKey",key);row.put("brandIds",new ArrayList<>((Collection<?>)brands));row.put("brandNames",new ArrayList<>((Collection<?>)brandNames));row.put("modelNames",new ArrayList<>((Collection<?>)names));row.put("phoneCodes",new ArrayList<>((Collection<?>)codes));row.put("phoneModelIds",new ArrayList<>((Collection<?>)phones));row.put("modelKind",kind);row.put("cells",new ArrayList<Map<String,Object>>());return row;}
    @SuppressWarnings("unchecked") private static void mergeNames(Map<String,Object> row,String key,Object added){var values=(Collection<Object>)row.get(key);for(var v:(Collection<?>)added)if(!values.contains(v))values.add(v);}
    @SuppressWarnings("unchecked") private static void mergeBrands(Map<String,Object> row,Map<String,Object> g){var ids=new ArrayList<>((Collection<Long>)g.get("brandIds"));var names=new ArrayList<>((Collection<String>)g.get("brandNames"));var existing=(List<Long>)row.get("brandIds");var labels=(List<String>)row.get("brandNames");for(int i=0;i<ids.size();i++)if(!existing.contains(ids.get(i))){existing.add(ids.get(i));labels.add(names.get(i));}}
    private static Map<String,Object> cell(Map<String,Object> row,String productKey,Object productId,boolean connected){var cells=records(row.get("cells"));for(var c:cells)if(productKey.equals(c.get("productKey")))return c;var cell=new LinkedHashMap<String,Object>();cell.put("productKey",productKey);cell.put("productLineId",productId);cell.put("salesQuantity",connected?BigDecimal.ZERO:null);cell.put("groupIds",new TreeSet<Long>());cell.put("sourceGroupKeys",new TreeSet<String>());cell.put("itemIds",new TreeSet<Long>());cell.put("applicationIds",new TreeSet<Long>());cell.put("registeredSets",0L);cell.put("groups",new ArrayList<Map<String,Object>>());cell.put("states",new ArrayList<String>());cells.add(cell);return cell;}
}

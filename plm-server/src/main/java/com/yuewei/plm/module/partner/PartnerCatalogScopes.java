package com.yuewei.plm.module.partner;

import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Current supply is a read projection. Selection and receipt history are never refreshed in place. */
final class PartnerCatalogScopes {
 private final PartnerStore store;
 PartnerCatalogScopes(PartnerStore store){this.store=store;}
 static boolean follows(Map<String,Object> row){var origin=map(row.get("catalogSourceSnapshot"));return number(row.getOrDefault("catalogStyleId",origin.get("catalogStyleId")))>0&&Set.of("quote","selection").contains(text(origin.get("sourceKind")))&&number(origin.get("recordId"))>0&&PartnerSelectionService.validRowId(text(origin.get("rowId")));}
 void project(List<Map<String,Object>> rows,PartnerCatalogPhoneResolver.ResolutionScope resolver){
  project(rows,resolver,false);
 }
 void project(List<Map<String,Object>> rows,PartnerCatalogPhoneResolver.ResolutionScope resolver,boolean lock){
  var styles=rows.stream().filter(PartnerCatalogScopes::follows).map(r->number(r.get("catalogStyleId"))).distinct().toList();if(styles.isEmpty())return;
  if(lock)lockSources(rows);
  var sources=sources(styles);
  for(var row:rows){if(!follows(row))continue;var current=new LinkedHashMap<>(map(row.get("currentCatalog")));current.put("phoneModelIds",List.of());current.put("phones",List.of());current.put("sourceScopeToken","");current.put("phoneScopeStatus","unavailable");current.put("phoneScopeComplete",false);current.put("phoneScopeMessage","目录来源已删除、不可见或关联失效，请核对原来源");
   var origin=map(row.get("catalogSourceSnapshot"));var exact=sources.stream().filter(s->number(s.get("style_id"))==number(row.get("catalogStyleId"))&&text(s.get("source_kind")).equals(text(origin.get("sourceKind")))&&number(s.get("record_id"))==number(origin.get("recordId"))&&text(s.get("row_id")).equals(text(origin.get("rowId")))).toList();
   if(exact.size()==1){var source=exact.get(0);var data=decode(store.json,source.get("data"));String supplier=text(row.get("supplierCode"));
    if(!supplier.equals(text(source.get("supplier_code")))||!supplier.equals(text(source.get("style_supplier"))))current.put("phoneScopeMessage","目录款式供应商关联冲突，请核对原来源");
    else if(Boolean.TRUE.equals(data.get("discarded"))||!"active".equals(text(source.get("supplier_status"))))current.put("phoneScopeMessage","供应商或目录款式已停用，不能登记新的收样");
    else if(resolver==null)current.put("phoneScopeMessage","当前供方型号资料未就绪，请重新读取");
    else{var resolved=resolver.resolve(data);var ids=PartnerSelectionService.scopeIds(resolved.get("phoneModelIds"));var denied=PartnerSelectionService.scopeIds(resolved.get("unavailablePhoneModelIds"));var available=ids.stream().filter(id->!denied.contains(id)&&resolver.availablePhone(id)).toList();var allIssues=((List<?>)resolved.getOrDefault("phoneMatchIssues",List.of())).stream().map(PartnerValues::text).toList();var issues=allIssues.stream().filter(issue->!issue.contains("已停用")).toList();boolean complete=allIssues.isEmpty()&&number(PartnerCatalogModelSheet.summary(PartnerCatalogModelSheet.project(data.get("supplierModelEntries"),resolver)).get("unresolvedEntryCount"))==0;
     if(!"supplier".equals(text(resolved.get("phoneScopeMode")))||available.isEmpty()&&!issues.isEmpty()){current.put("phoneScopeStatus","unresolved");current.put("phoneScopeMessage","供方型号尚未明确或未完整关联，请在供应商产品目录核对型号资料");}
     else if(available.isEmpty())current.put("phoneScopeMessage","本款当前没有可供且启用的手机型号");
     else{current.put("phoneScopeStatus","ready");current.put("phoneScopeComplete",complete);current.put("phoneScopeMessage",complete?"":"部分供方型号待核对，当前仅显示已明确可供型号");current.put("phoneModelIds",available);current.put("phones",maps(resolved.get("phones")).stream().filter(p->available.contains(number(p.get("phoneModelId")))).toList());current.put("sourceScopeToken",PartnerQualityRules.digest(store.json,Map.of("sourceKind",source.get("source_kind"),"recordId",source.get("record_id"),"rowId",source.get("row_id"),"recordVersion",source.get("record_version"),"rowVersion",source.get("row_version"),"styleId",source.get("style_id"),"complete",complete,"phones",current.get("phones"),"phoneModelIds",available,"unavailablePairs",maps(resolved.get("pairings")).stream().filter(p->"unavailable".equals(text(p.get("state")))).toList())));current.put("sourceRecordVersion",source.get("record_version"));current.put("sourceRowVersion",source.get("row_version"));}
    }
   }else if(exact.size()>1)current.put("phoneScopeMessage","目录当前来源存在冲突，请核对原来源");
   row.put("currentCatalog",current);
  }
 }
 private void lockSources(List<Map<String,Object>> rows){
  var origins=rows.stream().filter(PartnerCatalogScopes::follows).map(r->map(r.get("catalogSourceSnapshot"))).distinct().sorted(Comparator.comparingLong((Map<String,Object> o)->number(o.get("recordId"))).thenComparing(o->text(o.get("rowId")))).toList();
  // Writers take source head then source row before refreshing linked draft selections.
  // Never wait while holding the receipt selection: a writer may already await that selection.
  for(var origin:origins){String kind=text(origin.get("sourceKind")).equals("quote")?"supplier_quote":"selection";store.jdbc.queryForList("select r.record_id from plm_partner_record r where r.record_id=? and r.kind=? and r.deleted_flag=0 and ("+store.access.visibleSqlClause("partner","r.record_id")+") for share nowait",origin.get("recordId"),kind);}
  for(var origin:origins){boolean quote=text(origin.get("sourceKind")).equals("quote");String table=quote?"plm_partner_quote_line":"plm_partner_selection_row",key=quote?"line_id":"row_id";store.jdbc.queryForList("select "+key+" from "+table+" where record_id=? and "+key+"=?::uuid for share nowait",origin.get("recordId"),origin.get("rowId"));}
 }
 private List<Map<String,Object>> sources(List<Long> styles){return store.jdbc.queryForList("select link.style_id,style.supplier_code as style_supplier,p.status as supplier_status,current_source.source_kind,current_source.record_id,current_source.row_id,current_source.record_version,current_source.row_version,current_source.supplier_code,current_source.data::text from ("+new PartnerCatalogStyles(store).sourceSql(true)+") current_source join plm_partner_catalog_style_source link on link.source_kind=current_source.source_kind and link.record_id=current_source.record_id and link.row_id::text=current_source.row_id join plm_partner_catalog_style style on style.style_id=link.style_id join plm_supplier_profile p on p.supplier_code=style.supplier_code and p.deleted_flag=0 where link.style_id in ("+marks(styles.size())+")",styles.toArray());}
 private static String marks(int size){return String.join(",",Collections.nCopies(size,"?"));}
 static void requireCurrent(Map<String,Object> scope){require("ready".equals(text(scope.get("phoneScopeStatus"))),text(scope.get("phoneScopeMessage")));}
 static void requireToken(Map<String,Object> snapshot,Map<String,Object> input){if(snapshot.containsKey("sourceScopeToken"))require(!text(input.get("sourceScopeToken")).isEmpty()&&text(input.get("sourceScopeToken")).equals(text(snapshot.get("sourceScopeToken"))),"供方型号范围已变化或尚未预览，请保留输入并重新核对收样");}
}

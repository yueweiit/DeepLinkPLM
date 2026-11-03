package com.yuewei.plm.module.partner;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Stable supplier style identity, independent from source documents and all downstream facts. */
@Component @RequiredArgsConstructor
public class PartnerCatalogStyles {
 private final PartnerStore store;
 static Map<String,Object> currentData(Map<String,Object> source){var result=new LinkedHashMap<>(source);result.putAll(map(source.get("catalogCurrent")));return result;}
 String sourceSql(boolean includeDiscarded){
  String scope=store.access.visibleSqlClause("partner","r.record_id");
  String selectionActive=includeDiscarded?"":" and coalesce(s.data->'catalogCurrent'->>'discarded',s.data->>'discarded','false')<>'true'";
  String quoteActive=includeDiscarded?"":" and coalesce(q.data->'catalogCurrent'->>'discarded',q.data->>'discarded','false')<>'true'";
  return """
   select 'selection' as source_kind,r.record_id,r.code as source_code,r.name as source_name,
          r.version as record_version,r.status as source_status,r.updated_at,s.row_id::text as row_id,s.row_version,
          s.sort_order,s.data||coalesce(s.data->'catalogCurrent','{}'::jsonb) as data,s.confirmed,s.data->>'supplierCode' as supplier_code,
          case when coalesce(r.data->>'catalogSource','false')='true' then 'catalog_source' else 'my_selection' end as source_purpose
     from plm_partner_selection_row s join plm_partner_record r on r.record_id=s.record_id
    where r.kind='selection' and r.deleted_flag=0
      and (coalesce(r.data->>'purpose','')<>'my_selection' or r.data->>'catalogSource'='true')
      and not exists(select 1 from plm_partner_event e where e.record_id=r.record_id
          and e.action='catalog_add' and (e.data->'createdRowIds') @> jsonb_build_array(s.row_id::text))
      and (%s) %s
   union all
   select 'quote',r.record_id,r.code,r.name,r.version,r.status,r.updated_at,q.line_id::text,q.row_version,
          q.sort_order,q.data||coalesce(q.data->'catalogCurrent','{}'::jsonb),false,coalesce(nullif(q.data->>'supplierCode',''),r.supplier_code),'catalog_source'
     from plm_partner_quote_line q join plm_partner_record r on r.record_id=q.record_id
    where r.kind='supplier_quote' and r.deleted_flag=0
      and (coalesce(q.data->>'supplierCode','')='' or q.data->>'supplierCode'=r.supplier_code)
      and (%s) %s
   """.formatted(scope,selectionActive,scope,quoteActive);
 }
 String currentNameSearch(String styleExpression){
  return "exists(select 1 from ("+sourceSql(true)+") current_source join plm_partner_catalog_style_source current_link on current_link.source_kind=current_source.source_kind and current_link.record_id=current_source.record_id and current_link.row_id::text=current_source.row_id where current_link.style_id::text="+styleExpression+" and current_source.data->>'name' ilike ?)";
 }
 /** Current descriptive data is a read projection; receipt identities and confirmed snapshots stay immutable. */
 public void projectCurrent(List<Map<String,Object>> rows){
  var ids=rows.stream().map(r->number(r.get("catalogStyleId"))).filter(id->id>0).distinct().toList();if(ids.isEmpty())return;
  var sources=store.jdbc.queryForList("select link.style_id,current_source.record_id,current_source.row_id,current_source.source_kind,current_source.data::text from ("+sourceSql(true)+") current_source join plm_partner_catalog_style_source link on link.source_kind=current_source.source_kind and link.record_id=current_source.record_id and link.row_id::text=current_source.row_id where link.style_id in ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",ids.toArray());
  var byStyle=new HashMap<Long,List<Map<String,Object>>>();for(var source:sources){source.put("values",decode(store.json,source.remove("data")));byStyle.computeIfAbsent(number(source.get("style_id")),k->new ArrayList<>()).add(source);}
  boolean costs=new PartnerOperationsAccess(store.access).costs();
  for(var row:rows){var candidates=byStyle.getOrDefault(number(row.get("catalogStyleId")),List.of());if(candidates.isEmpty())continue;
   var origin=map(row.get("catalogSourceSnapshot"));if(origin.isEmpty())origin=map(row.get("sourceSnapshot"));
   if(origin.isEmpty()){var batches=maps(row.get("batches"));if(!batches.isEmpty())origin=map(batches.get(0).get("sourceSnapshot"));}
   long record=number(origin.getOrDefault("recordId",origin.get("sourceRecordId")));String sourceRow=text(origin.getOrDefault("rowId",origin.get("sourceRowId")));
   var exact=candidates.stream().filter(s->number(s.get("record_id"))==record&&text(s.get("row_id")).equals(sourceRow)).toList();
   var selected=exact.isEmpty()?candidates:exact;var current=new LinkedHashMap<String,Object>();
   var fields=new ArrayList<>(List.of("name","color","material","supplierItemCode","specification","modelText","discarded"));if(costs)fields.addAll(List.of("unitPrice","currency","priceNote","priceUnit"));
   for(String field:fields){var values=selected.stream().map(s->map(s.get("values")).get(field)).distinct().toList();if(values.size()==1)current.put(field,values.get(0));}
   current.put("available",selected.stream().anyMatch(s->!Boolean.TRUE.equals(map(s.get("values")).get("discarded"))));row.put("currentCatalog",current);
   String name=text(current.get("name"));if(!name.isEmpty()){row.put("recordedName",row.getOrDefault("name",""));row.put("name",name);if(row.containsKey("sampleCode"))row.put("productName",name);}
  }
 }
 public Map<String,Object> identity(String kind,long record,String row){
  var key=new SourceKey(kind,record,UUID.fromString(row));return identities(List.of(key)).getOrDefault(key,Map.of());
 }
 private record SourceKey(String kind,long record,UUID row) {}
 private Map<SourceKey,Map<String,Object>> identities(Collection<SourceKey> keys){
  if(keys.isEmpty())return Map.of();
  var requested=keys.stream().distinct().map(k->Map.of("source_kind",k.kind(),"record_id",k.record(),"row_id",k.row())).toList();
  var found=store.jdbc.queryForList("select s.style_id as \"catalogStyleId\",s.code as \"catalogStyleCode\",s.supplier_code as \"supplierCode\",coalesce(p.short_code,'') as \"supplierShortCode\",x.fields::text,x.source_kind,x.record_id,x.row_id as source_row_id from jsonb_to_recordset(?::jsonb) requested(source_kind text,record_id bigint,row_id uuid) join plm_partner_catalog_style_source x on x.source_kind=requested.source_kind and x.record_id=requested.record_id and x.row_id=requested.row_id join plm_partner_catalog_style s on s.style_id=x.style_id join plm_supplier_profile p on p.supplier_code=s.supplier_code",encode(store.json,requested));
  var result=new HashMap<SourceKey,Map<String,Object>>();for(var row:found){var value=new LinkedHashMap<>(row);var key=new SourceKey(text(value.remove("source_kind")),number(value.remove("record_id")),UUID.fromString(text(value.remove("source_row_id"))));value.putAll(decode(store.json,value.remove("fields")));String code=text(value.get("catalogStyleCode")),shortCode=text(value.remove("supplierShortCode"));value.put("catalogStyleLabel",shortCode.isEmpty()?code:shortCode+"-"+code);result.put(key,value);}return result;
 }
 public Map<String,Object> ensure(String kind,long record,Map<String,Object> row,long existingStyle,String reason,String request){
  require(Set.of("quote","selection").contains(kind)&&PartnerSelectionService.validRowId(text(row.get("rowId"))),"目录来源身份无效");
  String supplier=required(row.get("supplierCode"),"款式供应商");store.access.requirePartner(record);
  String key=kind+":"+record+":"+text(row.get("rowId"));store.jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))","catalog-style:"+key);
  var current=identity(kind,record,text(row.get("rowId")));if(!current.isEmpty()){require(existingStyle==0||number(current.get("catalogStyleId"))==existingStyle,"已有来源款式身份不能改绑");require(supplier.equals(text(current.get("supplierCode"))),"来源款式供应商不一致");return current;}
  long style=existingStyle;
  if(style>0){var actual=store.jdbc.queryForList("select s.supplier_code from plm_partner_catalog_style s where s.style_id=? and exists(select 1 from plm_partner_catalog_style_source x join plm_partner_record r on r.record_id=x.record_id where x.style_id=s.style_id and r.deleted_flag=0 and ("+store.access.visibleSqlClause("partner","r.record_id")+"))",style);require(actual.size()==1&&supplier.equals(text(actual.get(0).get("supplier_code"))),"目录款式不存在、无权访问或属于其他供应商");}
  else style=store.jdbc.queryForObject("insert into plm_partner_catalog_style(supplier_code,created_by) values(?,?) returning style_id",Long.class,supplier,store.access.actor().username());
  var fields=PartnerCatalogFields.normalize(row,Map.of(),true);
  String table=kind.equals("quote")?"plm_partner_quote_line":"plm_partner_selection_row",column=kind.equals("quote")?"line_id":"row_id";
  var head=store.load(record,kind.equals("quote")?"supplier_quote":"selection",false);long rowVersion=store.jdbc.queryForObject("select row_version from "+table+" where record_id=? and "+column+"=?::uuid",Long.class,record,row.get("rowId"));
  store.jdbc.update("insert into plm_partner_catalog_style_source(source_kind,record_id,row_id,style_id,fields,source_version,source_row_version,created_by) values(?,?,?::uuid,?,?::jsonb,?,?,?)",kind,record,row.get("rowId"),style,encode(store.json,fields),number(head.get("version")),rowVersion,store.access.actor().username());
  String eventKey="cp-"+UUID.nameUUIDFromBytes((request+":"+key).getBytes(StandardCharsets.UTF_8));
  store.event(record,"catalog_style_identity",Map.of("sourceKind",kind,"sourceRowId",row.get("rowId"),"catalogStyleId",style,"newStyle",existingStyle==0,"reason",required(reason,"款式关联原因")),eventKey);
  return identity(kind,record,text(row.get("rowId")));
 }
 public Map<String,Object> enrich(String kind,long record,Map<String,Object> source){
  return withIdentity(source,identity(kind,record,text(source.get("rowId"))));
 }
 public List<Map<String,Object>> enrichAll(String kind,long record,List<Map<String,Object>> sources){
  return enrichSources(sources,row->new SourceKey(kind,record,UUID.fromString(text(row.get("rowId")))));
 }
 public List<Map<String,Object>> enrichAll(List<Map<String,Object>> sources){
  return enrichSources(sources,row->new SourceKey(text(row.get("sourceKind")),number(row.get("recordId")),UUID.fromString(text(row.get("rowId")))));
 }
 private List<Map<String,Object>> enrichSources(List<Map<String,Object>> sources,Function<Map<String,Object>,SourceKey> key){
  var identities=identities(sources.stream().map(key).toList());return sources.stream().map(row->withIdentity(row,identities.getOrDefault(key.apply(row),Map.of()))).toList();
 }
 private Map<String,Object> withIdentity(Map<String,Object> source,Map<String,Object> id){
  var result=PartnerCatalogFields.project(source);
  if(!id.isEmpty()){for(String key:List.of("catalogStyleId","catalogStyleCode","catalogStyleLabel"))result.put(key,id.get(key));
   // Historical normalization lives on the identity link; source JSON/row version remains intact.
   for(String key:PartnerCatalogFields.FIELDS)if(!key.equals("currencySource")&&!source.containsKey(key))if(id.containsKey(key))result.put(key,id.get(key));
   if(text(source.get("currency")).isEmpty()&&id.containsKey("currency")){result.put("currency",id.get("currency"));result.put("currencySource",id.get("currencySource"));}
  }return result;
 }
 public void inherit(long selection,String row,Map<String,Object> origin,String request){
  long style=number(origin.get("catalogStyleId"));require(style>0,"目录款式编号尚未保存，请重新预览选品");
  var target=new LinkedHashMap<>(new PartnerSelectionRows(store).find(selection,row,false));require(text(target.get("supplierCode")).equals(text(origin.get("supplierCode"))),"目录来源供应商与选样不一致");
  ensure("selection",selection,target,style,"引用来源目录款式编号，不代表已收样、质检或上架",request);
 }
 List<Map<String,Object>> samplePhotos(long selection,Object adopted){var result=new ArrayList<Map<String,Object>>();var seen=new HashSet<Long>();for(var ref:maps(adopted)){long id=number(ref.get("id"));if(!seen.add(id))continue;var images=store.jdbc.queryForList("select a.attachment_id as id,a.file_name as name,a.version_no as version,a.content_type as type,a.file_size as size from plm_partner_file f join plm_attachment a on a.attachment_id=f.attachment_id where f.record_id=? and a.attachment_id=? and f.purpose not in ('cooperation_cost','financial') and a.content_type like 'image/%' and a.deleted_flag=0 and ("+store.access.visibleSqlClause("file","a.attachment_id")+")",selection,id);if(!images.isEmpty()){store.access.requireFile(id);result.add(images.get(0));}}return result;}
 /** Single no-cost source contract for actual receipt/QC; never auto-creates PP, PS or SP. */
 @Transactional(readOnly=true) public Map<String,Object> selectedSnapshot(long selection,String rowId){return selectedSnapshot(selection,rowId,false);}
 Map<String,Object> selectedSnapshot(long selection,String rowId,boolean lock){return selectionSnapshot(selection,rowId,lock,false);}
 Map<String,Object> selectedSnapshot(long selection,String rowId,boolean lock,PartnerCatalogPhoneResolver.ResolutionScope resolver){return selectionSnapshot(selection,rowId,lock,false,resolver);}
 Map<String,Object> receiptSnapshot(long selection,String rowId,boolean lock){return selectionSnapshot(selection,rowId,lock,true);}
 Map<String,Object> receiptSnapshot(long selection,String rowId,boolean lock,PartnerCatalogPhoneResolver.ResolutionScope resolver){return selectionSnapshot(selection,rowId,lock,true,resolver);}
 private Map<String,Object> selectionSnapshot(long selection,String rowId,boolean lock,boolean allowDraft){
  return selectionSnapshot(selection,rowId,lock,allowDraft,null);
 }
 private Map<String,Object> selectionSnapshot(long selection,String rowId,boolean lock,boolean allowDraft,PartnerCatalogPhoneResolver.ResolutionScope resolver){
  store.access.requireDomesticView();store.access.requirePermission("partner:view");var head=store.load(selection,"selection",false);PartnerSelectionService.requireSelectionPurpose(head);
  require(PartnerSelectionService.validRowId(rowId),"来源选样行无效");var found=new PartnerSelectionRows(store).find(selection,rowId,lock);
  boolean draft=!PartnerSelectionService.confirmed(found);require(!found.isEmpty()&&(!draft||allowDraft)&&!Boolean.TRUE.equals(found.get("discarded")),"选样已取消、来源无效或尚未保留收样依据");if(draft)require("my_selection".equals(text(head.get("purpose")))&&Boolean.TRUE.equals(found.get("selected")),"请先从供应商目录选择本次需要的款式及型号");
  var id=identity("selection",selection,rowId);require(!id.isEmpty(),"此历史选样尚未核实目录款式关联");
  new SupplierProfileService(store).requireSupplier(required(found.get("supplierCode"),"样品供应商"));
  var frozen=draft?found:map(map(found.get("confirmationSnapshot")).get("row"));require(!frozen.isEmpty(),"已确认选样缺少原始规格快照，请核对历史依据");
  var scopeRow=new LinkedHashMap<>(found);for(String field:List.of("catalogStyleId","catalogStyleCode","catalogStyleLabel"))scopeRow.put(field,id.get(field));if(resolver!=null)require(PartnerCatalogScopes.follows(scopeRow),"目录来源待核对，请核实原款式来源后再登记收样");boolean dynamic=resolver!=null;Map<String,Object> current=Map.of();
  if(dynamic){new PartnerCatalogScopes(store).project(List.of(scopeRow),resolver,lock);current=map(scopeRow.get("currentCatalog"));PartnerCatalogScopes.requireCurrent(current);}
  var pairs=maps(frozen.get("selectedPairs"));boolean atReceipt=pairs.isEmpty()&&(draft||(number(found.get("selectionConfirmationVersion"))>=2&&"at_receipt".equals(text(found.get("receiptColorMode")))));require(!pairs.isEmpty()||atReceipt,"历史选样缺少颜色确认依据，请核对原记录，不能自动扩大范围");
  var colors=new LinkedHashMap<Long,String>();for(var pair:pairs){long color=number(pair.get("colorId"));var actual=store.jdbc.queryForList("select name from plm_supplier_color where color_id=? and supplier_code=?",color,frozen.get("supplierCode"));require(color>0&&actual.size()==1,"所选供应商颜色已失效或尚未明确");String name=text(map(pair.get("colorSnapshot")).get("name"));require(!name.isEmpty(),"本次确认缺少供应商原色快照，不能用当前颜色冒充当时依据");colors.put(color,name);}
  var result=new LinkedHashMap<String,Object>();for(String key:List.of("catalogStyleId","catalogStyleCode","catalogStyleLabel"))result.put(key,id.get(key));
  for(String key:List.of("supplierCode","name","color","material","supplierItemCode","configKey","styleKey"))result.put(key,frozen.getOrDefault(key,"") );
  result.put("sampleColors",frozen.containsKey("sampleColors")?frozen.get("sampleColors"):atReceipt?PartnerReceiptColors.explicitNames(frozen.get("color")):List.of());
  result.put("sourceColors",PartnerReceiptColors.sourceNames(frozen));
  var chosen=PartnerSelectionService.scopeIds(dynamic?current.get("phoneModelIds"):frozen.get("phoneModelIds"));if(dynamic&&!pairs.isEmpty()){var paired=pairs.stream().map(p->number(p.get("phoneModelId"))).toList();chosen=chosen.stream().filter(paired::contains).toList();}var actualChosen=chosen;result.put("phoneModelIds",chosen);result.put("phones",maps(dynamic?current.get("phones"):frozen.get("phones")).stream().filter(p->actualChosen.contains(number(p.getOrDefault("phoneModelId",p.get("id"))))).toList());require(!chosen.isEmpty()&&!maps(result.get("phones")).isEmpty(),"本款当前没有可登记的真实手机型号");
  if(dynamic)for(String key:List.of("sourceScopeToken","sourceRecordVersion","sourceRowVersion"))result.put(key,current.get(key));
  result.put("sourceKind","catalog");result.put("selectionId",selection);result.put("selectionRowId",rowId);result.put("selectionVersion",head.get("version"));result.put("selectionRowVersion",found.get("rowVersion"));result.put("configuration",frozen.getOrDefault("specification",""));
  result.put("selectedPairs",pairs.stream().map(pair->{var value=new LinkedHashMap<String,Object>(pair);String color=colors.get(number(pair.get("colorId")));value.put("colorName",color);value.put("specName",text(frozen.get("name"))+" · "+color);return value;}).toList());result.put("supplierColorIds",new ArrayList<>(colors.keySet()));
  result.put("receiptColorMode",atReceipt?"at_receipt":"selected_pairs");result.put("unavailablePairs",maps(map(frozen.get("catalogSourceSnapshot")).get("unavailablePairs")).stream().map(p->Map.<String,Object>of("phoneModelId",number(p.get("phoneModelId")),"colorId",number(p.get("colorId")))).toList());result.put("supplyPairings",maps(frozen.get("pairings")).stream().map(p->Map.<String,Object>of("phoneModelId",number(p.get("phoneModelId")),"colorId",number(p.get("colorId")),"state",text(p.get("state")))).toList());
  if(atReceipt){var choices=store.jdbc.queryForList("select color_id as id,name,code,supplier_color_code as \"supplierColorCode\",style_key as \"styleKey\",config_key as \"configKey\",version from plm_supplier_color where supplier_code=? and status='active' and (style_key='' or style_key=?) and (config_key='' or config_key=?) order by color_id"+(lock?" for share":""),frozen.get("supplierCode"),text(frozen.get("styleKey")),text(frozen.get("configKey")));result.put("receiptColors",choices);}
  result.put("refs",samplePhotos(selection,frozen.get("refs")));
  var origin=map(found.get("catalogSourceSnapshot"));result.put("sourceRecordId",origin.getOrDefault("recordId",selection));result.put("sourceRowId",origin.getOrDefault("rowId",rowId));result.put("sourceVersion",dynamic?current.get("sourceRecordVersion"):origin.getOrDefault("recordVersion",head.get("version")));return result;
 }
}

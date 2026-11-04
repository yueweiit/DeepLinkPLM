package com.yuewei.plm.module.partner;

import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Read projections of the original SP event ledger; no independent inventory state. */
final class SampleIdentityQuery {
 private static final List<String> TOTALS=List.of("onhand","available","retained","damaged","borrowed","inTransit");
 private static final List<String> POSITION_TOTALS=List.of("good","retained","damaged","onhand","available");
 private final PartnerStore store;
 private final String visible;
 SampleIdentityQuery(PartnerStore store,String visible){this.store=store;this.visible=visible;}

 Map<String,Object> page(Map<String,String> query){
  String grouping=text(query.get("groupBy"));require(grouping.isEmpty()||grouping.equals("style"),"样品分组方式无效");
  var rows=load(query);boolean grouped=grouping.equals("style");List<Map<String,Object>> content=grouped?groups(rows,SampleSiteAccess.mexico(store)):rows;
  long page=Math.max(1,number(query.getOrDefault("page","1"))),size=Math.min(100,Math.max(1,number(query.getOrDefault("size","50")))),total=content.size();
  int from=(int)Math.min(total,(page-1)*size),to=(int)Math.min(total,from+size);
  return Map.of("content",content.subList(from,to),"page",page,"size",size,"totalElements",total,"totalSamples",rows.size(),"totalPages",(total+size-1)/size);
 }

 Map<String,Object> detail(long id){
  var rows=load(Map.of("identityId",Long.toString(id),"receiptStatus","all"));require(rows.size()==1,"样品主码不存在或未获授权");return rows.get(0);
 }

 Map<String,Object> query(Map<String,Object> input){
  require(!(input.containsKey("ids")&&input.containsKey("groupKeys")),"请按样品或款式选择一种查询范围");
  require(!input.containsKey("filters")||input.get("filters") instanceof Map<?,?>,"样品筛选格式无效");
  require(!input.containsKey("details")||input.get("details") instanceof Boolean,"详情开关无效");
  var filters=new LinkedHashMap<String,String>();
  for(var entry:map(input.get("filters")).entrySet()){
   require(Set.of("domain","supplierCode","site","locationId","unlocated","keyword","receiptStatus").contains(entry.getKey()),"样品筛选字段无效");
   require(entry.getValue() instanceof String||entry.getValue() instanceof Number||entry.getValue() instanceof Boolean,"样品筛选值无效");filters.put(entry.getKey(),String.valueOf(entry.getValue()));
  }
  var ids=new LinkedHashSet<Long>();var keys=new LinkedHashSet<String>();
  if(input.containsKey("ids")){require(input.get("ids") instanceof List<?>,"样品ID格式无效");for(Object value:(List<?>)input.get("ids")){long id=number(value);require(id>0&&Long.toString(id).equals(String.valueOf(value)),"样品ID无效");ids.add(id);}filters.putIfAbsent("receiptStatus","all");}
  if(input.containsKey("groupKeys")){require(input.get("groupKeys") instanceof List<?>,"款式范围格式无效");for(Object value:(List<?>)input.get("groupKeys")){require(value instanceof String&&!text(value).isEmpty(),"款式范围无效");keys.add((String)value);}}
  if(input.containsKey("ids")&&ids.isEmpty()||input.containsKey("groupKeys")&&keys.isEmpty())return Map.of("content",List.of(),"totalElements",0);
  var rows=load(filters,ids,Boolean.TRUE.equals(input.get("details"))).stream().filter(row->keys.isEmpty()||keys.contains(text(row.get("groupKey")))).toList();
  require(ids.isEmpty()||rows.size()==ids.size(),"所选样品已变化或未获授权，请刷新后重新选择");
  return Map.of("content",rows,"totalElements",rows.size());
 }

 Map<String,Object> options(Map<String,String> query){
  var rows=load(Map.of());var suppliers=new LinkedHashMap<String,Map<String,Object>>();String keyword=text(query.get("keyword")).toLowerCase(Locale.ROOT);
  for(var row:rows){String code=text(row.get("supplierCode"));if(code.isEmpty())continue;var value=Map.<String,Object>of("supplierCode",code,"supplierName",text(row.get("supplierName")),"shortName",text(row.get("supplierShortName")));
   if(keyword.isEmpty()||value.values().stream().anyMatch(v->text(v).toLowerCase(Locale.ROOT).contains(keyword)))suppliers.putIfAbsent(code,value);
  }
  return Map.of("suppliers",suppliers.values().stream().sorted(Comparator.comparing(s->text(s.get("supplierName")))).toList());
 }

 private List<Map<String,Object>> load(Map<String,String> query){
  return load(query,Set.of(),false);
 }
 private List<Map<String,Object>> load(Map<String,String> query,Set<Long> selectedIds,boolean details){
  String site=SampleSiteAccess.read(store,query.get("site"));boolean mexico=SampleSiteAccess.mexico(store);String domain=text(query.get("domain")),supplier=text(query.get("supplierCode"));
  var args=new ArrayList<Object>();String where="r.kind='sample_identity' and r.deleted_flag=0 and coalesce(r.data->>'redirectIdentityId','')=''";
  if(!mexico)where+=" and ("+visible+")";
  String receiptStatus=text(query.getOrDefault("receiptStatus","active"));require(Set.of("active","voided","all").contains(receiptStatus),"收样登记筛选无效");
  if(!receiptStatus.equals("all")){String scope="b.kind='sample' and b.deleted_flag=0 and b.sample_identity_id=r.record_id and ("+(mexico?"true":store.access.visibleSqlClause("partner","b.record_id"))+")";String any="exists(select 1 from plm_partner_record b where "+scope+")",active="exists(select 1 from plm_partner_record b where "+scope+" and "+SampleReceiptCorrections.activeSql("b")+")";where+=" and "+(receiptStatus.equals("active")?"(not "+any+" or "+active+")":any+" and not "+active);}

  if(!domain.isEmpty()){SampleDomains.validate(domain);where+=" and r.data->>'domain'=?";args.add(domain);}
  if(!supplier.isEmpty()){where+=" and coalesce(nullif(r.supplier_code,''),r.data->>'supplierCode')=?";args.add(supplier);}
  if(!selectedIds.isEmpty()){where+=" and r.record_id in ("+marks(selectedIds.size())+")";args.addAll(selectedIds);}
  if(number(query.get("identityId"))>0){where+=" and r.record_id=?";args.add(number(query.get("identityId")));}
  var raw=store.jdbc.queryForList("select r.record_id as id,r.code,r.name,r.status,r.version,r.supplier_code,r.data::text from plm_partner_record r where "+where+" order by r.updated_at desc,r.record_id desc",args.toArray());
  if(raw.isEmpty())return List.of();
  var headers=new LinkedHashMap<Long,Map<String,Object>>();
  for(var row:raw){var h=decode(store.json,row.get("data"));for(String key:List.of("id","code","name","status","version"))h.put(key,row.get(key));if(text(h.get("supplierCode")).isEmpty())h.put("supplierCode",text(row.get("supplier_code")));h.put("sampleIdentityId",row.get("id"));h.put("sampleCode",row.get("code"));var basis=map(h.get("identityBasis"));for(String field:List.of("catalogStyleId","colorId"))if(number(h.get(field))<=0&&number(basis.get(field))>0)h.put(field,number(basis.get(field)));headers.put(number(row.get("id")),h);}
  var suppliers=store.jdbc.queryForList("select supplier_code,supplier_name,short_code from plm_supplier_profile");var supplierNames=new HashMap<String,Map<String,Object>>();for(var s:suppliers)supplierNames.put(text(s.get("supplier_code")),s);
  var recordedSupplierNames=new HashMap<Long,String>();for(var h:headers.values()){String code=text(h.get("supplierCode"));var s=supplierNames.getOrDefault(code,Map.of());String recorded=text(h.get("supplierName"));if(recorded.isEmpty())recorded=text(map(h.get("sourceSnapshot")).get("supplierName"));recordedSupplierNames.put(number(h.get("id")),recorded);String name=text(s.get("supplier_name"));if(name.isEmpty())name=recorded;h.put("supplierCode",code);h.put("supplierName",name);h.put("supplierShortName",text(s.get("short_code")));SampleCodes.project(h,s.get("short_code"));}
  if(!mexico)new PartnerCatalogStyles(store).projectCurrent(new ArrayList<>(headers.values()));
  String ids=marks(headers.size());var batchArgs=new ArrayList<Object>(headers.keySet());String batchScope=mexico?"true":store.access.visibleSqlClause("partner","b.record_id");
  var batches=store.jdbc.queryForList("select b.record_id as id,b.code,b.version,b.sample_identity_id,b.data::text,"+SampleReceiptCorrections.effectiveSql("b")+" as effective_received_qty from plm_partner_record b where b.kind='sample' and b.deleted_flag=0 and b.sample_identity_id in ("+ids+") and ("+batchScope+") order by b.record_id",batchArgs.toArray());
  var events=new HashMap<Long,List<Map<String,Object>>>();var files=new HashMap<Long,List<Map<String,Object>>>();var directions=new HashMap<Long,String>();var locations=new HashMap<Long,Map<String,Object>>();
  locations.putAll(locations(store,mexico));
  if(!batches.isEmpty()){
   var bids=batches.stream().map(b->number(b.get("id"))).toList();String in=marks(bids.size());var missing=new ArrayList<Long>();
   for(var batch:batches){var cached=map(decode(store.json,batch.get("data")).get("balances"));if(cached.get("positions") instanceof List<?> && cached.get("loans") instanceof List<?> && cached.get("transits") instanceof List<?>)batch.put("ledgerBalance",cached);else missing.add(number(batch.get("id")));}
   var eventIds=details&&!mexico?bids:missing;
   if(!eventIds.isEmpty())for(var e:store.jdbc.queryForList("select event_id as id,record_id,action,data::text from plm_partner_event where record_id in ("+marks(eventIds.size())+") order by event_id",eventIds.toArray())){e.put("data",decode(store.json,e.get("data")));events.computeIfAbsent(number(e.get("record_id")),k->new ArrayList<>()).add(e);}
   for(var batch:batches)if(!batch.containsKey("ledgerBalance"))batch.put("ledgerBalance",SampleLedger.replay(events.getOrDefault(number(batch.get("id")),List.of())).view());
   String fileScope=mexico?"pf.purpose='photo' and not exists(select 1 from plm_partner_file private_file where private_file.attachment_id=a.attachment_id and private_file.purpose in ('financial','cooperation_cost'))":"pf.purpose in ('photo','material') and ("+store.access.visibleSqlClause("file","a.attachment_id")+")";
   for(var f:store.jdbc.queryForList("select pf.record_id,a.attachment_id as id,a.file_name as name,a.content_type as type from plm_partner_file pf join plm_attachment a on a.attachment_id=pf.attachment_id where pf.record_id in ("+in+") and "+fileScope+" and a.deleted_flag=0 and a.content_type like 'image/%' order by a.attachment_id",bids.toArray()))files.computeIfAbsent(number(f.get("record_id")),k->new ArrayList<>()).add(f);
   var shipmentIds=new TreeSet<Long>();for(var batch:batches)for(var transit:maps(map(batch.get("ledgerBalance")).get("transits")))if(number(transit.get("outstanding"))>0&&number(transit.get("shipmentId"))>0)shipmentIds.add(number(transit.get("shipmentId")));
   if(!shipmentIds.isEmpty())for(var shipment:store.jdbc.queryForList("select record_id,data->>'direction' as direction from plm_partner_record where kind='sample_shipment' and deleted_flag=0 and record_id in ("+marks(shipmentIds.size())+")",shipmentIds.toArray()))directions.put(number(shipment.get("record_id")),text(shipment.get("direction")));
  }
  var byIdentity=new HashMap<Long,List<Map<String,Object>>>();for(var batch:batches)byIdentity.computeIfAbsent(number(batch.get("sample_identity_id")),k->new ArrayList<>()).add(batch);
  var result=new ArrayList<Map<String,Object>>();long locationId=number(query.get("locationId"));boolean unlocated="true".equals(query.get("unlocated"));String keyword=text(query.get("keyword")).toLowerCase(Locale.ROOT),groupKey=text(query.get("groupKey"));
  var corrections=new SampleReceiptCorrections(store);var referenced=details&&!mexico?corrections.referenceBlocks(batches.stream().map(b->number(b.get("id"))).toList()):Set.<Long>of();
  for(var h:headers.values()){
   var row=project(h,byIdentity.getOrDefault(number(h.get("id")),List.of()),files,directions,locations,site,mexico,locationId,unlocated);
   if(details&&!mexico)for(var batch:maps(row.get("batches"))){map(batch.get("balances")).put("positions",batch.get("positions"));corrections.enrich(batch,events.getOrDefault(number(batch.get("id")),List.of()),referenced.contains(number(batch.get("id"))));}
   if((mexico||!site.isEmpty()||locationId>0||unlocated)&&!hasStock(row))continue;
   if(!groupKey.isEmpty()&&!groupKey.equals(row.get("groupKey")))continue;
   if(!keyword.isEmpty()&&!search(row,keyword)&&!recordedSupplierNames.getOrDefault(number(h.get("id")),"").toLowerCase(Locale.ROOT).contains(keyword))continue;result.add(row);
  }
  return result;
 }

 private Map<String,Object> project(Map<String,Object> head,List<Map<String,Object>> batches,Map<Long,List<Map<String,Object>>> files,Map<Long,String> directions,Map<Long,Map<String,Object>> locations,String site,boolean mexico,long locationId,boolean unlocated){
  var row=mexico?new LinkedHashMap<String,Object>():new LinkedHashMap<>(head);for(String key:List.of("id","code","sampleIdentityId","sampleCode","originalSampleCode","name","productName","specName","phoneName","phoneModelId","phoneModelIds","phones","colorId","colorName","sampleVersion","supplierCode","supplierName","supplierShortName","catalogStyleId","catalogStyleCode","catalogStyleLabel","productId","domain","sourceType","status","version"))if(head.containsKey(key))row.put(key,head.get(key));
  row.remove("identityBasis");row.put("groupKey",groupKey(head));var totals=zeros(TOTALS);var positions=new LinkedHashMap<String,Map<String,Object>>();var loans=new ArrayList<Map<String,Object>>();var photos=new LinkedHashMap<Long,Map<String,Object>>();var localBatches=new ArrayList<Map<String,Object>>();
  for(var batch:batches){long bid=number(batch.get("id"));var balance=map(batch.get("ledgerBalance"));var batchTotals=zeros(TOTALS);var batchPositions=new ArrayList<Map<String,Object>>();
   for(var raw:maps(balance.get("positions"))){var p=SampleLocations.project(raw,locations);if(!site.isEmpty()&&!site.equals(text(p.get("site"))))continue;if(locationId>0&&locationId!=number(p.get("locationId")))continue;if(unlocated&&!unlocated(p))continue;if(number(p.get("onhand"))==0)continue;
    for(String f:List.of("onhand","available","retained","damaged"))increase(batchTotals,f,number(p.get(f)));batchPositions.add(p);mergePosition(positions,p);
   }
   if(locationId==0&&!unlocated){
    for(var loan:maps(balance.get("loans")))if(number(loan.get("outstanding"))>0&&(site.isEmpty()||site.equals(SampleLocations.at(loan,"location").site()))){var safe=new LinkedHashMap<String,Object>();for(String f:List.of("id","person","due","qty","outstanding","date"))if(loan.containsKey(f))safe.put(f,loan.get(f));safe.putAll(SampleLocations.at(loan,"location").view());safe.put("batchId",bid);safe.put("batchCode",batch.get("code"));loans.add(safe);increase(batchTotals,"borrowed",number(loan.get("outstanding")));}
    for(var transit:maps(balance.get("transits")))if(number(transit.get("outstanding"))>0&&(site.isEmpty()||site.equals("MX")&&"mexico_outbound".equals(directions.get(number(transit.get("shipmentId"))))))increase(batchTotals,"inTransit",number(transit.get("outstanding")));
   }
   boolean relevant=hasStock(Map.of("balances",batchTotals));if(relevant||site.isEmpty()&&locationId==0&&!unlocated)for(var f:files.getOrDefault(bid,List.of()))photos.putIfAbsent(number(f.get("id")),Map.of("id",f.get("id"),"name",f.get("name"),"type",f.get("type")));
   if(relevant||!mexico&&site.isEmpty()&&locationId==0&&!unlocated){var b=mexico?new LinkedHashMap<String,Object>():decode(store.json,batch.get("data"));b.put("id",bid);b.put("code",batch.get("code"));b.put("version",batch.get("version"));if(!mexico){long received=number(batch.get("effective_received_qty"));b.put("effectiveReceivedQty",received);b.put("receiptStatus",received>0?"active":"voided");}b.put("balances",batchTotals);b.put("positions",batchPositions);localBatches.add(b);}
   for(String key:TOTALS)increase(totals,key,number(batchTotals.get(key)));
  }
  totals.put("positions",new ArrayList<>(positions.values()));totals.put("loans",loans);row.put("balances",totals);row.put("positions",totals.get("positions"));row.put("loans",loans);row.put("files",new ArrayList<>(photos.values()));
  row.put("batches",localBatches);if(!photos.isEmpty())row.put("photo",Map.of("sampleIdentityId",head.get("id"),"id",photos.keySet().iterator().next()));
  if(mexico)row.put("site","MX");else{long received=batches.stream().mapToLong(batch->number(batch.get("effective_received_qty"))).sum();row.put("effectiveReceivedQty",received);row.put("receiptStatus",batches.isEmpty()||received>0?"active":"voided");}return row;
 }

 static Map<Long,Map<String,Object>> locations(PartnerStore store,boolean mexico){var result=new HashMap<Long,Map<String,Object>>();for(var loc:store.jdbc.queryForList("select record_id as id,name,site,data::text from plm_partner_record where kind='sample_location'"+(mexico?" and site='MX'":""))){var value=decode(store.json,loc.get("data"));for(String key:List.of("id","name","site"))value.put(key,loc.get(key));result.put(number(loc.get("id")),value);}return result;}
 static List<Map<String,Object>> groups(List<Map<String,Object>> rows,boolean mexico){
  var grouped=new LinkedHashMap<String,List<Map<String,Object>>>();for(var row:rows)grouped.computeIfAbsent(text(row.get("groupKey")),k->new ArrayList<>()).add(row);
  var result=new ArrayList<Map<String,Object>>();for(var entry:grouped.entrySet()){
   var first=entry.getValue().get(0);var group=new LinkedHashMap<String,Object>();for(String key:List.of("name","catalogStyleId","catalogStyleCode","catalogStyleLabel","productId","supplierCode","supplierName","photo"))if(first.containsKey(key))group.put(key,first.get(key));
   group.put("groupKey",entry.getKey());group.put("sampleCount",entry.getValue().size());var phones=new HashSet<Long>();var colors=new HashSet<Long>();var unconfirmedColors=new HashSet<String>();var balances=zeros(TOTALS);var positions=new LinkedHashMap<String,Map<String,Object>>();
   for(var row:entry.getValue()){if(number(row.get("phoneModelId"))>0)phones.add(number(row.get("phoneModelId")));phones.addAll(phoneIds(row.get("phoneModelIds")));if(number(row.get("colorId"))>0)colors.add(number(row.get("colorId")));else if(row.get("colorName") instanceof String name&&!name.isBlank())unconfirmedColors.add(name);for(String key:TOTALS)increase(balances,key,number(map(row.get("balances")).get(key)));for(var p:maps(row.get("positions")))mergePosition(positions,p);if(!group.containsKey("photo")&&row.containsKey("photo"))group.put("photo",row.get("photo"));}
   group.put("phoneCount",phones.size());group.put("colorCount",colors.size());group.put("unconfirmedColorCount",unconfirmedColors.size());balances.put("positions",new ArrayList<>(positions.values()));group.put("balances",balances);group.put("positions",new ArrayList<>(positions.values()));long cn=0,mx=0;for(var p:positions.values()){if("CN".equals(p.get("site")))cn=add(cn,number(p.get("onhand")));if("MX".equals(p.get("site")))mx=add(mx,number(p.get("onhand")));}if(!mexico){group.put("cnOnhand",cn);group.put("effectiveReceivedQty",entry.getValue().stream().mapToLong(row->number(row.get("effectiveReceivedQty"))).sum());group.put("receiptStatus",entry.getValue().stream().allMatch(row->"voided".equals(row.get("receiptStatus")))?"voided":"active");}group.put("mxOnhand",mx);result.add(group);
  }return result;
 }
 static String groupKey(Map<String,Object> head){String supplier=text(head.get("supplierCode"));long style=number(head.get("catalogStyleId")),product=number(head.get("productId"));return !supplier.isEmpty()&&style>0?"catalog:"+style+":"+supplier:!supplier.isEmpty()&&product>0?"product:"+product+":"+supplier:"identity:"+number(head.get("sampleIdentityId"));}
 private static boolean hasStock(Map<String,Object> row){var b=map(row.get("balances"));return number(b.get("onhand"))>0||number(b.get("borrowed"))>0||number(b.get("inTransit"))>0;}
 private static boolean unlocated(Map<String,Object> p){return number(p.get("locationId"))==0&&text(p.get("locationCode")).isEmpty();}
 private static boolean search(Map<String,Object> row,String keyword){for(String key:List.of("name","productName","sampleCode","code","specName","phoneName","colorName","supplierCode","supplierName","supplierShortName"))if(text(row.get(key)).toLowerCase(Locale.ROOT).contains(keyword))return true;for(var p:maps(row.get("positions")))if((text(p.get("name"))+" "+text(p.get("locationCode"))).toLowerCase(Locale.ROOT).contains(keyword))return true;return false;}
 private static String marks(int size){return String.join(",",Collections.nCopies(size,"?"));}
 private static Map<String,Object> zeros(List<String> keys){var result=new LinkedHashMap<String,Object>();for(String key:keys)result.put(key,0L);return result;}
 private static void increase(Map<String,Object> value,String key,long amount){value.put(key,add(number(value.get(key)),amount));}
 private static void mergePosition(Map<String,Map<String,Object>> positions,Map<String,Object> p){String key=text(p.get("positionKey"));var total=positions.computeIfAbsent(key,k->{var value=new LinkedHashMap<>(p);for(String f:POSITION_TOTALS)value.put(f,0L);return value;});for(String f:POSITION_TOTALS)increase(total,f,number(p.get(f)));}
}

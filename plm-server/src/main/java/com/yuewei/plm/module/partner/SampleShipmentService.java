package com.yuewei.plm.module.partner;

import com.yuewei.plm.common.exception.BusinessException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Parcel quantities reference the existing SP custody ledger; no parallel inventory. */
@Service @RequiredArgsConstructor
public class SampleShipmentService {
 private static final String KIND="sample_shipment";
 private static final Set<String> DIRECTIONS=Set.of("supplier_inbound","mexico_outbound");
 private static final Set<String> SENT=Set.of("dispatched","partially_received","received");
 private final PartnerStore store;
 private final SampleIdentityRegistry identities;
 private final SupplierProfileService suppliers;

 private boolean mexico(){return "mexico".equals(store.access.access().role());}
 private void domestic(boolean write){store.access.requireDomesticView();store.access.requirePermission(write?"samples:write":"partner:view");}
 private void mexicoAccess(){if(!mexico()||!store.access.hasPermission("task:arrival"))throw forbidden();}
 private static BusinessException forbidden(){return new BusinessException(40103,"样品或寄样包裹不存在或未获授权");}
 private String request(Map<String,Object> input){String key=required(input.get("requestId"),"提交标识");require(key.length()<=80,"提交标识过长");return key;}
 private String eventKey(String key){return "shipment-"+UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 private String fingerprint(String action,Map<String,Object> input){return PartnerQualityRules.digest(store.json,Map.of("action",action,"input",input));}
 private boolean repeated(long id,String key,String action,String hash){
  var rows=store.jdbc.queryForList("select action,data::text from plm_partner_event where record_id=? and request_id=?",id,key);
  if(rows.isEmpty())return false;var data=decode(store.json,rows.get(0).get("data"));require(("sample_shipment_"+action).equals(rows.get(0).get("action"))&&hash.equals(data.get("requestHash")),"提交标识已用于不同动作或内容，请查看原寄样记录");return true;
 }
 private Map<String,Object> load(long id,boolean lock){
  if(!mexico())return store.load(id,KIND,lock);
  mexicoAccess();var rows=store.jdbc.queryForList("select record_id,code,name,status,version,data::text from plm_partner_record where record_id=? and kind=? and deleted_flag=0"+(lock?" for update":""),id,KIND);
  if(rows.size()!=1)throw forbidden();var raw=rows.get(0);var d=decode(store.json,raw.get("data"));
  if(number(d.get("receiverUserId"))!=store.access.actorId()||!"mexico_outbound".equals(d.get("direction"))||!SENT.contains(text(raw.get("status"))))throw forbidden();
  d.put("id",id);for(String field:List.of("code","name","status","version"))d.put(field,raw.get(field));return d;
 }
 public Map<String,Object> options(){
  domestic(false);var people=store.jdbc.queryForList("select u.user_id as id,u.display_name as name from sys_user u join plm_pilot_account a on a.user_id=u.user_id where u.status='active' and u.deleted_flag=0 and a.role_code='mexico' and jsonb_exists(a.permissions,'task:arrival') order by u.display_name,u.user_id");return Map.of("receivers",people);
 }
 public Map<String,Object> page(Map<String,String> query){
  boolean mx=mexico();if(mx)mexicoAccess();else domestic(false);
  var where=new ArrayList<String>(List.of("r.kind='sample_shipment'","r.deleted_flag=0"));var args=new ArrayList<Object>();
  if(mx){where.add("r.data->>'direction'='mexico_outbound' and r.status in ('dispatched','partially_received','received') and r.data->>'receiverUserId'=?");args.add(Long.toString(store.access.actorId()));}else where.add(store.access.visibleSqlClause("partner","r.record_id"));
  String keyword=text(query.get("keyword"));if(!keyword.isEmpty()){where.add("(r.name ilike ? or r.code ilike ? or r.data->'dispatch'->>'tracking' ilike ?)");for(int i=0;i<3;i++)args.add("%"+keyword+"%");}
  for(String field:List.of("direction","domain")){String value=text(query.get(field));if(!value.isEmpty()&&!value.equals("all")){if(field.equals("direction"))require(DIRECTIONS.contains(value),"寄样方向无效");else SampleDomains.validate(value);where.add("r.data->>?=?");args.add(field);args.add(value);}}
  String status=text(query.get("status"));if(!status.isEmpty()&&!status.equals("all")){require(Set.of("draft","dispatched","partially_received","received","cancelled").contains(status),"寄样状态无效");where.add("r.status=?");args.add(status);}
  if(number(query.get("selectionId"))>0){where.add("r.data->>'selectionId'=?");args.add(Long.toString(number(query.get("selectionId"))));}
  String predicate=String.join(" and ",where);long total=store.jdbc.queryForObject("select count(*) from plm_partner_record r where "+predicate,Long.class,args.toArray());long page=Math.max(1,number(query.getOrDefault("page","1"))),size=Math.max(1,Math.min(100,number(query.getOrDefault("size","50"))));
  var statistics=store.jdbc.queryForList("select r.status,count(*) as count from plm_partner_record r where "+predicate+" group by r.status order by r.status",args.toArray());args.add(size);args.add((page-1)*size);
  var rows=store.jdbc.queryForList("select r.record_id as id,r.code,r.name,r.status,r.version,r.data::text from plm_partner_record r where "+predicate+" order by r.updated_at desc,r.record_id desc limit ? offset ?",args.toArray());var content=new ArrayList<Map<String,Object>>();
  for(var row:rows){var d=decode(store.json,row.remove("data"));d.putAll(row);present(d);content.add(mx?minimal(d,null):d);}
  return Map.of("content",content,"page",page,"size",size,"totalElements",total,"totalPages",(total+size-1)/size,"statistics",statistics);
 }
 public Map<String,Object> detail(long id){var d=load(id,false);present(d);if(mexico())return minimal(d,null);d.put("events",store.events(id));d.put("files",store.files(id));d.put("revisions",store.revisions(id));d.put("receiptContext",receiptContext(d));return d;}
 @Transactional public Map<String,Object> create(Map<String,Object> input){
  domestic(true);String key=request(input),hash=fingerprint("create",input);Long previous=store.created(KIND,key);
  if(previous!=null){var d=store.load(previous,KIND,false);require(hash.equals(d.get("creationHash")),"提交标识已有不同寄样草稿，请核对原记录");return detail(previous);}
  var d=draft(input,Map.of());long id=store.create(KIND,text(d.get("name")));d.put("creationHash",hash);store.remember(KIND,key,id);store.save(id,d);store.event(id,"sample_shipment_create",Map.of("requestHash",hash,"inventoryChanged",false),eventKey(key));return detail(id);
 }
 @Transactional public Map<String,Object> save(long id,Map<String,Object> input){
  domestic(true);var d=load(id,true);String key=eventKey(request(input)),hash=fingerprint("save",input);if(repeated(id,key,"save",hash))return detail(id);store.check(d,number(input.get("version")));require("draft".equals(d.get("status")),"实际发出后请追加物流或签收记录，不覆盖原清单");
  var next=draft(input,d);store.revision(id,d);store.event(id,"sample_shipment_save",Map.of("requestHash",hash,"inventoryChanged",false),key);store.save(id,next);return detail(id);
 }
 @Transactional public Map<String,Object> delete(long id,Map<String,Object> input){
  domestic(true);var d=store.load(id,KIND,true,true);String key=eventKey(request(input)),hash=fingerprint("delete",input);if(repeated(id,key,"delete",hash))return Map.of("id",id,"deleted",true);require(!Boolean.TRUE.equals(d.get("deleted"))&&"draft".equals(d.get("status")),"仅尚未发出的寄样草稿可以删除");store.check(d,number(input.get("version")));String reason=required(input.get("reason"),"删除原因");store.revision(id,d);store.event(id,"sample_shipment_delete",Map.of("requestHash",hash,"reason",reason,"inventoryChanged",false),key);store.jdbc.update("update plm_partner_record set deleted_flag=1,updated_at=now(),updated_by=?,version=version+1 where record_id=?",store.access.actor().username(),id);return Map.of("id",id,"deleted",true);
 }
 public void requireFileWrite(long id){domestic(true);load(id,false);}
 private Map<String,Object> draft(Map<String,Object> input,Map<String,Object> previous){
  var d=new LinkedHashMap<>(previous);String direction=text(input.getOrDefault("direction",previous.get("direction")));require(DIRECTIONS.contains(direction),"请选择寄样方向");if(previous.containsKey("direction"))require(direction.equals(previous.get("direction")),"已有寄样方向不能改写，请另建草稿");d.put("direction",direction);d.put("status","draft");d.put("name",text(input.getOrDefault("name",previous.get("name"))).isEmpty()?(direction.equals("supplier_inbound")?"供应商寄样":"样品发往墨西哥"):text(input.getOrDefault("name",previous.get("name"))));
  for(String field:List.of("origin","destination","note"))if(input.containsKey(field))d.put(field,text(input.get(field)));
  if(input.containsKey("domain"))d.put("domain",SampleDomains.validate(text(input.get("domain"))));
  long selection=number(input.getOrDefault("selectionId",previous.get("selectionId")));if(selection>0){var source=store.load(selection,"selection",false);PartnerSelectionService.requireSelectionPurpose(source);}d.put("selectionId",selection);
  String supplier=text(input.getOrDefault("supplierCode",previous.get("supplierCode")));if(!supplier.isEmpty())suppliers.requireSupplier(supplier);d.put("supplierCode",supplier);
  long receiver=number(input.getOrDefault("receiverUserId",previous.get("receiverUserId")));d.put("receiverUserId",receiver);d.put("receiverName",text(input.getOrDefault("receiverName",previous.get("receiverName"))));if(receiver>0){var users=store.jdbc.queryForList("select u.display_name from sys_user u join plm_pilot_account a on a.user_id=u.user_id where u.user_id=? and u.status='active' and u.deleted_flag=0 and a.role_code='mexico' and jsonb_exists(a.permissions,'task:arrival')",receiver);require(users.size()==1,"请选择有效墨西哥收样账号");d.put("receiverName",users.get(0).get("display_name"));}
  var rows=normalizeRows(input.containsKey("rows")?input.get("rows"):previous.getOrDefault("rows",List.of()),direction,selection,false);d.put("rows",rows);var domains=rows.stream().map(row->text(row.get("domain"))).filter(value->!value.isEmpty()).distinct().toList();if(!domains.isEmpty())d.put("domain",domains.size()==1?domains.get(0):"unclassified");return d;
 }
 private List<Map<String,Object>> normalizeRows(Object value,String direction,long selection,boolean actual){
  require(value instanceof List<?>,"寄样明细格式无效");var requested=maps(value);require(requested.size()<=100,"每包最多100条样品明细");if(actual)require(!requested.isEmpty(),"请列出本次实际发出的样品");var rows=new ArrayList<Map<String,Object>>();var ids=new HashSet<String>();var sources=new HashSet<String>();
  for(var raw:requested){var row=new LinkedHashMap<String,Object>();String rowId=text(raw.get("rowId"));if(rowId.isEmpty())rowId=UUID.randomUUID().toString();require(rowId.length()<=80&&ids.add(rowId),"寄样明细标识重复或无效");row.put("rowId",rowId);
   long identity=number(raw.get("sampleIdentityId"));String sourceRow=text(raw.get("selectionRowId"));if(identity>0){identities.requireVisible(identity,false);long canonical=canonicalIdentity(store,identity);if(canonical!=identity)row.put("originalSampleIdentityId",identity);identity=canonical;}row.put("sampleIdentityId",identity);
   if(identity>0){var h=identities.requireVisible(identity,false);row.put("sampleCode",h.get("code"));row.put("name",h.get("name"));row.put("specName",text(h.get("specName")));row.put("domain",text(h.get("domain")));row.put("supplierCode",text(h.get("supplierCode")));row.put("supplierName",text(h.get("supplierName")));for(String field:List.of("phoneModelId","colorId"))if(number(h.get(field))>0)row.put(field,number(h.get(field)));}
   else {require(direction.equals("supplier_inbound"),"发墨明细必须关联已有样品主码");require(selection>0&&!sourceRow.isEmpty(),"未知型号／颜色的寄样请先关联原选品行");}
   if(!sourceRow.isEmpty()){require(selection>0&&PartnerSelectionService.validRowId(sourceRow),"来源选品行无效");var selected=new PartnerSelectionRows(store).find(selection,sourceRow,false);require(!selected.isEmpty(),"寄样来源行不属于当前选品资料");row.put("selectionRowId",sourceRow);if(identity==0){row.put("name",text(selected.getOrDefault("name",selected.get("styleName"))));row.put("domain","partner");}}
   String source=identity>0?"identity:"+identity+":"+number(raw.get("batchId"))+":"+text(raw.get("location")):"selection:"+sourceRow+":"+number(raw.get("phoneModelId"))+":"+number(raw.get("colorId"));require(sources.add(source),"同一来源及取出位置请合并数量");
   for(String field:List.of("phoneModelId","colorId","batchId"))if(number(raw.get(field))>0&&(identity==0||field.equals("batchId")))row.put(field,number(raw.get(field)));row.put("location",text(raw.get("location")));
   Object quantity=raw.get("qty");row.put("qty",text(quantity).isEmpty()?null:qty(quantity,false));if(actual)row.put("qty",qty(quantity,false));row.put("receivedGood",0L);row.put("receivedDamaged",0L);row.put("confirmedLoss",0L);row.put("allocations",new ArrayList<Map<String,Object>>());rows.add(row);
  }return rows;
 }
 @Transactional public Map<String,Object> action(long id,String action,Map<String,Object> input){
  boolean mx=mexico();if(mx){mexicoAccess();require(action.equals("receive"),"此账号仅能登记分配包裹的实际签收");}else domestic(true);
  require(Set.of("dispatched","tracking","receive","loss","cancel","undo").contains(action),"寄样动作无效");var d=load(id,true);String key=eventKey(request(input)),hash=fingerprint(action,input);if(repeated(id,key,action,hash))return detail(id);store.check(d,number(input.get("version")));
  var facts=new LinkedHashMap<String,Object>();facts.put("requestHash",hash);facts.put("reason",text(input.get("reason")));facts.put("note",text(input.get("note")));
  if(!mx){store.requireFiles(id,input.get("refs"));facts.put("refs",store.canonicalFiles(id,input.get("refs")));}
  if(action.equals("cancel")){require("draft".equals(d.get("status")),"已实际发出的包裹不能作废物流事实，请核对实物签收或短少");facts.put("reason",required(input.get("reason"),"取消原因"));d.put("status","cancelled");}
  else if(action.equals("dispatched"))dispatch(id,d,input,key,facts);
  else {require(SENT.contains(text(d.get("status"))),"包裹尚未实际发出，不能登记签收或物流");String actualDate=date(input.get("date"));require(!java.time.LocalDate.parse(actualDate).isBefore(java.time.LocalDate.parse(text(map(d.get("dispatch")).get("date")))),"实际物流或签收日期不能早于发出日期");facts.put("date",actualDate);
   if(action.equals("tracking")){facts.put("tracking",required(input.get("tracking"),"运单／物流依据"));facts.put("carrier",text(input.get("carrier")));facts.put("reason",required(input.getOrDefault("reason",input.get("note")),"物流更新说明"));d.put("dispatch",Map.of("date",map(d.get("dispatch")).get("date"),"tracking",facts.get("tracking"),"carrier",facts.get("carrier")));}
   else if(action.equals("undo"))undo(id,d,input,key,facts);
   else settle(id,d,action,input,key,facts,mx);
  }
  store.event(id,"sample_shipment_"+action,facts,key);store.save(id,d);return detail(id);
 }
 private void dispatch(long id,Map<String,Object> d,Map<String,Object> input,String key,Map<String,Object> facts){
  require("draft".equals(d.get("status")),"本包裹已有实际发出，不能重复扣出");required(d.get("destination"),"实际收样目的地");String direction=text(d.get("direction"));var rows=normalizeRows(input.getOrDefault("rows",d.get("rows")),direction,number(d.get("selectionId")),true);String date=date(input.get("date")),tracking=required(input.get("tracking"),"运单／物流依据");facts.put("date",date);facts.put("tracking",tracking);facts.put("carrier",text(input.get("carrier")));
  // Keep the last saved plan as evidence; only actual qty governs stock and receipt limits.
  var plannedRows=maps(d.get("rows"));for(var row:rows){var planned=plannedRows.stream().filter(r->text(r.get("rowId")).equals(text(row.get("rowId")))).findFirst().orElse(Map.of());row.put("plannedQty",planned.get("qty"));}store.revision(id,d);
  if(direction.equals("mexico_outbound")){
   var candidates=new TreeSet<Long>();for(var row:rows)for(var batch:identities.visibleBatches(number(row.get("sampleIdentityId")),false))if(number(row.get("batchId"))==0||number(row.get("batchId"))==number(batch.get("id")))candidates.add(number(batch.get("id")));
   var locked=new LinkedHashMap<Long,Map<String,Object>>();for(long batch:candidates)locked.put(batch,store.load(batch,"sample",true));
   for(var row:rows){long left=number(row.get("qty"));var allocations=new ArrayList<Map<String,Object>>();
    for(var entry:locked.entrySet()){
     long batchId=entry.getKey();if(number(row.get("batchId"))>0&&number(row.get("batchId"))!=batchId)continue;
     var identities=store.jdbc.queryForList("select sample_identity_id from plm_partner_record where record_id=?",batchId);if(number(identities.get(0).get("sample_identity_id"))!=number(row.get("sampleIdentityId")))continue;
     var state=SampleLedger.replay(store.events(batchId));var positions=maps(state.view().get("positions")).stream().sorted(Comparator.comparing(p->text(p.get("name")))).toList();
     for(var position:positions){String location=text(position.get("name"));if(!text(row.get("location")).isEmpty()&&!location.equals(row.get("location")))continue;long quantity=Math.min(left,number(position.get("available")));if(quantity==0)continue;
      var command=new LinkedHashMap<String,Object>();command.put("qty",quantity);command.put("date",date);command.put("location",location);command.put("shipmentId",id);command.put("shipmentRowId",row.get("rowId"));command.put("destination",text(d.get("destination")));String sub=subKey(key,batchId+":"+row.get("rowId")+":"+location);
      var actual=SampleLedger.prepare(SampleLedger.replay(store.events(batchId)),text(entry.getValue().get("code")),"dispatch",command);store.event(batchId,"dispatch",actual,sub);long event=eventId(batchId,sub);var allocation=new LinkedHashMap<String,Object>();allocation.put("batchId",batchId);allocation.put("dispatchEventId",event);allocation.put("qty",quantity);allocation.put("location",location);allocation.put("receivedGood",0L);allocation.put("receivedDamaged",0L);allocation.put("confirmedLoss",0L);allocations.add(allocation);saveBalance(batchId,entry.getValue());left-=quantity;if(left==0)break;
     }if(left==0)break;
    }require(left==0,"实际可用样品不足，借出、留样、坏品及在途数量不能重复寄出");row.put("allocations",allocations);
   }
  }
  d.put("rows",rows);d.put("status","dispatched");d.put("dispatch",Map.of("date",date,"tracking",tracking,"carrier",text(input.get("carrier"))));facts.put("rows",rows);
 }
 private void settle(long id,Map<String,Object> d,String action,Map<String,Object> input,String key,Map<String,Object> facts,boolean mx){
  boolean inbound="supplier_inbound".equals(d.get("direction"));require(!inbound||action.equals("loss"),"供应商寄样请从原选品资料登记实收，不另外增加一笔库存");var rows=copyRows(store,d);var requested=maps(input.get("rows"));require(!requested.isEmpty(),"请填写本次实际办理数量");var ids=new HashSet<String>();var changes=new ArrayList<Map<String,Object>>();
  var batchIds=new TreeSet<Long>();for(var row:rows)for(var a:maps(row.get("allocations")))batchIds.add(number(a.get("batchId")));var batches=new HashMap<Long,Map<String,Object>>();for(long batch:batchIds)batches.put(batch,lockedBatch(batch,mx));
  for(var raw:requested){String rowId=required(raw.get("rowId"),"寄样明细");require(ids.add(rowId),"同一寄样明细不能重复填写");var row=rows.stream().filter(r->rowId.equals(text(r.get("rowId")))).findFirst().orElseThrow(()->invalid("明细不属于此包裹"));long good=action.equals("receive")?qty(raw.getOrDefault("goodQty",0),true):0,bad=action.equals("receive")?qty(raw.getOrDefault("damagedQty",0),true):0,loss=action.equals("loss")?qty(raw.get("qty"),false):0,quantity=add(add(good,bad),loss);require(quantity>0&&quantity<=remaining(row),"签收／短少超过此明细在途剩余数量");if(action.equals("loss"))facts.put("reason",required(input.get("reason"),"确认短少原因"));
   long goodLeft=good,badLeft=bad,lossLeft=loss;var changed=new LinkedHashMap<String,Object>();changed.put("rowId",rowId);changed.put("goodQty",good);changed.put("damagedQty",bad);changed.put("qty",quantity);var ledgerEvents=new ArrayList<Map<String,Object>>();
   for(var allocation:maps(row.get("allocations"))){long capacity=remaining(allocation);if(capacity==0)continue;long takeGood=Math.min(capacity,goodLeft);capacity-=takeGood;long takeBad=Math.min(capacity,badLeft);capacity-=takeBad;long takeLoss=Math.min(capacity,lossLeft);long used=add(add(takeGood,takeBad),takeLoss);if(used==0)continue;
    long batch=number(allocation.get("batchId"));var command=new LinkedHashMap<String,Object>();command.put("date",facts.get("date"));command.put("dispatchEventId",allocation.get("dispatchEventId"));command.put("shipmentId",id);command.put("shipmentRowId",rowId);String ledgerAction=action.equals("receive")?"dispatch_receive":"dispatch_loss";
    if(action.equals("receive")){command.put("location",required(input.get("location"),"实际收样位置"));command.put("goodQty",takeGood);command.put("damagedQty",takeBad);command.put("person",text(input.get("person")));}else{command.put("qty",takeLoss);command.put("reason",facts.get("reason"));}
    String sub=subKey(key,batch+":"+allocation.get("dispatchEventId"));var actual=SampleLedger.prepare(SampleLedger.replay(store.events(batch)),text(batches.get(batch).get("code")),ledgerAction,command);store.event(batch,ledgerAction,actual,sub);ledgerEvents.add(Map.of("batchId",batch,"eventId",eventId(batch,sub),"dispatchEventId",allocation.get("dispatchEventId"),"goodQty",takeGood,"damagedQty",takeBad,"lossQty",takeLoss));saveBalance(batch,batches.get(batch));allocation.put("receivedGood",add(number(allocation.get("receivedGood")),takeGood));allocation.put("receivedDamaged",add(number(allocation.get("receivedDamaged")),takeBad));allocation.put("confirmedLoss",add(number(allocation.get("confirmedLoss")),takeLoss));goodLeft-=takeGood;badLeft-=takeBad;lossLeft-=takeLoss;
   }
   require(inbound||add(add(goodLeft,badLeft),lossLeft)==0,"原批次在途分配不足，请刷新后核对");row.put("receivedGood",add(number(row.get("receivedGood")),good));row.put("receivedDamaged",add(number(row.get("receivedDamaged")),bad));row.put("confirmedLoss",add(number(row.get("confirmedLoss")),loss));changed.put("ledgerEvents",ledgerEvents);changes.add(changed);
  }facts.put("rows",changes);facts.put("location",text(input.get("location")));d.put("rows",rows);setReceiptState(d);
 }
 private void undo(long id,Map<String,Object> d,Map<String,Object> input,String key,Map<String,Object> facts){
  long target=number(input.get("targetEventId"));String reason=required(input.get("reason"),"更正原因");var events=store.events(id);var original=events.stream().filter(e->number(e.get("id"))==target).findFirst().orElseThrow(()->invalid("原寄样签收记录不存在"));require(Set.of("sample_shipment_receive","sample_shipment_loss").contains(text(original.get("action"))),"仅能在此更正本包裹的签收或短少记录");require(events.stream().noneMatch(e->"sample_shipment_undo".equals(e.get("action"))&&number(map(e.get("data")).get("targetEventId"))==target),"原记录已经更正");var rows=copyRows(store,d);var originals=maps(map(original.get("data")).get("rows"));var batchIds=new TreeSet<Long>();for(var change:originals)for(var event:maps(change.get("ledgerEvents")))batchIds.add(number(event.get("batchId")));var batches=new HashMap<Long,Map<String,Object>>();for(long batch:batchIds)batches.put(batch,lockedBatch(batch,false));
  for(var change:originals){var row=rows.stream().filter(r->Objects.equals(r.get("rowId"),change.get("rowId"))).findFirst().orElseThrow(()->invalid("原明细已变化"));long good=number(change.get("goodQty")),bad=number(change.get("damagedQty")),loss=text(original.get("action")).equals("sample_shipment_loss")?number(change.get("qty")):0;
   for(var event:maps(change.get("ledgerEvents"))){long batch=number(event.get("batchId"));var command=Map.<String,Object>of("date",facts.get("date"),"targetEventId",event.get("eventId"),"reason",reason);String sub=subKey(key,batch+":"+event.get("eventId"));var actual=SampleLedger.prepare(SampleLedger.replay(store.events(batch)),text(batches.get(batch).get("code")),"undo",command);store.event(batch,"undo",actual,sub);saveBalance(batch,batches.get(batch));for(var a:maps(row.get("allocations")))if(number(a.get("dispatchEventId"))==number(event.get("dispatchEventId"))){a.put("receivedGood",number(a.get("receivedGood"))-number(event.get("goodQty")));a.put("receivedDamaged",number(a.get("receivedDamaged"))-number(event.get("damagedQty")));a.put("confirmedLoss",number(a.get("confirmedLoss"))-number(event.get("lossQty")));}}
   row.put("receivedGood",number(row.get("receivedGood"))-good);row.put("receivedDamaged",number(row.get("receivedDamaged"))-bad);row.put("confirmedLoss",number(row.get("confirmedLoss"))-loss);require(number(row.get("receivedGood"))>=0&&number(row.get("receivedDamaged"))>=0&&number(row.get("confirmedLoss"))>=0,"更正依赖发生变化，请核对后续记录");
  }facts.put("targetEventId",target);facts.put("reason",reason);d.put("rows",rows);setReceiptState(d);
 }
 private Map<String,Object> lockedBatch(long id,boolean mx){if(!mx)return store.load(id,"sample",true);var rows=store.jdbc.queryForList("select record_id,code,name,status,version,data::text from plm_partner_record where record_id=? and kind='sample' and deleted_flag=0 for update",id);if(rows.size()!=1)throw forbidden();var raw=rows.get(0);var d=decode(store.json,raw.get("data"));d.put("id",id);for(String f:List.of("code","name","status","version"))d.put(f,raw.get(f));return d;}
 private void saveBalance(long id,Map<String,Object> batch){batch.put("balances",SampleLedger.replay(store.events(id)).view());store.save(id,batch);}
 private long eventId(long batch,String key){return store.jdbc.queryForObject("select event_id from plm_partner_event where record_id=? and request_id=?",Long.class,batch,key);}
 private static String subKey(String key,String suffix){return "parcel-"+UUID.nameUUIDFromBytes((key+":"+suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 static List<Map<String,Object>> copyRows(PartnerStore store,Map<String,Object> shipment){return maps(copy(store.json,Map.of("rows",shipment.getOrDefault("rows",List.of()))).get("rows"));}
 static long remaining(Map<String,Object> row){return number(row.get("qty"))-number(row.get("receivedGood"))-number(row.get("receivedDamaged"))-number(row.get("confirmedLoss"));}
 static void setReceiptState(Map<String,Object> shipment){var rows=maps(shipment.get("rows"));boolean complete=!rows.isEmpty()&&rows.stream().allMatch(r->remaining(r)==0);boolean partial=rows.stream().anyMatch(r->remaining(r)<number(r.get("qty")));shipment.put("status",complete?"received":partial?"partially_received":"dispatched");}
 static Map<String,Object> totals(Map<String,Object> shipment){long planned=0,sent=0,good=0,bad=0,loss=0;boolean dispatched=SENT.contains(text(shipment.get("status")));for(var r:maps(shipment.get("rows"))){planned=add(planned,number(r.containsKey("plannedQty")?r.get("plannedQty"):r.get("qty")));if(dispatched)sent=add(sent,number(r.get("qty")));good=add(good,number(r.get("receivedGood")));bad=add(bad,number(r.get("receivedDamaged")));loss=add(loss,number(r.get("confirmedLoss")));}return Map.of("planned",planned,"dispatched",sent,"receivedGood",good,"receivedDamaged",bad,"confirmedLoss",loss,"inTransit",sent-good-bad-loss);}
 private void present(Map<String,Object> shipment){var rows=copyRows(store,shipment);boolean dispatched=SENT.contains(text(shipment.get("status")));for(var row:rows)row.put("inTransit",dispatched?remaining(row):0L);shipment.put("rows",rows);shipment.put("totals",totals(shipment));}
 private static Map<String,Object> receiptContext(Map<String,Object> d){var rows=maps(d.get("rows")).stream().map(r->{var out=new LinkedHashMap<String,Object>();for(String f:List.of("rowId","selectionRowId","sampleIdentityId","phoneModelId","colorId"))if(r.containsKey(f))out.put(f,r.get(f));out.put("remainingQty",remaining(r));return out;}).toList();return Map.of("selectionId",number(d.get("selectionId")),"supplierShipmentId",d.get("id"),"rows",rows);}
 private Map<String,Object> minimal(Map<String,Object> d,Long identity){var out=new LinkedHashMap<String,Object>();for(String f:List.of("id","code","name","version","status","direction","destination","dispatch","receiverName"))if(d.containsKey(f))out.put(f,d.get(f));var rows=maps(d.get("rows")).stream().filter(r->identity==null||number(r.get("sampleIdentityId"))>0&&canonicalIdentity(store,number(r.get("sampleIdentityId")))==identity).map(r->{var row=new LinkedHashMap<String,Object>();for(String f:List.of("rowId","sampleIdentityId","sampleCode","name","specName","qty","plannedQty","receivedGood","receivedDamaged","confirmedLoss"))if(r.containsKey(f))row.put(f,r.get(f));row.putAll(actualSupplier(number(r.get("sampleIdentityId"))));row.put("inTransit",SENT.contains(text(d.get("status")))?remaining(r):0L);return row;}).toList();out.put("rows",rows);out.put("totals",totals(Map.of("rows",rows,"status",d.get("status"))));out.put("minimal",true);return out;}
 // Called only after the parcel/receiver gate; expose no source snapshot or supplier profile.
 private Map<String,Object> actualSupplier(long identity){var row=store.jdbc.queryForMap("select data::text from plm_partner_record where record_id=? and kind='sample_identity' and deleted_flag=0",canonicalIdentity(store,identity));var data=decode(store.json,row.get("data"));String code=text(data.get("supplierCode")),name=text(data.get("supplierName"));if(!code.isEmpty()&&name.isEmpty()){var names=store.jdbc.queryForList("select supplier_name from plm_supplier_profile where supplier_code=?",code);if(names.size()==1)name=text(names.get(0).get("supplier_name"));}return Map.of("supplierCode",code,"supplierName",name);}
 static long canonicalIdentity(PartnerStore store,long id){var seen=new HashSet<Long>();for(int i=0;i<16;i++){require(id>0&&seen.add(id),"样品主码关联链异常，请核对");var rows=store.jdbc.queryForList("select data::text from plm_partner_record where record_id=? and kind='sample_identity' and deleted_flag=0",id);if(rows.size()!=1)throw forbidden();long next=number(decode(store.json,rows.get(0).get("data")).get("redirectIdentityId"));if(next==0)return id;id=next;}throw invalid("样品主码关联链过长，请核对");}
 public Map<String,Object> scan(String code){
  mexicoAccess();String value=text(code);if(!value.matches("(?:SY|SP)-[0-9]{6,}"))throw forbidden();var identities=store.jdbc.queryForList("select h.identity_id,r.code,r.name from plm_sample_identity h join plm_partner_record r on r.record_id=h.identity_id where r.deleted_flag=0 and (r.code=? or exists(select 1 from plm_partner_record b where b.kind='sample' and b.deleted_flag=0 and b.sample_identity_id=h.identity_id and b.code=?))",value,value);if(identities.size()!=1)throw forbidden();long id=canonicalIdentity(store,number(identities.get(0).get("identity_id")));var identity=store.jdbc.queryForMap("select code,name from plm_partner_record where record_id=?",id);
  var parcels=store.jdbc.queryForList("select record_id as id,code,name,status,version,data::text from plm_partner_record where kind='sample_shipment' and deleted_flag=0 and status in ('dispatched','partially_received','received') and data->>'direction'='mexico_outbound' and data->>'receiverUserId'=? order by record_id desc",Long.toString(store.access.actorId()));var shipments=new ArrayList<Map<String,Object>>();
  for(var raw:parcels){var d=decode(store.json,raw.remove("data"));d.putAll(raw);if(maps(d.get("rows")).stream().anyMatch(row->number(row.get("sampleIdentityId"))>0&&canonicalIdentity(store,number(row.get("sampleIdentityId")))==id))shipments.add(minimal(d,id));}if(shipments.isEmpty())throw forbidden();var result=new LinkedHashMap<String,Object>();result.put("type","assigned_shipments");result.put("sampleIdentityId",id);result.put("sampleCode",identity.get("code"));result.put("name",identity.get("name"));result.put("shipments",shipments);result.putAll(actualSupplier(id));return result;
 }
}

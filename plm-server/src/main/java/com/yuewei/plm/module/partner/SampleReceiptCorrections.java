package com.yuewei.plm.module.partner;

import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** One receipt projection serves the ledger, lists and their related supplier parcels. */
final class SampleReceiptCorrections {
 private final PartnerStore store;
 SampleReceiptCorrections(PartnerStore store){this.store=store;}
 static boolean action(String action){return Set.of("correct_receipt","void_receipt").contains(action);}
 static String effectiveSql(String alias){
  return "coalesce(nullif("+alias+".data->>'effectiveReceivedQty','')::bigint,case when exists(select 1 from plm_partner_event receipt_undo join plm_partner_event original_receipt on original_receipt.record_id=receipt_undo.record_id and original_receipt.event_id::text=receipt_undo.data->>'targetEventId' and original_receipt.action in ('receive','opening') where receipt_undo.record_id="+alias+".record_id and receipt_undo.action='undo') then 0 else coalesce(nullif("+alias+".data->>'receivedQty','')::bigint,(select sum((original_receipt.data->>'qty')::bigint) from plm_partner_event original_receipt where original_receipt.record_id="+alias+".record_id and original_receipt.action in ('receive','opening')),0) end)";
 }
 static String activeSql(String alias){return "("+effectiveSql(alias)+">0)";}
 static long quantity(SampleLedger.State state){return state.receiptQuantities.values().stream().mapToLong(Long::longValue).sum();}
 static List<Long> requestedBatches(Map<String,Object> input){
  if(number(input.get("batchId"))>0)return List.of(number(input.get("batchId")));
  return maps(input.get("allocations")).stream().map(a->number(a.get("batchId"))).distinct().sorted().toList();
 }
 static void lockInbound(PartnerStore store,List<Long> batches){
  var shipmentIds=new TreeSet<Long>();for(long id:batches){var batch=store.load(id,"sample",false);long shipment=number(batch.get("supplierShipmentId"));if(shipment>0)shipmentIds.add(shipment);}
  for(long id:shipmentIds)SampleShipmentReceipts.lockInbound(store,id);
 }
 void enrich(Map<String,Object> batch,List<Map<String,Object>> events){
  enrich(batch,events,referenceBlocks(List.of(number(batch.get("id")))).contains(number(batch.get("id"))));
 }
 void enrich(Map<String,Object> batch,List<Map<String,Object>> events,boolean referenced){
  var state=SampleLedger.replay(events);long effective=quantity(state);batch.put("effectiveReceivedQty",effective);batch.put("receiptStatus",effective>0?"active":"voided");
  String issue=blockedReason(batch,state,referenced);if(effective==0)issue="这次登记已删除，原始记录保留";batch.put("canCorrectReceipt",issue.isEmpty());batch.put("receiptChangeBlockedReason",issue);
 }
 Set<Long> referenceBlocks(List<Long> batches){
  if(batches.isEmpty())return Set.of();String scope=encode(store.json,batches.stream().map(id->Map.of("id",id)).toList());
  return new HashSet<>(store.jdbc.queryForList("select b.id from jsonb_to_recordset(?::jsonb) b(id bigint) where exists(select 1 from plm_partner_record r where r.deleted_flag=0 and ((r.kind='inspection' and r.parent_record_id=b.id) or (r.kind='listing' and (r.data->'basis'->'pairs' @> jsonb_build_array(jsonb_build_object('sampleId',b.id)) or r.data->'pairs' @> jsonb_build_array(jsonb_build_object('sampleId',b.id))))))",Long.class,scope));
 }
 List<Map<String,Object>> selectionReceipts(long selection,List<String> rowIds){
  String scope=encode(store.json,rowIds.stream().map(row->Map.of("row_id",row)).toList());
  var found=store.jdbc.queryForList("select r.record_id,r.code,r.name,r.version,r.data::text from plm_partner_record r where r.kind='sample' and r.deleted_flag=0 and r.data->>'selectionId'=? and r.data->>'selectionRowId' in (select row_id from jsonb_to_recordset(?::jsonb) s(row_id text)) and "+store.access.visibleSqlClause("partner","r.record_id")+" order by r.record_id",Long.toString(selection),scope);
  if(found.isEmpty())return List.of();
  String batches=encode(store.json,found.stream().map(row->Map.of("id",number(row.get("record_id")))).toList());
  var events=new HashMap<Long,List<Map<String,Object>>>();
  for(var event:store.jdbc.queryForList("select record_id,event_id as id,action,data::text from plm_partner_event where record_id in (select id from jsonb_to_recordset(?::jsonb) b(id bigint)) order by event_id",batches)){
   event.put("data",decode(store.json,event.get("data")));events.computeIfAbsent(number(event.get("record_id")),id->new ArrayList<>()).add(event);
  }
  var referenced=referenceBlocks(found.stream().map(row->number(row.get("record_id"))).toList());var result=new ArrayList<Map<String,Object>>();
  for(var row:found){long id=number(row.get("record_id"));var batch=decode(store.json,row.get("data"));for(String field:List.of("code","name","version"))batch.put(field,row.get(field));batch.put("id",id);enrich(batch,events.getOrDefault(id,List.of()),referenced.contains(id));if(number(batch.get("effectiveReceivedQty"))>0)result.add(batch);}
  return result;
 }
 void voidSelectionReceipts(long selection,List<String> rowIds,List<Map<String,Object>> requested,String request,String reason){
  required(reason,"更正原因");var eligible=new HashMap<Long,Map<String,Object>>();for(var batch:selectionReceipts(selection,rowIds))eligible.put(number(batch.get("id")),batch);
  for(var item:requested.stream().sorted(Comparator.comparingLong(row->number(row.get("id")))).toList()){
   long id=number(item.get("id"));require(eligible.containsKey(id),"收样登记不属于本次取消的选品、已作废或未获授权，请重新核对");
   var batch=store.load(id,"sample",true);store.check(batch,number(item.get("version")));
   var command=Map.<String,Object>of("reason",reason,"requestId","selection-void-"+UUID.nameUUIDFromBytes((request+":"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
   correct(id,batch,"void_receipt",command,PartnerQualityRules.digest(store.json,Map.of("action","void_receipt","input",command)));
  }
 }
 private String blockedReason(Map<String,Object> batch,SampleLedger.State state,boolean referenced){
  if(state.receiptQuantities.size()!=1)return "这次历史登记包含多个收样来源，请在原流转记录核对";
  for(var event:state.originals.entrySet())if(!state.undone.contains(event.getKey())&&!Set.of("receive","opening","receipt_correction").contains(text(event.getValue().get("action"))))return "已有借出、移库或其他样品流转，请先在详情处理后续记录";
  if(maps(batch.get("custody")).size()>1)return "这次收样已有多处保管分配，请先在原记录核对保管数量";
  return !referenced?"":"已有质检或上架依据，不能删除或修改原收样登记，请在详情查看后续业务";
 }
 void correct(long id,Map<String,Object> batch,String action,Map<String,Object> input,String hash){
  String reason=required(input.get("reason"),"更正原因");
  var events=store.events(id);var state=SampleLedger.replay(events);String issue=blockedReason(batch,state,referenceBlocks(List.of(id)).contains(id));require(issue.isEmpty(),issue);long before=quantity(state);require(before>0,"这次登记已删除，不能重复操作");
  long after=action.equals("correct_receipt")?qty(input.get("qty"),false):0;long target=state.receiptQuantities.keySet().iterator().next();
  var command=new LinkedHashMap<String,Object>();command.put("date",java.time.LocalDate.now().toString());command.put("targetEventId",target);command.put("beforeQty",before);command.put("afterQty",after);command.put("reason",reason);
  var actual=SampleLedger.prepare(state,text(batch.get("code")),"receipt_correction",command);actual.put("requestHash",hash);actual.put("requestedAction",action);
  SampleShipmentReceipts.correctInbound(store,batch,before,after,reason,text(input.get("requestId")));
  store.revision(id,batch);store.event(id,"receipt_correction",actual,text(input.get("requestId")));batch.put("effectiveReceivedQty",after);batch.put("receiptStatus",after>0?"active":"voided");
  var custody=maps(batch.get("custody"));if(custody.size()==1){var position=new LinkedHashMap<>(custody.get(0));position.put("qty",after);batch.put("custody",List.of(position));}
  batch.put("balances",SampleLedger.replay(store.events(id)).view());store.save(id,batch);
 }
}

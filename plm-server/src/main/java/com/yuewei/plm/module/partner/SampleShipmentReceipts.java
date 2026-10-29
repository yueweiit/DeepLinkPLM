package com.yuewei.plm.module.partner;

import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** The existing actual-receipt transaction calls this once; it creates no inventory. */
public final class SampleShipmentReceipts {
 private SampleShipmentReceipts() {}
 public static void lockInbound(PartnerStore store,long shipmentId){require(shipmentId>0,"寄样包裹无效");var shipment=store.load(shipmentId,"sample_shipment",true);require("supplier_inbound".equals(shipment.get("direction")),"此包裹不是供应商寄样");}
 public static void recordInbound(PartnerStore store,long shipmentId,List<Map<String,Object>> batches,String requestId){
  require(shipmentId>0,"寄样包裹无效");
  var shipment=store.load(shipmentId,"sample_shipment",true);
  require("supplier_inbound".equals(shipment.get("direction")),"此包裹不是供应商寄样");
  String key="inbound-"+UUID.nameUUIDFromBytes((store.access.actorId()+":"+required(requestId,"收样提交标识")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  var digestRows=batches.stream().map(b->Map.of("batchId",number(b.get("id")),"qty",number(b.get("receivedQty")))).sorted(Comparator.comparingLong(b->number(b.get("batchId")))).toList();
  String hash=PartnerQualityRules.digest(store.json,digestRows);
  var previous=store.jdbc.queryForList("select action,data::text from plm_partner_event where record_id=? and request_id=?",shipmentId,key);
  if(!previous.isEmpty()){var saved=decode(store.json,previous.get(0).get("data"));require("sample_inbound_receive".equals(previous.get(0).get("action"))&&hash.equals(saved.get("requestHash")),"收样标识已有不同结果，请核对原包裹");return;}
  require(Set.of("dispatched","partially_received").contains(text(shipment.get("status"))),"供应商包裹尚未实际发出或已收结，不能重复入库");
  var dispatchedDate=java.time.LocalDate.parse(date(map(shipment.get("dispatch")).get("date")));
  var rows=SampleShipmentService.copyRows(store,shipment);var allocations=new ArrayList<Map<String,Object>>();
  for(var provided:batches.stream().sorted(Comparator.comparingLong(b->number(b.get("id")))).toList()){
   long batchId=number(provided.get("id"));var batch=store.load(batchId,"sample",true);
   require(!java.time.LocalDate.parse(date(batch.get("receivedDate"))).isBefore(dispatchedDate),"实际收样日期不能早于供应商发出日期");
   long linked=number(batch.get("supplierShipmentId"));require(linked==0||linked==shipmentId,"此实收批次已经属于其他寄样包裹");require(linked==0,"此实收批次已经计入原包裹，不能重复登记");
   require(number(shipment.get("selectionId"))==0||number(batch.get("selectionId"))==number(shipment.get("selectionId")),"实际收样来源与寄样包裹不一致");require(text(shipment.get("supplierCode")).isEmpty()||text(shipment.get("supplierCode")).equals(text(batch.get("supplierCode"))),"实际收样供方与寄样包裹不一致");
   var identities=store.jdbc.queryForList("select b.sample_identity_id,i.code from plm_partner_record b join plm_partner_record i on i.record_id=b.sample_identity_id and i.kind='sample_identity' and i.deleted_flag=0 where b.record_id=?",batchId);require(identities.size()==1,"请先关联实际样品主码");long identity=number(identities.get(0).get("sample_identity_id"));String sampleCode=required(identities.get(0).get("code"),"实际样品主码");
   long quantity=qty(batch.get("receivedQty"),false);String selectionRow=text(batch.get("selectionRowId"));
   var matching=rows.stream().filter(row->number(row.get("sampleIdentityId"))>0?SampleShipmentService.canonicalIdentity(store,number(row.get("sampleIdentityId")))==identity:!selectionRow.isEmpty()&&selectionRow.equals(text(row.get("selectionRowId")))&&(number(row.get("phoneModelId"))==0||number(row.get("phoneModelId"))==number(batch.get("phoneModelId")))&&(number(row.get("colorId"))==0||number(row.get("colorId"))==number(batch.get("colorId")))).sorted(Comparator.<Map<String,Object>>comparingInt(row->(number(row.get("sampleIdentityId"))>0?4:0)+(number(row.get("phoneModelId"))>0?1:0)+(number(row.get("colorId"))>0?1:0)).reversed()).toList();
   require(!matching.isEmpty(),"实收主码或来源行不在本包裹清单中");long remaining=quantity;
   for(var row:matching){long capacity=SampleShipmentService.remaining(row),used=Math.min(capacity,remaining);if(used==0)continue;row.put("receivedGood",add(number(row.get("receivedGood")),used));
    var allocation=new LinkedHashMap<String,Object>();allocation.put("rowId",row.get("rowId"));allocation.put("batchId",batchId);allocation.put("batchCode",batch.get("code"));allocation.put("sampleIdentityId",identity);allocation.put("sampleCode",sampleCode);allocation.put("qty",used);allocations.add(allocation);var received=new ArrayList<>(maps(row.get("receivedBatches")));received.add(allocation);row.put("receivedBatches",received);row.put("actualIdentityIds",received.stream().map(a->number(a.get("sampleIdentityId"))).distinct().toList());remaining-=used;if(remaining==0)break;
   }
   require(remaining==0,"实际收样数量超过本包裹尚未收取的发出数量");batch.put("supplierShipmentId",shipmentId);store.save(batchId,batch);
  }
  shipment.put("rows",rows);SampleShipmentService.setReceiptState(shipment);store.event(shipmentId,"sample_inbound_receive",Map.of("requestHash",hash,"batches",allocations),key);store.save(shipmentId,shipment);
 }
}

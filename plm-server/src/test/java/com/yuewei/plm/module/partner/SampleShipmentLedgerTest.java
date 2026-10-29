package com.yuewei.plm.module.partner;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

class SampleShipmentLedgerTest {
 private final List<Map<String,Object>> events=new ArrayList<>();
 private long next=1;
 private Map<String,Object> data(Object... values){var d=new LinkedHashMap<String,Object>();d.put("date","2026-01-01");for(int i=0;i<values.length;i+=2)d.put(values[i].toString(),values[i+1]);return d;}
 private long add(String action,Map<String,Object> input){long id=next++;var actual=SampleLedger.prepare(state(),"SP-000001",action,input);events.add(Map.of("id",id,"action",action,"data",actual));return id;}
 private SampleLedger.State state(){return SampleLedger.replay(events);}
 private long receive(long qty){return add("receive",data("qty",qty,"location","深圳办公室"));}
 private long dispatch(long qty,long parcel){return add("dispatch",data("qty",qty,"location","深圳办公室","shipmentId",parcel,"shipmentRowId","r1","destination","墨西哥办公室"));}
 @Test void dispatchSeparatesTransitFromOnhandAndDoesNotCreateBorrowableVirtualStock(){
  receive(10);dispatch(4,70);assertThat(state().view()).containsEntry("onhand",6L).containsEntry("available",6L).containsEntry("inTransit",4L);
  assertThat(maps(state().view().get("positions"))).allMatch(p->!text(p.get("name")).equals("在途"));
  assertThatThrownBy(()->add("loan",data("qty",1,"location","在途","person","小周","due","2026-01-02"))).hasMessageContaining("数量不足");
 }
 @Test void borrowedRetainedAndDamagedStockCannotBeDispatched(){
  receive(10);add("retain",data("qty",2,"location","深圳办公室"));add("loan",data("qty",3,"location","深圳办公室","person","小周","due","2026-01-02"));
  assertThat(state().view()).containsEntry("available",5L);
  assertThatThrownBy(()->dispatch(6,70)).hasMessageContaining("数量不足");dispatch(5,70);
  assertThat(state().view()).containsEntry("onhand",2L).containsEntry("retained",2L).containsEntry("borrowed",3L).containsEntry("inTransit",5L).containsEntry("available",0L);
 }
 @Test void oneBatchMayHaveTwoParcelsAndPartialGoodDamagedAndLossRemainConserved(){
  receive(10);long first=dispatch(4,70);dispatch(3,71);
  add("dispatch_receive",data("dispatchEventId",first,"goodQty",2,"damagedQty",1,"location","墨西哥办公室"));
  assertThat(state().view()).containsEntry("onhand",6L).containsEntry("available",5L).containsEntry("damaged",1L).containsEntry("inTransit",4L);
  add("dispatch_loss",data("dispatchEventId",first,"qty",1,"reason","包裹短少已确认"));assertThat(state().view()).containsEntry("inTransit",3L);
  assertThat(maps(state().view().get("transits"))).filteredOn(t->number(t.get("id"))==first).singleElement().satisfies(t->assertThat(t).containsEntry("receivedGood",2L).containsEntry("receivedDamaged",1L).containsEntry("confirmedLoss",1L).containsEntry("outstanding",0L));
 }
 @Test void receiptsAndLossCannotExceedTheExactOriginalDispatch(){
  receive(10);long first=dispatch(4,70);long second=dispatch(2,71);
  add("dispatch_receive",data("dispatchEventId",first,"goodQty",3,"damagedQty",0,"location","墨西哥办公室"));
  assertThatThrownBy(()->add("dispatch_loss",data("dispatchEventId",first,"qty",2,"reason","核实短少"))).hasMessageContaining("在途剩余");
  assertThatThrownBy(()->add("dispatch_receive",data("dispatchEventId",second,"goodQty",3,"location","墨西哥办公室"))).hasMessageContaining("在途剩余");
 }
 @Test void receiptCorrectionReturnsTheSamePiecesToTransitWithoutAnotherBatch(){
  receive(5);long sent=dispatch(5,70);long arrived=add("dispatch_receive",data("dispatchEventId",sent,"goodQty",2,"damagedQty",1,"location","墨西哥办公室"));
  add("undo",data("targetEventId",arrived,"reason","实际只开箱核对，误记签收"));assertThat(state().view()).containsEntry("onhand",0L).containsEntry("inTransit",5L);
  add("dispatch_receive",data("dispatchEventId",sent,"goodQty",5,"location","墨西哥办公室"));assertThat(state().view()).containsEntry("onhand",5L).containsEntry("inTransit",0L);
 }
 @Test void aReceiptAlreadyUsedByALoanCannotBeSilentlyUndone(){
  receive(5);long sent=dispatch(5,70);long arrived=add("dispatch_receive",data("dispatchEventId",sent,"goodQty",3,"location","墨西哥办公室"));
  add("loan",data("qty",2,"location","墨西哥办公室","person","Carlos","due","2026-01-02"));
  assertThatThrownBy(()->add("undo",data("targetEventId",arrived,"reason","误录"))).hasMessageContaining("数量不足");
  assertThatThrownBy(()->add("undo",data("targetEventId",sent,"reason","误录发出"))).hasMessageContaining("已有目的地签收");
 }
 @Test void oldMovementsAndOldReceiptUndoKeepTheirOriginalMeaning(){
  long received=receive(5);add("move",data("qty",2,"location","深圳办公室","to","工程桌"));assertThat(state().view()).containsEntry("onhand",5L).containsEntry("inTransit",0L);
  assertThatThrownBy(()->add("undo",data("targetEventId",received,"reason","旧收样纠错"))).hasMessageContaining("数量不足");
 }
 @Test void shipmentCountsKeepUnknownDraftQuantityOutOfInventoryAndOnlyCloseWhenAccounted(){
  var first=data("rowId","a","qty",5L,"receivedGood",2L,"receivedDamaged",1L,"confirmedLoss",0L);var second=data("rowId","b","qty",3L,"receivedGood",0L,"receivedDamaged",0L,"confirmedLoss",0L);
  var parcel=new LinkedHashMap<String,Object>(Map.of("rows",List.of(first,second),"status","dispatched"));SampleShipmentService.setReceiptState(parcel);assertThat(parcel).containsEntry("status","partially_received");assertThat(SampleShipmentService.totals(parcel)).containsEntry("dispatched",8L).containsEntry("inTransit",5L);
  first.put("confirmedLoss",2L);second.put("receivedGood",3L);SampleShipmentService.setReceiptState(parcel);assertThat(parcel).containsEntry("status","received");assertThat(SampleShipmentService.totals(parcel)).containsEntry("inTransit",0L);
 }
}

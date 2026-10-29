package com.yuewei.plm.module.partner;

import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Append-only real fee facts; deductions are not cash receipts. */
final class SampleFeeLedger {
 private SampleFeeLedger(){}
 static Map<String,Object> balances(List<Map<String,Object>> events){
  Set<Long> reversed=new HashSet<>();for(var e:events)if(text(e.get("action")).equals("fee_reverse"))reversed.add(number(map(e.get("data")).get("originalEventId")));
  long charged=0,refunded=0,offset=0;
  for(var e:events){if(reversed.contains(number(e.get("id"))))continue;long amount=number(map(e.get("data")).get("amountCents"));switch(text(e.get("action"))){case "fee_charge"->charged=add(charged,amount);case "fee_refund"->refunded=add(refunded,amount);case "fee_offset"->offset=add(offset,amount);default->{}}}
  require(charged>=add(refunded,offset),"更正后抵退超过原实际样品费，须先更正依赖记录");
  return Map.of("chargedCents",charged,"refundedCents",refunded,"offsetCents",offset,"remainingCents",charged-refunded-offset);
 }
 static Map<String,Object> prepare(String action,Map<String,Object> input,List<Map<String,Object>> events){
  require(Set.of("charge","refund","offset","reverse","agreement").contains(action),"样品费用动作无效");
  var d=new LinkedHashMap<String,Object>();d.put("date",date(input.get("date")));d.put("reason",required(input.get("reason"),"实际约定／办理说明"));
  d.put("refs",maps(input.get("refs")));d.put("evidence",text(input.get("evidence")));
  if(action.equals("agreement")){d.put("terms",required(input.get("terms"),"本次样品费约定"));return d;}
  if(action.equals("reverse")){
   long id=number(input.get("originalEventId"));var original=events.stream().filter(e->number(e.get("id"))==id&&Set.of("fee_charge","fee_refund","fee_offset").contains(text(e.get("action")))).findFirst().orElseThrow(()->invalid("请选择本笔费用的原实际办理记录"));
   require(events.stream().noneMatch(e->text(e.get("action")).equals("fee_reverse")&&number(map(e.get("data")).get("originalEventId"))==id),"原记录已更正，不能重复冲回");
   d.put("originalEventId",id);var next=new ArrayList<>(events);next.add(Map.of("id",-1,"action","fee_reverse","data",d));balances(next);return d;
  }
  require(!d.get("evidence").equals("")||!maps(d.get("refs")).isEmpty(),"请引用真实凭据或供应商确认依据");
  long amount=cents(input.get("amount"));require(amount>0,"实际金额须大于0");d.put("amountCents",amount);
  d.put("payer",required(input.get("payer"),"实际付款／承担方"));d.put("payee",required(input.get("payee"),"实际收款／抵扣方"));d.put("executor",required(input.get("executor"),"实际经办岗位／人员"));
  if(!action.equals("charge"))require(amount<=number(balances(events).get("remainingCents")),"超过本笔尚未抵退的实际样品费，不能重复抵扣或退款");
  if(action.equals("offset"))d.put("bulkReference",required(input.get("bulkReference"),"本次抵扣对应的大货结算依据"));
  return d;
 }
}

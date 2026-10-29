package com.yuewei.plm.module.partner;
import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;
/** Calculations use integer minor units. Commit and correction events are never overwritten. */
public final class SalesLedger {
 private SalesLedger(){}
 public static long amount(List<Map<String,Object>> events,String action,String supplier){long n=0;for(var e:events)if(action.equals(e.get("action"))&&(supplier==null||supplier.equals(map(e.get("data")).get("supplierCode"))))n=add(n,number(map(e.get("data")).get("amountCents")));return n;}
 public static long quantity(List<Map<String,Object>> events,String action,String line){return events.stream().filter(e->action.equals(e.get("action"))&&line.equals(map(e.get("data")).get("lineId"))).mapToLong(e->number(map(e.get("data")).get("qty"))).sum();}
 public static long refundedReturns(List<Map<String,Object>> events,String line){return events.stream().filter(e->text(e.get("action")).equals("returngoods")&&text(map(e.get("data")).get("lineId")).equals(line)&&text(map(e.get("data")).getOrDefault("disposition","refund")).equals("refund")).mapToLong(e->number(map(e.get("data")).get("qty"))).sum();}
 public static long total(Map<String,Object> sale,List<Map<String,Object>> events){long total=0;for(var l:maps(sale.get("lines"))){String id=text(l.get("id"));long q=number(l.get("qty"))-quantity(events,"cancel",id)-refundedReturns(events,id);require(q>=0,"取消与退款退货数量超过订单");total=add(total,multiply(q,number(l.get("priceCents"))));}return total;}
 public static Map<String,Object> settlement(Map<String,Object> sale,List<Map<String,Object>> events,String supplier){
  var agreement=maps(sale.get("agreements")).stream().filter(a->supplier.equals(a.get("supplierCode"))).findFirst().orElse(Map.of());
  long offset=events.stream().filter(e->text(e.get("action")).equals("supplierpay")&&supplier.equals(map(e.get("data")).get("supplierCode"))).mapToLong(e->number(map(e.get("data")).get("offsetCents"))).sum()-amount(events,"offsetreverse",supplier);
  long paid=amount(events,"supplierpay",supplier)-amount(events,"supplierrefund",supplier),fees=amount(events,"feereceipt",supplier)-amount(events,"feerefund",supplier);
  return Map.of("agreement",agreement,"paidCents",paid,"offsetCents",offset,"feeReceivedCents",fees,"payRemainingCents",number(agreement.get("grossCents"))-paid-offset,"feeRemainingCents",number(agreement.get("feeCents"))-fees-offset);
 }
 public static Map<String,Object> stats(Map<String,Object> sale,List<Map<String,Object>> events){
  long paid=amount(events,"receipt",null)-amount(events,"refund",null),total=total(sale,events),ordered=0,sent=0,returned=0;
  for(var l:maps(sale.get("lines"))){String line=text(l.get("id"));ordered+=number(l.get("qty"))-quantity(events,"cancel",line);sent+=quantity(events,"ship",line)-quantity(events,"shipreverse",line);returned+=quantity(events,"returngoods",line);}
  List<Map<String,Object>> settlements=maps(sale.get("lines")).stream().map(l->text(l.get("supplierCode"))).distinct().map(code->{var d=new LinkedHashMap<>(settlement(sale,events,code));d.put("supplierCode",code);return (Map<String,Object>)d;}).toList();
  return Map.of("totalCents",total,"paidCents",paid,"customerRemainingCents",total-paid,"ordered",ordered,"shipped",sent,"returned",returned,"settlements",settlements);
 }
 public static long appliedTo(List<Map<String,Object>> events,String action,long original){return events.stream().filter(e->action.equals(e.get("action"))&&number(map(e.get("data")).get("originalEventId"))==original).mapToLong(e->number(map(e.get("data")).get("amountCents"))).sum();}
 public static Map<String,Object> original(List<Map<String,Object>> events,long id,String action){return events.stream().filter(e->number(e.get("id"))==id&&action.equals(e.get("action"))).map(e->map(e.get("data"))).findFirst().orElseThrow(()->invalid("请选择本单对应的原办理记录"));}
}

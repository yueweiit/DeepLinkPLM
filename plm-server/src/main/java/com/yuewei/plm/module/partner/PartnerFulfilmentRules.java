package com.yuewei.plm.module.partner;

import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Actual releases are allocated once; a truck leaving never means the overseas receipt is complete. */
final class PartnerFulfilmentRules {
 private PartnerFulfilmentRules(){}
 static long allocated(List<Map<String,Object>> events,long inspection,long spec){return events.stream().filter(e->text(e.get("action")).equals("dispatch")).flatMap(e->maps(map(e.get("data")).get("allocations")).stream()).filter(a->number(a.get("inspectionId"))==inspection&&number(a.get("specId"))==spec).mapToLong(a->number(a.get("qty"))).sum();}
 static Map<String,Object> dispatch(Map<String,Object> input,List<Map<String,Object>> events,Map<Long,Map<String,Object>> inspections){
  var allocations=new ArrayList<Map<String,Object>>();Set<String> seen=new HashSet<>();
  for(var raw:maps(input.get("allocations"))){long id=number(raw.get("inspectionId")),spec=number(raw.get("specId")),qty=qty(raw.get("qty"),false);require(seen.add(id+":"+spec),"同次发运的放行分配不能重复");var qc=inspections.get(id);require(qc!=null&&text(qc.get("status")).equals("signed"),"必须引用本批工程现场检验且工厂已签字的放行记录");
   var scope=maps(map(qc.get("resultSnapshot")).get("scope")).stream().filter(s->number(s.get("specId"))==spec).findFirst().orElseThrow(()->invalid("本规格未在放行范围内"));
   require(qty<=number(scope.get("releaseQty"))-allocated(events,id,spec),"超过尚未使用的放行数量，不能重复发运");allocations.add(Map.of("inspectionId",id,"specId",spec,"qty",qty,"specName",text(scope.get("specName"))));
  }
  require(!allocations.isEmpty(),"请选择本次放行分配");
  return new LinkedHashMap<>(Map.of("allocations",allocations,"date",date(input.get("date")),"tracking",required(input.get("tracking"),"运单／物流依据"),"destination",required(input.get("destination"),"约定实际收货点"),"evidence",required(input.get("evidence"),"装箱／工厂出货资料依据"),"refs",maps(input.get("refs"))));
 }
 static Map<String,Object> receipt(Map<String,Object> input,List<Map<String,Object>> events){
  long dispatch=number(input.get("dispatchId")),spec=number(input.get("specId"));
  var shipment=events.stream().filter(e->number(e.get("id"))==dispatch&&text(e.get("action")).equals("dispatch")).findFirst().orElseThrow(()->invalid("请选择本单真实发运记录"));
  long sent=maps(map(shipment.get("data")).get("allocations")).stream().filter(a->number(a.get("specId"))==spec).mapToLong(a->number(a.get("qty"))).sum();
  long accounted=events.stream().filter(e->text(e.get("action")).equals("receipt")&&number(map(e.get("data")).get("dispatchId"))==dispatch&&number(map(e.get("data")).get("specId"))==spec).mapToLong(e->add(number(map(e.get("data")).get("qty")),number(map(e.get("data")).get("shortage")))).sum();
  long qty=qty(input.get("qty"),true),shortage=qty(input.get("shortage"),true);require(add(qty,shortage)>0&&add(qty,shortage)<=sent-accounted,"收货／短少合计超过本次发运剩余数量");
  if(shortage>0)required(input.get("difference"),"短少／破损差异说明");
  return new LinkedHashMap<>(Map.of("dispatchId",dispatch,"specId",spec,"qty",qty,"shortage",shortage,"difference",text(input.get("difference")),"date",date(input.get("date")),"evidence",required(input.get("evidence"),"实际收货依据"),"refs",maps(input.get("refs"))));
 }
}

package com.yuewei.plm.module.partner;

import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Physical custody is independent from research acceptance and commercial fulfillment. */
public final class SampleLedger {
 public record Position(long good,long retained,long damaged) {}
 public static final class State {
  final Map<String,long[]> positions=new LinkedHashMap<>();
  final Map<Long,Map<String,Object>> loans=new LinkedHashMap<>();
  final Map<Long,Map<String,Object>> transits=new LinkedHashMap<>();
  final Map<String,String> retainedIds=new LinkedHashMap<>();
  final Map<Long,Map<String,Object>> originals=new HashMap<>();
  final Set<Long> undone=new HashSet<>();
  long[] pos(String name){return positions.computeIfAbsent(name,k->new long[3]);}
  public long available(String p){var s=pos(p);return s[0]-s[1];}
  public Map<String,Object> view(){
   long good=0,reserved=0,damaged=0,borrowed=0;List<Map<String,Object>> locations=new ArrayList<>();
   for(var entry:positions.entrySet()){var a=entry.getValue();good+=a[0];reserved+=a[1];damaged+=a[2];locations.add(Map.of("name",entry.getKey(),"good",a[0],"retained",a[1],"damaged",a[2],"onhand",a[0]+a[2],"available",a[0]-a[1]));}
   for(var loan:loans.values())borrowed+=number(loan.get("outstanding"));
   long inTransit=0;for(var transit:transits.values())inTransit=add(inTransit,number(transit.get("outstanding")));
   var out=new LinkedHashMap<String,Object>();out.put("onhand",good+damaged);out.put("available",good-reserved);out.put("retained",reserved);out.put("damaged",damaged);out.put("borrowed",borrowed);out.put("positions",locations);out.put("loans",new ArrayList<>(loans.values()));out.put("retainedIds",retainedIds);out.put("inTransit",inTransit);out.put("transits",new ArrayList<>(transits.values()));return out;
  }
  void valid(){for(var p:positions.values())require(p[0]>=0&&p[1]>=0&&p[2]>=0&&p[1]<=p[0],"该位置数量不足，留样和损坏品不能按普通好品处理");for(var loan:loans.values())require(number(loan.get("outstanding"))>=0&&number(loan.get("outstanding"))<=number(loan.get("qty")),"超出原借用待还数量，或更正依赖尚未处理");for(var t:transits.values()){long remaining=number(t.get("outstanding")),good=number(t.get("receivedGood")),bad=number(t.get("receivedDamaged")),loss=number(t.get("confirmedLoss"));require(remaining>=0&&good>=0&&bad>=0&&loss>=0&&add(add(remaining,good),add(bad,loss))==number(t.get("qty")),"超过本次在途剩余数量，或更正依赖尚未处理");}}
 }
 public static State replay(List<Map<String,Object>> events){
  State s=new State();for(var event:events){String action=text(event.get("action"));if(Set.of("sample_save","file_add","source_resolve","custody","color_correction").contains(action))continue;apply(s,number(event.get("id")),action,map(event.get("data")),1);s.valid();}return s;
 }
 public static Map<String,Object> prepare(State s,String code,String action,Map<String,Object> input){
  Map<String,Object> d=new LinkedHashMap<>();for(String key:List.of("qty","date","location","condition","to","person","due","loanId","projectId","projectName","customerId","customerName","damaged","reason","actual","targetEventId","refs","shipmentId","shipmentRowId","dispatchEventId","goodQty","damagedQty","destination"))if(input.containsKey(key))d.put(key,input.get(key));if(Set.of("receive","opening").contains(action)&&input.containsKey("sourceSnapshot"))d.put("sourceSnapshot",input.get("sourceSnapshot"));d.put("date",date(d.get("date")));
  if(action.equals("undo")){long target=number(d.get("targetEventId"));require(s.originals.containsKey(target)&&!s.undone.contains(target),"原记录不存在或已更正");required(d.get("reason"),"更正原因");apply(s,-1,action,d,1);s.valid();return d;}
  require(Set.of("receive","opening","retain","move","loan","return","gift","consume","scrap","loss","count","dispatch","dispatch_receive","dispatch_loss").contains(action),"样品动作无效");
  if(action.equals("dispatch_receive")){long good=qty(d.getOrDefault("goodQty",0),true),damaged=qty(d.getOrDefault("damagedQty",0),true);require(add(good,damaged)>0,"请填写本次实际收到的数量");d.put("goodQty",good);d.put("damagedQty",damaged);d.put("qty",add(good,damaged));}
  long q=action.equals("count")?0:qty(d.get("qty"),false);d.put("qty",q);
  if(!Set.of("loss","dispatch_loss").contains(action))d.put("location",required(d.get("location"),"存放／取出位置"));
  String location=text(d.get("location")),condition=text(d.getOrDefault("condition","good"));require(Set.of("good","retained","damaged").contains(condition),"样品状态无效");d.put("condition",condition);
  if(action.equals("dispatch")){require(condition.equals("good"),"寄样仅能从实际可用好品扣出，不能挪用留样或坏品");require(number(d.get("shipmentId"))>0,"请从寄样包裹办理实际发出");required(d.get("shipmentRowId"),"寄样明细");}
  if(Set.of("dispatch_receive","dispatch_loss").contains(action))require(number(d.get("dispatchEventId"))>0,"请关联原实际发出记录");
  if(action.equals("dispatch_loss"))required(d.get("reason"),"在途短少确认原因");
  if(action.equals("loan")){required(d.get("person"),"接收人");String due=required(d.get("due"),"预计归还日期");try{require(!java.time.LocalDate.parse(due).isBefore(java.time.LocalDate.parse(text(d.get("date")))),"预计归还不能早于借出日期");}catch(java.time.format.DateTimeParseException e){throw invalid("预计归还日期无效");}}
  if(action.equals("move"))require(!required(d.get("to"),"目标位置").equals(location),"目标位置须不同");
  if(action.equals("return")){long damaged=qty(d.getOrDefault("damaged",0),true);require(damaged<=q,"损坏数量不能超过归还数量");d.put("damaged",damaged);}
  if(Set.of("gift","consume","scrap","loss","count").contains(action))required(d.get("reason"),"接收人、用途或原因");
  if(action.equals("retain"))require(q<=1000,"单次确认留样最多1000件");
  if(action.equals("retain"))d.put("retainedIds",java.util.stream.LongStream.range(0,q).mapToObj(n->code+"-L-"+UUID.randomUUID().toString().substring(0,8)).toList());
  if((action.equals("move")||action.equals("scrap"))&&condition.equals("retained")){
   List<String> ids=s.retainedIds.entrySet().stream().filter(e->e.getValue().equals(location)).map(Map.Entry::getKey).limit(q).toList();require(ids.size()==q,"该位置留样数量不足");d.put("retainedIds",ids);
  }
  if(action.equals("count")){long actual=qty(d.get("actual"),true);d.put("before",s.pos(location)[0]);d.put("delta",actual-s.pos(location)[0]);require(number(d.get("delta"))!=0,"账实一致，无需更正");}
  apply(s,-1,action,d,1);s.valid();return d;
 }
 private static List<String> ids(Object value){return value instanceof List<?> list?list.stream().map(PartnerValues::text).toList():List.of();}
 private static void apply(State s,long eventId,String action,Map<String,Object> d,int sign){
  if(action.equals("undo")){
   long target=number(d.get("targetEventId"));require(sign==1&&!s.undone.contains(target),"不能重复冲正");var original=s.originals.get(target);require(original!=null,"原流转不存在");
   apply(s,target,text(original.get("action")),map(original.get("data")),-1);s.undone.add(target);return;
  }
  if(sign==1&&eventId>0)s.originals.put(eventId,Map.of("action",action,"data",new LinkedHashMap<>(d)));
  long q=number(d.get("qty"))*sign;String from=text(d.get("location")),condition=text(d.getOrDefault("condition","good"));
  if(Set.of("dispatch_receive","dispatch_loss").contains(action)){
   var transit=s.transits.get(number(d.get("dispatchEventId")));require(transit!=null,"原寄样在途记录不存在");transit.put("outstanding",number(transit.get("outstanding"))-q);
   if(action.equals("dispatch_receive")){long good=number(d.get("goodQty"))*sign,damaged=number(d.get("damagedQty"))*sign;var target=s.pos(from);target[0]+=good;target[2]+=damaged;transit.put("receivedGood",number(transit.get("receivedGood"))+good);transit.put("receivedDamaged",number(transit.get("receivedDamaged"))+damaged);}
   else transit.put("confirmedLoss",number(transit.get("confirmedLoss"))+q);return;
  }
  long[] p=s.pos(from);
  switch(action){
   case "receive","opening" -> p[0]+=q;
   case "dispatch" -> {p[0]-=q;if(sign==1){var t=new LinkedHashMap<String,Object>(d);t.put("id",eventId);t.put("outstanding",q);t.put("receivedGood",0L);t.put("receivedDamaged",0L);t.put("confirmedLoss",0L);s.transits.put(eventId,t);}else{var t=s.transits.get(eventId);require(t!=null&&number(t.get("outstanding"))==number(d.get("qty")),"已有目的地签收或短少，请先处理后续寄样记录");s.transits.remove(eventId);}}
   case "retain" -> {p[1]+=q;for(String id:ids(d.get("retainedIds"))){if(sign==1){require(!s.retainedIds.containsKey(id),"留样编号重复");s.retainedIds.put(id,from);}else{require(from.equals(s.retainedIds.get(id)),"留样已流转，请先处理后续记录");s.retainedIds.remove(id);}}}
   case "move" -> {var to=s.pos(text(d.get("to")));int index=condition.equals("damaged")?2:0;p[index]-=q;to[index]+=q;if(condition.equals("retained")){p[1]-=q;to[1]+=q;for(String id:ids(d.get("retainedIds"))){String source=sign==1?from:text(d.get("to"));require(source.equals(s.retainedIds.get(id)),"留样已移位，不能覆盖后续流转");s.retainedIds.put(id,sign==1?text(d.get("to")):from);}}}
   case "loan" -> {p[0]-=q;if(sign==1){var loan=new LinkedHashMap<>(d);loan.put("id",eventId);loan.put("outstanding",q);s.loans.put(eventId,loan);}else{var loan=s.loans.get(eventId);require(loan!=null&&number(loan.get("outstanding"))==number(d.get("qty")),"借用已有归还或损失，请先处理后续记录");loan.put("outstanding",0);}}
   case "return","loss" -> {long loanId=number(d.get("loanId"));var loan=s.loans.get(loanId);require(loan!=null,"原借用不存在");loan.put("outstanding",number(loan.get("outstanding"))-q);if(action.equals("return")){long damaged=number(d.get("damaged"))*sign;p[0]+=q-damaged;p[2]+=damaged;}}
   case "gift","consume" -> p[0]-=q;
   case "scrap" -> {p[condition.equals("damaged")?2:0]-=q;if(condition.equals("retained")){p[1]-=q;for(String id:ids(d.get("retainedIds"))){if(sign==1){require(from.equals(s.retainedIds.get(id)),"留样位置不符");s.retainedIds.remove(id);}else{require(!s.retainedIds.containsKey(id),"留样已有后续记录");s.retainedIds.put(id,from);}}}}
   case "count" -> p[0]+=number(d.get("delta"))*sign;
   default -> throw invalid("未知样品流转："+action);
  }
 }
}

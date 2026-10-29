package com.yuewei.plm.module.partner;

import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Actual receipt text resolves to the existing supplier-colour identity, never a company colour code. */
final class PartnerReceiptColors {
 private final PartnerStore store;
 PartnerReceiptColors(PartnerStore store){this.store=store;}
 record Resolution(long id,String name,boolean proposed){
  String state(){return proposed?"new":"matched";}
  String duplicateKey(){return id>0?"id:"+id:"name:"+fold(name);}
 }
 static String fold(String value){return text(value).strip().toLowerCase(Locale.ROOT);}
 static String name(Object value){
  String result=text(value).strip();require(result.length()<=255,"实收颜色名称不能超过255字");
  require(result.codePoints().noneMatch(Character::isISOControl),"一条实收规格只填写一个颜色，不要粘贴多行");return result;
 }
 /** Acquired before snapshot colour locks; generic colour maintenance takes the supplier UPDATE lock. */
 void lockNames(long selection,Map<String,Object> input){
  var keys=new TreeSet<String>();
  for(var row:maps(input.get("rows"))){String value=name(row.get("colorName"));if(value.isEmpty())continue;
   String rowId=text(row.get("rowId"));require(PartnerSelectionService.validRowId(rowId),"来源选样行标识无效");var source=new PartnerSelectionRows(store).find(selection,rowId,false);
   String supplier=text(source.get("supplierCode"));if(!supplier.isEmpty())keys.add("sample-receipt-color:"+supplier+":"+fold(value));
  }
  for(String key:keys)store.jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))",key);
 }
 static boolean atReceipt(Map<String,Object> snapshot){return "at_receipt".equals(text(snapshot.get("receiptColorMode")))&&maps(snapshot.get("selectedPairs")).isEmpty();}
 static List<Map<String,Object>> choices(Map<String,Object> snapshot,long phone){
  if(atReceipt(snapshot))return maps(snapshot.get("receiptColors"));
  var result=new LinkedHashMap<Long,Map<String,Object>>();
  for(var pair:maps(snapshot.get("selectedPairs")))if(phone<=0||number(pair.get("phoneModelId"))==phone){
   var color=new LinkedHashMap<>(map(pair.get("colorSnapshot")));color.put("id",number(pair.get("colorId")));
   color.put("name",text(pair.getOrDefault("colorName",color.get("name"))));result.put(number(pair.get("colorId")),color);
  }
  return new ArrayList<>(result.values());
 }
 Resolution resolve(Map<String,Object> snapshot,long phone,Map<String,Object> row){
  String value=name(row.get("colorName"));long id=number(row.get("colorId"));var allowed=choices(snapshot,phone);
  if(id>0){var existing=allowed.stream().filter(c->number(c.get("id"))==id).findFirst().orElseThrow(()->invalid("所选颜色不属于本供应商、本款配置或已选范围，请在本行重新填写"));
   String actual=required(existing.get("name"),"实际颜色");require(value.isEmpty()||fold(value).equals(fold(actual)),"填写的颜色与所选颜色不一致，请重新输入或选择本行颜色");return new Resolution(id,actual,false);
  }
  required(value,"实收颜色");var matched=allowed.stream().filter(c->fold(text(c.get("name"))).equals(fold(value))).toList();
  require(matched.size()<=1,"这个颜色名称对应多条供方记录，请在本行选择对应颜色");
  if(matched.size()==1)return new Resolution(number(matched.get(0).get("id")),text(matched.get(0).get("name")),false);
  require(atReceipt(snapshot),"这个颜色不在原选样确认范围内，请选择本行已有颜色");
  var inactive=store.jdbc.queryForList("select color_id from plm_supplier_color where supplier_code=? and lower(name)=lower(?) and status='inactive' and (style_key='' or style_key=?) and (config_key='' or config_key=?)",snapshot.get("supplierCode"),value,text(snapshot.get("styleKey")),text(snapshot.get("configKey")));
  require(inactive.isEmpty(),"这个供方颜色已停用，不能通过重新输入名称启用，请核对原颜色记录");
  return new Resolution(0,value,true);
 }
 /** Called only after every receipt row has passed read-only validation, inside its receipt transaction. */
 Resolution persist(long selection,Map<String,Object> snapshot,long phone,Map<String,Object> row,String request){
  return persist(selection,snapshot,phone,row,request,"实际收样时记录颜色");
 }
 Resolution persistCorrection(long identity,Map<String,Object> snapshot,long phone,Map<String,Object> row,String request){return persist(identity,snapshot,phone,row,request,"更正样品颜色时记录实际颜色");}
 private Resolution persist(long selection,Map<String,Object> snapshot,long phone,Map<String,Object> row,String request,String purpose){
  var resolved=resolve(snapshot,phone,row);if(!resolved.proposed())return resolved;
  String supplier=required(snapshot.get("supplierCode"),"实际供应商"),style=text(snapshot.get("styleKey")),config=text(snapshot.get("configKey"));
  // The pre-acquired name lock serialises other receipt sheets. The existing supplier SHARE lock
  // also excludes the supplier editor's UPDATE lock, without a SHARE-to-UPDATE lock upgrade.
  var data=new LinkedHashMap<String,Object>();data.put("standardColorId",0);data.put("refs",List.of());
  data.put("notes","");data.put("recordedAtSampleReceipt",true);
  long id=store.jdbc.queryForObject("insert into plm_supplier_color(supplier_code,code,name,supplier_color_code,style_key,config_key,status,data,created_by,updated_by) values(?,?,?,'',?,?,'active',?::jsonb,?,?) returning color_id",Long.class,supplier,UUID.randomUUID().toString(),resolved.name(),style,config,encode(store.json,data),store.access.actor().username(),store.access.actor().username());
  String code="SC-"+String.format(Locale.ROOT,"%06d",id);store.jdbc.update("update plm_supplier_color set code=? where color_id=?",code,id);
  var saved=new LinkedHashMap<>(data);saved.putAll(Map.of("id",id,"code",code,"name",resolved.name(),"supplierCode",supplier,"supplierColorCode","","styleKey",style,"configKey",config,"status","active","version",1L));
  store.jdbc.update("insert into plm_supplier_color_version(color_id,version,snapshot,created_by) values(?,1,?::jsonb,?)",id,encode(store.json,saved),store.access.actor().username());
  String event="receipt-color-"+UUID.nameUUIDFromBytes((store.access.actorId()+":"+request+":"+supplier+":"+id).getBytes(StandardCharsets.UTF_8));
  store.event(selection,"sample_receipt_color",Map.of("supplierCode",supplier,"color",saved,"selectionRowId",text(row.get("rowId")),"reason",purpose,"createsStandardColor",false),event);
  return new Resolution(id,resolved.name(),false);
 }
}

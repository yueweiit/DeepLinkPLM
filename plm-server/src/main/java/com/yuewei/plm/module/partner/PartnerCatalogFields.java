package com.yuewei.plm.module.partner;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Shared quote input contract. Raw supplier evidence is never replaced by business defaults. */
public final class PartnerCatalogFields {
 private PartnerCatalogFields(){}
 public static final List<String> FIELDS=List.of("priceUnit","moqQuantity","moqUnit","moqScope","moqNote","moqSource","currencySource","rawQuoteFields");
 private static final Pattern MOQ=Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*(个|件|套|只|pcs?|pieces?)\\s*[/／]\\s*(每颜色型号|每型号颜色|每型号每颜色|每型号|每颜色|整款|整单)\\s*$",Pattern.CASE_INSENSITIVE);
 public static Map<String,Object> normalize(Map<String,Object> previous,Map<String,Object> input,boolean businessDefault){
  var out=new LinkedHashMap<String,Object>();
  for(String field:FIELDS)if(previous.containsKey(field))out.put(field,previous.get(field));
  var raw=new LinkedHashMap<>(map(previous.get("rawQuoteFields")));
  for(String key:List.of("currency","moq","unitPrice"))if(!raw.containsKey(key)&&(input.containsKey(key)||previous.containsKey(key))&&(input.containsKey(key)||!previous.containsKey("rawQuoteFields")||!text(previous.get(key)).isEmpty()))raw.put(key,previous.containsKey(key)?previous.get(key):input.get(key));
  if(!raw.isEmpty())out.put("rawQuoteFields",raw);
  String supplied=text(input.getOrDefault("currency",previous.get("currency"))).toUpperCase(Locale.ROOT);
  if(!supplied.isEmpty()){
   out.put("currency",currency(supplied));
   out.put("currencySource",input.containsKey("currency")&&!text(input.get("currency")).equals(text(previous.get("currency")))?"explicit":text(previous.get("currencySource")).isEmpty()?"explicit":previous.get("currencySource"));
  }else{out.put("currency",businessDefault?"CNY":"");out.put("currencySource",businessDefault?"business_default":"historical_unknown");if(businessDefault&&!raw.containsKey("currency")){raw.put("currency","");raw.put("currencyPresent",false);out.put("rawQuoteFields",raw);}}
  for(String key:List.of("priceUnit","moqUnit","moqScope","moqNote"))if(input.containsKey(key)){String value=text(input.get(key));require(value.length()<=12000,"报价／起订说明过长");out.put(key,value);}
  if(input.containsKey("moqQuantity")){
   String value=text(input.get("moqQuantity"));if(!value.isEmpty()){try{require(value.length()<=256,"起订数量文本过长");var n=new BigDecimal(value);require(n.signum()>0,"起订数量须大于零");long length=(long)n.precision()+Math.max(0L,-(long)n.scale())+Math.max(0L,(long)n.scale())+2;require(length<=64,"起订数量超出安全数值表示范围，请核对原值");value=n.stripTrailingZeros().toPlainString();}catch(NumberFormatException e){throw invalid("起订数量必须为数值，原文请填补充条件");}}out.put("moqQuantity",value);out.put("moqSource",value.isEmpty()?(previous.isEmpty()?"":"explicit_empty"):"explicit");
  }
  String original=text(input.getOrDefault("moq",previous.get("moq")));
  if(!original.isEmpty()&&!"explicit_empty".equals(text(out.get("moqSource")))&&(text(out.get("moqQuantity")).isEmpty()||("source_text".equals(text(previous.get("moqSource")))&&input.containsKey("moq")&&!input.containsKey("moqQuantity")))){
   var match=MOQ.matcher(original);if(match.matches()){require(match.group(1).length()<=64,"起订数量原文过长，请核对");out.put("moqQuantity",new BigDecimal(match.group(1)).stripTrailingZeros().toPlainString());if(!input.containsKey("moqUnit"))out.put("moqUnit",match.group(2));String scope=match.group(3);if(!input.containsKey("moqScope"))out.put("moqScope",scope.equals("每颜色型号")||scope.equals("每型号颜色")?"每型号每颜色":scope);out.put("moqSource","source_text");}else{if("source_text".equals(text(previous.get("moqSource"))))for(String key:List.of("moqQuantity","moqUnit","moqScope","moqSource"))out.put(key,"");if(!input.containsKey("moqNote")&&!previous.containsKey("moqNote"))out.put("moqNote",original);}
  }else if(input.containsKey("moq")&&!input.containsKey("moqQuantity")&&!text(out.get("moqQuantity")).isEmpty()){
   var match=MOQ.matcher(original);require(!match.matches()||new BigDecimal(match.group(1)).compareTo(new BigDecimal(text(out.get("moqQuantity"))))==0,"起订原文与手工数量冲突，请核对数量及口径后明确保存");
  }
  for(String key:List.of("priceUnit","moqQuantity","moqUnit","moqScope","moqNote"))out.putIfAbsent(key,"");
  return out;
 }
 public static Map<String,Object> project(Map<String,Object> source){var result=new LinkedHashMap<>(source);result.putAll(normalize(source,Map.of(),false));return result;}
 public static Map<String,Object> operationalFields(Map<String,Object> source,List<String> fields){var result=new LinkedHashMap<String,Object>();for(String field:fields)if(source.containsKey(field))result.put(field,source.get(field));return result;}
 public static List<Map<String,Object>> operationalPairings(Object raw){return maps(raw).stream().map(pair->{var result=operationalFields(pair,List.of("phoneModelId","colorId","state"));var phone=operationalFields(map(pair.get("phoneSnapshot")),List.of("phoneModelId","displayName","phoneModelCode"));var color=operationalFields(map(pair.get("colorSnapshot")),List.of("id","name","supplierColorCode"));if(!phone.isEmpty())result.put("phoneSnapshot",phone);if(!color.isEmpty())result.put("colorSnapshot",color);return result;}).toList();}
}

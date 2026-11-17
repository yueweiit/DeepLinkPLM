package com.yuewei.plm.module.partner;

import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** A physical sample covers one actual pair, never a Cartesian product or a fabricated PS. */
final class PartnerCatalogSampleRules {
 private PartnerCatalogSampleRules() {}
 static final String PHONE_NOT_APPLICABLE="not_applicable",NO_PHONE_LABEL="无需手机型号";
 /** An omitted model is still incomplete; only the explicit receipt fact makes it inapplicable. */
 static boolean phoneNotApplicable(Map<String,Object> input){
  String value=text(input.get("phoneModelApplicability"));require(value.isEmpty()||PHONE_NOT_APPLICABLE.equals(value),"手机型号适用标记无效");
  boolean notApplicable=PHONE_NOT_APPLICABLE.equals(value);require(!notApplicable||number(input.get("phoneModelId"))<=0,"无需手机型号不能同时填写手机型号");return notApplicable;
 }
 static void requireNoPhoneSource(Map<String,Object> snapshot){require(PartnerReceiptColors.atReceipt(snapshot),"本款已确认具体型号与颜色组合，请核对原选样，不能改为无需手机型号");}
 static String key(long style,long phone,long color){require(style>0&&phone>0&&color>0,"请明确目录款式、手机型号和供应商颜色");return "CP:"+style+":"+phone+":"+color;}
 static String key(Map<String,Object> row){
  long style=number(row.get("catalogStyleId")),phone=number(row.get("phoneModelId")),color=number(row.get("colorId"));if(!phoneNotApplicable(row))return key(style,phone,color);require(style>0&&color>0,"请明确目录款式和供应商颜色");return "CP:"+style+":"+PHONE_NOT_APPLICABLE+":"+color;
 }
 static String configurationKey(Map<String,Object> snapshot){String explicit=text(snapshot.get("configKey"));return PartnerQualityRules.digest(new com.fasterxml.jackson.databind.ObjectMapper(),explicit.isEmpty()?Map.of("material",text(snapshot.get("material")),"configuration",text(snapshot.get("configuration"))):Map.of("configKey",explicit));}
 static Map<String,Object> pair(Map<String,Object> snapshot,long phone,long color){
  if(phoneNotApplicable(snapshot))return noPhonePair(snapshot,phone,color);
  key(number(snapshot.get("catalogStyleId")),phone,color);
  Map<String,Object> pair;
  if("at_receipt".equals(text(snapshot.get("receiptColorMode")))&&maps(snapshot.get("selectedPairs")).isEmpty()){
   require(PartnerSelectionService.scopeIds(snapshot.get("phoneModelIds")).contains(phone),"实际型号不属于已确认选样");
   var original=maps(snapshot.get("receiptColors")).stream().filter(c->number(c.get("id"))==color).findFirst().orElseThrow(()->invalid("请选择本供应商已维护且适用本款的原色；没有时先维护供应商颜色"));
   boolean unavailable=maps(snapshot.get("unavailablePairs")).stream().anyMatch(p->number(p.get("phoneModelId"))==phone&&number(p.get("colorId"))==color)||maps(snapshot.get("supplyPairings")).stream().anyMatch(p->number(p.get("phoneModelId"))==phone&&number(p.get("colorId"))==color&&"unavailable".equals(text(p.get("state"))));require(!unavailable,"供应商已明确不供该型号与颜色组合，不能登记收样");
   pair=new LinkedHashMap<>();pair.put("phoneModelId",phone);pair.put("colorId",color);pair.put("colorName",required(original.get("name"),"实际供应商颜色"));pair.put("colorSnapshot",new LinkedHashMap<>(original));pair.put("colorConfirmedAtReceipt",true);
  }else pair=maps(snapshot.get("selectedPairs")).stream().filter(p->number(p.get("phoneModelId"))==phone&&number(p.get("colorId"))==color).findFirst().orElseThrow(()->invalid("实际规格不属于已确认的样品选择；未选型号或颜色不能登记收样"));
  var out=new LinkedHashMap<>(pair);out.put("catalogStyleId",snapshot.get("catalogStyleId"));out.put("catalogStyleCode",snapshot.get("catalogStyleCode"));out.put("catalogConfigurationKey",configurationKey(snapshot));out.put("catalogSpecKey",key(number(snapshot.get("catalogStyleId")),phone,color)+":"+configurationKey(snapshot));
  var member=maps(snapshot.get("phones")).stream().filter(p->number(p.getOrDefault("phoneModelId",p.get("id")))==phone).findFirst().orElseThrow(()->invalid("所选手机型号快照缺失，请核对选择来源"));
  out.put("phones",List.of(member));out.put("phoneModelIds",List.of(phone));
  String colorName=PartnerReceiptColors.name(required(pair.getOrDefault("colorName",pair.get("color")),"实际供应商颜色"));out.put("colorName",colorName);
  out.put("specName",text(member.getOrDefault("displayName",member.get("modelName")))+" · "+colorName);return out;
 }
 private static Map<String,Object> noPhonePair(Map<String,Object> snapshot,long phone,long color){
  require(phone<=0,"无需手机型号不能同时填写手机型号");require(number(snapshot.get("catalogStyleId"))>0&&color>0,"请明确目录款式和供应商颜色");requireNoPhoneSource(snapshot);
  var original=maps(snapshot.get("receiptColors")).stream().filter(c->number(c.get("id"))==color).findFirst().orElseThrow(()->invalid("请选择本供应商已维护且适用本款的原色；没有时先维护供应商颜色"));
  String colorName=PartnerReceiptColors.name(required(original.get("name"),"实际供应商颜色")),configuration=configurationKey(snapshot);
  var out=new LinkedHashMap<String,Object>();out.put("catalogStyleId",snapshot.get("catalogStyleId"));out.put("catalogStyleCode",snapshot.get("catalogStyleCode"));out.put("catalogConfigurationKey",configuration);
  out.put("phoneModelApplicability",PHONE_NOT_APPLICABLE);out.put("phoneModelId",0L);out.put("phones",List.of());out.put("phoneModelIds",List.of());out.put("colorId",color);out.put("colorName",colorName);out.put("colorSnapshot",new LinkedHashMap<>(original));out.put("colorConfirmedAtReceipt",true);out.put("catalogSpecKey",key(out)+":"+configuration);out.put("specName",NO_PHONE_LABEL+" · "+colorName);return out;
 }
 static Map<String,Object> qualityScope(Map<String,Object> sample){
  var row=new LinkedHashMap<String,Object>();
  for(String field:List.of("catalogStyleId","catalogStyleCode","catalogSpecKey","catalogConfigurationKey","phoneModelId","colorId","colorName","specName","qualityBasis"))row.put(field,sample.get(field));
  if(phoneNotApplicable(sample))row.put("phoneModelApplicability",PHONE_NOT_APPLICABLE);
  row.put("sampleId",sample.get("id"));row.put("qty",sample.getOrDefault("effectiveReceivedQty",sample.get("receivedQty")));return row;
 }
 static String qualityBasis(com.fasterxml.jackson.databind.ObjectMapper json,Map<String,Object> snapshot,Map<String,Object> pair){
  var basis=new LinkedHashMap<String,Object>();
  for(String field:List.of("catalogStyleId","supplierCode","supplierItemCode","configKey","material","configuration"))basis.put(field,snapshot.get(field));
  basis.put("phoneModelId",pair.get("phoneModelId"));basis.put("colorId",pair.get("colorId"));if(phoneNotApplicable(pair))basis.put("phoneModelApplicability",PHONE_NOT_APPLICABLE);return PartnerQualityRules.digest(json,basis);
 }
}

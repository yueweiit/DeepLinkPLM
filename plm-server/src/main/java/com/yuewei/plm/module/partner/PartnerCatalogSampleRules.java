package com.yuewei.plm.module.partner;

import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** A physical sample covers one actual pair, never a Cartesian product or a fabricated PS. */
final class PartnerCatalogSampleRules {
 private PartnerCatalogSampleRules() {}
 static String key(long style,long phone,long color){require(style>0&&phone>0&&color>0,"请明确目录款式、手机型号和供应商颜色");return "CP:"+style+":"+phone+":"+color;}
 static String configurationKey(Map<String,Object> snapshot){String explicit=text(snapshot.get("configKey"));return PartnerQualityRules.digest(new com.fasterxml.jackson.databind.ObjectMapper(),explicit.isEmpty()?Map.of("material",text(snapshot.get("material")),"configuration",text(snapshot.get("configuration"))):Map.of("configKey",explicit));}
 static Map<String,Object> pair(Map<String,Object> snapshot,long phone,long color){
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
  String colorName=text(pair.getOrDefault("colorName",pair.get("color")));required(colorName,"实际供应商颜色");out.put("colorName",colorName);
  out.put("specName",text(member.getOrDefault("displayName",member.get("modelName")))+" · "+colorName);return out;
 }
 static Map<String,Object> qualityScope(Map<String,Object> sample){
  var row=new LinkedHashMap<String,Object>();
  for(String field:List.of("catalogStyleId","catalogStyleCode","catalogSpecKey","catalogConfigurationKey","phoneModelId","colorId","colorName","specName","qualityBasis"))row.put(field,sample.get(field));
  row.put("sampleId",sample.get("id"));row.put("qty",sample.get("receivedQty"));return row;
 }
 static String qualityBasis(com.fasterxml.jackson.databind.ObjectMapper json,Map<String,Object> snapshot,Map<String,Object> pair){
  var basis=new LinkedHashMap<String,Object>();
  for(String field:List.of("catalogStyleId","supplierCode","supplierItemCode","configKey","material","configuration"))basis.put(field,snapshot.get(field));
  basis.put("phoneModelId",pair.get("phoneModelId"));basis.put("colorId",pair.get("colorId"));return PartnerQualityRules.digest(json,basis);
 }
}

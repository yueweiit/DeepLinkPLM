package com.yuewei.plm.module.partner;

import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Immutable real IDs determine reuse. Receipt, quantity, location and display names do not. */
final class SampleIdentityKeys {
 private SampleIdentityKeys(){}
 record Evidence(String key,String domain,Map<String,Object> basis,boolean exact){}
 static Evidence from(Map<String,Object> sample){
  String type=text(sample.get("sourceType"));var source=map(sample.get("sourceSnapshot"));var basis=new LinkedHashMap<String,Object>();
  for(String f:List.of("sourceType","supplierCode","catalogStyleId","phoneModelId","colorId","catalogConfigurationKey","catalogSpecKey","specId","productId","modelProductId","projectId","sampleKind","sampleVersion"))if(sample.containsKey(f))basis.put(f,sample.get(f));
  if(Boolean.TRUE.equals(sample.get("pending")))return new Evidence("",Set.of("partner","catalog").contains(type)?"partner":Set.of("model","device","trial").contains(type)?"model":"unclassified",basis,false);
  long style=number(sample.get("catalogStyleId")),phone=number(sample.get("phoneModelId")),color=number(sample.get("colorId"));String config=text(sample.get("catalogConfigurationKey"));
  String material=text(source.get("material")),configuration=text(source.get("configuration"));if(!material.isEmpty())basis.put("technicalMaterial",material);if(!configuration.isEmpty())basis.put("technicalConfiguration",configuration);if(!material.isEmpty()&&!configuration.isEmpty())basis.put("technicalSignature",PartnerQualityRules.digest(new com.fasterxml.jackson.databind.ObjectMapper(),Map.of("material",material,"configuration",configuration)));
  if(style>0&&phone>0&&color>0&&!config.isEmpty())return new Evidence("catalog:"+style+":"+phone+":"+color+":"+config,"partner",basis,true);
  if(type.equals("partner")&&number(sample.get("specId"))>0&&!text(sample.get("supplierCode")).isEmpty())return new Evidence("partner:"+sample.get("specId")+":"+sample.get("supplierCode")+":"+text(sample.get("catalogConfigurationKey")),"partner",basis,true);
  if(type.equals("model")&&number(sample.get("modelProductId"))>0&&phone>0&&!text(sample.get("sampleVersion")).isEmpty())return new Evidence("model:"+sample.get("modelProductId")+":"+phone+":"+text(sample.get("sampleVersion")),"model",basis,true);
  if(type.equals("device")&&phone>0&&!text(sample.get("sampleKind")).isEmpty())return new Evidence("device:"+phone+":"+text(sample.get("sampleKind"))+":"+text(sample.get("sampleVersion")),"model",basis,true);
  if(type.equals("self")&&number(sample.get("projectId"))>0&&!text(sample.get("sampleKind")).isEmpty()&&!text(sample.get("sampleVersion")).isEmpty()){
   var ids=phoneIds(sample.getOrDefault("phoneModelIds",map(source.get("round")).getOrDefault("phoneModelIds",List.of())));if(ids.isEmpty())ids=phoneIds(map(map(source.get("round")).get("details")).getOrDefault("phoneModelIds",List.of()));
   if(ids.size()==1){basis.put("phoneModelId",ids.get(0));return new Evidence("research:"+sample.get("projectId")+":"+ids.get(0)+":"+sample.get("sampleKind")+":"+text(sample.get("sampleVersion")),"research",basis,true);}
  }
  return new Evidence("",Set.of("model","device","trial").contains(type)?"model":SampleDomains.project(map(source.get("project"))),basis,false);
 }
 private static String label(String field){return switch(field){case "catalogStyleId"->"目录款式";case "phoneModelId"->"手机型号";case "colorId"->"颜色";case "catalogConfigurationKey"->"技术配置";case "sampleVersion"->"样品版本";case "specId"->"实物规格";case "supplierCode"->"供应商";case "technicalMaterial"->"材料";case "technicalConfiguration"->"结构配置";case "technicalSignature"->"技术依据";default->"实物来源";};}
 static void compatible(Map<String,Object> before,Map<String,Object> after){
  String sourceBefore=text(before.get("sourceType")),sourceAfter=text(after.get("sourceType"));if(!sourceBefore.isEmpty()&&!sourceAfter.isEmpty()&&(sourceBefore.equals("device")||sourceAfter.equals("device")))require(sourceBefore.equals(sourceAfter),"真机／模型机与产品样品不是同一种实物，不能合并主码");
  for(String field:List.of("catalogStyleId","phoneModelId","colorId","modelProductId","projectId","sampleKind","catalogConfigurationKey","sampleVersion","specId","supplierCode","technicalMaterial","technicalConfiguration","technicalSignature")){
   if(field.equals("projectId")&&number(before.get("modelProductId"))>0&&number(before.get("modelProductId"))==number(after.get("modelProductId")))continue;
   if(field.equals("specId")&&number(before.get("catalogStyleId"))>0&&number(before.get("catalogStyleId"))==number(after.get("catalogStyleId")))continue;
   String a=text(before.get(field)),b=text(after.get(field));if(!a.isEmpty()&&!a.equals("0")&&!b.isEmpty()&&!b.equals("0"))require(a.equals(b),label(field)+"与该主码的既有依据不同，请回来源资料区分真实配置；不能合并不同实物");
  }
 }
}

package com.yuewei.plm.module.partner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** One rule set for sample and bulk inspections. No stock movements, approvals or implied coverage. */
final class PartnerQualityRules {
 private PartnerQualityRules(){}
 static Map<String,Object> result(Map<String,Object> source,Map<String,Object> input){
  var d=new LinkedHashMap<String,Object>();d.put("date",date(input.get("date")));d.put("factory",required(input.get("factory"),"实际检验工厂"));
  d.put("checks",required(input.get("checks"),"实际检查内容与结果"));d.put("issues",text(input.get("issues")));d.put("rectification",text(input.get("rectification")));
  String result=required(input.get("result"),"检验结论");require(Set.of("pass","fail","partial").contains(result),"检验结论无效");
  boolean bulk=text(source.get("sourceKind")).equals("fulfilment");require(bulk||!result.equals("partial"),"样品按本次明确范围记录通过或不合格");
  require(Boolean.TRUE.equals(input.get("onsite")),"必须记录公司人员实际现场检验，不能只凭工厂报告");
  var allowed=maps(source.get("allowedScope"));var scope=new ArrayList<Map<String,Object>>();Set<String> seen=new HashSet<>();
  for(var raw:maps(input.get("scope"))){String identity=scopeKey(raw);require(seen.add(identity),"同次检验规格不能重复");var actual=allowed.stream().filter(x->scopeKey(x).equals(identity)).findFirst().orElseThrow(()->invalid("检验范围不属于当前样品／批次"));
   long checked=qty(raw.get("checkedQty"),false);var row=new LinkedHashMap<>(actual);row.put("checkedQty",checked);
   if(!text(actual.get("catalogSpecKey")).isEmpty())require(checked<=number(actual.get("qty")),"检查数量不能超过本批真实收样数量");
   if(bulk){long total=qty(actual.get("qty"),false),covered=raw.containsKey("coveredQty")?qty(raw.get("coveredQty"),false):total;require(covered<=total&&checked<=covered,"检查数量不能超过本次明确覆盖的实际批量");long released=qty(raw.get("releaseQty"),true);require(released<=covered,"放行数量不能超过本次明确覆盖批量");require(!result.equals("fail")||released==0,"不合格结论不能放行");require(!result.equals("pass")||released==covered,"本次覆盖范围仅部分合格时，请注明问题与隔离依据");row.put("coveredQty",covered);row.put("releaseQty",released);}
   scope.add(row);
  }
  require(!scope.isEmpty(),"请明确本次检验适用的型号／规格，未列入的不算通过");
  if(!result.equals("pass"))required(d.get("issues"),"问题清单");
  if(result.equals("partial"))d.put("isolation",required(input.get("isolation"),"合格部分明确隔离的依据"));
  d.put("scope",scope);d.put("result",result);d.put("onsite",true);d.put("refs",maps(input.get("refs")));return d;
 }
 static boolean passed(Map<String,Object> inspection,long spec){return text(inspection.get("status")).equals("signed")&&text(map(inspection.get("resultSnapshot")).get("result")).equals("pass")&&maps(map(inspection.get("resultSnapshot")).get("scope")).stream().anyMatch(x->number(x.get("specId"))==spec);}
 static String scopeKey(Map<String,Object> row){String key=text(row.get("catalogSpecKey"));if(!key.isEmpty()){String config=required(row.get("catalogConfigurationKey"),"目录配置身份");require(config.matches("[a-f0-9]{64}")&&key.equals(PartnerCatalogSampleRules.key(number(row.get("catalogStyleId")),number(row.get("phoneModelId")),number(row.get("colorId")))+":"+config),"目录检验规格身份不一致");return key;}return "PS:"+number(row.get("specId"));}
 static boolean passedCatalog(Map<String,Object> inspection,String key){return text(inspection.get("status")).equals("signed")&&text(map(inspection.get("resultSnapshot")).get("result")).equals("pass")&&maps(map(inspection.get("resultSnapshot")).get("scope")).stream().anyMatch(x->key.equals(text(x.get("catalogSpecKey"))));}
 static String digest(ObjectMapper json,Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signature(json,value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw invalid("检验内容摘要生成失败");}}
}

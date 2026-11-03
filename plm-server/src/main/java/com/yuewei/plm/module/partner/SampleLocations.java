package com.yuewei.plm.module.partner;

import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Physical positions remain separate from the durable sample identity. */
final class SampleLocations {
 private static final List<String> PARTS=List.of("area","rack","shelf","box","sequence");
 record Position(String site,long locationId,String name,String code) {
  Position(String name,String code){this(legacySite(name),0,legacySite(name).isEmpty()?name:"",code);}
  String key(){return site.length()+":"+site+(locationId>0?"#"+locationId:"/"+name.length()+":"+name+code.length()+":"+code);}
  @Override public boolean equals(Object other){return other instanceof Position p&&key().equals(p.key());}
  @Override public int hashCode(){return key().hashCode();}
  Map<String,Object> view(){var d=new LinkedHashMap<String,Object>();d.put("site",site);d.put("locationId",locationId);d.put("name",name);d.put("locationCode",code);d.put("locationParts",parts(code));d.put("positionKey",key());return d;}
 }
 static String legacySite(String value){return Set.of("CN","MX").contains(value.toUpperCase(Locale.ROOT))?value.toUpperCase(Locale.ROOT):"";}
 static String siteField(String field){return field.equals("to")?"toSite":"site";}
 static String idField(String field){return field.equals("to")?"toLocationId":"locationId";}
 static String site(Map<String,Object> position){return position(position).site();}
 static Map<String,Object> project(Map<String,Object> position){var out=new LinkedHashMap<>(position);out.putAll(position(position).view());return out;}
 static Map<String,Object> project(Map<String,Object> position,Map<Long,Map<String,Object>> catalog){var out=project(position);var current=catalog.get(number(out.get("locationId")));if(current!=null){out.put("name",text(current.get("name")));out.put("locationCode",text(current.get("locationCode")));out.put("locationParts",map(current.get("locationParts")));}return out;}
 private SampleLocations() {}
 static String codeField(String field){return field.equals("to")?"toLocationCode":"locationCode";}
 static String partsField(String field){return field.equals("to")?"toLocationParts":"locationParts";}
 static Position at(Map<String,Object> data,String field){String name=text(data.get(field)),site=legacySite(text(data.get(siteField(field))));if(site.isEmpty()&&text(data.get(siteField(field))).isEmpty()){site=legacySite(name);if(!site.isEmpty())name="";}if(number(data.get(idField(field)))==0&&!site.isEmpty()&&site.equals(legacySite(name)))name="";return new Position(site,number(data.get(idField(field))),name,text(data.get(codeField(field))));}
 static Position position(Map<String,Object> data){var d=new LinkedHashMap<>(data);d.put("location",data.get("name"));if(!d.containsKey("locationId")&&"sample_location".equals(text(d.get("kind"))))d.put("locationId",d.get("id"));return at(d,"location");}
 static boolean matches(Map<String,Object> position,Map<String,Object> command){return position(position).equals(at(command,"location"));}
 static void normalize(Map<String,Object> data,String field){
  String codeKey=codeField(field),partsKey=partsField(field);Object rawCode=data.get(codeKey);require(rawCode==null||rawCode instanceof String,"位置编码请按文本填写，保留前导零");String code=text(rawCode);
  Object rawParts=data.get(partsKey);require(rawParts==null||rawParts instanceof Map<?,?>,"位置分段格式无效");var supplied=map(rawParts);var values=new LinkedHashMap<String,String>();
  for(String part:PARTS){Object raw=supplied.get(part);require(raw==null||raw instanceof String,"位置编码各段请按文本填写，保留前导零");values.put(part,text(raw));}
  if(values.values().stream().anyMatch(v->!v.isEmpty())){
   require(values.values().stream().allMatch(v->!v.isEmpty()&&!v.contains("-")),"位置编码须完整填写区域、货架编码、货架层号、收纳盒号和收纳盒序号");String assembled=String.join("-",values.values());require(code.isEmpty()||code.equals(assembled),"位置编码与五段内容不一致");code=assembled;
  }
  var resolved=parts(code);data.put(codeKey,code);data.put(partsKey,resolved);
 }
 private static Map<String,String> parts(String code){
  if(code.isEmpty())return Map.of();String[] values=code.split("-",-1);require(values.length==PARTS.size()&&Arrays.stream(values).allMatch(v->!v.isEmpty()&&v.chars().noneMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.isISOControl(c))),"位置编码须为区域-货架编码-货架层号-收纳盒号-收纳盒序号五段");var out=new LinkedHashMap<String,String>();for(int i=0;i<PARTS.size();i++)out.put(PARTS.get(i),values[i]);return out;
 }
 static void copy(Map<String,Object> source,String sourceField,Map<String,Object> target,String targetField){
  if(source.containsKey(sourceField))target.put(targetField,source.get(sourceField));if(source.containsKey(siteField(sourceField)))target.put(siteField(targetField),source.get(siteField(sourceField)));if(source.containsKey(idField(sourceField)))target.put(idField(targetField),source.get(idField(sourceField)));for(String key:List.of("Code","Parts")){String from=key.equals("Code")?codeField(sourceField):partsField(sourceField),to=key.equals("Code")?codeField(targetField):partsField(targetField);if(source.containsKey(from))target.put(to,source.get(from));}
 }
 static void fromPosition(Map<String,Object> position,Map<String,Object> target,String field){target.put(field,position.get("name"));target.put(siteField(field),text(position.get("site")));target.put(idField(field),number(position.get("locationId")));target.put(codeField(field),text(position.get("locationCode")));target.put(partsField(field),position.getOrDefault("locationParts",Map.of()));}
}

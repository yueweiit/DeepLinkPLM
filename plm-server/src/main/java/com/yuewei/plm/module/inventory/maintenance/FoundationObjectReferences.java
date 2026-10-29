package com.yuewei.plm.module.inventory.maintenance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Exact, typed identities in actual schema columns and structured snapshots. Never a free-text search. */
@Component @RequiredArgsConstructor
public class FoundationObjectReferences {
 private final JdbcTemplate jdbc; private final ObjectMapper json; private final PilotAccessService access;
 public Map<String,Object> inspect(String kind,long id,String code){
  if(!Set.of("inventory","customer").contains(kind))throw new IllegalArgumentException("kind");
  List<String> ids=kind.equals("inventory")?List.of("inventory_id","inventoryId","pcInventoryId","tpuInventoryId"):List.of("customer_id","customerId");
  List<String> codes=kind.equals("inventory")?List.of("inventory_code","inventoryCode"):List.of("customer_code","customerCode");
  var schemas=jdbc.queryForList("select table_name,column_name,data_type from information_schema.columns where table_schema=current_schema() and table_name like 'plm\\_%' escape '\\' order by table_name,ordinal_position");
  Map<String,List<Map<String,Object>>> tables=new LinkedHashMap<>();for(var c:schemas){String t=s(c.get("table_name"));if(t.matches("plm_[a-z0-9_]+"))tables.computeIfAbsent(t,k->new ArrayList<>()).add(c);}
  var groups=new LinkedHashMap<String,Map<String,Object>>();boolean used=false,restricted=false;var seen=new HashSet<String>();
  for(var e:tables.entrySet()){
   String table=e.getKey();if(Set.of("plm_inventory","plm_material_group","plm_customer","plm_operation_log","plm_partner_create_request","plm_pilot_scope","plm_supplier_profile").contains(table))continue;
   var tableCodes=new ArrayList<>(codes);if(kind.equals("inventory")&&table.contains("bom"))tableCodes.addAll(List.of("item_code","itemCode","child_code_normalized","output_inventory_code"));
   var predicates=new ArrayList<String>();var args=new ArrayList<Object>();
   for(var c:e.getValue()){
    String col=s(c.get("column_name"));if(!col.matches("[a-z0-9_]+"))continue;
    if(ids.contains(col)){predicates.add("t."+col+"::text=?");args.add(Long.toString(id));}
    else if(tableCodes.contains(col)&&!code.isBlank()){predicates.add("btrim(t."+col+"::text)=?");args.add(code);}
    else if(s(c.get("data_type")).equals("jsonb")){
     String path="$.** ? ("+String.join(" || ",ids.stream().map(k->"@."+k+" == $id || @."+k+" == $idText").toList())+" || "+String.join(" || ",tableCodes.stream().map(k->"@."+k+" == $code").toList())+")";
     predicates.add("jsonb_path_exists(t."+col+",?::jsonpath,?::jsonb)");args.add(path);args.add(encode(Map.of("id",id,"idText",Long.toString(id),"code",code)));
    }
   }
   if(predicates.isEmpty())continue;
   String exclude=table.equals("plm_partner_event")?" and t.action not like 'customer\\_%' escape '\\'":"";
   if(table.equals("plm_master_data_source")){predicates.add("t.object_type=? and t.object_id=?");args.add(kind);args.add(id);}
   for(var raw:jdbc.queryForList("select to_jsonb(t)::text as row from "+table+" t where ("+String.join(" or ",predicates)+")"+exclude,args.toArray())){
    used=true;Map<String,Object> row=decode(raw.get("row"));Owner owner=owner(table,row);String permission=permission(table);
    boolean allowed=access.hasPermission(permission)&&(access.access().allObjects()||owner.id>0&&!owner.type.isBlank()&&Boolean.TRUE.equals(jdbc.queryForObject("select "+access.visibleSqlClause(owner.type,"o.id")+" from(select ?::bigint id)o",Boolean.class,owner.id)));
    if(!allowed){restricted=true;continue;}
    String recordId=first(row,"record_id","product_bom_item_id","inventory_supplier_item_id","history_id","event_id","revision_id","order_id","id");
    String key=table+":"+raw.get("row");if(!seen.add(key))continue;
    boolean history=table.matches(".*(history|event|revision|snapshot|release).*" )||n(row.get("deleted_flag"))!=0||owner.deleted;
    String label=owner.label.isBlank()?label(table)+(recordId.isBlank()?"":" #"+recordId):owner.label;
    var item=new LinkedHashMap<String,Object>();item.put("id",recordId.isBlank()?Integer.toString(seen.size()):recordId);item.put("label",label);item.put("targetPath",owner.path);item.put("history",history);
    var group=groups.computeIfAbsent(table,k->new LinkedHashMap<>(Map.of("kind",table,"label",label(table),"items",new ArrayList<Map<String,Object>>())));((List<Map<String,Object>>)group.get("items")).add(item);
   }
  }
  for(var g:groups.values())g.put("count",((List<?>)g.get("items")).size());
  return Map.of("used",used,"references",new ArrayList<>(groups.values()),"hasRestrictedReferences",restricted);
 }
 private record Owner(String type,long id,String label,String path,boolean deleted){}
 private Owner owner(String table,Map<String,Object> row){
  String type="",ownerTable="",pk="",path="";long id=0;
  if(n(row.get("mold_set_id"))>0){type="mold";id=n(row.get("mold_set_id"));ownerTable="plm_collaboration_mold_set";pk="mold_set_id";path="/molds?moldSetId=";}
  else if(n(row.get("record_id"))>0){type="partner";id=n(row.get("record_id"));ownerTable="plm_partner_record";pk="record_id";}
  else for(String k:List.of("product_id","project_id","product_line_id","source_project_id"))if(n(row.get(k))>0){type="product";id=n(row.get(k));ownerTable="plm_product";pk="product_id";path="/product-research?productId=";break;}
  if(id==0&&n(row.get("product_bom_id"))>0){var parents=jdbc.queryForList("select product_id from plm_product_bom where product_bom_id=?",n(row.get("product_bom_id")));if(!parents.isEmpty()){type="product";id=n(parents.get(0).get("product_id"));ownerTable="plm_product";pk="product_id";path="/product-research?productId=";}}
  if(id==0)return new Owner("",0,"","",false);
  var parents=jdbc.queryForList("select to_jsonb(p)::text as row from "+ownerTable+" p where "+pk+"=?",id);if(parents.isEmpty())return new Owner(type,id,"","",true);
  var parent=decode(parents.get(0).get("row"));boolean deleted=n(parent.get("deleted_flag"))!=0;
  if(type.equals("partner"))path=switch(s(parent.get("kind"))){case "sale"->"/partner-sales?id=";case "sample"->"/samples?id=";case "product"->"/partner-products?id=";default->"";};
  if(type.equals("product")&&(!access.hasPermission("project:view")||!s(parent.get("development_mode")).equals("launch_v1")))path="";
  String name=first(parent,"product_name","name","set_name"),code=first(parent,"product_code","code","set_no");return new Owner(type,id,name+(code.isBlank()?"":" · "+code),deleted||path.isBlank()?"":path+id,deleted);
 }
 private static String permission(String t){return t.contains("cost")||t.contains("finance")||t.contains("payment")?"finance:read":t.contains("inventory")?"inventory:view":t.contains("partner")?"partner:view":t.contains("mold")||t.contains("collaboration")?"collaboration:view":t.contains("order")?"order:view":"product:view";}
 private static String label(String t){return t.contains("bom")?"BOM与历史快照":t.contains("inventory")?"库存与供货记录":t.contains("partner")?"合作业务与历史":t.contains("mold")||t.contains("collaboration")?"模具与排程":t.contains("cost")||t.contains("finance")?"成本与费用历史":t.contains("order")?"订单与历史":t.contains("source")?"来源与同步关系":"项目与历史资料";}
 private Map<String,Object> decode(Object raw){try{return json.readValue(s(raw),new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("引用资料读取失败",e);}}
 private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalArgumentException(e);}}
 private static String first(Map<String,Object> row,String...keys){for(String k:keys)if(!s(row.get(k)).isBlank())return s(row.get(k));return "";}
 private static String s(Object v){return Objects.toString(v,"").trim();}private static long n(Object v){try{return Long.parseLong(s(v));}catch(Exception e){return 0;}}
}

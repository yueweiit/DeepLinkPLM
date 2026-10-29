package com.yuewei.plm.module.partner;

import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Existing structured identities only; never a supplier-name substring search or another ledger. */
final class SupplierReferences {
 private final PartnerStore store;
 SupplierReferences(PartnerStore store){this.store=store;}
 private record Source(String table,String kind,String label,String permission,String objectType,String owner,String path){}
 private static final List<Source> SOURCES=List.of(
  new Source("plm_product_development_state","research","研发项目与历史","project:view","product","product_id","/product-research?productId="),
  new Source("plm_product_development_event","research","研发项目与历史","project:view","product","product_id","/product-research?productId="),
  new Source("plm_product_development_round","research","研发项目与历史","project:view","product","product_id","/product-research?productId="),
  new Source("plm_product_development_release","research","研发项目与历史","project:view","product","product_id","/product-research?productId="),
  new Source("plm_partner_record","partner","合作业务与历史","partner:view","partner","record_id","/partner-products?id="),
  new Source("plm_partner_spec","partner","合作业务与历史","partner:view","partner","record_id","/partner-products?id="),
  new Source("plm_partner_revision","partner","合作业务与历史","partner:view","partner","record_id","/partner-products?id="),
  new Source("plm_partner_event","partner","合作业务与历史","partner:view","partner","record_id","/partner-products?id="),
  new Source("plm_partner_selection_row","partner","合作业务与历史","partner:view","partner","record_id","/partner-selections?id="),
  new Source("plm_partner_selection_row_revision","partner","合作业务与历史","partner:view","partner","record_id","/partner-selections?id="),
  new Source("plm_partner_quote_line","partner","合作业务与历史","partner:view","partner","record_id","/partner-operations/supplier_quote?id="),
  new Source("plm_partner_quote_line_revision","partner","合作业务与历史","partner:view","partner","record_id","/partner-operations/supplier_quote?id="),
  new Source("plm_partner_file_job","import","导入原件与任务","partner:view","partner","record_id",""),
  new Source("plm_partner_file_job_row","import","导入原件与任务","partner:view","partner","record_id",""),
  new Source("plm_partner_import","import","导入原件与任务","partner:view","","",""),
  new Source("plm_partner_catalog_style","catalog","供方款式目录","partner:view","supplier_profile","supplier_id",""),
  new Source("plm_partner_catalog_style_source","catalog","供方款式目录","partner:view","partner","record_id",""),
  new Source("plm_phone_model_intake_source","intake","手机资料来源","partner:view","partner","record_id",""),
  new Source("plm_supplier_color","color","供应商颜色资料","supplier:view","supplier_profile","supplier_id",""),
  new Source("plm_supplier_color_version","color","供应商颜色资料","supplier:view","supplier_profile","supplier_id",""),
  new Source("plm_supplier_range","range","供应商适用范围资料","supplier:view","supplier_profile","supplier_id",""),
  new Source("plm_supplier_range_version","range","供应商适用范围资料","supplier:view","supplier_profile","supplier_id",""),
  new Source("plm_supplier_range_request","range","供应商适用范围资料","supplier:view","supplier_profile","supplier_id",""),
  new Source("plm_inventory","inventory","库存与供货关系","inventory:view","product","product_id",""),
  new Source("plm_inventory_supplier_item","inventory","库存与供货关系","inventory:view","product","product_id",""),
  new Source("plm_inventory_default","inventory","库存默认供方","inventory:view","product","product_id",""),
  new Source("plm_product_bom_item","bom","BOM与成本快照","product:view","product","product_id",""),
  new Source("plm_product_bom_cost_snapshot","bom","BOM与成本快照","finance:read","product","product_id",""),
  new Source("plm_product_bom_import_batch","bom","BOM导入原件","product:view","product","product_id",""),
  new Source("plm_process","process","委外工艺","process:view","product","product_id",""),
  new Source("plm_project_cost_item","cost","项目费用与历史","finance:read","product","product_id",""),
  new Source("plm_development_finance_history","cost","项目费用与历史","finance:read","product","product_id",""),
  new Source("plm_collaboration_mold_set","mold","实物模具与排程","collaboration:view","mold","mold_set_id","/molds?moldSetId="),
  new Source("plm_collaboration_history","mold","实物模具与排程","collaboration:view","mold","mold_set_id",""),
  new Source("plm_mold_schedule_plan","mold","实物模具与排程","schedule:read","mold","mold_set_id",""),
  new Source("plm_mold_application","application","开模申请与历史","project:view","product","project_id",""),
  new Source("plm_mold_application_activity","application","开模申请与历史","project:view","product","project_id",""),
  new Source("plm_import_detail","import","历史导入原件","partner:view","","",""),
  new Source("plm_pilot_trial","validation","验证与交付历史","report:view","","",""),
  new Source("plm_pilot_trial_history","validation","验证与交付历史","report:view","","",""),
  new Source("plm_pilot_bom_release","validation","验证与交付历史","report:view","product","project_id","")
 );
 private static final List<String> CODE_KEYS=List.of("supplierCode","supplier_code","previousSupplierCode","_sourceSupplierCode","default_supplier_code","supplier_code_snapshot");
 private static final List<String> ID_KEYS=List.of("supplierId","supplier_id");
 private static final List<String> NAME_KEYS=List.of("supplierName","supplier_name","default_supplier_name","supplier_name_snapshot","manufacturerName","manufacturer_name");

 Map<String,Object> inspect(Map<String,Object> profile){
  String code=text(profile.get("supplier_code"));long supplierId=number(profile.get("supplier_id"));
  var aliases=new TreeSet<String>();aliases.add(text(profile.get("supplier_name")).toLowerCase(Locale.ROOT));
  Object prior=decode(store.json,profile.get("data")).get("legacyNames");if(prior instanceof List<?> names)for(var name:names)if(!text(name).isEmpty())aliases.add(text(name).toLowerCase(Locale.ROOT));
  aliases.remove("");var variables=Map.of("code",code,"id",supplierId,"idText",Long.toString(supplierId));
  String identityPath="$.** ? ("+String.join(" || ",CODE_KEYS.stream().map(k->"@."+k+" == $code").toList())+" || "+String.join(" || ",ID_KEYS.stream().map(k->"@."+k+" == $id || @."+k+" == $idText").toList())+")";
  var groups=new LinkedHashMap<String,Map<String,Object>>();var seen=new HashSet<String>();boolean restricted=false,used=false;
  var access=store.access.access();var visibility=new HashMap<String,Boolean>();
  for(var source:SOURCES){
   var args=new ArrayList<Object>();args.add(identityPath);args.add(encode(store.json,variables));
   String predicate="jsonb_path_exists(to_jsonb(t),?::jsonpath,?::jsonb)";
   for(String key:CODE_KEYS){predicate+=" or btrim(to_jsonb(t)->>'"+key+"')=?";args.add(code);}
   for(String key:NAME_KEYS){predicate+=" or exists(select 1 from jsonb_array_elements_text(jsonb_path_query_array(to_jsonb(t),'$.**."+key+"')) n where lower(btrim(n))=any(?::text[]))";args.add(aliases.toArray(String[]::new));}
   if(Set.of("plm_collaboration_history","plm_development_finance_history").contains(source.table)){
    predicate+=" or (t.field_name in ('supplierName','manufacturerName') and (lower(btrim(t.before_value#>>'{}'))=any(?::text[]) or lower(btrim(t.after_value#>>'{}'))=any(?::text[])))";args.add(aliases.toArray(String[]::new));args.add(aliases.toArray(String[]::new));
   }
   String excluded=source.table.equals("plm_partner_event")?" and t.action not in ('supplier_save','supplier_delete','supplier_deactivate','supplier_activate','supplier_metadata')":source.table.equals("plm_supplier_range_request")?" and t.action<>'metadata'":"";
   var rows=store.jdbc.queryForList("select to_jsonb(t)::text as row from "+source.table+" t where ("+predicate+")"+excluded,args.toArray());
   for(var raw:rows){var row=decode(store.json,raw.get("row"));int match=identityMatch(row,code,supplierId,aliases);if(match==0&&Set.of("plm_collaboration_history","plm_development_finance_history").contains(source.table)&&Set.of("supplierName","manufacturerName").contains(text(row.get("field_name"))))match=aliases.contains(text(row.get("before_value")).toLowerCase(Locale.ROOT))||aliases.contains(text(row.get("after_value")).toLowerCase(Locale.ROOT))?1:0;if(match==0)continue;used=true;Owner owner=owner(source,row,supplierId);String scope=owner.type+":"+owner.id;
    boolean permitted=(access.actor().allPermissions()||access.permissions().contains(source.permission))
     &&(access.allObjects()||owner.id>0&&!owner.type.isEmpty()&&visibility.computeIfAbsent(scope,key->Boolean.TRUE.equals(store.jdbc.queryForObject("select "+store.access.visibleSqlClause(owner.type,"owner.id")+" from (select ?::bigint as id) owner",Boolean.class,owner.id))));
    if(!permitted){restricted=true;continue;}
    String id=first(row,"product_bom_item_id","product_bom_cost_snapshot_id","product_bom_import_batch_id","project_cost_item_id","plan_id","application_id","activity_id","event_id","revision_id","row_id","spec_id","color_id","range_id","style_id","inventory_supplier_item_id","inventory_id","cost_item_id","history_id","import_id","job_id","trial_id","id","product_id","process_id","mold_set_id");
    String key=source.kind+":"+(owner.id>0&&!owner.type.equals("supplier_profile")?scope:source.table+":"+(id.isEmpty()?raw.get("row"):id)+":"+text(row.get("version"))+text(row.get("version_no")));if(!seen.add(key))continue;
    boolean history=owner.deleted||number(row.get("deleted_flag"))!=0||source.table.contains("history")||source.table.contains("revision")||source.table.contains("event")||source.table.contains("version");
    String label=owner.label.isEmpty()?source.label+(id.isEmpty()?"":" #"+id):owner.label;if(owner.type.equals("supplier_profile")){String detail=first(row,"name","code");if(!detail.isEmpty())label=source.label+" · "+detail;}
    String target=owner.deleted?"":owner.path;if(owner.type.equals("product")&&!store.access.hasPermission("project:view"))target="";
    var item=new LinkedHashMap<String,Object>();item.put("id",owner.id>0&&!owner.type.equals("supplier_profile")?Long.toString(owner.id):id);item.put("label",label);item.put("history",history);item.put("targetPath",target);item.put("matchBasis",match==2?"供应商编码关联":"历史名称或旧身份，待核对");item.put("needsReview",match!=2);
    var group=groups.computeIfAbsent(source.kind,k->new LinkedHashMap<>(Map.of("kind",source.kind,"label",source.label,"items",new ArrayList<Map<String,Object>>())));
    ((List<Map<String,Object>>)group.get("items")).add(item);
   }
  }
  for(var group:groups.values())group.put("count",((List<?>)group.get("items")).size());
  return Map.of("used",used,"references",new ArrayList<>(groups.values()),"hasRestrictedReferences",restricted);
 }
 private record Owner(String type,long id,String label,String path,boolean deleted){}
 private Owner owner(Source source,Map<String,Object> row,long supplierId){
  String type=source.objectType;long id=number(row.get(source.owner));
  if(type.equals("supplier_profile"))id=supplierId;
  if(id==0&&source.table.equals("plm_partner_file_job_row"))id=lookup("select record_id from plm_partner_file_job where job_id=?::uuid",text(row.get("job_id")));
  if(source.table.equals("plm_collaboration_history")){type="item";id=number(row.get("item_id"));}
  if(source.table.equals("plm_mold_application_activity"))id=lookup("select project_id from plm_mold_application where application_id=?",number(row.get("application_id")));
  if(source.table.equals("plm_development_finance_history")){
   var parents=store.jdbc.queryForList("select to_jsonb(t)::text as row from "+("expense".equals(row.get("entity_type"))?"plm_project_cost_item t where project_cost_item_id=?":"plm_development_budget t where budget_id=?"),number(row.get("entity_id")));
   if(!parents.isEmpty()){var parent=decode(store.json,parents.get(0).get("row"));if(number(parent.get("mold_set_id"))>0){type="mold";id=number(parent.get("mold_set_id"));}else if(number(parent.get("item_id"))>0){type="item";id=number(parent.get("item_id"));}else{id=number(parent.get("product_id"));if(id==0)id=number(parent.get("product_line_id"));}}
  }
  if(type.equals("product")&&id==0)id=number(row.get("product_line_id"));
  if(type.equals("product")&&id==0&&number(row.get("product_bom_id"))>0)id=lookup("select product_id from plm_product_bom where product_bom_id=?",number(row.get("product_bom_id")));
  if(id==0)return new Owner(type,0,"","",false);
  String table,pk,name,code,path;
  switch(type){
   case "product"->{table="plm_product";pk="product_id";name="product_name";code="product_code";path="/product-research?productId=";}
   case "partner"->{table="plm_partner_record";pk="record_id";name="name";code="code";path="";}
   case "mold"->{table="plm_collaboration_mold_set";pk="mold_set_id";name="set_name";code="set_no";path="/molds?moldSetId=";}
   case "item"->{table="plm_collaboration_item";pk="item_id";name="style_name";code="phone_model_code";path="";}
   case "supplier_profile"->{table="plm_supplier_profile";pk="supplier_id";name="supplier_name";code="supplier_code";path="";}
   default->{return new Owner(type,id,"","",false);}
  }
  var found=store.jdbc.queryForList("select to_jsonb(t)::text as row from "+table+" t where "+pk+"=?",id);if(found.isEmpty())return new Owner(type,id,"","",true);
  var parent=decode(store.json,found.get(0).get("row"));boolean deleted=number(parent.get("deleted_flag"))!=0;
  if(type.equals("partner"))path=switch(text(parent.get("kind"))){case "product"->"/partner-products?id=";case "sale"->"/partner-sales?id=";case "sample"->"/samples?id=";case "selection"->"/partner-selections?id=";case "supplier_quote","sample_fee","fulfilment","inspection","listing"->"/partner-operations/"+parent.get("kind")+"?id=";default->"";};
  if(type.equals("product")&&!"launch_v1".equals(parent.get("development_mode")))path="";
  String label=text(parent.get(name));if(!text(parent.get(code)).isEmpty())label+=" · "+text(parent.get(code));
  return new Owner(type,id,label,path.isEmpty()||deleted?"":path+id,deleted);
 }
 private long lookup(String sql,Object arg){var rows=store.jdbc.queryForList(sql,arg);return rows.isEmpty()?0:number(rows.get(0).values().iterator().next());}
 /** A stable nonblank code wins over legacy numeric/name snapshots in the same object. */
 static int identityMatch(Object value,String code,long id,Set<String> aliases){
  if(value instanceof Map<?,?> map){
   int result=0;boolean stable=false;
   for(String key:CODE_KEYS)if(!text(map.get(key)).isEmpty()){stable=true;if(code.equals(text(map.get(key))))result=2;}
   if(!stable){for(String key:ID_KEYS)if(Long.toString(id).equals(text(map.get(key))))result=Math.max(result,1);for(String key:NAME_KEYS)if(aliases.contains(text(map.get(key)).toLowerCase(Locale.ROOT)))result=Math.max(result,1);}
   for(Object child:map.values())if(child instanceof Map<?,?>||child instanceof List<?>)result=Math.max(result,identityMatch(child,code,id,aliases));return result;
  }
  if(value instanceof List<?> list){int result=0;for(Object child:list)result=Math.max(result,identityMatch(child,code,id,aliases));return result;}return 0;
 }
 private static String first(Map<String,Object> row,String... keys){for(String key:keys)if(!text(row.get(key)).isEmpty())return text(row.get(key));return "";}
}

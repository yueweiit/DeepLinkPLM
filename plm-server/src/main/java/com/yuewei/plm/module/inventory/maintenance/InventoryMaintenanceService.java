package com.yuewei.plm.module.inventory.maintenance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.operationlog.service.*;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class InventoryMaintenanceService {
 private final JdbcTemplate jdbc;private final ObjectMapper json;private final PilotAccessService access;private final OperationLogService audit;private final FoundationObjectReferences references;
 private void read(){access.requireDomesticView();access.requirePermission("inventory:view");}
 private void write(){read();access.requireEngineeringMaintenance();}
 private String table(boolean group){return group?"plm_material_group":"plm_inventory";}private String pk(boolean group){return group?"material_group_id":"inventory_id";}
 private Map<String,Object> row(long id,boolean group,boolean lock){var rows=jdbc.queryForList("select * from "+table(group)+" where "+pk(group)+"=?"+(lock?" for update":""),id);require(rows.size()==1,"资料不存在");return rows.get(0);}
 public Map<String,Object> detail(long id,boolean group){read();var r=row(id,group,false);require(n(r.get("deleted_flag"))==0,"资料已删除");return view(r,group);}
 public List<Map<String,Object>> groups(){read();return jdbc.queryForList("select * from plm_material_group where deleted_flag=0 and group_level=1 order by sort_order,display_name,material_group_id").stream().map(r->view(r,true)).toList();}
 private Map<String,Object> view(Map<String,Object> r,boolean group){
  var out=new LinkedHashMap<String,Object>();out.put("id",r.get(pk(group)));out.put("code",r.get(group?"group_code":"inventory_code"));out.put("name",r.get(group?"display_name":"inventory_name"));out.put("version",r.get("version"));out.put("status",r.get(group?"status":"lifecycle_status"));out.put("deleted",n(r.get("deleted_flag"))!=0);out.put("remark",s(r.get("remark")));out.put("updatedAt",s(r.get("updated_at")));
  if(group){out.put("parentId",r.get("parent_material_group_id"));out.put("sourceSystem",s(r.get("source_system")));}
  else{out.put("spec",s(r.get("specification")));out.put("unit",s(r.get("stock_uom")));out.put("inventoryType",s(r.get("inventory_type")));out.put("groupId",r.get("material_group_id"));out.put("quantity",r.get("quantity"));out.put("stockStatus",s(r.get("status")));out.put("supplierName",s(r.get("supplier_name")));String source=sourceSystem(n(r.get("inventory_id")));out.put("sourceSystem",source);out.put("sourceNameReadonly",!source.isEmpty());}
  return out;
 }
 private String sourceSystem(long id){return String.join(" / ",jdbc.queryForList("select distinct upper(source_system) from plm_master_data_source where object_type='inventory' and object_id=? and source_system in ('mes','crm') order by 1",String.class,id));}
 @Transactional(readOnly=true) public Map<String,Object> inspect(long id,boolean group){read();var r=row(id,group,false);require(n(r.get("deleted_flag"))==0,"资料已删除");return inspection(r,group);}
 private Map<String,Object> usage(Map<String,Object> r,boolean group){
  long id=n(r.get(pk(group)));if(!group){var result=new LinkedHashMap<>(references.inspect("inventory",id,s(r.get("inventory_code"))));if(n(r.get("product_id"))>0){result.put("used",true);long product=n(r.get("product_id"));boolean visible=access.hasPermission("product:view")&&Boolean.TRUE.equals(jdbc.queryForObject("select "+access.visibleSqlClause("product","o.id")+" from(select ?::bigint id)o",Boolean.class,product));if(!visible)result.put("hasRestrictedReferences",true);else{var parents=jdbc.queryForList("select product_name,product_code,development_mode,deleted_flag from plm_product where product_id=?",product);if(!parents.isEmpty()){var p=parents.get(0);var groups=new ArrayList<Map<String,Object>>((List<Map<String,Object>>)result.get("references"));groups.add(Map.of("kind","inventory_owner","label","所属产品","count",1,"items",List.of(Map.of("id",product,"label",s(p.get("product_name"))+" · "+s(p.get("product_code")),"history",n(p.get("deleted_flag"))!=0,"targetPath",n(p.get("deleted_flag"))==0&&s(p.get("development_mode")).equals("launch_v1")&&access.hasPermission("project:view")?"/product-research?productId="+product:""))));result.put("references",groups);}}}return result;}
  var items=new ArrayList<Map<String,Object>>();boolean restricted=false,used=false;
  for(String t:List.of("plm_inventory","plm_material_group")){String key=t.equals("plm_inventory")?"material_group_id":"parent_material_group_id";var rows=jdbc.queryForList("select to_jsonb(t)::text as data from "+t+" t where "+key+"=?",id);for(var raw:rows){used=true;var child=decode(raw.get("data"));boolean inv=t.equals("plm_inventory");items.add(Map.of("id",s(child.get(inv?"inventory_id":"material_group_id")),"label",s(child.get(inv?"inventory_name":"display_name"))+" · "+s(child.get(inv?"inventory_code":"group_code")),"history",n(child.get("deleted_flag"))!=0,"targetPath",inv?"/inventories?inventoryId="+child.get("inventory_id"):""));}}
  return Map.of("used",used,"references",items.isEmpty()?List.of():List.of(Map.of("kind","group","label","物料与下级分组","count",items.size(),"items",items)),"hasRestrictedReferences",restricted);
 }
 private Map<String,Object> inspection(Map<String,Object> r,boolean group){var out=view(r,group);var used=usage(r,group);boolean writable=access.canMaintainEngineeringData(),stock=!group&&decimalNonzero(r.get("quantity"));String status=s(out.get("status"));out.put("canDelete",writable&&!Boolean.TRUE.equals(used.get("used"))&&!stock);out.put("canDeactivate",writable&&!status.equals("inactive"));out.put("canActivate",writable&&status.equals("inactive"));out.put("references",used.get("references"));out.put("hasRestrictedReferences",used.get("hasRestrictedReferences"));if(stock)out.put("blockedReason","物料仍有库存余额，不能删除；请按真实出入库处理库存，可先停用档案");else if(Boolean.TRUE.equals(used.get("used")))out.put("blockedReason","存在业务、来源或历史引用，不能删除，可停用并保留记录");return out;}
 @Transactional public Map<String,Object> save(Long id,boolean group,Map<String,Object> input){
  write();String request=request(input),code=required(input.get("code"),"编码"),name=required(input.get("name"),"名称");require(code.length()<=64&&name.length()<=255,"编码或名称过长");
  jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))",access.actorId()+":"+table(group)+":"+request);
  String signature=encode(new TreeMap<>(input));String reqKey=access.actorId()+":"+request;
  if(id==null){var prior=jdbc.queryForList("select "+pk(group)+" as id from "+table(group)+" where maintenance_data->'create'->>'key'=?",reqKey);if(!prior.isEmpty()){var r=row(n(prior.get(0).get("id")),group,true);require(signature.equals(decode(r.get("maintenance_data")).get("createSignature")),"提交标识已用于其他保存");require(n(r.get("deleted_flag"))==0,"此请求建立的档案已删除，不能恢复");return view(r,group);}}
  Map<String,Object> before=id==null?Map.of():row(id,group,true);Map<String,Object> metadata=id==null?new LinkedHashMap<>():decode(before.get("maintenance_data"));
  var prior=map(metadata.get("requests")).get(reqKey);if(prior!=null){var p=map(prior);require(signature.equals(p.get("signature")),"提交标识已用于其他保存");return map(p.get("after"));}
  if(id!=null){check(before,input);require(code.equals(s(before.get(group?"group_code":"inventory_code"))),"原编码不可修改");required(input.get("reason"),"本次修改原因");}
  String actor=access.actor().username();Long groupId=nullableId(input.get(group?"parentId":"groupId"));
  if(groupId!=null){require(!group||id==null||!id.equals(groupId),"不能选择自身为父级");var parents=jdbc.queryForList("select material_group_id from plm_material_group where material_group_id=? and deleted_flag=0 and "+(id!=null&&!group&&groupId.equals(nullableId(before.get("material_group_id")))?"":"status='active' and ")+"group_level=1 for share",groupId);require(parents.size()==1,"物料组不存在或已停用");if(group)throw invalid("物料组采用现有一级目录，不能新增嵌套分组");}
  if(group){
   if(id==null)id=jdbc.queryForObject("insert into plm_material_group(group_key,group_code,group_name,display_name,group_level,source_system,status,created_by,updated_by) values(?,?,?,?,1,'PLM','active',?,?) returning material_group_id",Long.class,"PLM:"+code,code,name,name,actor,actor);
   jdbc.update("update plm_material_group set group_name=?,display_name=?,normalized_display_name=null,remark=?,version=version+1,updated_by=?,updated_at=now() where material_group_id=?",name,name,s(input.get("remark")),actor,id);
  }else{
   String unit=required(input.get("unit"),"库存单位"),type=s(input.getOrDefault("inventoryType","material"));require(Set.of("material","semi_finished","finished","packaging","tooling","fixture").contains(type)||(id!=null&&type.equals(s(before.get("inventory_type")))),"物料类型无效");
   if(id!=null&&!sourceSystem(id).isEmpty())require(name.equals(s(before.get("inventory_name"))),"该物料名称由来源系统同步维护，请在来源系统更正；规格与备注可在本页补充");
   if(id!=null&&!unit.equals(s(before.get("stock_uom"))))require(!Boolean.TRUE.equals(usage(before,false).get("used"))&&!decimalNonzero(before.get("quantity")),"已有业务或库存时不能改单位，请维护单位换算关系");
   if(id==null)id=jdbc.queryForObject("insert into plm_inventory(inventory_code,inventory_name,inventory_type,stock_uom,quantity,status,created_by,updated_by) values(?,?,?,?,0,'available',?,?) returning inventory_id",Long.class,code,name,type,unit,actor,actor);
   jdbc.update("update plm_inventory set inventory_name=?,inventory_type=?,specification=?,stock_uom=?,material_group_id=?,remark=?,version=version+1,updated_by=?,updated_at=now() where inventory_id=?",name,type,s(input.get("spec")),unit,groupId,s(input.get("remark")),actor,id);
  }
  var after=view(row(id,group,false),group);var requests=new LinkedHashMap<>(map(metadata.get("requests")));requests.put(reqKey,Map.of("signature",signature,"after",after));metadata.put("requests",requests);if(before.isEmpty()){metadata.put("create",Map.of("key",reqKey));metadata.put("createSignature",signature);}jdbc.update("update "+table(group)+" set maintenance_data=?::jsonb where "+pk(group)+"=?",encode(metadata),id);audit(id,group,"save",request,s(input.get("reason")),before.isEmpty()?Map.of():view(before,group),after);return after;
 }
 @Transactional public Map<String,Object> lifecycle(long id,boolean group,String action,Map<String,Object> input){
  write();require(Set.of("delete","deactivate","activate").contains(action),"操作无效");String request=request(input),reason=required(input.get("reason"),"本次操作原因");require(reason.length()<=2000,"原因最多2000字");var r=row(id,group,true);var metadata=decode(r.get("maintenance_data"));String reqKey=access.actorId()+":"+request,signature=encode(Map.of("action",action,"version",n(input.get("version")),"reason",reason));var requests=new LinkedHashMap<>(map(metadata.get("requests")));if(requests.containsKey(reqKey)){var prior=map(requests.get(reqKey));require(signature.equals(prior.get("signature")),"提交标识已用于其他操作");return map(prior.get("after"));}check(r,input);
  var check=inspection(r,group);String capability=switch(action){case "delete"->"canDelete";case "deactivate"->"canDeactivate";default->"canActivate";};if(!Boolean.TRUE.equals(check.get(capability)))throw new BusinessException(40901,s(check.getOrDefault("blockedReason","资料状态已变化，请刷新后核对")));
  if(action.equals("delete"))jdbc.update("update "+table(group)+" set deleted_flag=1,version=version+1,updated_by=?,updated_at=now() where "+pk(group)+"=?",access.actor().username(),id);else jdbc.update("update "+table(group)+" set "+(group?"status":"lifecycle_status")+"=?,version=version+1,updated_by=?,updated_at=now() where "+pk(group)+"=?",action.equals("deactivate")?"inactive":"active",access.actor().username(),id);
  var after=view(row(id,group,false),group);requests.put(reqKey,Map.of("signature",signature,"after",after));metadata.put("requests",requests);jdbc.update("update "+table(group)+" set maintenance_data=?::jsonb where "+pk(group)+"=?",encode(metadata),id);audit(id,group,action,request,reason,view(r,group),after);return after;
 }
 private void audit(long id,boolean group,String action,String request,String reason,Object before,Object after){audit.logRequired(OperationLogCreateCommand.builder().businessType(group?"MATERIAL_GROUP":"INVENTORY").businessId(Long.toString(id)).action((group?"MATERIAL_GROUP_":"INVENTORY_")+action.toUpperCase(Locale.ROOT)).detailJson(encode(Map.of("requestId",request,"reason",reason,"before",before,"after",after))).build(),access.actor());}
 private void check(Map<String,Object> before,Map<String,Object> input){require(n(before.get("deleted_flag"))==0,"档案已删除，不能恢复");if(input.get("version")==null||n(input.get("version"))!=n(before.get("version")))throw new BusinessException(40901,"资料已变化，请刷新核对；本次输入未覆盖服务器");}
 private String request(Map<String,Object> in){String v=required(in.get("requestId"),"提交标识");require(v.length()<=80,"提交标识过长");return v;}
 private Map<String,Object> decode(Object v){if(v==null)return new LinkedHashMap<>();try{return json.readValue(v.toString(),new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("维护资料读取失败",e);}}
 private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalArgumentException(e);}}
 @SuppressWarnings("unchecked") private static Map<String,Object> map(Object v){return v instanceof Map<?,?>?(Map<String,Object>)v:new LinkedHashMap<>();}
 private static String s(Object v){return Objects.toString(v,"").trim();}private static long n(Object v){try{return Long.parseLong(s(v));}catch(Exception e){return 0;}}private static Long nullableId(Object v){return n(v)>0?n(v):null;}private static boolean decimalNonzero(Object v){return v!=null&&new java.math.BigDecimal(s(v)).signum()!=0;}
 private static String required(Object v,String label){String value=s(v);require(!value.isEmpty(),"请填写"+label);return value;}private static void require(boolean b,String msg){if(!b)throw invalid(msg);}private static BusinessException invalid(String msg){return new BusinessException(40001,msg);}
}

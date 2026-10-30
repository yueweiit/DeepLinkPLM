package com.yuewei.plm.module.partner;

import com.yuewei.plm.module.inventory.dto.SupplierSupplySideSaveDTO;
import com.yuewei.plm.module.inventory.vo.InventorySupplierCenterSnapshotVO.SupplierDetail;
import java.util.*;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.partner.PartnerValues.*;

@Service @RequiredArgsConstructor
public class SupplierProfileService {
 private final PartnerStore store;
 public List<SupplierDetail> decorate(List<SupplierDetail> legacy){
  store.access.requireDomesticView();store.access.requirePermission("supplier:view");
  var profiles=store.jdbc.queryForList("select * from plm_supplier_profile p where p.deleted_flag=0 and "+store.access.visibleSqlClause("supplier_profile","p.supplier_id")+" order by p.updated_at desc");
  Map<String,SupplierDetail> old=new LinkedHashMap<>();legacy.forEach(x->old.put(x.supplierCode(),x));
  List<SupplierDetail> result=new ArrayList<>();
  for(var p:profiles){String code=text(p.get("supplier_code"));var existing=old.remove(code);var d=decode(store.json,p.get("data"));result.add(new SupplierDetail(
   existing==null?number(p.get("supplier_id")):existing.supplierId(),code,text(p.get("supplier_name")),text(d.get("shortName")),text(d.get("contactPerson")),text(d.get("contactPhone")),text(d.get("contactEmail")),
   d.get("supplyCategories") instanceof List<?> x?x.stream().map(PartnerValues::text).toList():existing==null?List.of():existing.supplyCategories(),text(d.get("region")),text(p.get("status")),p.get("updated_at").toString(),
   text(d.getOrDefault("cooperationLevel",existing==null?"":existing.cooperationLevel())),store.access.hasPermission("finance:read")?text(d.getOrDefault("paymentTerm",existing==null?"":existing.paymentTerm())):"",text(d.getOrDefault("deliveryRisk",existing==null?"":existing.deliveryRisk())),
   existing==null?List.of():existing.supplyRecords().stream().map(r->store.access.hasPermission("finance:read")?r:new com.yuewei.plm.module.inventory.vo.InventorySupplierCenterSnapshotVO.SupplyRecord(r.recordId(),r.supplyType(),r.itemCode(),r.itemName(),r.relatedProduct(),null,r.currency(),r.lastDeliveryDate(),r.status(),r.targetPath())).toList(),existing==null?List.of():existing.relatedProjects().stream().filter(r->store.access.access().allObjects()||Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_product p where p.product_code=? and p.deleted_flag=0 and "+store.access.visibleSqlClause("product","p.product_id")+")",Boolean.class,r.projectCode()))).toList(),existing==null?List.of():existing.qualificationFiles(),
   text(p.get("supplier_type")),number(p.get("version")),Boolean.TRUE.equals(p.get("needs_review")),text(p.get("short_code")),businessCategories(d.get("businessCategories"))));
  }
  var tombstones=new HashSet<>(store.jdbc.queryForList("select supplier_code from plm_supplier_profile where deleted_flag<>0",String.class));
  for(var x:old.values())if(!tombstones.contains(x.supplierCode())&&store.access.access().allObjects())result.add(x);
  return result;
 }
 public Map<String,Object> page(Map<String,String> q){
  store.access.requireDomesticView();store.access.requirePermission("supplier:view");String where="p.deleted_flag=0 and "+store.access.visibleSqlClause("supplier_profile","p.supplier_id");List<Object> args=new ArrayList<>();if(!text(q.get("keyword")).isEmpty()){where+=" and (p.supplier_name ilike ? or p.supplier_code ilike ? or p.data->>'contactPerson' ilike ? or p.short_code ilike ? or exists(select 1 from plm_supplier_short_code_reservation c where c.supplier_code=p.supplier_code and c.short_code ilike ?))";for(int i=0;i<5;i++)args.add("%"+q.get("keyword")+"%");}if(!text(q.get("status")).isEmpty()){where+=" and p.status=?";args.add(q.get("status"));}if(!text(q.get("type")).isEmpty()){where+=" and p.supplier_type=?";args.add(q.get("type"));}String category=text(q.get("businessCategory"));if(!category.isEmpty()){require(Set.of("mold","partner_selection","unclassified").contains(category),"供应商业务分类无效");if(category.equals("unclassified"))where+=" and coalesce(p.data->'businessCategories','[]'::jsonb)='[]'::jsonb";else{where+=" and jsonb_exists(p.data->'businessCategories',?)";args.add(category);}}long total=store.jdbc.queryForObject("select count(*) from plm_supplier_profile p where "+where,Long.class,args.toArray()),page=Math.max(1,number(q.getOrDefault("page","1")));args.add(30);args.add((page-1)*30);var rows=store.jdbc.queryForList("select p.supplier_id as id,p.supplier_code as \"supplierCode\",p.supplier_name as \"supplierName\",p.short_code as \"shortCode\",p.supplier_type as \"supplierType\",p.status,p.version,p.data::text from plm_supplier_profile p where "+where+" order by p.updated_at desc limit ? offset ?",args.toArray());for(var r:rows){var data=decode(store.json,r.remove("data"));r.put("businessCategories",businessCategories(data.get("businessCategories")));r.put("contactPerson",text(data.get("contactPerson")));r.put("contactPhone",text(data.get("contactPhone")));r.put("contactEmail",text(data.get("contactEmail")));}return Map.of("content",rows,"totalElements",total,"page",page,"size",30);
 }
 public Map<String,Object> requireSupplier(String code){
  var rows=store.jdbc.queryForList("select supplier_id,supplier_code,supplier_name,supplier_type,status,needs_review,version,short_code,data::text from plm_supplier_profile p where p.supplier_code=? and p.deleted_flag=0 and "+store.access.visibleSqlClause("supplier_profile","p.supplier_id")+(writingTransaction()?" for share":""),code);
  require(!rows.isEmpty(),"供应商不存在或未获授权，请先保存供应商档案");var p=rows.get(0);
  var result=new LinkedHashMap<String,Object>(Map.of("supplierId",p.get("supplier_id"),"supplierCode",code,"supplierName",p.get("supplier_name"),"supplierType",p.get("supplier_type"),"status",p.get("status"),"version",p.get("version"),"needsReview",p.get("needs_review"),"shortCode",text(p.get("short_code"))));result.put("businessCategories",businessCategories(decode(store.json,p.get("data")).get("businessCategories")));return result;
 }
 public List<Map<String,Object>> options(){
  if(!store.access.hasPermission("supplier:view"))return List.of();store.access.requireDomesticView();
  var rows=store.jdbc.queryForList("select p.supplier_id as \"supplierId\",p.supplier_code as \"supplierCode\",p.supplier_name as \"supplierName\",p.status,p.version,p.needs_review as \"needsReview\",p.data::text from plm_supplier_profile p where p.deleted_flag=0 and "+store.access.visibleSqlClause("supplier_profile","p.supplier_id")+" order by lower(p.supplier_name),p.supplier_id");
  for(var row:rows)row.put("businessCategories",businessCategories(decode(store.json,row.remove("data")).get("businessCategories")));return rows;
 }
 public static List<String> businessCategories(Object value){
  if(value==null)return List.of();require(value instanceof List<?>,"供应商业务分类格式无效");var categories=((List<?>)value).stream().map(PartnerValues::text).distinct().toList();require(categories.stream().allMatch(Set.of("mold","partner_selection")::contains),"供应商业务分类无效");return categories;
 }
 public static void requireBusinessCategory(Map<String,Object> supplier,String category){require(businessCategories(supplier.get("businessCategories")).contains(category),"该供应商未登记为"+(category.equals("mold")?"开模供应商":"合作选品供应商")+"，请到供应商管理补充业务分类");}
 private boolean writingTransaction(){return TransactionSynchronizationManager.isActualTransactionActive()&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly();}
 public Map<String,Object> requireSupplierForNewUse(String code){
  var supplier=requireSupplier(code);require(!"inactive".equals(supplier.get("status")),"供应商已停用，不能建立新的业务采用关系");return supplier;
 }
 /** ERP/free-text snapshots remain snapshots; a code that names a real profile must obey its lifecycle. */
 public void requireSnapshotReference(String code,String previousCode){
  code=text(code);if(code.isEmpty()||!Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_supplier_profile where supplier_code=?)",Boolean.class,code)))return;
  if(code.equals(text(previousCode)))requireSupplier(code);else requireSupplierForNewUse(code);
 }
 @Transactional(readOnly=true) public Map<String,Object> references(String code){
  store.access.requireDomesticView();store.access.requirePermission("supplier:view");requireSupplier(code);
  return lifecycleView(profile(code,false),new SupplierReferences(store).inspect(profile(code,false)));
 }
 private Map<String,Object> profile(String code,boolean lock){
  var rows=store.jdbc.queryForList("select * from plm_supplier_profile p where p.supplier_code=? and ("+store.access.visibleSqlClause("supplier_profile","p.supplier_id")+")"+(lock?" for update":""),code);
  require(rows.size()==1,"供应商不存在或未获授权");return rows.get(0);
 }
 private Map<String,Object> lifecycleView(Map<String,Object> row,Map<String,Object> usage){
  boolean writable=store.access.hasPermission("partner:write"),deleted=number(row.get("deleted_flag"))!=0;String status=text(row.get("status"));
  var out=new LinkedHashMap<String,Object>();for(String key:List.of("supplierId","supplierCode","supplierName"))out.put(key,row.get(switch(key){case "supplierId"->"supplier_id";case "supplierCode"->"supplier_code";default->"supplier_name";}));
  out.put("status",status);out.put("version",row.get("version"));out.put("deleted",deleted);
  out.put("canDelete",writable&&!deleted&&!Boolean.TRUE.equals(usage.get("used")));out.put("canDeactivate",writable&&!deleted&&!status.equals("inactive"));
  out.put("canActivate",writable&&!deleted&&status.equals("inactive")&&!"unknown".equals(row.get("supplier_type"))&&!Boolean.TRUE.equals(row.get("needs_review")));
  out.put("references",usage.get("references"));out.put("hasRestrictedReferences",usage.get("hasRestrictedReferences"));return out;
 }
 private String lifecycleReason(Object value){String reason=required(value,"本次操作原因");require(reason.length()<=2000,"操作原因最多2000字");return reason;}
 private void statusChangeAllowed(Map<String,Object> before,String next,String reason){
  if(!text(before.get("status")).equals(next)&&(text(before.get("status")).equals("inactive")||next.equals("inactive")))lifecycleReason(reason);
  require(!next.equals("active")||(!"unknown".equals(before.get("supplier_type"))&&!Boolean.TRUE.equals(before.get("needs_review"))),"启用前请核对供应商身份并明确公司或个人");
 }
 private Map<String,Object> result(Map<String,Object> row){return Map.of("supplierCode",row.get("supplier_code"),"deleted",number(row.get("deleted_flag"))!=0,"status",row.get("status"),"version",row.get("version"));}
 private void lifecycleEvent(String code,String action,String request,String reason,Map<String,Object> before,Map<String,Object> after,String signature){
  var data=new LinkedHashMap<String,Object>();data.put("reason",reason);data.put("before",result(before));data.put("after",result(after));data.put("signature",signature);
  store.jdbc.update("insert into plm_partner_event(supplier_code,action,data,request_id,created_by,actor_id) values(?,?,?::jsonb,?,?,?)",code,"supplier_"+action,encode(store.json,data),request,store.access.actor().displayName(),store.access.actorId());
  store.audit.logRequired(OperationLogCreateCommand.builder().action("SUPPLIER_"+action.toUpperCase(Locale.ROOT)).businessType("SUPPLIER").businessId(code).detailJson(encode(store.json,data)).build(),store.access.actor());
 }
 @Transactional public Map<String,Object> lifecycle(String code,String action,Map<String,Object> input){
  store.access.requireDomesticView();store.access.requirePermission("supplier:view");store.access.requirePermission("partner:write");
  require(Set.of("delete","deactivate","activate").contains(action),"供应商操作无效");String request=required(input.get("requestId"),"提交标识");require(request.length()<=80,"提交标识过长");String reason=lifecycleReason(input.get("reason"));
  var row=profile(code,true);String signature=signature(store.json,Map.of("action",action,"version",number(input.get("version")),"reason",reason));
  var repeats=store.jdbc.queryForList("select action,data::text from plm_partner_event where supplier_code=? and actor_id=? and request_id=? order by event_id",code,store.access.actorId(),request);
  if(!repeats.isEmpty()){var prior=repeats.get(0);var data=decode(store.json,prior.get("data"));require(("supplier_"+action).equals(prior.get("action"))&&signature.equals(data.get("signature")),"提交标识已用于其他供应商操作，请重新核对");return map(data.get("after"));}
  require(number(row.get("deleted_flag"))==0,"供应商档案已删除，不能恢复或更新；如需新建请使用新请求");
  if(input.get("version")==null||number(input.get("version"))!=number(row.get("version")))throw new BusinessException(40901,"供应商已变化，请刷新引用清单后重新确认");
  if(action.equals("delete")){var usage=new SupplierReferences(store).inspect(row);if(Boolean.TRUE.equals(usage.get("used")))throw new BusinessException(40901,"供应商已有业务、范围资料或历史引用，不能删除；请刷新引用清单，停止使用可选择停用");
   store.jdbc.update("update plm_supplier_profile set deleted_flag=1,version=version+1,updated_at=now(),updated_by=? where supplier_code=?",store.access.actor().username(),code);
  }else{String next=action.equals("deactivate")?"inactive":"active";require(!next.equals(row.get("status")),"供应商已处于该状态，请刷新核对");statusChangeAllowed(row,next,reason);
   store.jdbc.update("update plm_supplier_profile set status=?,version=version+1,updated_at=now(),updated_by=? where supplier_code=?",next,store.access.actor().username(),code);
  }
  var after=profile(code,false);lifecycleEvent(code,action,request,reason,row,after,signature);return result(after);
 }
 public List<String> shortCodeHistory(String code){
  store.access.requireDomesticView();store.access.requirePermission("supplier:view");
  requireSupplier(code);
  return store.jdbc.queryForList("select original_short_code from plm_supplier_short_code_reservation where supplier_code=? order by created_at,short_code",String.class,code);
 }
 @Transactional public String save(String code,SupplierSupplySideSaveDTO dto){
  store.access.requireDomesticView();store.access.requirePermission("partner:write");
  var actor=store.access.actor();String request=text(dto.getRequestId());
  if(code==null&&!request.isEmpty()){Long repeated=store.created("supplier",request);if(repeated!=null){var previous=store.jdbc.queryForList("select supplier_code,deleted_flag from plm_supplier_profile where supplier_id=?",repeated);require(previous.size()==1&&number(previous.get(0).get("deleted_flag"))==0,"原新建请求的供应商已删除，不能复活；如需新建请使用新请求");return text(previous.get(0).get("supplier_code"));}}
  var old=code==null?List.<Map<String,Object>>of():store.jdbc.queryForList("select * from plm_supplier_profile where supplier_code=? for update",code);
  if(code!=null){require(!old.isEmpty()&&number(old.get(0).get("deleted_flag"))==0,"供应商不存在或已删除");store.access.requireSupplierProfile(number(old.get(0).get("supplier_id")));require(dto.getVersion()!=null&&dto.getVersion()==number(old.get(0).get("version")),"供应商已变化，请刷新后重试");}
  require(code==null||text(dto.getSupplierCode()).isEmpty()||code.equals(dto.getSupplierCode()),"供应商代码是稳定关联，不能修改");
  String type=text(dto.getSupplierType()).isEmpty()?"unknown":dto.getSupplierType(),status=text(dto.getStatus()).isEmpty()?"draft":dto.getStatus();
  require(Set.of("company","person","unknown").contains(type),"供应商类型无效");require(Set.of("draft","active","inactive").contains(status),"供应商状态无效");
  if(code!=null){var proposed=new LinkedHashMap<>(old.get(0));proposed.put("supplier_type",type);proposed.put("needs_review",false);statusChangeAllowed(proposed,status,text(dto.getChangeReason()));}require(!status.equals("active")||!type.equals("unknown"),"启用前请明确公司或个人，资料未齐可存草稿");
  Map<String,Object> data=store.json.convertValue(dto,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
  var previous=old.isEmpty()?Map.<String,Object>of():decode(store.json,old.get(0).get("data"));
  data.put("businessCategories",businessCategories(dto.getBusinessCategories()==null?previous.get("businessCategories"):dto.getBusinessCategories()));
  var aliases=new LinkedHashSet<String>();if(previous.get("legacyNames") instanceof List<?> names)for(var name:names)if(!text(name).isEmpty())aliases.add(text(name));if(!old.isEmpty())aliases.add(text(old.get(0).get("supplier_name")));aliases.remove("");if(!aliases.isEmpty())data.put("legacyNames",new ArrayList<>(aliases));
  if(!store.access.hasPermission("finance:write")){require(text(dto.getPaymentTerm()).isEmpty()||text(dto.getPaymentTerm()).equals(text(previous.get("paymentTerm"))),"没有修改结算约定的权限");data.put("paymentTerm",text(previous.get("paymentTerm")));}
  boolean creating=code==null;
  if(code==null){code="SUP-"+UUID.randomUUID().toString().substring(0,12).toUpperCase(Locale.ROOT);store.jdbc.update("insert into plm_supplier_profile(supplier_code,supplier_name,supplier_type,status,version,data,created_by,updated_by) values(?,?,?,?,1,?::jsonb,?,?)",code,required(dto.getSupplierName(),"供应商名称"),type,status,encode(store.json,data),actor.username(),actor.username());}
  else{store.jdbc.update("update plm_supplier_profile set supplier_name=?,supplier_type=?,status=?,version=version+1,data=?::jsonb,needs_review=false,updated_at=now(),updated_by=? where supplier_code=?",required(dto.getSupplierName(),"供应商名称"),type,status,encode(store.json,data),actor.username(),code);
   // Only the supplier identity changes; physical inventory quantities/types/statuses are independent.
   store.jdbc.update("update plm_inventory i set supplier_name=?,updated_at=now(),updated_by=? where exists(select 1 from plm_inventory_supplier_item si where si.inventory_id=i.inventory_id and si.supplier_code=? and si.deleted_flag=0)",dto.getSupplierName(),actor.username(),code);
   store.jdbc.update("update plm_inventory_supplier_item set supplier_name=?,supplier_short_name=?,supplier_contact_person=?,supplier_contact_phone=?,supplier_contact_email=?,supplier_region=?,updated_at=now(),updated_by=? where supplier_code=? and deleted_flag=0",dto.getSupplierName(),dto.getShortName(),dto.getContactPerson(),dto.getContactPhone(),dto.getContactEmail(),dto.getRegion(),actor.username(),code);
  }
  if(!old.isEmpty()&&!text(old.get(0).get("status")).equals(status)&&(status.equals("inactive")||"inactive".equals(old.get(0).get("status"))))lifecycleEvent(code,status.equals("inactive")?"deactivate":"activate",request,text(dto.getChangeReason()),old.get(0),profile(code,false),signature(store.json,Map.of("status",status,"version",dto.getVersion(),"reason",text(dto.getChangeReason()))));
  long sid=store.jdbc.queryForObject("select supplier_id from plm_supplier_profile where supplier_code=?",Long.class,code);
  if(creating&&!request.isEmpty())store.remember("supplier",request,sid);
  if(store.access.isPilot())store.jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) values(?,'supplier_profile',?) on conflict do nothing",actor.userId(),sid);
  store.jdbc.update("insert into plm_partner_event(supplier_code,action,data,created_by,actor_id) values(?,'supplier_save',?::jsonb,?,?)",code,encode(store.json,Map.of("before",old,"after",data)),actor.displayName(),actor.userId());
  return code;
 }
}

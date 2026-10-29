package com.yuewei.plm.module.partner;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Allocate one type-level intent to visible original SP ledgers, in one transaction. */
@Service @RequiredArgsConstructor
public class SampleIdentityActions {
 private final PartnerStore store;private final SampleIdentityRegistry identities;private final PartnerSampleService samples;
 @Transactional public Map<String,Object> action(long id,String action,Map<String,Object> input){
  if(action.equals("correct_color"))return correctColor(id,input);
  store.access.requirePermission("samples:write");require(Set.of("loan","return","move","retain","gift","consume","scrap","loss","count","undo","custody").contains(action),"样品主码动作无效");
  var head=identities.requireVisible(id,true);String request=required(input.get("requestId"),"提交标识"),hash=PartnerQualityRules.digest(store.json,Map.of("action",action,"input",input));
  var old=store.jdbc.queryForList("select action,data::text from plm_partner_event where record_id=? and request_id=?",id,request);if(!old.isEmpty()){var data=decode(store.json,old.get(0).get("data"));require(text(old.get(0).get("action")).equals("sample_identity_action")&&hash.equals(text(data.get("requestHash"))),"同一提交标识已用于不同样品动作，请核对原结果");return identities.detail(id);}
  store.check(head,number(input.get("version")));var batches=identities.visibleBatches(id,true);var byId=new LinkedHashMap<Long,Map<String,Object>>();for(var b:batches)byId.put(number(b.get("id")),b);
  var allocations=new ArrayList<Map<String,Object>>();long selected=number(input.get("batchId"));
  if(selected>0){require(byId.containsKey(selected),"所选批次不属于此主码或未获授权");var a=new LinkedHashMap<>(input);a.put("batchId",selected);allocations.add(a);}
  else if(input.get("allocations") instanceof List<?>){for(var raw:maps(input.get("allocations"))){long batch=number(raw.get("batchId"));require(byId.containsKey(batch),"分配批次不属于此主码或未获授权");var a=new LinkedHashMap<>(input);a.putAll(raw);allocations.add(a);}}
  else{
   require(!Set.of("return","loss","count","undo","custody").contains(action),"归还、损失、盘点、更正和保管请明确原批次／原借用记录");long remaining=qty(input.get("qty"),false);String location=required(input.get("location"),"实际取出位置"),condition=text(input.getOrDefault("condition","good"));
   for(var b:batches){long available=maps(map(b.get("balances")).get("positions")).stream().filter(p->location.equals(text(p.get("name")))).mapToLong(p->number(p.get(condition.equals("retained")?"retained":condition.equals("damaged")?"damaged":"available"))).sum();if(available<=0)continue;long amount=Math.min(remaining,available);var a=new LinkedHashMap<>(input);a.put("batchId",b.get("id"));a.put("qty",amount);allocations.add(a);remaining-=amount;if(remaining==0)break;}
   require(remaining==0,"当前授权批次在该位置的可用数量不足，不能动用留样、借出或隐藏批次");
  }
  require(!allocations.isEmpty()&&allocations.size()<=100,"请列明本次实际分配批次");var saved=new ArrayList<Map<String,Object>>();int index=0;
  for(var a:allocations){long batch=number(a.get("batchId"));var current=byId.get(batch);a.put("version",current.get("version"));a.put("requestId","identity-action-"+UUID.nameUUIDFromBytes((id+":"+request+":"+(index++)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));var result=samples.action(batch,action,a);byId.put(batch,result);var allocation=new LinkedHashMap<String,Object>();allocation.put("batchId",batch);allocation.put("batchCode",current.get("code"));for(String f:List.of("qty","location","to","loanId","targetEventId"))if(a.containsKey(f))allocation.put(f,a.get(f));saved.add(allocation);}
  store.event(id,"sample_identity_action",Map.of("requestHash",hash,"action",action,"allocations",saved),request);store.revision(id,head);store.save(id,head);return identities.detail(id);
 }

 /** Multiple types use the original action allocator, atomically, with one immutable request digest. */
 @Transactional public Map<String,Object> bulk(String action,Map<String,Object> input){
  store.access.requireDomesticView();store.access.requirePermission("samples:write");require(Set.of("loan","return").contains(action),"批量操作仅支持借出和归还");
  String request=required(input.get("requestId"),"提交标识"),hash=PartnerQualityRules.digest(store.json,Map.of("action",action,"input",input));
  require(input.get("items") instanceof List<?>,"请列出本次样品");var rows=maps(input.get("items"));require(!rows.isEmpty()&&rows.size()<=100,"一次请选择1至100种样品");
  var ids=new TreeSet<Long>();for(var row:rows)require(number(row.get("sampleIdentityId"))>0&&ids.add(number(row.get("sampleIdentityId"))),"样品主码重复或无效");
  Long previous=store.created("sample_bulk",request);String groupKey="bulk-"+UUID.nameUUIDFromBytes((store.access.actorId()+":"+request).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  if(previous!=null){var saved=store.jdbc.queryForList("select data::text from plm_partner_event where record_id=? and request_id=? and action='sample_bulk_action'",previous,groupKey);require(saved.size()==1&&hash.equals(text(decode(store.json,saved.get(0).get("data")).get("requestHash"))),"同一提交标识已用于不同批量操作，请核对原结果");return Map.of("content",ids.stream().map(identities::detail).toList());}
  for(long id:ids)identities.requireVisible(id,true);
  for(var row:rows){var command=new LinkedHashMap<>(input);command.remove("items");command.putAll(row);command.put("bulkRequestHash",hash);command.put("requestId","bulk-item-"+UUID.nameUUIDFromBytes((request+":"+row.get("sampleIdentityId")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));action(number(row.get("sampleIdentityId")),action,command);}
  long first=ids.first();store.event(first,"sample_bulk_action",Map.of("requestHash",hash,"action",action,"identityIds",ids),groupKey);store.remember("sample_bulk",request,first);return Map.of("content",ids.stream().map(identities::detail).toList());
 }
 public Map<String,Object> colorOptions(long id){var head=identities.requireVisible(id,false);colorAccess(head);var scope=colorScope(id,head);return Map.of("content",colorChoices(head,scope),"currentColorId",number(head.get("colorId")),"currentColorName",text(head.get("colorName")));}
 public Map<String,Object> previewColor(long id,Map<String,Object> input){
  var head=identities.requireVisible(id,false);colorAccess(head);var scope=colorScope(id,head);var color=new PartnerReceiptColors(store).resolve(scope,number(head.get("phoneModelId")),input);var basis=new LinkedHashMap<>(map(head.get("identityBasis")));basis.put("colorId",color.id());var target=color.id()>0?colorTarget(basis):Map.<String,Object>of();
  if(number(target.get("identity_id"))>0)identities.requireVisible(number(target.get("identity_id")),false);
  return Map.of("sampleCode",head.get("code"),"version",head.get("version"),"before",Map.of("id",number(head.get("colorId")),"name",text(head.get("colorName"))),"after",Map.of("id",color.id(),"name",color.name()),"targetIdentityId",number(target.get("identity_id")),"targetSampleCode",text(target.get("code")),"batchCount",identities.visibleBatches(id,false).size());
 }
 private void colorAccess(Map<String,Object> head){store.access.requirePermission("samples:write");store.access.requireEngineeringMaintenance();require("catalog".equals(text(head.get("sourceType")))&&number(head.get("catalogStyleId"))>0,"此样品颜色由原商品规格维护，请回原规格更正，不直接改写来源身份");require(number(head.get("redirectIdentityId"))==0,"请从当前有效样品码更正颜色");}
 private List<Map<String,Object>> colorChoices(Map<String,Object> head,Map<String,Object> scope){return store.jdbc.queryForList("select color_id as id,name,supplier_color_code as \"supplierColorCode\",style_key as \"styleKey\",config_key as \"configKey\" from plm_supplier_color where supplier_code=? and status='active' and (style_key='' or style_key=?) and (config_key='' or config_key=?) order by name,color_id",text(head.get("supplierCode")),text(scope.get("styleKey")),text(scope.get("configKey")));}
 private Map<String,Object> colorScope(long id,Map<String,Object> head){var batches=identities.visibleBatches(id,false);var snapshot=batches.isEmpty()?Map.<String,Object>of():map(batches.get(0).get("sourceSnapshot"));var scope=new LinkedHashMap<>(snapshot);scope.put("supplierCode",required(head.get("supplierCode"),"原供应商"));scope.put("receiptColorMode","at_receipt");scope.put("selectedPairs",List.of());scope.put("receiptColors",colorChoices(head,scope));return scope;}
 private Map<String,Object> colorTarget(Map<String,Object> basis){String key=SampleIdentityKeys.from(basis).key();require(!key.isEmpty(),"原样品缺少完整型号、颜色或技术配置，不能猜测更正身份");var targets=store.jdbc.queryForList("select si.identity_id,r.code from plm_sample_identity si join plm_partner_record r on r.record_id=si.identity_id and r.deleted_flag=0 where si.identity_key=?",key);return targets.isEmpty()?Map.of():targets.get(0);}
 @Transactional public Map<String,Object> correctColor(long id,Map<String,Object> input){
  var before=identities.requireVisible(id,false);String request=required(input.get("requestId"),"提交标识"),hash=PartnerQualityRules.digest(store.json,input),reason=required(input.get("reason"),"颜色更正原因");
  store.jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))","sample-color-correction:"+id+":"+request);
  var repeated=store.jdbc.queryForList("select data::text from plm_partner_event where record_id=? and request_id=? and action='sample_color_correction'",id,request);if(!repeated.isEmpty()){require(hash.equals(text(decode(store.json,repeated.get(0).get("data")).get("requestHash"))),"同一提交标识已用于不同颜色更正");return identities.detail(id);}
  colorAccess(before);var scope=colorScope(id,before);String name=PartnerReceiptColors.name(input.get("colorName"));store.jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))","sample-receipt-color:"+text(before.get("supplierCode"))+":"+PartnerReceiptColors.fold(name));scope.put("receiptColors",colorChoices(before,scope));
  var color=new PartnerReceiptColors(store).persistCorrection(id,scope,number(before.get("phoneModelId")),input,request);require(color.id()!=number(before.get("colorId"))||!color.name().equals(text(before.get("colorName"))),"颜色没有变化");
  var basis=new LinkedHashMap<>(map(before.get("identityBasis")));basis.put("colorId",color.id());String newKey=SampleIdentityKeys.from(basis).key();require(!newKey.isEmpty(),"原样品身份不完整，请先补齐来源");store.jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))","sample-identity:"+newKey);
  var candidate=colorTarget(basis);long targetId=number(candidate.get("identity_id"));if(targetId==0)targetId=id;
  if(targetId!=id)identities.requireVisible(targetId,false);
  if(targetId!=id)require(Boolean.TRUE.equals(input.get("confirmSameType"))&&number(input.get("targetIdentityId"))==targetId,"更正颜色后对应已有样品 "+text(candidate.get("code"))+"，请预览并明确确认归入该主码，不能自动合并");
  var locks=new TreeSet<Long>(List.of(id,targetId));for(long lock:locks)identities.requireVisible(lock,true);var head=identities.requireVisible(id,true);store.check(head,number(input.get("version")));var batches=identities.visibleBatches(id,true);long all=store.jdbc.queryForObject("select count(*) from plm_partner_record where kind='sample' and deleted_flag=0 and sample_identity_id=?",Long.class,id);require(all==batches.size(),"还有未获授权的收样批次，不能更正整个样品的颜色");
  if(targetId!=id)SampleIdentityKeys.compatible(map(identities.requireVisible(targetId,true).get("identityBasis")),basis);
  var change=new LinkedHashMap<String,Object>();change.put("before",Map.of("colorId",number(head.get("colorId")),"colorName",text(head.get("colorName"))));change.put("after",Map.of("colorId",color.id(),"colorName",color.name()));change.put("reason",reason);change.put("requestHash",hash);change.put("sourceIdentityId",id);change.put("targetIdentityId",targetId);change.put("inventoryChanged",false);
  String specification=String.join(" · ",List.of(text(head.get("phoneName")),color.name(),text(head.get("sampleVersion"))).stream().filter(v->!v.isEmpty()).toList());
  for(var b:batches){long batch=number(b.get("id"));var current=store.load(batch,"sample",true);store.revision(batch,current);current.put("colorId",color.id());current.put("colorName",color.name());current.put("specName",specification);store.event(batch,"color_correction",change,"color-"+UUID.nameUUIDFromBytes((id+":"+request+":"+batch).getBytes(java.nio.charset.StandardCharsets.UTF_8)));store.save(batch,current);if(targetId!=id)store.jdbc.update("update plm_partner_record set sample_identity_id=? where record_id=?",targetId,batch);}
  store.revision(id,head);
  if(targetId==id){head.put("colorId",color.id());head.put("colorName",color.name());head.put("specName",specification);head.put("identityBasis",basis);head.put("identityKey",newKey);store.jdbc.update("update plm_sample_identity set identity_key=?,basis=?::jsonb where identity_id=?",newKey,encode(store.json,basis),id);}
  else{head.put("redirectIdentityId",targetId);head.put("status","superseded");head.put("identityKey","corrected:"+id+":"+request);store.jdbc.update("update plm_sample_identity set identity_key=? where identity_id=?",head.get("identityKey"),id);var destination=identities.requireVisible(targetId,true);store.revision(targetId,destination);store.event(targetId,"sample_color_correction",change,"color-merge-"+UUID.nameUUIDFromBytes((id+":"+request).getBytes(java.nio.charset.StandardCharsets.UTF_8)));store.save(targetId,destination);}
  store.event(id,"sample_color_correction",change,request);store.save(id,head);return identities.detail(id);
 }

 @Transactional public Map<String,Object> bind(long batch,Map<String,Object> input){store.access.requirePermission("samples:write");store.access.requireEngineeringMaintenance();var b=store.load(batch,"sample",false);required(input.get("identityReason"),"原批次关联主码原因");var identity=identities.ensure(b,input);b=store.load(batch,"sample",true);store.check(b,number(input.get("version")));identities.attach(batch,number(identity.get("id")),input);return identities.detail(number(identity.get("id")));}
}

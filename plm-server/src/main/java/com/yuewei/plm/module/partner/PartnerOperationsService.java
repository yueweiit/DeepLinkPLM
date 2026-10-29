package com.yuewei.plm.module.partner;

import java.time.OffsetDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Supplier-facing workflow; customer sales and physical sample ledgers remain independent. */
@Service @RequiredArgsConstructor
public class PartnerOperationsService {
 private final PartnerStore store;
 private final PartnerOperationsAccess permissions;
 private final PartnerQueryService query;
 private final SupplierProfileService suppliers;
 private final PartnerProductService products;

 public Map<String,Object> capabilities(){
  store.access.requireDomesticView();store.access.requirePermission("partner:view");
  return Map.of("engineering",permissions.engineering(),"canReadCost",permissions.costs(),"listing",store.access.hasPermission("partner:listing"),"delivery",store.access.hasPermission("partner:delivery"),"externalListingConnected",false);
 }
 public Map<String,Object> page(String kind,Map<String,String> q){permissions.view(kind);return query.page(kind,q);}
 public Map<String,Object> detail(String kind,long id){
  permissions.view(kind);var d=store.load(id,kind,false);d.put("events",store.events(id));d.put("files",store.files(id));
  if(kind.equals("sample_fee"))d.put("balances",SampleFeeLedger.balances(maps(d.get("events"))));
  if(kind.equals("fulfilment")&&!permissions.costs())d.put("events",maps(d.get("events")).stream().filter(e->!text(e.get("action")).equals("expense")&&!text(map(e.get("data")).get("purpose")).equals("cooperation_cost")).toList());
  return d;
 }
 private Map<String,Object> source(long id){
  var rows=store.jdbc.queryForList("select kind from plm_partner_record where record_id=? and deleted_flag=0",id);require(rows.size()==1,"来源记录不存在");String kind=text(rows.get(0).get("kind"));return store.load(id,kind,true);
 }
 private String currencyOrUnknown(Object value){String s=text(value).toUpperCase(Locale.ROOT);require(s.isEmpty()||s.matches("[A-Z]{3}"),"币种请填写真实三位代码，未知可留空");return s;}
 private void optionalAmount(Map<String,Object> d,String key,Object raw){if(!text(raw).isEmpty())d.put(key,cents(raw));}
 private Map<String,Object> supplier(Object code){return suppliers.requireSupplier(required(code,"供应商"));}
 private Map<String,Object> base(Map<String,Object> input){
  var d=new LinkedHashMap<String,Object>();d.put("name",required(input.get("name"),"记录名称"));require(text(d.get("name")).length()<=255,"名称最长255字");d.put("status","draft");d.put("notes",text(input.get("notes")));return d;
 }
 private void parent(Map<String,Object> d,Map<String,Object> parent){d.put("parentId",parent.get("id"));d.put("sourceSnapshot",Map.of("id",parent.get("id"),"kind",parent.get("kind"),"code",parent.get("code"),"name",parent.get("name"),"version",parent.get("version")));}

 @Transactional public Map<String,Object> create(String kind,Map<String,Object> input){
  permissions.write(kind);require(!kind.equals("listing"),"请从产品一次确认上架");String request=required(input.get("requestId"),"提交标识");Long repeated=store.created(kind,request);if(repeated!=null)return detail(kind,repeated);
  var d=base(input);
  switch(kind){
   case "supplier_quote"->{
    var supplier=suppliers.requireSupplierForNewUse(required(input.get("supplierCode"),"供应商"));d.putAll(supplier);d.put("currency",currencyOrUnknown(input.get("currency")));optionalAmount(d,"unitPriceCents",input.get("unitPrice"));
    d.put("specDescription",text(input.get("specDescription")));d.put("supplierItemCode",text(input.get("supplierItemCode")));d.put("terms",text(input.get("terms")));d.put("date",text(input.get("date")).isEmpty()?"":date(input.get("date")));
    if(!text(input.get("qty")).isEmpty())d.put("qty",qty(input.get("qty"),false));
    if(number(input.get("specId"))>0){var snapshot=products.snapshot(number(input.get("specId")),text(d.get("supplierCode")),false);require(number(input.get("parentId"))==0||number(input.get("parentId"))==number(snapshot.get("productId")),"选中的规格不属于当前产品，请返回原来源重新选择");d.put("specId",snapshot.get("specId"));d.put("productId",snapshot.get("productId"));parent(d,store.load(number(snapshot.get("productId")),"product",true));}
    else if(number(input.get("parentId"))>0){var p=store.load(number(input.get("parentId")),"product",true);parent(d,p);d.put("productId",p.get("id"));}
    if(number(input.get("previousId"))>0){var previous=store.load(number(input.get("previousId")),kind,true);require(text(previous.get("supplierCode")).equals(text(d.get("supplierCode"))),"新旧报价须为同一供方");d.put("previousId",previous.get("id"));}
   }
   case "sample_fee"->{
    var p=source(number(input.get("parentId")));require(Set.of("sample","supplier_quote").contains(text(p.get("kind"))),"样品费请引用原样品或供方报价，不虚构收样");parent(d,p);d.putAll(supplier(p.get("supplierCode")));d.put("terms",required(input.get("terms"),"本次收费及抵退约定"));d.put("currency",currencyOrUnknown(input.get("currency")));optionalAmount(d,"agreedAmountCents",input.get("amount"));
   }
   case "fulfilment"->{
    d.putAll(supplier(input.get("supplierCode")));require(!text(d.get("status")).equals("inactive"),"供方已停用");d.put("status","pending");
    d.put("sourceChannel",required(input.get("sourceChannel"),"确认需求来源渠道"));d.put("sourceReference",required(input.get("sourceReference"),"原订单／渠道确认依据"));d.put("factory",required(input.get("factory"),"实际工厂"));d.put("requirements",text(input.get("requirements")));
    var lines=new ArrayList<Map<String,Object>>();Set<Long> seen=new HashSet<>();
    for(var raw:maps(input.get("lines"))){long spec=number(raw.get("specId"));require(seen.add(spec),"同规格请合并数量，来源依据在本单保留");var snapshot=products.snapshot(spec,text(d.get("supplierCode")),true);var line=new LinkedHashMap<String,Object>();line.put("specId",spec);line.put("qty",qty(raw.get("qty"),false));line.put("specName",snapshot.get("specName"));line.put("productId",snapshot.get("productId"));line.put("productName",snapshot.get("productName"));line.put("qualityBasis",qualityBasis(spec,text(d.get("supplierCode"))));lines.add(line);}
    require(!lines.isEmpty()&&lines.size()<=100,"请列明1至100项实际规格和数量");d.put("lines",lines);
   }
   case "inspection"->{
    var p=source(number(input.get("parentId")));String sourceKind=text(p.get("kind"));require(Set.of("sample","fulfilment").contains(sourceKind),"检验仅能关联现有样品或供方批次");require(!Boolean.TRUE.equals(p.get("pending")),"请先核实样品实际来源");require(!text(p.get("status")).equals("closed"),"已结案批次不能新增检验");parent(d,p);d.put("sourceKind",sourceKind);d.put("supplierCode",text(p.get("supplierCode")));d.put("factory",text(input.get("factory")));d.put("allowedScope",inspectionScope(p));
    if(number(p.get("productId"))>0)d.put("productId",p.get("productId"));
    for(String field:List.of("catalogStyleId","catalogStyleCode","catalogStyleLabel","selectionId","selectionRowId"))if(p.containsKey(field))d.put(field,p.get(field));
    if(number(input.get("previousId"))>0){var old=store.load(number(input.get("previousId")),kind,true);require(number(old.get("parentId"))==number(p.get("id")),"复检须引用同一来源的原检查");d.put("previousId",old.get("id"));}
   }
   default->throw invalid("业务类型无效");
  }
  // Supplier identity/status do not substitute the new business record's own status.
  if(!kind.equals("fulfilment"))d.put("status","draft");
  long id=store.create(kind,text(d.get("name")));d.put("refs",List.of());store.remember(kind,request,id);store.revision(id,d);store.save(id,d);store.event(id,"operation_create",Map.of("kind",kind,"name",d.get("name")),request);return detail(kind,id);
 }

 private List<Map<String,Object>> inspectionScope(Map<String,Object> p){
  if(text(p.get("kind")).equals("fulfilment"))return maps(p.get("lines"));
  if(text(p.get("sourceType")).equals("catalog"))return List.of(PartnerCatalogSampleRules.qualityScope(p));
  long spec=number(p.get("specId"));if(spec>0)return List.of(Map.of("specId",spec,"specName",text(p.get("specName")),"productId",number(p.get("productId")),"qualityBasis",qualityBasis(spec,text(p.get("supplierCode")))));
  // Self-developed samples have no fabricated commercial specification or SKU identity.
  return List.of(Map.of("specId",0,"specName",required(p.get("specName"),"自研样品适用型号／规格"),"projectId",number(p.get("projectId"))));
 }
 String qualityBasis(long spec,String supplier){
  var snapshot=products.snapshot(spec,supplier,false);var rows=store.jdbc.queryForList("select data::text from plm_partner_spec where spec_id=? and deleted_flag=0",spec);var d=decode(store.json,rows.get(0).get("data"));
  return PartnerQualityRules.digest(store.json,Map.of("specId",spec,"phoneModelIds",phoneIds(d.get("phoneModelIds")),"specification",text(d.getOrDefault("specification",d.get("name"))),"supplierCode",supplier,"supplierItemCode",text(map(snapshot.get("source")).get("supplierItemCode"))));
 }
 private Map<String,Object> facts(long id,Map<String,Object> input){var d=new LinkedHashMap<>(input);d.put("refs",store.canonicalFiles(id,input.get("refs")));return d;}
 private void requirePicture(long id,Object refs){
  require(maps(refs).stream().anyMatch(f->Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_attachment where attachment_id=? and content_type like 'image/%' and deleted_flag=0)",Boolean.class,number(f.get("id"))))),"有质量问题请附本次实际问题照片，不能只留通过结论");
 }
 @Transactional public Map<String,Object> action(String kind,long id,String action,Map<String,Object> input){
  permissions.view(kind);
  if(kind.equals("fulfilment")&&Set.of("receipt","after_sales").contains(action))permissions.sales("delivery");else permissions.write(kind);
  var d=store.load(id,kind,true);String request=required(input.get("requestId"),"提交标识");
  if(store.repeated(id,request)){String actual=store.jdbc.queryForObject("select action from plm_partner_event where record_id=? and request_id=?",String.class,id,request);require(actual.equals(eventName(kind,action)),"提交标识已用于其他动作，请重新核对");return detail(kind,id);}
  store.check(d,number(input.get("version")));require(!text(d.get("status")).equals("closed"),"记录已结案，历史不可覆盖");
  var actual=facts(id,input);
  switch(kind){
   case "supplier_quote"->{
    require(text(d.get("status")).equals("draft")&&Set.of("save_draft","confirm").contains(action),"报价依据已确认；变更请追加新报价并关联原记录");
    if(action.equals("save_draft")){
     for(String key:List.of("specDescription","supplierItemCode","terms"))if(input.containsKey(key))d.put(key,text(input.get(key)));
     if(input.containsKey("currency"))d.put("currency",currencyOrUnknown(input.get("currency")));if(input.containsKey("unitPrice")){if(text(input.get("unitPrice")).isEmpty())d.remove("unitPriceCents");else d.put("unitPriceCents",cents(input.get("unitPrice")));}
     if(input.containsKey("qty")){if(text(input.get("qty")).isEmpty())d.remove("qty");else d.put("qty",qty(input.get("qty"),false));}if(input.containsKey("date"))d.put("date",text(input.get("date")).isEmpty()?"":date(input.get("date")));
    }else{required(d.get("specDescription"),"本版实际报价型号／规格");required(d.get("date"),"实际报价日期");d.put("status","recorded");}
    d.put("refs",actual.get("refs"));
   }
   case "sample_fee"->{
    if(!Set.of("agreement","reverse").contains(action))require(!text(d.get("currency")).isEmpty(),"币种尚未明确，请先追加完整约定");
    actual=SampleFeeLedger.prepare(action,actual,store.events(id));
    if(action.equals("agreement")){String curr=currencyOrUnknown(input.get("currency"));if(!text(d.get("currency")).isEmpty())require(curr.isEmpty()||curr.equals(d.get("currency")),"已有费用记录的币种不能改写");else d.put("currency",curr);d.put("terms",actual.get("terms"));}
    d.put("status","recorded");
   }
   case "inspection"->actual=inspectionAction(d,id,action,actual);
   case "fulfilment"->actual=fulfilmentAction(d,id,action,actual);
   default->throw invalid("此记录不支持该动作");
  }
  store.revision(id,d);store.event(id,eventName(kind,action),actual,request);store.save(id,d);return detail(kind,id);
 }
 private String eventName(String kind,String action){return kind.equals("sample_fee")?"fee_"+action:action;}

 private Map<String,Object> inspectionAction(Map<String,Object> d,long id,String action,Map<String,Object> input){
  if(action.equals("save_draft")){
   require(text(d.get("status")).equals("draft"),"检验已冻结；不能覆盖已确认结果或签名");var draft=new LinkedHashMap<String,Object>();
   for(String key:List.of("factory","checks","issues","rectification","qualityEvidence","isolation","result"))draft.put(key,text(input.get(key)));
   require(text(draft.get("result")).isEmpty()||Set.of("pass","fail","partial").contains(text(draft.get("result"))),"草稿检验结论无效");
   draft.put("date",text(input.get("date")).isEmpty()?"":date(input.get("date")));draft.put("onsite",Boolean.TRUE.equals(input.get("onsite")));draft.put("refs",input.get("refs"));
   var scope=new ArrayList<Map<String,Object>>();for(var row:maps(input.get("scope"))){var allowed=maps(d.get("allowedScope")).stream().filter(s->PartnerQualityRules.scopeKey(s).equals(PartnerQualityRules.scopeKey(row))).findFirst().orElseThrow(()->invalid("草稿范围不属于原样品／批次"));var saved=new LinkedHashMap<>(allowed);for(String key:List.of("checkedQty","coveredQty","releaseQty"))if(!text(row.get(key)).isEmpty())saved.put(key,qty(row.get(key),key.equals("releaseQty")));scope.add(saved);}draft.put("scope",scope);
   d.put("draftResult",draft);return Map.of("draftOnly",true,"draft",draft);
  }
  if(action.equals("confirm")){
   require(text(d.get("status")).equals("draft"),"检验已冻结；整改或复检须新增关联记录");var p=source(number(d.get("parentId")));d.put("allowedScope",inspectionScope(p));var result=PartnerQualityRules.result(d,input);
   if(!text(result.get("issues")).isEmpty())requirePicture(id,result.get("refs"));
   result.put("inspector",store.access.actor().displayName());result.put("inspectorId",store.access.actorId());result.put("confirmedAt",OffsetDateTime.now().toString());
   if(text(d.get("sourceKind")).equals("fulfilment")){
    required(input.get("qualityEvidence"),"现有对照样品／质量依据");result.put("qualityEvidence",input.get("qualityEvidence"));
    for(var s:maps(result.get("scope"))){long spec=number(s.get("specId"));long other=otherReleased(number(d.get("parentId")),id,spec,number(d.get("previousId")));long batch=maps(p.get("lines")).stream().filter(x->number(x.get("specId"))==spec).mapToLong(x->number(x.get("qty"))).sum();require(add(other,number(s.get("releaseQty")))<=batch,"同一批量已被其他检验放行，不能通过复检重复增加放行数量");}
   }
   d.put("resultSnapshot",result);d.put("basisHash",PartnerQualityRules.digest(store.json,result));d.put("status","pending_signature");return result;
  }
  if(action.equals("pending_signature")){
   require(text(d.get("status")).equals("pending_signature"),"请先由检验人确认实际结果");return Map.of("reason",required(input.get("reason"),"拒签／不在场原因"),"basisHash",d.get("basisHash"));
  }
  if(action.equals("sign")){
   require(text(d.get("status")).equals("pending_signature"),"只能签署已由工程确认的本次结果");require(text(input.get("basisHash")).equals(text(d.get("basisHash"))),"检验内容已变化，请工厂代表重新阅读本次结果");require(Boolean.TRUE.equals(input.get("reviewed")),"请工厂本人读完结果及问题清单后签字");
   long file=number(input.get("signatureFileId"));store.requireFiles(id,List.of(Map.of("id",file)));require(Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_file where record_id=? and attachment_id=? and purpose='factory_signature')",Boolean.class,id,file)),"必须使用本次现场签字画布，不能把普通照片当签名");var image=store.jdbc.queryForList("select attachment_id,file_name,version_no,content_type,created_at,checksum from plm_attachment where attachment_id=? and deleted_flag=0",file);
   require(image.size()==1&&text(image.get(0).get("content_type")).equals("image/png"),"签名图片未保存成功，请重新签署");require(maps(map(d.get("resultSnapshot")).get("refs")).stream().noneMatch(f->number(f.get("id"))==file),"不能把问题照片作为签名");
   require(Boolean.TRUE.equals(store.jdbc.queryForObject("select ?::timestamptz <= created_at at time zone current_setting('TimeZone') from plm_attachment where attachment_id=?",Boolean.class,map(d.get("resultSnapshot")).get("confirmedAt"),file)),"请为本次冻结结果重新现场签名，不能复用旧图片");
   var signed=new LinkedHashMap<String,Object>();signed.put("name",required(input.get("representativeName"),"工厂代表姓名"));signed.put("position",required(input.get("representativePosition"),"工厂代表岗位"));signed.put("signatureFileId",file);signed.put("signatureChecksum",image.get(0).get("checksum"));signed.put("basisHash",d.get("basisHash"));signed.put("signedAt",OffsetDateTime.now().toString());signed.put("recordedBy",store.access.actorId());d.put("factorySignature",signed);d.put("status","signed");return signed;
  }
  if(action.equals("reference")){
   require(text(d.get("sourceKind")).equals("sample")&&text(d.get("status")).equals("signed")&&text(map(d.get("resultSnapshot")).get("result")).equals("pass"),"仅本次检验通过且工厂已签的样品可指定对照用途");var p=source(number(d.get("parentId")));require(text(p.get("kind")).equals("sample"),"对照样品引用原台账");
   String location=required(input.get("location"),"实际存放位置");require(maps(SampleLedger.replay(store.events(number(p.get("id")))).view().get("positions")).stream().anyMatch(pos->location.equals(pos.get("name"))&&number(pos.get("onhand"))>0),"请引用原样品台账有实物的当前位置；移动去向先在原记录办理，不另建对照库存");
   var reference=Map.<String,Object>of("sampleId",p.get("id"),"location",location,"custodian",required(input.get("custodian"),"实际保管人"),"reason",required(input.get("reason"),"对照用途／范围说明"),"date",date(input.get("date")));d.put("referenceSample",reference);return reference;
  }
  throw invalid("检验动作无效");
 }
 private long otherReleased(long parent,long exclude,long spec,long replacing){
  var rows=store.jdbc.queryForList("select record_id,data::text from plm_partner_record where parent_record_id=? and kind='inspection' and record_id<>? and status in ('pending_signature','signed') and deleted_flag=0",parent,exclude);long total=0;var events=store.events(parent);
  Set<Long> superseded=new HashSet<>();if(replacing>0)superseded.add(replacing);for(var r:rows){long previous=number(decode(store.json,r.get("data")).get("previousId"));if(previous>0)superseded.add(previous);}
  for(var r:rows){long id=number(r.get("record_id"));if(superseded.contains(id)){total=add(total,PartnerFulfilmentRules.allocated(events,id,spec));continue;}for(var s:maps(map(decode(store.json,r.get("data")).get("resultSnapshot")).get("scope")))if(number(s.get("specId"))==spec)total=add(total,number(s.get("releaseQty")));}return total;
 }
 private Map<String,Object> fulfilmentAction(Map<String,Object> d,long id,String action,Map<String,Object> input){
  var events=store.events(id);var actual=new LinkedHashMap<String,Object>();actual.put("refs",input.get("refs"));
  switch(action){
   case "accept","delay","complete","change"->{
    actual.put("date",date(input.get("date")));actual.put("evidence",required(input.get("evidence"),"供应商确认／实际依据"));actual.put("reason",required(input.get("reason"),"实际情况／变更原因"));
    if(action.equals("accept")||action.equals("delay")){if(action.equals("accept"))require(text(d.get("status")).equals("pending"),"工厂接单已记录，交期变化请另记延期，不回退发运／收货事实");else require(!text(d.get("status")).equals("pending"),"请先记录工厂实际接单");String due=required(input.get("expectedFactoryDate"),"本单预计出厂时间");try{java.time.LocalDate.parse(due);}catch(Exception e){throw invalid("预计出厂日期无效");}actual.put("expectedFactoryDate",due);d.put("expectedFactoryDate",due);if(action.equals("accept"))d.put("status","accepted");}
    if(action.equals("complete")){require(!text(d.get("status")).equals("pending")&&text(d.get("actualCompletedDate")).isEmpty(),"请先记录工厂接单；已有实际完工不能重复覆盖");d.put("actualCompletedDate",actual.get("date"));if(text(d.get("status")).equals("accepted"))d.put("status","completed");}
    // Quality-relevant factory/material/structure changes create a new execution batch; do not rewrite released facts.
    if(action.equals("change")){require(!Boolean.TRUE.equals(input.get("qualityChanged")),"换厂或材料／结构变更须另建关联执行批次并重新检验；原批次及放行不覆盖");}
   }
   case "dispatch"->{
    require(!text(d.get("status")).equals("pending"),"请先记录供方接单");Map<Long,Map<String,Object>> inspections=new LinkedHashMap<>();
    for(var a:maps(input.get("allocations"))){long qc=number(a.get("inspectionId"));var q=store.load(qc,"inspection",true);require(number(q.get("parentId"))==id,"放行记录不属于本执行批次");require(!Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_record where kind='inspection' and parent_record_id=? and data->>'previousId'=? and status in ('pending_signature','signed') and deleted_flag=0)",Boolean.class,id,Long.toString(qc))),"原放行已进入整改／复检，请使用新结果，不复用旧未发数量");inspections.put(qc,q);}
    actual=new LinkedHashMap<>(PartnerFulfilmentRules.dispatch(input,events,inspections));
    for(var line:maps(d.get("lines"))){long spec=number(line.get("specId"));long sent=events.stream().filter(e->text(e.get("action")).equals("dispatch")).flatMap(e->maps(map(e.get("data")).get("allocations")).stream()).filter(a->number(a.get("specId"))==spec).mapToLong(a->number(a.get("qty"))).sum();long next=maps(actual.get("allocations")).stream().filter(a->number(a.get("specId"))==spec).mapToLong(a->number(a.get("qty"))).sum();require(add(sent,next)<=number(line.get("qty")),"累计发运超出本批实际数量");}
    d.put("status","shipping");
   }
   case "receipt"->{actual=new LinkedHashMap<>(PartnerFulfilmentRules.receipt(input,events));var next=new ArrayList<>(events);next.add(Map.of("action","receipt","data",actual));boolean allAccounted=maps(d.get("lines")).stream().allMatch(line->next.stream().filter(e->text(e.get("action")).equals("receipt")&&number(map(e.get("data")).get("specId"))==number(line.get("specId"))).mapToLong(e->add(number(map(e.get("data")).get("qty")),number(map(e.get("data")).get("shortage")))).sum()==number(line.get("qty")));if(allAccounted)d.put("status","received");}
   case "after_sales"->{actual.put("date",date(input.get("date")));actual.put("reason",required(input.get("reason"),"问题／实际处理结果"));actual.put("evidence",required(input.get("evidence"),"原批次及问题依据"));actual.put("resolved",Boolean.TRUE.equals(input.get("resolved")));}
   case "expense"->{
    require(permissions.costs(),"费用记录仅限获授权工程／财务");actual.put("date",date(input.get("date")));actual.put("category",required(input.get("category"),"费用／服务费类别"));actual.put("terms",required(input.get("terms"),"本单实际约定"));actual.put("currency",currencyOrUnknown(input.get("currency")));optionalAmount(actual,"amountCents",input.get("amount"));
    for(String key:List.of("payer","payee","executor","evidence"))actual.put(key,required(input.get(key),"实际资金约定／经办及依据"));
   }
   case "close"->{
    require(Boolean.TRUE.equals(input.get("deliveryResolved"))&&Boolean.TRUE.equals(input.get("qualityResolved"))&&Boolean.TRUE.equals(input.get("expensesResolved")),"请分别核对交付、质量售后及本单适用费用，未解决不可结案");
    boolean difference=false;for(var line:maps(d.get("lines"))){long spec=number(line.get("specId"));var received=events.stream().filter(e->text(e.get("action")).equals("receipt")&&number(map(e.get("data")).get("specId"))==spec).toList();long actualQty=received.stream().mapToLong(e->number(map(e.get("data")).get("qty"))).sum(),shortage=received.stream().mapToLong(e->number(map(e.get("data")).get("shortage"))).sum();require(add(actualQty,shortage)==number(line.get("qty")),"尚有未实际收货或未核实差异数量，不能以出厂或发运代替交付结案");if(shortage>0)difference=true;}
    var issues=events.stream().filter(e->text(e.get("action")).equals("after_sales")).toList();require(issues.isEmpty()||Boolean.TRUE.equals(map(issues.get(issues.size()-1).get("data")).get("resolved")),"最后的售后／差异记录仍未解决，不能仅勾选结案");
    require(!difference||!issues.isEmpty()&&Boolean.TRUE.equals(map(issues.get(issues.size()-1).get("data")).get("resolved")),"本单有真实短少／差异，须保留实际处理和解决依据，不能伪造全部收到");
    actual.put("reason",required(input.get("reason"),"结案核对依据"));actual.put("date",date(input.get("date")));d.put("status","closed");
   }
   default->throw invalid("委外交付动作无效");
  }
  return actual;
 }
}

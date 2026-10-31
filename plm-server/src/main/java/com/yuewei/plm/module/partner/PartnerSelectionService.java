package com.yuewei.plm.module.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.attachment.vo.AttachmentVO;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** One candidate write path, paginated rows, explicit supply facts, and explicit selection confirmation; product adoption belongs to listing. */
@Service @RequiredArgsConstructor
public class PartnerSelectionService {
 private static final Set<String> DEVELOPMENT=Set.of("unknown","claimed","preferred","alternative","disputed");
 private static final Set<String> COST_FIELDS=Set.of("unitPrice","currency","priceNote","priceCents","priceOptions","sampleFeeNote","terms","sourceAttachmentId","rawQuoteFields","currencySource","priceUnit");
 private static final List<String> TEXT_FIELDS=List.of("name","series","supplierCode","supplierItemCode","material","specification","thickness","features","leadTimeNote","sampleFeeNote","color","modelText","phoneScopeDeclaration","priceNote","moq","packaging","notes","sourcePosition","sourceSequence","sameStyleKey","developmentEvidence","supplyScopeNote","styleKey","configKey","quoteVersion","quoteId","quoteLineId","sourceSheet","sourceVersion");
 private static final Set<String> READ_ONLY=Set.of("productId","productCode","specId","specIds","specLinks","confirmedAt","confirmationSnapshot","confirmed","selectionConfirmationVersion","receiptColorMode","rangeSnapshots","sampleBatches","sampleBatchCodes","catalogSourceSnapshot","catalogStyleId","catalogStyleCode","catalogStyleLabel","sourcePhoneModelIds","sourcePhones","supplierColors");
 private final PartnerStore store;
 private final PartnerOperationsAccess permissions;
 private final SupplierProfileService suppliers;
 private final PartnerProductService products;
 private final PartnerMaterialService materials;
 @Autowired(required=false) private SupplierRangeService ranges;
 @Autowired(required=false) private PartnerCatalogPhoneResolver phoneResolver;
 private PartnerSelectionRows rows(){return new PartnerSelectionRows(store);}
 private void view(){store.access.requireDomesticView();store.access.requirePermission("partner:view");}
 static boolean isCatalogSource(Map<String,Object> header){return Boolean.TRUE.equals(header.get("catalogSource"));}
 static void requireSelectionPurpose(Map<String,Object> header){require(!isCatalogSource(header),"该资料是供应商候选来源，请从产品目录选款后加入我方选品，不能直接修改或确认来源");}
 static Map<String,Object> sourceSupplierHeader(PartnerStore store,Map<String,Object> input){
  var result=new LinkedHashMap<>(input);if(!"supplier_quote".equals(text(input.get("kind")))){
   var found=store.jdbc.queryForList("select distinct coalesce(data->>'supplierCode','') as supplier_code from plm_partner_selection_row where record_id=? order by supplier_code",number(input.get("id")));
   if(!found.isEmpty()){require(found.size()==1&&!text(found.get(0).get("supplier_code")).isEmpty(),"这份历史资料含多个或未确认供应商，请先明确来源，不能自动归类");String actual=text(found.get(0).get("supplier_code"));require(text(input.get("supplierCode")).isEmpty()||actual.equals(text(input.get("supplierCode"))),"历史表头与款式供应商不一致，请先核对");result.put("supplierCode",actual);}
  }return result;
 }
 private Map<String,Object> selection(long id,boolean lock){var header=store.load(id,"selection",lock);requireSelectionPurpose(header);if(lock)require("my_selection".equals(text(header.get("purpose"))),"这份旧资料用途尚未明确，只读保留；请从供应商目录选择本次需要的款式及规格");return header;}

 public Map<String,Object> page(Map<String,String> query){
  view();String where="r.kind='selection' and r.deleted_flag=0 and coalesce(r.data->>'catalogSource','false')<>'true' and "+store.access.visibleSqlClause("partner","r.record_id");var args=new ArrayList<Object>();
  String purpose=text(query.get("purpose"));if(purpose.equals("historical"))where+=" and coalesce(r.data->>'purpose','')=''";else if(!purpose.equals("all"))where+=" and r.data->>'purpose'='my_selection'";
  String k=text(query.get("keyword"));if(!k.isEmpty()){where+=" and (r.name ilike ? or r.code ilike ?)";args.add("%"+k+"%");args.add("%"+k+"%");}
  String status=text(query.get("status"));if(!status.isEmpty()&&!status.equals("all")){where+=" and r.status=?";args.add(status);}
  String supplier=text(query.get("supplierCode"));if(!supplier.isEmpty()){
   suppliers.requireSupplier(supplier);where+=" and coalesce(r.supplier_code,'') in ('',?) and not exists(select 1 from plm_partner_selection_row sr where sr.record_id=r.record_id and coalesce(sr.data->>'supplierCode','')<>'' and sr.data->>'supplierCode'<>?)";args.add(supplier);args.add(supplier);
  }
  long page=Math.max(1,number(query.getOrDefault("page","1"))),size=Math.max(1,Math.min(100,number(query.getOrDefault("size","30"))));
  long total=store.jdbc.queryForObject("select count(*) from plm_partner_record r where "+where,Long.class,args.toArray());args.add(size);args.add(Math.multiplyExact(page-1,size));
  var content=store.jdbc.queryForList("select r.record_id as id,r.code,r.name,r.status,r.version,r.updated_at as \"updatedAt\",coalesce((r.data->>'rowCount')::bigint,0) as \"rowCount\",coalesce((r.data->>'confirmedCount')::bigint,0) as \"confirmedCount\" from plm_partner_record r where "+where+" order by r.updated_at desc,r.record_id desc limit ? offset ?",args.toArray());
  return Map.of("content",content,"totalElements",total,"page",page,"size",size,"totalPages",(total+size-1)/size,"canWrite",permissions.engineering(),"canReadCost",permissions.costs());
 }
 public Map<String,Object> detail(long id){
  view();var d=store.load(id,"selection",false);if(isCatalogSource(d))d=sourceSupplierHeader(store,d);d.remove("rows");d.put("schemaVersion",2);d.putAll(rows().counts(id));
  d.put("files",store.jdbc.queryForList("select a.attachment_id as id,a.file_name as name,a.version_no as version,a.content_type as type,a.file_size as size from plm_partner_file f join plm_attachment a on a.attachment_id=f.attachment_id where f.record_id=? and a.deleted_flag=0 and "+store.access.visibleSqlClause("file","a.attachment_id")+" order by a.attachment_id desc limit 100",id));
  d.put("events",store.jdbc.queryForList("select event_id as id,action,created_at as time,created_by as operator from plm_partner_event where record_id=? order by event_id desc limit 30",id));
  var originals=permissions.costs()?originals(id,Map.of("page","1","size","5")):Map.of("content",List.of(),"totalElements",0);
  d.put("sourceOriginals",originals.get("content"));d.put("sourceOriginalCount",originals.get("totalElements"));
  boolean historical=!isCatalogSource(d)&&!"my_selection".equals(text(d.get("purpose")));d.put("purpose",isCatalogSource(d)?"catalog_source":historical?"historical_draft":"my_selection");d.put("canWrite",permissions.engineering()&&!isCatalogSource(d)&&!historical);d.put("canReadCost",permissions.costs());return visible(d);
 }
 public Map<String,Object> originals(long id,Map<String,String> q){
  view();store.load(id,"selection",false);require(permissions.costs(),"仅工程或获授权财务可查看报价原件");
  long page=Math.max(1,number(q.getOrDefault("page","1"))),size=Math.max(1,Math.min(100,number(q.getOrDefault("size","20"))));
  String where="f.record_id=? and a.deleted_flag=0 and coalesce(a.content_type,'') not like 'image/%' and (f.purpose='cooperation_cost' or exists(select 1 from plm_partner_file_job j where j.kind='import' and j.record_id=f.record_id and j.source_attachment_id=a.attachment_id)) and "+store.access.visibleSqlClause("file","a.attachment_id");
  long count=store.jdbc.queryForObject("select count(*) from plm_partner_file f join plm_attachment a on a.attachment_id=f.attachment_id where "+where,Long.class,id);
  var files=store.jdbc.queryForList("select a.attachment_id as id,a.file_name as name,a.version_no as version,a.content_type as type,a.file_size as size from plm_partner_file f join plm_attachment a on a.attachment_id=f.attachment_id where "+where+" order by a.attachment_id desc limit ? offset ?",id,size,Math.multiplyExact(page-1,size));
  return Map.of("content",files,"totalElements",count,"page",page,"size",size);
 }
 private Map<String,String> query(Map<String,String> raw){
  var q=new LinkedHashMap<>(raw);if(!q.containsKey("keyword")&&q.containsKey("q"))q.put("keyword",q.get("q"));
  switch(text(q.get("state"))){case "selected"->q.put("selected","true");case "confirmed"->q.put("confirmed","true");case "draft"->q.put("confirmed","false");case "discarded"->q.put("discarded","true");default->{}}
  return q;
 }
 public Map<String,Object> pageRows(long id,Map<String,String> q){
  view();selection(id,false);var page=new LinkedHashMap<>(rows().page(id,query(q)));
  page.put("content",currentRows(id,enrichBatch(maps(page.get("content")),true)));return page;
 }
 @Transactional(readOnly=true) public void forEachRow(long id,Map<String,String> q,Consumer<Map<String,Object>> sink){
  forEachRowBatch(id,q,batch->batch.forEach(sink));
 }
 @Transactional(readOnly=true) public void forEachRowBatch(long id,Map<String,String> q,Consumer<List<Map<String,Object>>> sink){
  view();store.load(id,"selection",false);rows().scanBatches(id,query(q),batch->sink.accept(currentRows(id,enrichBatch(batch,false))));
 }
 public List<String> supplierCodes(long id){view();store.load(id,"selection",false);return store.jdbc.queryForList("select distinct coalesce(data->>'supplierCode','') as code from plm_partner_selection_row where record_id=? order by code",String.class,id);}
 public Map<String,Object> row(long id,String rowId){view();selection(id,false);require(validRowId(rowId),"行标识无效");var r=rows().find(id,rowId,false);require(!r.isEmpty(),"选品行不存在或不属于当前表");return currentRows(id,List.of(enrich(r))).get(0);}
 private List<Map<String,Object>> currentRows(long id,List<Map<String,Object>> rows){var styles=new PartnerCatalogStyles(store);var result=rows.stream().map(r->styles.enrich("selection",id,r)).toList();styles.projectCurrent(result);return result.stream().map(this::visible).toList();}
 public Map<String,Object> importedRowBaseline(long id,String rowId){permissions.engineer();store.load(id,"selection",false);require(validRowId(rowId),"行标识无效");return rows().find(id,rowId,false);}
 private Map<String,Object> enrich(Map<String,Object> row){return enrichBatch(List.of(row),true).get(0);}
 /** Related facts are fetched per bounded page/batch, never once per exported row. */
 private List<Map<String,Object>> enrichBatch(List<Map<String,Object>> input,boolean refreshRanges){
  var scope=input.stream().anyMatch(this::needsPhoneResolution)?phoneScope():null;
  List<Map<String,Object>> result=input.stream().map(r->scope!=null&&needsPhoneResolution(r)?scope.resolve(r):(Map<String,Object>)new LinkedHashMap<>(r)).toList();
  var productIds=result.stream().filter(r->number(r.get("productId"))>0&&text(r.get("productCode")).isEmpty()).map(r->number(r.get("productId"))).distinct().toList();
  Map<Long,String> codes=new HashMap<>();for(var chunk:chunks(productIds))for(var item:store.jdbc.queryForList("select record_id,code from plm_partner_record where kind='product' and deleted_flag=0 and record_id in ("+marks(chunk.size())+")",chunk.toArray()))codes.put(number(item.get("record_id")),text(item.get("code")));
  var confirmedIds=result.stream().filter(PartnerSelectionService::confirmed).map(r->text(r.get("rowId"))).toList();Map<String,List<Map<String,Object>>> samples=new HashMap<>();
  if(!confirmedIds.isEmpty())for(var sample:store.jdbc.queryForList("select s.record_id as id,s.code,s.name,s.data->>'selectionRowId' as \"selectionRowId\",s.data->>'receivedDate' as date,s.data->>'displayLabel' as \"displayLabel\" from plm_partner_record s where s.kind='sample' and s.deleted_flag=0 and s.data->>'selectionRowId' in ("+marks(confirmedIds.size())+") and "+store.access.visibleSqlClause("partner","s.record_id")+" order by s.record_id desc",confirmedIds.toArray())){String rid=text(sample.remove("selectionRowId"));samples.computeIfAbsent(rid,k->new ArrayList<>()).add(sample);}
  var ids=result.stream().flatMap(r->maps(r.get("refs")).stream()).map(r->number(r.get("id"))).filter(n->n>0).distinct().toList();Map<Long,Map<String,Object>> files=new HashMap<>();
  for(var chunk:chunks(ids))for(var file:store.jdbc.queryForList("select a.attachment_id as id,a.file_name as name,a.version_no as version,a.content_type as type,a.file_size as size from plm_attachment a where a.deleted_flag=0 and a.attachment_id in ("+marks(chunk.size())+") and "+store.access.visibleSqlClause("file","a.attachment_id"),chunk.toArray()))files.put(number(file.get("id")),file);
  var downstream=downstreamRows(result.stream().map(r->text(r.get("rowId"))).filter(r->!r.isEmpty()).toList(),null);
  for(var r:result){
  var origin=map(r.get("catalogSourceSnapshot"));if(!origin.isEmpty()){var allowed=scopeIds(origin.get("phoneModelIds")).stream().filter(n->!scopeIds(origin.get("unavailablePhoneModelIds")).contains(n)).toList();r.put("sourcePhoneModelIds",allowed);r.put("sourcePhones",maps(origin.get("phones")).stream().filter(p->allowed.contains(number(p.get("phoneModelId")))).toList());}
  if(refreshRanges&&!confirmed(r)&&ranges!=null&&permissions.engineering()&&!text(r.get("supplierCode")).isEmpty()){
   try{r.put("rangeSnapshots",ranges.resolveReferences(text(r.get("supplierCode")),maps(r.get("rangeRefs")),text(r.get("styleKey")),text(r.get("configKey")),false));r.put("rangeIssues",List.of());}catch(BusinessException e){r.put("rangeIssues",List.of(e.getMessage()));}
  }
  if(codes.containsKey(number(r.get("productId"))))r.put("productCode",codes.get(number(r.get("productId"))));
  r.put("sampleBatches",samples.getOrDefault(text(r.get("rowId")),List.of()));
  r.put("sampleBatchCodes",maps(r.get("sampleBatches")).stream().map(x->text(x.get("code"))).toList());
  var fileIds=maps(r.get("refs")).stream().map(f->number(f.get("id"))).filter(n->n>0).distinct().toList();
  r.put("fileDetails",fileIds.stream().sorted().map(files::get).filter(Objects::nonNull).toList());
  boolean blocked=directDownstream(r)||downstream.contains(text(r.get("rowId")));r.put("canReviseSelection",!blocked);r.put("selectionChangeBlockedReason",blocked?"已有真实收样或后续业务记录，原事实保留":"");
  }return result;
 }
 private static String marks(int size){return String.join(",",Collections.nCopies(size,"?"));}
 private static <T> List<List<T>> chunks(List<T> values){var result=new ArrayList<List<T>>();for(int i=0;i<values.size();i+=500)result.add(values.subList(i,Math.min(i+500,values.size())));return result;}

 @Transactional public AttachmentVO upload(long id,MultipartFile file,String purpose,Long previous,String version,String reason,HttpServletRequest request){
  permissions.engineer();var old=selection(id,true);var result=materials.upload(id,"selection",file,purpose,previous,version,reason,request);saveHeader(id,old);return result;
 }
 @Transactional public Map<String,Object> save(Long id,Map<String,Object> input){
  permissions.engineer();require(!input.containsKey("rows"),"新版选品按行增量保存，不接受整表覆盖；请刷新后重试");String request=request(input);boolean creating=id==null;
  if(creating){Long repeated=store.created("selection",request);if(repeated!=null)return detail(repeated);id=store.create("selection",selectionName(input,Map.of()));store.remember("selection",request,id);}
  var old=creating?store.load(id,"selection",true):selection(id,true);if(replayed(id,request,"selection_save"))return detail(id);if(!creating)store.check(old,number(input.get("version")));
  var next=header(old,input);if(creating)next.put("purpose","my_selection");saveHeader(id,next);store.event(id,"selection_save",Map.of("name",next.get("name"),"schemaVersion",2),request);return detail(id);
 }
 private Map<String,Object> header(Map<String,Object> old,Map<String,Object> input){
  var d=new LinkedHashMap<>(old);d.keySet().removeAll(Set.of("rows","files","events","revisions","canWrite","canReadCost"));
  d.put("name",selectionName(input,old));d.put("notes",bounded(input.getOrDefault("notes",old.get("notes")),12000,"备注"));
  for(String key:List.of("supplierCode","currency","quoteDate","quoteVersion","sourceVersion"))if(input.containsKey(key))d.put(key,text(input.get(key)));
  if(!text(d.get("supplierCode")).isEmpty()){if(text(d.get("supplierCode")).equals(text(old.get("supplierCode"))))suppliers.requireSupplier(text(d.get("supplierCode")));else suppliers.requireSupplierForNewUse(text(d.get("supplierCode")));}
  d.put("refs",canonicalRefs(number(old.get("id")),input.getOrDefault("refs",old.getOrDefault("refs",List.of()))));d.put("schemaVersion",2);return d;
 }
 private void saveHeader(long id,Map<String,Object> current){
  var d=new LinkedHashMap<>(current);d.keySet().removeAll(Set.of("rows","files","events","revisions","canWrite","canReadCost","sampleBatches"));
  var counts=rows().counts(id);d.putAll(counts);long total=number(counts.get("rowCount")),confirmed=number(counts.get("confirmedCount"));d.put("schemaVersion",2);
  d.put("status",total>0&&number(counts.get("finishedCount"))==total?"confirmed":confirmed>0?"partial":"draft");store.revision(id,d);store.save(id,d);
 }
 void refreshCatalogDrafts(String kind,long source,String sourceRow,Map<String,Object> current,Set<String> changedFields,String request){
  var fields=new LinkedHashSet<>(changedFields);fields.retainAll(Set.of("name","series","supplierItemCode","color","supplierColorIds","supplierColors","material","specification","packaging","thickness","features","leadTimeNote","unitPrice","currency","currencySource","priceNote","priceOptions","priceUnit","moq","moqQuantity","moqUnit","moqScope","moqNote"));
  if(fields.isEmpty())return;if(fields.contains("currency"))fields.add("currencySource");
  var linked=store.jdbc.queryForList("select s.record_id,s.row_id::text from plm_partner_selection_row s join plm_partner_record r on r.record_id=s.record_id where r.kind='selection' and r.deleted_flag=0 and r.data->>'purpose'='my_selection' and coalesce(r.data->>'catalogSource','false')<>'true' and not s.confirmed and s.data->'catalogSourceSnapshot'->>'sourceKind'=? and s.data->'catalogSourceSnapshot'->>'recordId'=? and s.data->'catalogSourceSnapshot'->>'rowId'=? and ("+store.access.visibleSqlClause("partner","r.record_id")+") order by s.record_id,s.row_id",kind,Long.toString(source),sourceRow);
  var grouped=new LinkedHashMap<Long,List<String>>();for(var link:linked)grouped.computeIfAbsent(number(link.get("record_id")),k->new ArrayList<>()).add(text(link.get("row_id")));
  for(var entry:grouped.entrySet()){long id=entry.getKey();var head=selection(id,true);long changed=0;for(String rid:entry.getValue()){
   var old=rows().find(id,rid,true);if(confirmed(old))continue;var next=new LinkedHashMap<>(old);for(String field:fields)next.put(field,current.get(field));
   if(signature(store.json,old).equals(signature(store.json,next)))continue;rows().put(id,next,old,request);changed++;
  }if(changed>0){saveHeader(id,head);store.event(id,"selection_catalog_refresh",Map.of("sourceKind",kind,"sourceRecordId",source,"sourceRowId",sourceRow,"fields",fields,"changed",changed,"confirmedHistoryChanged",false),"catalog-refresh-"+UUID.nameUUIDFromBytes((source+":"+sourceRow+":"+request).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}}
 }
 @Transactional public Map<String,Object> patchRows(long id,Map<String,Object> input){
  return patchRows(id,input,false);
 }
 /** Only the catalogue service may attach a server-resolved immutable provenance snapshot. */
 Map<String,Object> patchCatalogRows(long id,Map<String,Object> input){return patchRows(id,input,true);}
 private Map<String,Object> patchRows(long id,Map<String,Object> input,boolean catalogue){
  permissions.engineer();String request=request(input);var head=selection(id,true);if(replayed(id,request,"selection_rows"))return detail(id);store.check(head,number(input.get("version")));
  require(input.get("operations") instanceof List<?>,"请指定本次修改的行");long changed=0;var phoneScope=phoneScope();var blocked=downstreamRows(maps(input.get("operations")).stream().map(op->text(op.get("rowId"))).filter(rid->!rid.isEmpty()).distinct().toList(),id);
  for(var op:maps(input.get("operations"))){String kind=text(op.getOrDefault("op","upsert"));require(Set.of("upsert","clear","exclude").contains(kind),"行操作无效");String rid=text(op.get("rowId"));if(rid.isEmpty())rid=UUID.randomUUID().toString();require(validRowId(rid),"选品行标识无效");var old=rows().find(id,rid,true);
   require(old.isEmpty()?number(op.get("rowVersion"))==0:number(op.get("rowVersion"))==number(old.get("rowVersion")),"选品行已被修改，请核对前后差异");
   var changes=new LinkedHashMap<>(map(op.get("changes")));if(kind.equals("exclude"))changes.put("discarded",true);
   if(kind.equals("clear")){for(Object key:list(op.get("fields")))changes.put(text(key),"");}
   if(op.containsKey("original"))for(var e:map(op.get("original")).entrySet())require(signature(store.json,e.getValue()).equals(signature(store.json,old.get(e.getKey()))),"原值已变化："+e.getKey());
   requireDraftChangeAllowed(old,changes,blocked);
   var baseline=old;if(catalogue&&changes.containsKey("catalogSourceSnapshot")){require(old.isEmpty(),"不能覆盖既有选品的来源快照");baseline=new LinkedHashMap<>(old);baseline.put("catalogSourceSnapshot",map(changes.remove("catalogSourceSnapshot")));for(String field:COST_FIELDS)if(changes.containsKey(field))baseline.put(field,changes.get(field));if(changes.containsKey("pairings"))baseline.put("pairings",changes.get("pairings"));}
   var prepared=canonicalRow(id,baseline,rawWithId(changes,rid),phoneScope);
   if(catalogue&&old.isEmpty()&&!map(prepared.get("catalogSourceSnapshot")).isEmpty()){var snapshot=new LinkedHashMap<>(map(prepared.get("catalogSourceSnapshot")));snapshot.put("selectionBaseline",catalogBaseline(prepared));prepared.put("catalogSourceSnapshot",snapshot);}
   rows().put(id,prepared,old,request);if(catalogue&&old.isEmpty())new PartnerCatalogStyles(store).inherit(id,rid,map(prepared.get("catalogSourceSnapshot")),request);changed++;
  }
  saveHeader(id,head);store.event(id,"selection_rows",Map.of("changed",changed),request);return detail(id);
 }
 @Transactional public Map<String,Object> batch(long id,Map<String,Object> input){
  permissions.engineer();String request=request(input);var head=selection(id,true);if(replayed(id,request,"selection_batch"))return detail(id);store.check(head,number(input.get("version")));
  var changes=map(input.get("changes"));require(!changes.isEmpty(),"请选择本次批量修改的字段");final long[] count={0};var phoneScope=phoneScope();
  java.util.function.BiConsumer<Map<String,Object>,Set<String>> apply=(old,blocked)->{require(!confirmed(old),"批量目标包含已冻结的选品行，请先筛选草稿");requireDraftChangeAllowed(old,changes,blocked);var perRow=new LinkedHashMap<>(changes);
   if(changes.containsKey("rangeRefs")){var incoming=maps(changes.get("rangeRefs"));var types=new HashSet<String>();for(var reference:incoming){String type=required(reference.get("type"),"范围类型");require(Set.of("model","color").contains(type),"范围类型无效");types.add(type);}var refs=new ArrayList<>(maps(old.get("rangeRefs")).stream().filter(ref->!types.contains(text(ref.get("type")))).toList());refs.addAll(incoming);perRow.put("rangeRefs",refs);}
   var prepared=canonicalRow(id,old,rawWithId(perRow,text(old.get("rowId"))),phoneScope);rows().put(id,prepared,old,request);count[0]++;};
  var ids=list(input.get("rowIds"));if(!ids.isEmpty()){var seen=new HashSet<String>();var blocked=downstreamRows(ids.stream().map(PartnerValues::text).toList(),id);for(Object value:ids){String rid=text(value);require(validRowId(rid)&&seen.add(rid),"批量行标识无效或重复");var old=rows().find(id,rid,true);require(!old.isEmpty(),"批量行不属于本表");apply.accept(old,blocked);}}
  else{var snap=map(input.get("filterSnapshot"));require(!snap.isEmpty()&&number(snap.get("version"))==number(head.get("version")),"全部筛选结果已变化，请重新核对");var q=new LinkedHashMap<String,String>();for(var e:snap.entrySet())q.put(e.getKey(),text(e.getValue()));rows().scanBatches(id,query(q),batch->{var blocked=downstreamRows(batch.stream().map(row->text(row.get("rowId"))).toList(),id);for(var row:batch)apply.accept(row,blocked);});}
  require(count[0]>0,"没有符合本次操作的行");saveHeader(id,head);store.event(id,"selection_batch",Map.of("changed",count[0],"fields",changes.keySet()),request);return detail(id);
 }
 /** Import staging is streamed; one transaction publishes the whole confirmed logical import. */
 @Transactional public Map<String,Object> applyImportedRows(long id,long baseVersion,String requestId,Iterable<Map<String,Object>> incoming){
  permissions.engineer();var head=selection(id,true);String request=request(Map.of("requestId",requestId));if(replayed(id,request,"selection_import"))return detail(id);store.check(head,baseVersion);long count=0;var phoneScope=phoneScope();
  var batch=new ArrayList<Map<String,Object>>(100);for(var input:incoming){batch.add(input);if(batch.size()==100){count+=applyImportedBatch(id,request,batch,phoneScope);batch.clear();}}if(!batch.isEmpty())count+=applyImportedBatch(id,request,batch,phoneScope);
  saveHeader(id,head);store.event(id,"selection_import",Map.of("rows",count,"baseVersion",baseVersion),request);return detail(id);
 }
 private long applyImportedBatch(long id,String request,List<Map<String,Object>> batch,PartnerCatalogPhoneResolver.ResolutionScope phoneScope){var blocked=downstreamRows(batch.stream().map(row->text(row.get("rowId"))).toList(),id);long count=0;for(var input:batch){String rid=required(input.get("rowId"),"导入行身份");require(validRowId(rid),"导入行标识无效");var old=rows().find(id,rid,true);
   if(!old.isEmpty())require(number(input.get("rowVersion"))==number(old.get("rowVersion")),"导入行已变化，请重新预览");
   var merged=new LinkedHashMap<>(input);if(!old.isEmpty())merged.entrySet().removeIf(e->text(e.getValue()).isEmpty()&&!Set.of("selected","discarded").contains(e.getKey()));
   requireDraftChangeAllowed(old,merged,blocked);
   var prepared=canonicalRow(id,old,rawWithId(merged,rid),phoneScope);rows().put(id,prepared,old,request);count++;
  }
  return count;
 }
 public Map<String,Object> canonicalRow(long id,Map<String,Object> previous,Map<String,Object> input){
  return canonicalRow(id,previous,input,null);
 }
 Map<String,Object> canonicalRow(long id,Map<String,Object> previous,Map<String,Object> input,PartnerCatalogPhoneResolver.ResolutionScope scope){
  permissions.engineer();String rid=required(input.getOrDefault("rowId",previous.get("rowId")),"行标识");require(validRowId(rid),"行标识无效");
  if(!permissions.costs()){for(String field:COST_FIELDS)if(input.containsKey(field))require(signature(store.json,input.get(field)).equals(signature(store.json,previous.get(field)))||(!previous.containsKey(field)&&text(input.get(field)).isEmpty()),"无权修改报价成本资料："+field);if(input.containsKey("pairings")){Object proposed=input.get("pairings");var saved=maps(previous.get("pairings"));require(signature(store.json,proposed).equals(signature(store.json,saved))||signature(store.json,proposed).equals(signature(store.json,PartnerCatalogFields.operationalPairings(saved))),"供货依据仅有成本维护权限可修改；请保留原可供配对并选择实际规格");input=new LinkedHashMap<>(input);input.put("pairings",saved);}}
  if(confirmed(previous)){for(var e:input.entrySet())if(!Set.of("rowVersion","sortOrder","sampleBatches","sampleBatchCodes","phones").contains(e.getKey()))require(signature(store.json,previous.get(e.getKey())).equals(signature(store.json,e.getValue())),"已确认行冻结，请复制新候选");return copy(store.json,previous);}
  var row=new LinkedHashMap<>(previous);row.put("rowId",rid);
  var readOnlyBaseline=PartnerCatalogStyles.currentData(previous);
  for(String key:READ_ONLY)if(input.containsKey(key)&&!Set.of("rangeSnapshots","sampleBatches","sampleBatchCodes").contains(key)){
   Object value=input.get(key);boolean empty=value==null||text(value).isEmpty()||Boolean.FALSE.equals(value)||(value instanceof Number n&&n.longValue()==0)||(value instanceof Map<?,?> m&&m.isEmpty())||(value instanceof List<?> l&&l.isEmpty());
   require(readOnlyBaseline.containsKey(key)?signature(store.json,value).equals(signature(store.json,readOnlyBaseline.get(key))):empty,"系统只读资料不可修改："+key);
  }
  for(String key:TEXT_FIELDS)row.put(key,bounded(input.getOrDefault(key,previous.get(key)),key.equals("name")||key.equals("sourceSequence")?255:12000,key));
  require(Set.of("","not_provided").contains(text(row.get("phoneScopeDeclaration"))),"型号资料声明无效，请核对来源原件");
  if(!map(previous.get("catalogSourceSnapshot")).isEmpty())require(text(row.get("phoneScopeDeclaration")).equals(text(previous.get("phoneScopeDeclaration"))),"选品不能改写供应商的型号声明，请在来源资料维护");
  for(String key:List.of("selected","discarded")){Object value=input.getOrDefault(key,previous.getOrDefault(key,false));require(value instanceof Boolean,"选择／排除标记必须为布尔值");row.put(key,value);}
  String supplier=text(row.get("supplierCode"));if(!supplier.isEmpty()){if(supplier.equals(text(previous.get("supplierCode"))))suppliers.requireSupplier(supplier);else suppliers.requireSupplierForNewUse(supplier);}
  if(input.containsKey("sourceAttachmentId")){long file=number(input.get("sourceAttachmentId"));require(file>0,"来源原件身份无效");store.access.requireFile(file);require(Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_file where record_id=? and attachment_id=?)",Boolean.class,id,file)),"原件不属于当前选品资料");row.put("sourceAttachmentId",file);}
  if(!text(row.get("quoteId")).isEmpty()){
   require(text(row.get("quoteId")).matches("[1-9][0-9]*"),"来源报价身份无效");long quote=number(row.get("quoteId"));var source=store.load(quote,"supplier_quote",false);require(supplier.equals(text(source.get("supplierCode"))),"来源报价不属于当前供应商");
   require(text(source.get("quoteVersion")).isEmpty()||text(source.get("quoteVersion")).equals(text(row.get("quoteVersion"))),"来源报价版本不一致");
   if(!text(row.get("quoteLineId")).isEmpty()){require(validRowId(text(row.get("quoteLineId"))),"来源报价行身份无效");require(Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_quote_line where record_id=? and line_id=?::uuid)",Boolean.class,quote,row.get("quoteLineId"))),"来源报价行不属于该报价");}
  }
  row.put("phoneModelIds",scopeIds(input.getOrDefault("phoneModelIds",previous.get("phoneModelIds"))));
  if(needsPhoneResolution(row)&&phoneResolver!=null){var resolved=(scope==null?phoneScope():scope).resolve(row);for(String key:List.of("phoneModelIds","phoneMatchIssues","phoneMatchSummary"))row.put(key,resolved.get(key));}
  var origin=map(row.get("catalogSourceSnapshot"));if(!origin.isEmpty()){
   require(text(origin.get("supplierCode")).equals(supplier),"目录来源的供应商不可更换，请另选真实来源");
   var allowed=scopeIds(origin.get("phoneModelIds"));var chosen=scopeIds(row.get("phoneModelIds"));
   boolean styleDraft="style".equals(text(origin.get("selectionIntent")));
   require((styleDraft&&chosen.isEmpty())||(!chosen.isEmpty()&&(allowed.containsAll(chosen)||styleDraft&&"unspecified".equals(text(origin.get("phoneScopeMode"))))),"unspecified".equals(text(origin.get("phoneScopeMode")))
    ?"本行只能调整此前所选型号；新增型号请从目录增选，不能改写原选品快照"
    :"所选型号必须是来源当时可供型号的非空子集；请从目录另选来源，不能扩大供方范围");
   require(chosen.stream().noneMatch(scopeIds(origin.get("unavailablePhoneModelIds"))::contains),"来源明确不供的型号不能加入选品");
   if("quote".equals(text(origin.get("sourceKind"))))require(text(row.get("quoteId")).equals(text(origin.get("recordId")))&&text(row.get("quoteLineId")).equals(text(origin.get("rowId"))),"目录来源报价身份不可清空或更换");
  }
  row.put("phones",scopePhones(((List<?>)row.get("phoneModelIds")).stream().map(PartnerValues::number).toList(),scopeIds(previous.get("phoneModelIds"))));
  // Legacy facts are kept as evidence, never upgraded to verified color pairings.
  for(String key:List.of("availablePhoneModelIds","unavailablePhoneModelIds"))row.put(key,scopeIds(input.getOrDefault(key,previous.get(key))));
  String price=text(input.getOrDefault("unitPrice",previous.get("unitPrice")));row.put("unitPrice",price.isEmpty()?"":BigDecimal.valueOf(cents(price),2).toPlainString());
  String curr=text(input.getOrDefault("currency",previous.get("currency"))).toUpperCase(Locale.ROOT);row.put("currency",curr.isEmpty()?"":currency(curr));
  row.putAll(PartnerCatalogFields.normalize(previous,input,previous.isEmpty()));
  row.put("priceOptions",priceOptions(input.getOrDefault("priceOptions",previous.get("priceOptions"))));
  String quantity=text(input.getOrDefault("sampleQty",previous.get("sampleQty")));row.put("sampleQty",quantity.isEmpty()?"":qty(quantity,true));
  String development=text(input.getOrDefault("developmentStatus",previous.getOrDefault("developmentStatus","unknown")));require(DEVELOPMENT.contains(development),"开发优先状态无效");row.put("developmentStatus",development);
  String developed=text(input.getOrDefault("developedOn",previous.get("developedOn")));row.put("developedOn",developed.isEmpty()?"":date(developed));
  if(development.equals("preferred")){required(row.get("sameStyleKey"),"人工确认的同款分组");required(row.get("developmentEvidence"),"开发先后核对依据");required(supplier,"供应商");}
  long target=number(input.getOrDefault("targetSpecId",previous.get("targetSpecId")));if(target>0)requireTarget(target);row.put("targetSpecId",target);
  Object acknowledgement=input.getOrDefault("targetColorMappingAcknowledged",previous.getOrDefault("targetColorMappingAcknowledged",false));require(acknowledgement instanceof Boolean,"现有规格颜色核对须明确勾选");row.put("targetColorMappingAcknowledged",acknowledgement);
  row.put("refs",canonicalRefs(id,input.getOrDefault("refs",previous.getOrDefault("refs",List.of()))));
  row.put("styleKey",text(row.get("styleKey")).isEmpty()?(!text(row.get("supplierItemCode")).isEmpty()?row.get("supplierItemCode"):rid):row.get("styleKey"));
  row.put("configKey",text(row.get("configKey")).isEmpty()?"default":row.get("configKey"));
  boolean colorContextChanged=List.of("supplierCode","styleKey","configKey").stream().anyMatch(key->!text(row.get(key)).equals(text(previous.get(key))));
  if(input.containsKey("supplierColorIds")||input.containsKey("newSupplierColors")||(colorContextChanged&&!scopeIds(previous.get("supplierColorIds")).isEmpty())){
   require(ranges!=null&&!supplier.isEmpty(),"请先选择供应商再维护颜色");
   var chosen=ranges.catalogColors(supplier,input.getOrDefault("supplierColorIds",previous.get("supplierColorIds")),input.get("newSupplierColors"),scopeIds(previous.get("supplierColorIds")),text(row.get("styleKey")),text(row.get("configKey")));
   row.put("supplierColorIds",chosen.stream().map(color->number(color.get("id"))).toList());
   row.put("supplierColors",chosen.stream().map(color->Map.of("id",color.get("id"),"name",color.get("name"),"supplierColorCode",color.get("supplierColorCode"))).toList());
   if(!chosen.isEmpty()||(input.containsKey("color")&&text(input.get("color")).isEmpty()))row.put("color",chosen.stream().map(color->text(color.get("name"))).collect(java.util.stream.Collectors.joining("、")));row.remove("newSupplierColors");
  }else if(input.containsKey("color")&&!text(input.get("color")).equals(text(previous.get("color")))){row.remove("supplierColorIds");row.remove("supplierColors");}
  row.put("rangeRefs",maps(input.getOrDefault("rangeRefs",previous.get("rangeRefs"))));row.put("pairings",maps(input.getOrDefault("pairings",previous.get("pairings"))));row.put("selectedPairs",maps(input.getOrDefault("selectedPairs",previous.get("selectedPairs"))));
  for(var pair:maps(row.get("selectedPairs")))require(scopeIds(row.get("phoneModelIds")).contains(number(pair.get("phoneModelId"))),"选择的型号颜色组合超出本行已选型号");
  if("not_provided".equals(text(row.get("phoneScopeDeclaration")))&&!("style".equals(text(origin.get("selectionIntent")))&&"unspecified".equals(text(origin.get("phoneScopeMode")))))require(!PartnerCatalogPhoneResolver.supplierScopeProvided(row),"已有型号原文、范围或配对，不能标记为供应商未注明型号");
  if(!origin.isEmpty())for(var pair:maps(row.get("selectedPairs")))require(maps(origin.get("unavailablePairs")).stream().noneMatch(p->number(p.get("phoneModelId"))==number(pair.get("phoneModelId"))&&number(p.get("colorId"))==number(pair.get("colorId"))),"来源已明确不供该型号颜色组合，不能在选品中改写为可供");
  row.put("rangeVersionAcknowledgements",maps(input.getOrDefault("rangeVersionAcknowledgements",previous.get("rangeVersionAcknowledgements"))));
  row.put("rangeIssues",List.of());row.put("rangeSnapshots",List.of());
  if(ranges!=null&&!supplier.isEmpty()){
   row.put("pairings",supplyFacts(row,true));
   row.put("selectedPairs",ranges.normalizeSelectedPairs(supplier,maps(row.get("selectedPairs")),true));
   try{row.put("rangeSnapshots",ranges.resolveReferences(supplier,maps(row.get("rangeRefs")),text(row.get("styleKey")),text(row.get("configKey")),false));}catch(BusinessException e){row.put("rangeIssues",List.of(e.getMessage()));}
  }else require(maps(row.get("pairings")).isEmpty()&&maps(row.get("selectedPairs")).isEmpty()&&maps(row.get("rangeRefs")).isEmpty(),"请先选择供应商，再核对型号颜色范围");
  row.put("confirmed",false);row.putIfAbsent("confirmedAt","");row.putIfAbsent("productId",0L);row.putIfAbsent("specId",0L);row.putIfAbsent("confirmationSnapshot",Map.of());return row;
 }
 private Map<String,Object> previewSnapshot(Map<String,Object> source,List<String> fields){var result=new LinkedHashMap<String,Object>();for(String field:fields)if(source.containsKey(field))result.put(field,source.get(field));return result;}
 private List<Map<String,Object>> previewPairs(Map<String,Object> row,boolean facts){var selected=maps(row.get("selectedPairs")).stream().map(pair->number(pair.get("phoneModelId"))+":"+number(pair.get("colorId"))).collect(java.util.stream.Collectors.toSet());var phones=new HashSet<>(scopeIds(row.get("phoneModelIds")));return maps(row.get(facts?"pairings":"selectedPairs")).stream().filter(pair->phones.contains(number(pair.get("phoneModelId")))&&selected.contains(number(pair.get("phoneModelId"))+":"+number(pair.get("colorId")))).map(pair->{var value=previewSnapshot(pair,List.of("phoneModelId","colorId"));var color=previewSnapshot(map(pair.get("colorSnapshot")),List.of("id","name","supplierColorCode"));if(!color.isEmpty())value.put("colorSnapshot",color);return value;}).toList();}
 public Map<String,Object> confirmationPreview(long id,Map<String,Object> input){
  permissions.engineer();var head=selection(id,false);store.check(head,number(input.get("version")));var reports=new ArrayList<Map<String,Object>>();long errors=0;
  for(var row:requested(id,input,false)){var messages=new ArrayList<String>();var identity=new PartnerCatalogStyles(store).identity("selection",id,text(row.get("rowId")));
   try{if(!confirmed(row)){require(!identity.isEmpty(),"请先核实并关联目录款式编号");validateConfirmation(row);}}catch(BusinessException e){messages.add(e.getMessage());errors++;}
   var report=new LinkedHashMap<String,Object>();for(String key:List.of("rowId","name","phoneModelIds"))report.put(key,row.get(key));var chosen=scopeIds(row.get("phoneModelIds"));report.put("phones",maps(row.get("phones")).stream().filter(phone->chosen.contains(number(phone.get("phoneModelId")))).map(phone->previewSnapshot(phone,List.of("phoneModelId","modelName","brandName","displayName","phoneModelCode"))).toList());report.put("refs",new PartnerCatalogStyles(store).samplePhotos(id,row.get("refs")));report.put("selectedPairs",previewPairs(row,false));report.put("pairings",previewPairs(row,true));for(String key:List.of("catalogStyleId","catalogStyleCode","catalogStyleLabel"))if(identity.containsKey(key))report.put(key,identity.get(key));report.put("messages",messages);report.put("canConfirm",messages.isEmpty());reports.add(report);
  }return Map.of("id",id,"version",head.get("version"),"canConfirm",errors==0,"rows",reports,"errorCount",errors);
 }
 @Transactional public Map<String,Object> confirm(long id,Map<String,Object> input){
  permissions.engineer();String request=request(input);var head=selection(id,true);if(replayed(id,request,"selection_confirm")){var saved=store.jdbc.queryForList("select data::text from plm_partner_event where record_id=? and request_id=?",id,request);require(saved.size()==1&&signature(store.json,input).equals(text(decode(store.json,saved.get(0).get("data")).get("inputFingerprint"))),"提交标识已用于不同选样确认，请重新核对");return detail(id);}store.check(head,number(input.get("version")));long completed=0;
  for(var row:requested(id,input,true)){if(confirmed(row))continue;freezeRow(id,head,row,request);completed++;}
  saveHeader(id,head);store.event(id,"selection_confirm",Map.of("rows",completed,"rowIds",input.get("rowIds"),"createsProduct",false,"createsSample",false,"inputFingerprint",signature(store.json,input)),request);return detail(id);
 }
 void validateReceiptDraft(Map<String,Object> row){permissions.engineer();validateConfirmation(row);}
 private void freezeRow(long id,Map<String,Object> head,Map<String,Object> row,String request){
  var identity=new PartnerCatalogStyles(store).identity("selection",id,text(row.get("rowId")));require(!identity.isEmpty(),"目录款式关联已失效，请刷新后核对");validateConfirmation(row);
  var next=new LinkedHashMap<>(row);next.put("confirmed",true);next.put("confirmedAt",OffsetDateTime.now().toString());next.put("selectionConfirmationVersion",2);next.put("receiptColorMode",maps(row.get("selectedPairs")).isEmpty()?"at_receipt":"selected_pairs");
  var basis=new LinkedHashMap<>(row);for(String key:List.of("confirmationSnapshot","confirmedAt","selectionConfirmationVersion","receiptColorMode"))basis.remove(key);
  next.put("confirmationSnapshot",Map.of("row",copy(store.json,basis),"catalogStyleId",identity.get("catalogStyleId"),"catalogStyleCode",identity.get("catalogStyleCode"),"selectionId",id,"selectionVersion",head.get("version"),"confirmedBy",store.access.actorId()));rows().put(id,next,row,request);
 }
 /** Called inside the receipt transaction after every actual row has passed validation. */
 void freezeForReceipt(long id,Map<String,Object> input,String request){
  var head=selection(id,true);store.check(head,number(input.get("version")));var distinct=new LinkedHashMap<String,Map<String,Object>>();for(var item:maps(input.get("rows"))){String rid=text(item.get("rowId"));var row=rows().find(id,rid,true);require(!row.isEmpty()&&number(item.get("rowVersion"))==number(row.get("rowVersion")),"选样资料已更新，请刷新后重新核对");distinct.put(rid,row);}
  long changed=0;for(var row:distinct.values())if(!confirmed(row)){permissions.engineer();freezeRow(id,head,row,request);changed++;}
  if(changed>0){saveHeader(id,head);store.event(id,"selection_receipt_freeze",Map.of("rowIds",distinct.keySet(),"rows",changed,"reason","保存实际收样时保留本次选样依据","createsProduct",false),request);}
 }
 @Transactional public Map<String,Object> transitionRows(long id,Map<String,Object> input){
  permissions.engineer();String request=request(input),action=required(input.get("action"),"选样操作");require(Set.of("edit","cancel","restore").contains(action),"选样操作无效");var head=selection(id,true);
  String key="transition-"+UUID.nameUUIDFromBytes((store.access.actorId()+":"+request).getBytes(java.nio.charset.StandardCharsets.UTF_8));String fingerprint=PartnerQualityRules.digest(store.json,input);var existing=store.jdbc.queryForList("select action,data::text from plm_partner_event where record_id=? and request_id=?",id,key);
  if(!existing.isEmpty()){require("selection_transition".equals(text(existing.get(0).get("action")))&&fingerprint.equals(text(decode(store.json,existing.get(0).get("data")).get("requestHash"))),"提交标识已用于不同选样操作，请核对原结果");return detail(id);}store.check(head,number(input.get("version")));
  require(input.get("rows") instanceof List<?> && !maps(input.get("rows")).isEmpty(),"请选择本次修改的选样");var seen=new HashSet<String>();var changes=new ArrayList<Map<String,Object>>();var blocked=downstreamRows(maps(input.get("rows")).stream().map(row->text(row.get("rowId"))).toList(),id);
  for(var item:maps(input.get("rows"))){String rid=required(item.get("rowId"),"选样行");require(validRowId(rid)&&seen.add(rid),"选样行无效或重复");var row=rows().find(id,rid,true);require(!row.isEmpty()&&number(item.get("rowVersion"))==number(row.get("rowVersion")),"选样资料已变化，请刷新后重试");requireNoDownstream(row,blocked);if(action.equals("restore"))require(Boolean.TRUE.equals(row.get("discarded")),"仅已取消的选样可恢复");else require(!Boolean.TRUE.equals(row.get("discarded")),"此选样已取消，可先恢复");if(action.equals("restore"))suppliers.requireSupplierForNewUse(required(row.get("supplierCode"),"供应商"));var next=copy(store.json,row);for(String field:List.of("confirmationSnapshot","confirmedAt","selectionConfirmationVersion","receiptColorMode"))next.remove(field);next.put("confirmed",false);next.put("selected",!action.equals("cancel"));next.put("discarded",action.equals("cancel"));changes.add(Map.of("before",row,"after",next));}
  for(var change:changes)rows().put(id,map(change.get("after")),map(change.get("before")),key);saveHeader(id,head);String reason=text(input.get("reason"));if(reason.isEmpty())reason=switch(action){case "edit"->"修改选样";case "cancel"->"取消选样";default->"恢复选样";};store.event(id,"selection_transition",Map.of("action",action,"changes",changes,"reason",reason,"requestHash",fingerprint),key);return detail(id);
 }
 private void requireNoDownstream(Map<String,Object> row,Set<String> blocked){
  boolean direct=directDownstream(row);String rid=text(row.get("rowId"));
  require(!direct&&!blocked.contains(rid),"这条选样已有真实收样或后续业务记录，不能修改或取消原事实；可增选其他型号或登记再次收样");
 }
 private void requireDraftChangeAllowed(Map<String,Object> previous,Map<String,Object> changes,Set<String> blocked){if(previous.isEmpty()||confirmed(previous))return;var fields=new HashSet<>(TEXT_FIELDS);fields.addAll(List.of("selected","discarded","phoneModelIds","selectedPairs","supplierColorIds","newSupplierColors","sampleQty","pairings","rangeRefs","refs","targetSpecId"));if(changes.entrySet().stream().anyMatch(e->fields.contains(e.getKey())&&!signature(store.json,e.getValue()).equals(signature(store.json,previous.get(e.getKey())))))requireNoDownstream(previous,blocked);}
 private Set<String> downstreamRows(List<String> rows,Long selection){var found=new HashSet<String>();for(var chunk:chunks(rows)){var args=new ArrayList<Object>(chunk);String filter="";if(selection!=null){filter=" and x.basis->>'selectionId'=?";args.add(Long.toString(selection));}String kinds="r.kind in ('sample','inspection','listing','product')";
  found.addAll(store.jdbc.queryForList("with docs as (select r.data from plm_partner_record r where "+kinds+" union all select v.data from plm_partner_revision v join plm_partner_record r on r.record_id=v.record_id where "+kinds+" union all select e.data from plm_partner_event e join plm_partner_record r on r.record_id=e.record_id where "+kinds+") select distinct x.basis->>'selectionRowId' from docs d cross join lateral (values(d.data),(d.data->'sourceSnapshot'),(d.data->'before'),(d.data->'after')) x(basis) where x.basis->>'selectionRowId' in ("+marks(chunk.size())+")"+filter,String.class,args.toArray()));}return found;}
 private static boolean directDownstream(Map<String,Object> row){return number(row.get("productId"))>0||number(row.get("specId"))>0||!scopeIds(row.get("specIds")).isEmpty()||!maps(row.get("specLinks")).isEmpty();}
 private List<Map<String,Object>> requested(long id,Map<String,Object> input,boolean lock){
  var ids=list(input.get("rowIds"));require(!ids.isEmpty(),"请明确选择本次确认的行");var seen=new HashSet<String>();var out=new ArrayList<Map<String,Object>>();
  for(Object value:ids){String rid=text(value);require(validRowId(rid)&&seen.add(rid),"选品行标识无效或重复");var row=rows().find(id,rid,lock);require(!row.isEmpty(),"选品行不属于当前表");out.add(row);}return out;
 }
 void validateConfirmation(Map<String,Object> row){
  require(Boolean.TRUE.equals(row.get("selected"))&&!Boolean.TRUE.equals(row.get("discarded")),"只能确认明确选中且未排除的行");required(row.get("name"),"款名");String supplier=required(row.get("supplierCode"),"供应商");var s=suppliers.requireSupplier(supplier);require(!text(s.get("status")).equals("inactive"),"供应商已停用");
  require(list(row.get("rangeIssues")).isEmpty(),"范围存在待核对项");require(ranges!=null,"供应商范围服务未就绪");
  var snapshots=ranges.resolveReferences(supplier,maps(row.get("rangeRefs")),text(row.get("styleKey")),text(row.get("configKey")),false);
  for(var snapshot:snapshots)if(Boolean.TRUE.equals(snapshot.get("versionChanged")))require(maps(row.get("rangeVersionAcknowledgements")).stream().anyMatch(a->number(a.get("id"))==number(snapshot.get("id"))&&number(a.get("version"))==number(snapshot.get("version"))&&number(a.get("currentVersion"))==number(snapshot.get("currentVersion"))),"范围版本已变化，请查看增减成员并明确确认采用所见旧版本，或改用新版本");
  var pairs=ranges.normalizeSelectedPairs(supplier,maps(row.get("selectedPairs")),false);var facts=supplyFacts(row,false);
  var ids=scopeIds(row.get("phoneModelIds"));require(!ids.isEmpty(),"请明确本次所选手机型号");scopePhones(ids,ids);
  var origin=map(row.get("catalogSourceSnapshot"));if(!origin.isEmpty()){require(scopeIds(origin.get("phoneModelIds")).containsAll(ids)||("style".equals(text(origin.get("selectionIntent")))&&"unspecified".equals(text(origin.get("phoneScopeMode")))),"所选型号超出供应商提供的范围");require(ids.stream().noneMatch(scopeIds(origin.get("unavailablePhoneModelIds"))::contains),"供应商明确不供所选型号");}
  for(String type:List.of("model","color")){var applicable=snapshots.stream().filter(scope->type.equals(text(scope.get("type")))).toList();if(!applicable.isEmpty()){var members=new HashSet<Long>();for(var scope:applicable)for(var member:maps(scope.get("members")))members.add(number(member.get("id")));if(type.equals("model"))require(members.containsAll(ids),"所选型号不在引用的范围快照内");for(var pair:pairs)require(members.contains(number(pair.get(type.equals("model")?"phoneModelId":"colorId"))),"所选型号／颜色不在引用的范围快照内，请核对具体成员");}}
  if(!pairs.isEmpty()){SupplierSupplyRules.validate(supplier,text(row.get("styleKey")),text(row.get("configKey")),text(row.get("quoteVersion")),facts,pairs,supplyContext(row));var pairIds=pairs.stream().map(p->number(p.get("phoneModelId"))).collect(java.util.stream.Collectors.toSet());require(new HashSet<>(ids).equals(pairIds),"选中型号与明确的型号—颜色配对不一致，请核对规格");}
  if(number(row.get("targetSpecId"))>0)products.snapshot(number(row.get("targetSpecId")),supplier,false);
 }

 /** Only the catalog service can create this immutable origin; client row patches cannot replace it. */
 private String supplyContext(Map<String,Object> row){
  var origin=map(row.get("catalogSourceSnapshot"));
  if(!text(row.get("quoteVersion")).isEmpty()||!text(row.get("quoteId")).isEmpty()||!"selection".equals(text(origin.get("sourceKind"))))return "";
  require(text(row.get("supplierCode")).equals(text(origin.get("supplierCode")))&&number(origin.get("recordId"))>0&&validRowId(text(origin.get("rowId")))&&number(origin.get("rowVersion"))>0&&number(origin.get("recordVersion"))>0,"目录来源版本不完整，请重新从已核实的来源选款");
  return "selection:"+number(origin.get("recordId"))+":"+text(origin.get("rowId"))+":"+number(origin.get("recordVersion"))+":"+number(origin.get("rowVersion"));
 }
 private List<Map<String,Object>> supplyFacts(Map<String,Object> row,boolean allowInactive){
  String context=supplyContext(row),supplier=text(row.get("supplierCode")),style=text(row.get("styleKey")),config=text(row.get("configKey")),version=text(row.get("quoteVersion"));
  return context.isEmpty()?ranges.normalizePairings(supplier,maps(row.get("pairings")),style,config,version,allowInactive):ranges.normalizePairings(supplier,maps(row.get("pairings")),style,config,version,allowInactive,context);
 }

 private void requireTarget(long id){var ids=store.jdbc.queryForList("select record_id from plm_partner_spec where spec_id=? and deleted_flag=0",Long.class,id);require(ids.size()==1,"关联规格不存在");store.load(ids.get(0),"product",false);}
 private List<Map<String,Object>> canonicalRefs(long id,Object refs){require(refs==null||refs instanceof List<?>,"附件引用格式无效");var list=maps(refs);for(var ref:list)require(number(ref.get("id"))>0,"附件引用无效");return list.isEmpty()?List.of():store.canonicalFiles(id,list);}
 private static Map<String,Object> rawWithId(Map<String,Object> input,String rid){var raw=new LinkedHashMap<>(input);raw.put("rowId",rid);return raw;}
 private static List<?> list(Object value){return value instanceof List<?> l?l:List.of();}
 static List<Long> scopeIds(Object value){if(value==null)return List.of();require(value instanceof List<?>,"型号必须是列表");return ((List<?>)value).stream().map(PartnerValues::number).peek(n->require(n>0,"型号编号无效")).distinct().sorted().toList();}
 private List<Map<String,Object>> scopePhones(List<Long> ids,List<Long> old){var out=new ArrayList<Map<String,Object>>();for(int i=0;i<ids.size();i+=100)out.addAll(products.selectedPhones(ids.subList(i,Math.min(i+100,ids.size())),old));return out;}
 PartnerCatalogPhoneResolver.ResolutionScope phoneScope(){return phoneResolver==null?null:phoneResolver.newScope();}
 static Map<String,Object> catalogBaseline(Map<String,Object> row){var result=new LinkedHashMap<String,Object>();for(String field:TEXT_FIELDS)if(!field.equals("modelText"))result.put(field,row.get(field));for(String field:List.of("unitPrice","currency","priceOptions","sampleQty","developmentStatus","developedOn","targetSpecId","targetColorMappingAcknowledged","refs","rangeRefs","pairings"))result.put(field,row.get(field));return result;}
 List<Map<String,Object>> validateCatalogChoice(Map<String,Object> source,List<Long> chosen,List<Map<String,Object>> pairs){
  require(!chosen.isEmpty()&&scopeIds(source.get("phoneModelIds")).containsAll(chosen),"请明确选择来源可供型号，不能选择未提供的型号");scopePhones(chosen,List.of());
  require(chosen.stream().noneMatch(scopeIds(source.get("unavailablePhoneModelIds"))::contains),"来源已明确不供该型号，请调整本次选择");
  if(pairs.isEmpty())return List.of();require(ranges!=null,"供应商范围服务未就绪");
  for(var pair:pairs)require(chosen.contains(number(pair.get("phoneModelId"))),"型号颜色组合超出本次所选型号");
  var normalized=ranges.normalizeSelectedPairs(text(source.get("supplierCode")),pairs,false);
  SupplierSupplyRules.validate(text(source.get("supplierCode")),text(source.get("styleKey")),text(source.get("configKey")),text(source.get("quoteVersion")),maps(source.get("pairings")),normalized,supplyContext(source));return normalized;
 }
 // Style adoption keeps unchosen requirements as text; resolving them would turn supply scope into a selection.
 private boolean needsPhoneResolution(Map<String,Object> row){return !confirmed(row)&&!"style".equals(text(map(row.get("catalogSourceSnapshot")).get("selectionIntent")))&&scopeIds(row.get("phoneModelIds")).isEmpty()&&!text(row.get("modelText")).isEmpty();}
 /** Labeled alternatives remain alternatives: no minimum-price guessing, currency default or arbitrary nested input. */
 private List<Map<String,Object>> priceOptions(Object raw){
  require(raw==null||raw instanceof List<?>,"报价选项必须是列表");var result=new ArrayList<Map<String,Object>>();
  for(Object value:list(raw)){require(value instanceof Map<?,?>,"报价选项格式无效");var option=map(value);var clean=new LinkedHashMap<String,Object>();clean.put("label",bounded(required(option.get("label"),"报价适用条件"),255,"报价适用条件"));
   for(String key:List.of("priceNote","moq","packaging"))clean.put(key,bounded(option.get(key),12000,key));
   String price=text(option.get("unitPrice"));clean.put("unitPrice",price.isEmpty()?"":BigDecimal.valueOf(cents(price),2).toPlainString());clean.putAll(PartnerCatalogFields.normalize(option,Map.of(),true));result.add(clean);
  }return result;
 }
 private boolean replayed(long id,String request,String action){var events=store.jdbc.queryForList("select action from plm_partner_event where record_id=? and request_id=?",id,request);if(events.isEmpty())return false;require(action.equals(text(events.get(0).get("action"))),"提交标识已用于其他动作");return true;}
 private String request(Map<String,Object> input){String key=required(input.get("requestId"),"提交标识");require(key.length()<=80,"提交标识过长");return key;}
 private String selectionName(Map<String,Object> input,Map<String,Object> old){return bounded(required(input.getOrDefault("name",input.getOrDefault("title",old.getOrDefault("name","未命名选品表"))),"名称"),255,"名称");}
 private static String shortKey(String value){return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}
 private static String bounded(Object value,int max,String label){String s=text(value);require(s.length()<=max,label+"过长");return s;}
 static boolean validRowId(String value){try{return UUID.fromString(value).toString().equalsIgnoreCase(value);}catch(Exception e){return false;}}
 static boolean confirmed(Map<String,Object> row){return Boolean.TRUE.equals(row.get("confirmed"))||!text(row.get("confirmedAt")).isEmpty();}
 private Map<String,Object> visible(Map<String,Object> d){var publicData=publicOrigins(d);return map(permissions.costs()?publicData:withoutCosts(publicData));}
 private Object publicOrigins(Object value){
  if(value instanceof List<?> list)return list.stream().map(this::publicOrigins).toList();
  if(value instanceof Map<?,?> source){var result=new LinkedHashMap<String,Object>();source.forEach((key,item)->{if(key.toString().equals("catalogSourceSnapshot")){var origin=map(item);var visible=new LinkedHashMap<String,Object>();for(String field:List.of("sourceKind","recordId","rowId","rowVersion","recordVersion","sourceCode","sourceName","supplierCode","modelText","phoneModelIds","phones","phoneScopeMode","phoneScopeDeclaration","unavailablePhoneModelIds","catalogStyleId","catalogStyleCode","catalogStyleLabel","supplierModelEntries","modelSheetRefs","selectionIntent","selectionModelText"))if(origin.containsKey(field))visible.put(field,origin.get(field));result.put(key.toString(),visible);}else result.put(key.toString(),publicOrigins(item));});return result;}
  return value;
 }
 private Object withoutCosts(Object value){if(value instanceof List<?> l)return l.stream().filter(v->!hiddenFile(v)).map(this::withoutCosts).toList();if(value instanceof Map<?,?> m){var out=new LinkedHashMap<String,Object>();m.forEach((k,v)->{if(!COST_FIELDS.contains(k.toString())&&!k.toString().endsWith("Cents"))out.put(k.toString(),Set.of("pairings","selectedPairs").contains(k.toString())?PartnerCatalogFields.operationalPairings(v):withoutCosts(v));});return out;}return value;}
 private boolean hiddenFile(Object value){if(!(value instanceof Map<?,?>))return false;var d=map(value);long file=number(d.getOrDefault("fileId",d.containsKey("name")&&d.containsKey("version")&&!d.containsKey("kind")?d.get("id"):0));return file>0&&restrictedOriginal(file);}
 private boolean restrictedOriginal(long file){return Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_file where attachment_id=? and purpose in ('cooperation_cost','financial')) or exists(select 1 from plm_partner_file_job where kind='import' and source_attachment_id=?)",Boolean.class,file,file));}
}

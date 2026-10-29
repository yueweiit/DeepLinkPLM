package com.yuewei.plm.module.partner;

import java.time.OffsetDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** A saved sales confirmation is not an external listing. No remote writes are configured here. */
@Service @RequiredArgsConstructor
public class PartnerListingService {
 private final PartnerStore store;
 private final PartnerOperationsAccess permissions;
 private final PartnerOperationsService operations;
 @org.springframework.beans.factory.annotation.Autowired private PartnerCatalogStyles catalogStyles;
 @org.springframework.beans.factory.annotation.Autowired private PartnerProductService products;
 @org.springframework.beans.factory.annotation.Autowired private PartnerMaterialService materials;

 public Map<String,Object> catalogOptions(long selectionId,String rowId){
  permissions.sales("listing");var snapshot=listingSnapshot(selectionId,rowId);var out=new LinkedHashMap<>(snapshot);out.put("photos",catalogPhotos(snapshot,null));
  var pairs=new ArrayList<Map<String,Object>>();for(var raw:maps(snapshot.get("selectedPairs"))){var pair=PartnerCatalogSampleRules.pair(snapshot,number(raw.get("phoneModelId")),number(raw.get("colorId")));var evidence=catalogEvidence(snapshot,pair);pair.putAll(evidence);pairs.add(pair);}out.put("pairs",pairs);out.put("externalListingConnected",false);out.remove("refs");return out;
 }
 /** Late colour choices become listing candidates only after an actual receipt; QC/signing stays mandatory. */
 private Map<String,Object> listingSnapshot(long selection,String rowId){
  var snapshot=catalogStyles.selectedSnapshot(selection,rowId);if(!"at_receipt".equals(text(snapshot.get("receiptColorMode"))))return snapshot;
  var pairs=new LinkedHashMap<String,Map<String,Object>>();var rows=store.jdbc.queryForList("select r.record_id,r.data::text from plm_partner_record r where r.kind='sample' and r.data->>'sourceType'='catalog' and r.data->>'selectionId'=? and r.data->>'selectionRowId'=? and r.data->>'catalogStyleId'=? and r.supplier_code=? and r.deleted_flag=0 and ("+store.access.visibleSqlClause("partner","r.record_id")+") order by r.record_id desc",Long.toString(selection),rowId,text(snapshot.get("catalogStyleId")),text(snapshot.get("supplierCode")));
  for(var row:rows){var sample=decode(store.json,row.get("data"));if(!PartnerCatalogSampleRules.configurationKey(snapshot).equals(text(sample.get("catalogConfigurationKey"))))continue;var received=map(map(sample.get("sourceSnapshot")).get("receivedPair"));long phone=number(received.get("phoneModelId")),color=number(received.get("colorId"));if(!PartnerSelectionService.scopeIds(snapshot.get("phoneModelIds")).contains(phone)||color<=0||phone!=number(sample.get("phoneModelId"))||color!=number(sample.get("colorId")))continue;String name=text(received.get("colorName"));if(name.isEmpty())continue;var pair=Map.<String,Object>of("phoneModelId",phone,"colorId",color,"colorName",name);pairs.putIfAbsent(phone+":"+color,pair);}
  var actual=new LinkedHashMap<>(snapshot);actual.put("selectedPairs",new ArrayList<>(pairs.values()));actual.put("receiptColorMode","selected_pairs");actual.remove("receiptColors");return actual;
 }
 private List<Map<String,Object>> catalogPhotos(Map<String,Object> snapshot,Object requested){
  var allowed=maps(snapshot.get("refs"));var refs=requested==null?allowed:maps(requested);var result=new ArrayList<Map<String,Object>>();var seen=new HashSet<Long>();
  for(var ref:refs){long id=number(ref.get("id"));require(allowed.stream().anyMatch(f->number(f.get("id"))==id),"商品图片不属于本次目录款式");if(!seen.add(id))continue;store.access.requireFile(id);
   var rows=store.jdbc.queryForList("select attachment_id as id,file_name as name,version_no as version from plm_attachment where attachment_id=? and content_type like 'image/%' and deleted_flag=0",id);if(!rows.isEmpty())result.add(rows.get(0));
  }return result;
 }
 private Map<String,Object> catalogEvidence(Map<String,Object> snapshot,Map<String,Object> pair){
  String key=text(pair.get("catalogSpecKey"));var rows=store.jdbc.queryForList("select r.record_id,r.status,r.data::text from plm_partner_record r where r.kind='inspection' and r.data->>'sourceKind'='sample' and r.data->>'catalogStyleId'=? and r.supplier_code=? and r.status in ('pending_signature','signed') and r.deleted_flag=0 and ("+store.access.visibleSqlClause("partner","r.record_id")+") order by r.record_id desc",text(snapshot.get("catalogStyleId")),text(snapshot.get("supplierCode")));
  Map<String,Object> latest=null;for(var row:rows){var candidate=decode(store.json,row.get("data"));candidate.put("status",row.get("status"));candidate.put("id",row.get("record_id"));if(maps(map(candidate.get("resultSnapshot")).get("scope")).stream().anyMatch(s->key.equals(text(s.get("catalogSpecKey"))))){latest=candidate;break;}}
  var result=new LinkedHashMap<String,Object>();result.put("eligible",false);
  if(latest==null){result.put("reason","尚无本规格真实收样后的检验依据");return result;}
  store.access.requirePartner(number(latest.get("id")));var sample=store.load(number(latest.get("parentId")),"sample",false);
  if(!text(sample.get("sourceType")).equals("catalog")||!key.equals(text(sample.get("catalogSpecKey")))){result.put("reason","检验与真实样品规格不一致，请工程核实");return result;}
  if(!PartnerQualityRules.passedCatalog(latest,key)){result.put("reason",text(latest.get("status")).equals("pending_signature")?"本次检验尚待工厂签字":"最新检验未通过，请工程整改复检");return result;}
  var scope=maps(map(latest.get("resultSnapshot")).get("scope")).stream().filter(s->key.equals(text(s.get("catalogSpecKey")))).findFirst().orElseThrow();
  if(!PartnerCatalogSampleRules.qualityBasis(store.json,snapshot,pair).equals(text(scope.get("qualityBasis")))){result.put("reason","本规格材料或配置依据已变化，请工程核实适用检查");return result;}
  if(!latest.containsKey("referenceSample")){result.put("reason","请工程在通过并签字的原样品中指定对照样品");return result;}
  result.put("eligible",true);result.put("reason","");result.put("inspectionId",latest.get("id"));result.put("inspectionHash",latest.get("basisHash"));result.put("sampleId",sample.get("id"));result.put("referenceSample",latest.get("referenceSample"));return result;
 }
 @Transactional public Map<String,Object> confirmCatalog(Map<String,Object> input){
  permissions.sales("listing");String request=required(input.get("requestId"),"提交标识");Long repeated=store.created("listing",request);if(repeated!=null){var saved=operations.detail("listing",repeated);var events=store.jdbc.queryForList("select data::text from plm_partner_event where record_id=? and request_id=?",repeated,request);require(events.size()==1&&text(decode(store.json,events.get(0).get("data")).get("requestHash")).equals(PartnerQualityRules.digest(store.json,input)),"提交标识已用于其他上架内容，请核对原结果");return saved;}
  var snapshot=listingSnapshot(number(input.get("selectionId")),required(input.get("selectionRowId"),"来源选样行"));
  require(number(input.get("selectionVersion"))==number(snapshot.get("selectionVersion"))&&number(input.get("selectionRowVersion"))==number(snapshot.get("selectionRowVersion")),"样品选择已变化，请保留输入并重新核对");
  store.jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))","catalog-listing:"+text(snapshot.get("catalogStyleId")));
  String name=required(input.get("name"),"商品名称"),intro=required(input.get("introduction"),"简短商品介绍");require(name.length()<=255,"商品名称最长255字");var photos=catalogPhotos(snapshot,input.get("refs"));require(!photos.isEmpty(),"请确认本款实际商品图片");
  var pairs=new ArrayList<Map<String,Object>>();Set<String> seen=new HashSet<>();
  for(var raw:maps(input.get("selectedPairs"))){var pair=PartnerCatalogSampleRules.pair(snapshot,number(raw.get("phoneModelId")),number(raw.get("colorId")));require(seen.add(text(pair.get("catalogSpecKey"))),"经营规格重复");var evidence=catalogEvidence(snapshot,pair);require(Boolean.TRUE.equals(evidence.get("eligible")),text(pair.get("specName"))+"："+text(evidence.get("reason")));pair.putAll(evidence);pairs.add(pair);}require(!pairs.isEmpty(),"请选择本次明确决定经营的规格");
  var basis=new LinkedHashMap<String,Object>();basis.put("catalogStyleId",snapshot.get("catalogStyleId"));basis.put("catalogStyleCode",snapshot.get("catalogStyleCode"));basis.put("name",name);basis.put("introduction",intro);basis.put("photos",photos);basis.put("pairs",pairs);basis.put("supplierCode",snapshot.get("supplierCode"));basis.put("site",text(input.get("site")));
  String hash=PartnerQualityRules.digest(store.json,basis);var old=store.jdbc.queryForList("select record_id from plm_partner_record where kind='listing' and data->>'basisHash'=? and deleted_flag=0",hash);
  if(!old.isEmpty()){long id=number(old.get(0).get("record_id"));store.access.requirePartner(id);store.remember("listing",request,id);store.event(id,"catalog_listing_reconfirm",Map.of("requestHash",PartnerQualityRules.digest(store.json,input),"basisHash",hash),request);return operations.detail("listing",id);}
  if(text(input.get("productLinkMode")).equals("create"))require(!Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_record where kind='product' and deleted_flag=0 and (data->>'catalogStyleId'=? or exists(select 1 from jsonb_array_elements(coalesce(data->'catalogLinks','[]')) x where x->>'catalogStyleId'=?)))",Boolean.class,text(snapshot.get("catalogStyleId")),text(snapshot.get("catalogStyleId")))),"本款已有内部经营档案，请明确关联已有档案，不能重复创建");
  var linked=products.linkCatalogListing(snapshot,pairs,input,request);long productId=number(linked.get("productId"));materials.inherit(productId,photos);
  long id=store.create("listing",name);var d=new LinkedHashMap<String,Object>();d.put("name",name);d.put("parentId",productId);d.put("productId",productId);for(String field:List.of("catalogStyleId","catalogStyleCode","catalogStyleLabel","supplierCode","selectionId","selectionRowId"))d.put(field,snapshot.get(field));
  d.put("basis",basis);d.put("basisHash",hash);d.put("pairs",linked.get("pairs"));d.put("phoneModelIds",pairs.stream().map(p->p.get("phoneModelId")).distinct().toList());d.put("phones",pairs.stream().flatMap(p->maps(p.get("phones")).stream()).distinct().toList());d.put("color",pairs.stream().map(p->text(p.get("colorName"))).distinct().collect(java.util.stream.Collectors.joining(" / ")));d.put("status","not_connected");d.put("confirmedBy",store.access.actorId());d.put("confirmedAt",OffsetDateTime.now().toString());d.put("reason","销售确认已保存；CRM／商城正式写接口尚未接通，未发起外部上架");
  d.put("requestHash",PartnerQualityRules.digest(store.json,input));store.remember("listing",request,id);materials.inherit(id,photos);store.revision(id,d);store.save(id,d);store.event(id,"catalog_listing_confirm",Map.of("basisHash",hash,"productId",productId,"catalogStyleId",snapshot.get("catalogStyleId"),"reason",d.get("reason"),"requestHash",d.get("requestHash")),request);return operations.detail("listing",id);
 }
 /** Read the actual old sales state and new confirmations together; never relabel a PP draft as listed. */
 public Map<String,Object> catalogPage(Map<String,String> q){
  store.access.requireDomesticView();store.access.requirePermission("partner:view");String status=text(q.getOrDefault("status","listed"));require(Set.of("listed","pending","unlisted","all").contains(status),"上架目录状态无效");
  // A PP may represent several supplier styles. Keep each catalogue confirmation visible, and never
  // let an old PP's listed flag turn a newly associated supplier style into an externally listed item.
  String scopedProducts="p.kind='product' and p.deleted_flag=0 and ("+store.access.visibleSqlClause("partner","p.record_id")+")";
  String prefix="with entries as (select p.record_id as product_id,p.code as product_code,p.name,p.data as product_data,null::bigint as listing_id,null::text as listing_code,null::text as integration_status,null::jsonb as listing_data,p.data->>'listing' as directory_status,p.updated_at as directory_updated_at from plm_partner_record p where "+scopedProducts+" and p.data->>'listing' in ('listed','unlisted') union all select p.record_id,p.code,l.name,p.data,l.record_id,l.code,l.status,l.data,case when l.status in ('listed','unlisted') then l.status else 'pending' end,l.updated_at from plm_partner_record p join (select distinct on (parent_record_id,coalesce(data->>'catalogStyleId',''),coalesce(supplier_code,'')) r.* from plm_partner_record r where r.kind='listing' and r.deleted_flag=0 and ("+store.access.visibleSqlClause("partner","r.record_id")+") order by parent_record_id,coalesce(data->>'catalogStyleId',''),coalesce(supplier_code,''),record_id desc) l on l.parent_record_id=p.record_id where "+scopedProducts+" and (coalesce(l.data->>'catalogStyleId','')<>'' or coalesce(p.data->>'listing','') not in ('listed','unlisted'))) ";
  var args=new ArrayList<Object>();String filter="true";if(!status.equals("all")){filter+=" and e.directory_status=?";args.add(status);}String supplier=text(q.get("supplierCode"));if(!supplier.isEmpty()){filter+=" and (e.listing_data->>'supplierCode'=? or (e.listing_id is null and jsonb_path_exists(e.product_data,'$.specs[*].sources[*] ? (@.supplierCode == $code)',jsonb_build_object('code',?::text))))";args.add(supplier);args.add(supplier);}
  String keyword=text(q.get("keyword"));if(!keyword.isEmpty()){
   // Search only public phone fields. A new catalogue confirmation must not match unselected
   // phone members from its wider PP archive; historical entries retain their existing specs.
   String legacyPhones="(coalesce(e.listing_data->>'catalogStyleId','')='' and exists(select 1 from jsonb_array_elements(case when jsonb_typeof(e.product_data->'specs')='array' then e.product_data->'specs' else '[]'::jsonb end) spec where "+phoneKeywordSql("spec->'phones'")+"))";
   filter+=" and (e.name ilike ? or e.product_code ilike ? or e.listing_data->>'catalogStyleCode' ilike ? or "+phoneKeywordSql("e.listing_data->'phones'")+" or "+legacyPhones+")";for(int i=0;i<9;i++)args.add("%"+keyword+"%");
  }
  String from=" from entries e where "+filter;
  long total=store.jdbc.queryForObject(prefix+"select count(*)"+from,Long.class,args.toArray()),page=Math.max(1,number(q.getOrDefault("page","1"))),size=Math.max(1,Math.min(100,number(q.getOrDefault("size","30"))));var pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add((page-1)*size);
  var rows=store.jdbc.queryForList(prefix+"select e.product_id as \"productId\",e.product_code as \"productCode\",e.name,e.product_data::text,e.listing_id as \"listingId\",e.listing_code as \"listingCode\",e.integration_status as \"integrationStatus\",e.listing_data::text,e.directory_status as status,(select f.attachment_id from plm_partner_file f join plm_attachment a on a.attachment_id=f.attachment_id where f.record_id=coalesce(e.listing_id,e.product_id) and f.purpose='photo' and a.deleted_flag=0 order by f.attachment_id desc limit 1) as \"photoId\""+from+" order by e.directory_updated_at desc,e.product_id desc,e.listing_id desc nulls last limit ? offset ?",pageArgs.toArray());
  for(var row:rows){var p=decode(store.json,row.remove("product_data"));Object listingJson=row.remove("listing_data");var l=listingJson==null?Map.<String,Object>of():decode(store.json,listingJson);row.put("id",row.get("listingId")==null?row.get("productId"):row.get("listingId"));row.put("legacyProduct",row.get("listingId")==null);row.put("code",text(l.get("catalogStyleCode")).isEmpty()?row.get("productCode"):l.get("catalogStyleCode"));for(String field:List.of("catalogStyleId","catalogStyleCode","catalogStyleLabel","color","supplierCode","reason"))row.put(field,l.getOrDefault(field,p.getOrDefault(field,"")));row.put("phones",l.getOrDefault("phones",maps(p.get("specs")).stream().flatMap(s->maps(s.get("phones")).stream()).distinct().toList()));row.put("phoneModelIds",l.getOrDefault("phoneModelIds",maps(p.get("specs")).stream().flatMap(s->phoneIds(s.get("phoneModelIds")).stream()).distinct().toList()));row.put("externalListingConnected",false);row.put("qualityEvidenceStatus",row.get("listingId")==null?"historical_unverified":"verified_at_confirmation");}
  return Map.of("content",rows,"page",page,"size",size,"totalElements",total,"totalPages",(total+size-1)/size,"externalListingConnected",false);
 }
 private static String phoneKeywordSql(String array){return "exists(select 1 from jsonb_array_elements(case when jsonb_typeof("+array+")='array' then "+array+" else '[]'::jsonb end) phone where coalesce(phone->>'modelName','') ilike ? or coalesce(phone->>'displayName','') ilike ? or coalesce(nullif(phone->>'phoneModelCode',''),phone->>'code','') ilike ?)";}

 @Transactional public Map<String,Object> confirm(long productId,Map<String,Object> input){
  permissions.sales("listing");var product=store.load(productId,"product",true);String request=required(input.get("requestId"),"提交标识");Long repeated=store.created("listing",request);if(repeated!=null)return operations.detail("listing",repeated);
  store.check(product,number(input.get("version")));require(!text(product.get("notes")).isEmpty(),"请先补充简短产品介绍");
  var photos=store.jdbc.queryForList("select a.attachment_id as id,a.version_no as version from plm_partner_file f join plm_attachment a on a.attachment_id=f.attachment_id where f.record_id=? and f.purpose='photo' and a.content_type like 'image/%' and a.deleted_flag=0 order by a.attachment_id",productId);require(!photos.isEmpty(),"请先补充实际商品图片");for(var f:photos)store.access.requireFile(number(f.get("id")));
  var selected=phoneIds(input.get("specIds"));require(!selected.isEmpty(),"请选择本次可售规格");var evidence=new ArrayList<Map<String,Object>>();
  for(long specId:selected){
   var spec=maps(product.get("specs")).stream().filter(s->number(s.get("id"))==specId).findFirst().orElseThrow(()->invalid("规格不属于本产品"));
   var source=maps(spec.get("sources")).stream().filter(s->text(s.get("supplierCode")).equals(text(input.get("supplierCode")))).findFirst().orElseThrow(()->invalid("请明确本次上架所采用的真实供方"));
   String supplier=text(source.get("supplierCode")),basis=operations.qualityBasis(specId,supplier);
   // Read every confirmed check, not just passed ones: a later failed/reinspection must not expose an old pass.
   var rows=store.jdbc.queryForList("select record_id,data::text,status from plm_partner_record where kind='inspection' and data->>'sourceKind'='sample' and data->>'productId'=? and supplier_code=? and status in ('pending_signature','signed') and deleted_flag=0 order by record_id desc",Long.toString(productId),supplier);
   Map<String,Object> qc=null;
   for(var row:rows){var candidate=decode(store.json,row.get("data"));candidate.put("status",row.get("status"));candidate.put("id",row.get("record_id"));if(maps(map(candidate.get("resultSnapshot")).get("scope")).stream().anyMatch(s->number(s.get("specId"))==specId)){qc=candidate;break;}}
   require(qc!=null&&PartnerQualityRules.passed(qc,specId),"所选规格缺少最新已通过并由工厂签字的样品质检："+text(spec.get("name")));store.access.requirePartner(number(qc.get("id")));store.access.requirePartner(number(qc.get("parentId")));
   var scope=maps(map(qc.get("resultSnapshot")).get("scope")).stream().filter(s->number(s.get("specId"))==specId).findFirst().orElseThrow();require(basis.equals(text(scope.get("qualityBasis"))),"规格或供方质量依据已变化，请工程核实影响并补充适用检查");require(qc.containsKey("referenceSample"),"请先在已通过的样品记录指定对照样品");
   evidence.add(Map.of("specId",specId,"specName",spec.get("name"),"websiteSku",text(spec.get("websiteSku")),"inspectionId",qc.get("id"),"inspectionHash",qc.get("basisHash"),"supplierCode",supplier));
  }
  var basis=new LinkedHashMap<String,Object>();basis.put("productId",productId);basis.put("name",product.get("name"));basis.put("introduction",product.get("notes"));basis.put("photos",photos);basis.put("specs",evidence);basis.put("site",text(product.get("site")));basis.put("externalId",text(product.get("externalId")));
  String hash=PartnerQualityRules.digest(store.json,basis);var old=store.jdbc.queryForList("select record_id from plm_partner_record where kind='listing' and parent_record_id=? and data->>'basisHash'=? and deleted_flag=0",productId,hash);
  if(!old.isEmpty()){long id=number(old.get(0).get("record_id"));store.access.requirePartner(id);store.remember("listing",request,id);return operations.detail("listing",id);}
  long id=store.create("listing",text(product.get("name")));var d=new LinkedHashMap<String,Object>();d.put("name",product.get("name"));d.put("parentId",productId);d.put("productId",productId);d.put("supplierCode",text(input.get("supplierCode")));d.put("basis",basis);d.put("basisHash",hash);d.put("status","not_connected");d.put("confirmedBy",store.access.actorId());d.put("confirmedAt",OffsetDateTime.now().toString());d.put("reason","销售确认已保存；CRM／商城正式写接口尚未接通，未发起外部上架，原实际销售状态不变");
  store.remember("listing",request,id);store.revision(id,d);store.save(id,d);store.event(id,"listing_confirm",Map.of("basisHash",hash,"reason",d.get("reason")),request);return operations.detail("listing",id);
 }
}

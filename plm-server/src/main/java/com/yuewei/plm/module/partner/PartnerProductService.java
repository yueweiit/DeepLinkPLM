package com.yuewei.plm.module.partner;

import java.util.*;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.partner.PartnerValues.*;

@Service @RequiredArgsConstructor
public class PartnerProductService {
 private final PartnerStore store; private final SupplierProfileService suppliers; private final PhoneModelReferenceService phoneReference;
 public Map<String,Object> detail(long id){var d=store.load(id,"product",false);d.put("events",store.events(id));d.put("revisions",store.revisions(id));d.put("files",store.files(id));return d;}
 @Transactional public Map<String,Object> save(Long id,Map<String,Object> input){
  store.access.requirePermission("partner:write");String name=required(input.get("name"),"产品名称");require(name.length()<=255,"名称过长");
  boolean creating=id==null;if(creating){Long repeated=store.created("product",required(input.get("requestId"),"提交标识"));if(repeated!=null)return detail(repeated);id=store.create("product",name);store.remember("product",text(input.get("requestId")),id);}var old=store.load(id,"product",true);String request=required(input.get("requestId"),"提交标识");if(store.repeated(id,request))return detail(id);store.check(old,creating?0L:number(input.get("version")));
  Map<String,Object> d=new LinkedHashMap<>();d.put("name",name);d.put("status",text(input.getOrDefault("status","draft")));require(Set.of("draft","pending","adopted","inactive").contains(d.get("status")),"选品状态无效");d.put("site",text(input.get("site")));d.put("externalId",text(input.get("externalId")));d.put("listing",text(input.getOrDefault("listing","unrecorded")));require(Set.of("unrecorded","listed","unlisted").contains(d.get("listing")),"网站状态无效");d.put("notes",text(input.get("notes")));d.put("websiteUrl",url(input.get("websiteUrl")));
  for(String field:List.of("catalogStyleId","catalogStyleCode","catalogStyleLabel","catalogLinks"))if(old.containsKey(field))d.put(field,old.get(field));
  d.put("listing",text(input.getOrDefault("listing",old.getOrDefault("listing","unrecorded"))));require(text(d.get("listing")).equals(text(old.getOrDefault("listing","unrecorded"))),"原实际销售状态保留；新的上架请从上架依据确认，不能手工绕过质检、签字或外部成功核对");
  List<Map<String,Object>> specs=new ArrayList<>();Set<Long> used=new HashSet<>();
  for(var requested:maps(input.get("specs"))){var spec=new LinkedHashMap<String,Object>();long sid=number(requested.get("id"));var priorSpec=maps(old.get("specs")).stream().filter(x->number(x.get("id"))==number(requested.get("id"))).findFirst().orElse(Map.of());
   var ids=phoneIds(requested.containsKey("phoneModelIds")?requested.get("phoneModelIds"):priorSpec.get("phoneModelIds"));var phones=selectedPhones(ids,phoneIds(priorSpec.get("phoneModelIds")));
   String specification=text(requested.get("specification"));String sn=requested.containsKey("specification")?specification:text(requested.get("name"));
   if(!requested.containsKey("specification")&&priorSpec.containsKey("specification")){specification=text(priorSpec.get("specification"));sn=specification;spec.put("specification",specification);}
   if(!ids.isEmpty()&&!spec.containsKey("specification"))spec.put("specification",sn);
   if(!ids.isEmpty())sn=phones.stream().map(p->text(p.get("displayName"))).collect(java.util.stream.Collectors.joining(" / "))+(sn.isEmpty()?"":" · "+sn);
   if(sn.isEmpty())sn="待确认";require(sn.length()<=30000,"规格描述过长");spec.put("phoneModelIds",ids);spec.put("phones",phones);if(requested.containsKey("specification"))spec.put("specification",specification);
   if(sid==0){sid=store.jdbc.queryForObject("insert into plm_partner_spec(record_id,code,name) values(?,?,?) returning spec_id",Long.class,id,UUID.randomUUID().toString(),sn);store.jdbc.update("update plm_partner_spec set code=? where spec_id=?","PS-"+String.format("%06d",sid),sid);}else require(Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_spec where spec_id=? and record_id=? and deleted_flag=0)",Boolean.class,sid,id)),"规格不属于当前产品");
   require(used.add(sid),"规格重复");spec.put("id",sid);spec.put("code",store.jdbc.queryForObject("select code from plm_partner_spec where spec_id=?",String.class,sid));spec.put("name",sn);spec.put("websiteSku",text(requested.get("websiteSku")));
   for(String field:List.of("catalogStyleId","catalogStyleCode","catalogSpecKey","catalogConfigurationKey","colorName","colorId"))if(priorSpec.containsKey(field))spec.put(field,priorSpec.get(field));
   long internal=number(requested.get("internalSkuId"));if(internal>0){store.access.requireProduct(internal);require(Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_product where product_id=? and product_type='sku' and deleted_flag=0)",Boolean.class,internal)),"内部成品关联必须选择真实 SKU");}spec.put("internalSkuId",internal);
   if(requested.containsKey("externalId")||priorSpec.containsKey("externalId")){
    String external=text(requested.getOrDefault("externalId",priorSpec.get("externalId")));require(external.length()<=128,"规格来源身份过长");
    require(text(priorSpec.get("externalId")).isEmpty()||external.equals(text(priorSpec.get("externalId"))),"规格来源身份固定保留，不能改绑");spec.put("externalId",external);
   }
   List<Map<String,Object>> sources=new ArrayList<>();Set<String> codes=new HashSet<>();
   for(var src:maps(requested.get("sources"))){String code=required(src.get("supplierCode"),"供方");boolean existing=maps(priorSpec.get("sources")).stream().anyMatch(previous->code.equals(previous.get("supplierCode")));var supplier=existing?suppliers.requireSupplier(code):suppliers.requireSupplierForNewUse(code);require(codes.add(code),"同规格供方重复");
    var prior=maps(old.get("specs")).stream().filter(x->number(x.get("id"))==number(requested.get("id"))).flatMap(x->maps(x.get("sources")).stream()).filter(x->code.equals(x.get("supplierCode"))).findFirst().orElse(Map.of());
    Map<String,Object> source=new LinkedHashMap<>();source.put("supplierCode",code);source.put("supplierName",supplier.get("supplierName"));source.put("supplierItemCode",text(src.get("supplierItemCode")));
    boolean priceKnown=!text(src.get("unitPrice")).isEmpty()||prior.containsKey("priceCents");long price=!text(src.get("unitPrice")).isEmpty()?cents(src.get("unitPrice")):number(prior.get("priceCents"));String curr=!text(src.get("currency")).isEmpty()?currency(src.get("currency")):text(prior.get("currency")),terms=src.containsKey("terms")?text(src.get("terms")):text(prior.get("terms"));
    if(!store.access.hasPermission("finance:write"))require(priceKnown==prior.containsKey("priceCents")&&price==number(prior.get("priceCents"))&&terms.equals(text(prior.get("terms")))&&curr.equals(text(prior.get("currency"))),"没有权限修改历史供货参考价；工程供方报价请从询报价记录办理");
    if(priceKnown){source.put("priceCents",price);source.put("unitPrice",java.math.BigDecimal.valueOf(price,2).toPlainString());}source.put("currency",curr);source.put("terms",terms);sources.add(source);
   }
   spec.put("sources",sources);
   var supplierColors=maps(requested.getOrDefault("supplierColors",priorSpec.get("supplierColors")));for(var color:supplierColors){String supplier=required(color.get("supplierCode"),"颜色供应商");require(codes.contains(supplier),"颜色供应商与规格供方不一致");require(Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_supplier_color where color_id=? and supplier_code=?)",Boolean.class,number(color.get("colorId")),supplier)),"供应商原色身份不存在或不属于该供方");}spec.put("supplierColors",supplierColors);
   store.jdbc.update("update plm_partner_spec set name=?,site=?,website_sku=?,data=?::jsonb where spec_id=?",sn,d.get("site"),spec.get("websiteSku"),encode(store.json,spec),sid);specs.add(spec);
  }
  for(var previous:maps(old.get("specs")))require(used.contains(number(previous.get("id"))),"删除已有规格请在规格列表使用“删除规格”，核对业务引用后办理");
  d.put("specs",specs);d.put("refs",store.editableFiles(id,input.get("refs"),old.get("refs")));List<Map<String,Object>> links=new ArrayList<>();for(var raw:maps(input.get("links"))){String target=url(raw.get("url"));require(!target.isEmpty(),"链接地址不能为空");links.add(Map.of("url",target,"title",text(raw.get("title")),"version",number(old.get("version"))+1));}d.put("links",links);
  d.put("summary",specs.stream().map(x->text(x.get("name"))).collect(java.util.stream.Collectors.joining(" / ")));
  store.revision(id,d);store.save(id,d);store.event(id,"product_save",Map.of("name",name,"revision",number(old.get("version"))+1),request);return detail(id);
 }
 @Transactional public Map<String,Object> delete(long id,Map<String,Object> input){
  store.access.requirePermission("partner:write");var d=store.load(id,"product",true,true);String request=required(input.get("requestId"),"提交标识");
  if(repeatedDeletion(id,request,"product_delete",0))return Map.of("id",id,"deleted",true);
  require(!Boolean.TRUE.equals(d.get("deleted")),"产品已删除，请刷新目录");store.check(d,number(input.get("version")));requireNotListed(d);requireUnused(id,null);
  var specs=maps(d.get("specs"));d.put("deleted",true);store.revision(id,d);
  store.jdbc.update("update plm_partner_spec set deleted_flag=1 where record_id=? and deleted_flag=0",id);
  store.jdbc.update("update plm_partner_record set deleted_flag=1,version=version+1,updated_at=now(),updated_by=? where record_id=?",store.access.actor().username(),id);
  store.event(id,"product_delete",Map.of("name",d.get("name"),"code",d.get("code"),"specCount",specs.size()),request);
  return Map.of("id",id,"deleted",true);
 }
 @Transactional public Map<String,Object> deleteSpec(long id,long specId,Map<String,Object> input){
  store.access.requirePermission("partner:write");var d=store.load(id,"product",true);String request=required(input.get("requestId"),"提交标识");
  if(repeatedDeletion(id,request,"spec_delete",specId))return detail(id);
  store.check(d,number(input.get("version")));requireNotListed(d);
  var specs=maps(d.get("specs"));var removed=specs.stream().filter(s->number(s.get("id"))==specId).findFirst().orElseThrow(()->invalid("规格不存在或不属于当前产品"));requireUnused(id,specId);
  d.put("specs",specs.stream().filter(s->number(s.get("id"))!=specId).toList());d.put("summary",maps(d.get("specs")).stream().map(s->text(s.get("name"))).collect(java.util.stream.Collectors.joining(" / ")));
  store.jdbc.update("update plm_partner_spec set deleted_flag=1 where spec_id=? and record_id=? and deleted_flag=0",specId,id);
  store.revision(id,d);store.save(id,d);store.event(id,"spec_delete",Map.of("specId",specId,"name",removed.get("name"),"code",removed.get("code")),request);return detail(id);
 }
 private boolean repeatedDeletion(long id,String request,String action,long specId){
  var events=store.jdbc.queryForList("select action,data::text from plm_partner_event where record_id=? and request_id=?",id,request);if(events.isEmpty())return false;
  var previous=events.get(0);require(action.equals(previous.get("action"))&&number(decode(store.json,previous.get("data")).get("specId"))==specId,"提交标识已用于其他动作，请重新核对");return true;
 }
 private void requireNotListed(Map<String,Object> d){require(!text(d.get("listing")).equals("listed"),"产品当前已上架，请先在原销售渠道核实下架，再处理产品资料");}
 private void requireUnused(long id,Long specId){
  var ids=specId==null?store.jdbc.queryForList("select spec_id from plm_partner_spec where record_id=?",Long.class,id):List.of(specId);
  boolean selectionReference=Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_selection_row where confirmed and (data->>'productId'=? or data->>'specId'=any(?::text[]) or exists(select 1 from jsonb_array_elements_text(coalesce(data->'specIds','[]')) x where x=any(?::text[]))))",Boolean.class,specId==null?Long.toString(id):"",ids.stream().map(String::valueOf).toArray(String[]::new),ids.stream().map(String::valueOf).toArray(String[]::new)));
  require(!selectionReference,"已有选品确认依据，不能删除产品／规格；停止使用请设为停用，历史关联继续保留");
  // Read all downstream history, including records outside the operator's visible scope, without exposing their contents.
  String reference="jsonb_path_exists(%s,'$.**.specId ? (@ == $ids[*])',jsonb_build_object('ids',?::jsonb))";
  String product=specId==null?" or r.parent_record_id=? or r.data->>'productId'=?":"";
  var args=new ArrayList<Object>();args.add(encode(store.json,ids));if(specId==null){args.add(id);args.add(Long.toString(id));}args.add(encode(store.json,ids));args.add(encode(store.json,ids));
  var kinds=store.jdbc.queryForList("select distinct r.kind from plm_partner_record r where r.kind<>'product' and r.deleted_flag=0 and ("+reference.formatted("r.data")+product+" or exists(select 1 from plm_partner_revision v where v.record_id=r.record_id and "+reference.formatted("v.data")+") or exists(select 1 from plm_partner_event e where e.record_id=r.record_id and "+reference.formatted("e.data")+")) order by r.kind",String.class,args.toArray());
  var labels=Map.of("sale","销售／询价","sample","样品","supplier_quote","供方报价","sample_fee","样品费","inspection","质检","fulfilment","执行批次","listing","上架依据");
  require(kinds.isEmpty(),"已有"+kinds.stream().map(k->labels.getOrDefault(k,k)).collect(java.util.stream.Collectors.joining("、"))+"关联，不能删除"+(specId==null?"产品":"规格")+"；请更正原业务记录，停止使用产品可在编辑资料中设为停用");
 }
 public Map<String,Object> snapshot(long specId,String supplierCode,boolean actual){
  var rows=store.jdbc.queryForList("select record_id from plm_partner_spec where spec_id=? and deleted_flag=0",specId);require(!rows.isEmpty(),"规格不存在");long pid=number(rows.get(0).get("record_id"));var product=store.load(pid,"product",true);
  var spec=maps(product.get("specs")).stream().filter(s->number(s.get("id"))==specId).findFirst().orElseThrow(()->invalid("规格已失效"));
  var source=maps(spec.get("sources")).stream().filter(s->supplierCode.equals(s.get("supplierCode"))).findFirst().orElseThrow(()->invalid("供方与规格不匹配"));var supplier=suppliers.requireSupplier(supplierCode);
  require(!text(supplier.get("status")).equals("inactive"),"供方已停用");if(actual){require(!text(product.get("status")).equals("inactive"),"产品已停用");require(!Boolean.TRUE.equals(supplier.get("needsReview"))&&!text(supplier.get("supplierType")).equals("unknown"),"供方类型或历史身份仍待核对");}
  if(!store.access.hasPermission("finance:read"))product.put("refs",maps(product.get("refs")).stream().filter(f->!store.financialFile(number(f.get("id")))).toList());
  for(var ref:maps(product.get("refs")))store.access.requireFile(number(ref.get("id")));
  var out=new LinkedHashMap<String,Object>();out.put("productId",pid);out.put("productCode",product.get("code"));out.put("productName",product.get("name"));out.put("productVersion",product.get("version"));out.put("specId",specId);out.put("specCode",spec.get("code"));out.put("specName",spec.get("name"));out.put("phoneModelIds",spec.getOrDefault("phoneModelIds",List.of()));out.put("phones",spec.getOrDefault("phones",List.of()));out.put("supplierCode",supplierCode);out.put("supplierName",supplier.get("supplierName"));out.put("source",source);out.put("refs",product.getOrDefault("refs",List.of()));out.put("links",product.getOrDefault("links",List.of()));return out;
 }
 /** Invoked only after sales confirmation has validated the exact signed sample evidence. */
 Map<String,Object> linkCatalogListing(Map<String,Object> catalog,List<Map<String,Object>> pairs,Map<String,Object> input,String request){
  store.access.requirePermission("partner:listing");String mode=required(input.get("productLinkMode"),"请选择关联已有经营档案或新建");require(Set.of("create","existing").contains(mode),"经营档案关联方式无效");
  String supplierCode=required(catalog.get("supplierCode"),"供应商");var supplier=suppliers.requireSupplier(supplierCode);require(!text(supplier.get("status")).equals("inactive"),"供应商已停用");
  Map<String,Object> product;long id;var linked=new ArrayList<Map<String,Object>>();
  if(mode.equals("existing")){
   id=number(input.get("existingProductId"));product=store.load(id,"product",true);store.check(product,number(input.get("existingProductVersion")));require(!text(product.get("status")).equals("inactive"),"经营档案已停用");
   for(var pair:pairs){var link=maps(input.get("specLinks")).stream().filter(p->number(p.get("phoneModelId"))==number(pair.get("phoneModelId"))&&number(p.get("colorId"))==number(pair.get("colorId"))).findFirst().orElseThrow(()->invalid("请为每项经营规格明确关联已有规格"));long sid=number(link.get("specId"));
    if(Boolean.TRUE.equals(link.get("createNew"))){require(sid==0,"新建规格和关联已有规格不能同时选择");require(maps(product.get("catalogLinks")).stream().noneMatch(x->text(pair.get("catalogSpecKey")).equals(text(x.get("catalogSpecKey")))),"此款此配置已有关联规格，请直接关联已有规格");var specs=new ArrayList<>(maps(product.get("specs")));var created=createCatalogSpec(id,catalog,pair,supplier);specs.add(created);product.put("specs",specs);var bound=new LinkedHashMap<>(pair);bound.put("specId",created.get("id"));linked.add(bound);continue;}
    var spec=maps(product.get("specs")).stream().filter(s->number(s.get("id"))==sid).findFirst().orElseThrow(()->invalid("关联规格不属于所选经营档案"));
    require(phoneIds(spec.get("phoneModelIds")).contains(number(pair.get("phoneModelId"))),"关联规格的实际手机型号不一致");
    require(maps(spec.get("sources")).stream().anyMatch(s->supplierCode.equals(text(s.get("supplierCode")))),"关联规格不存在该供应商，不能混用其他供方质量依据");
    require(maps(spec.get("supplierColors")).stream().anyMatch(c->supplierCode.equals(text(c.get("supplierCode")))&&number(c.get("colorId"))==number(pair.get("colorId"))),"关联规格的供应商原色不一致，请先核实已有档案");
    String priorCatalogKey=text(spec.get("catalogSpecKey"));if(!priorCatalogKey.isEmpty())require(priorCatalogKey.equals(text(pair.get("catalogSpecKey"))),"已有规格关联的是其他配置，不能复用该配置的质检依据");else require(Boolean.TRUE.equals(link.get("configurationConfirmed")),"历史规格缺少配置身份，请明确核对材质／配置一致后关联");
    var bound=new LinkedHashMap<>(pair);bound.put("specId",sid);linked.add(bound);
   }
  }else{
   String name=required(input.get("name"),"商品名称");require(name.length()<=255,"商品名称最长255字");id=store.createListingProduct(name);product=new LinkedHashMap<>();product.put("name",name);product.put("status","draft");product.put("listing","unrecorded");product.put("notes",required(input.get("introduction"),"简短商品介绍"));product.put("site",text(input.get("site")));product.put("refs",List.of());product.put("links",List.of());
   for(String field:List.of("catalogStyleId","catalogStyleCode","catalogStyleLabel"))product.put(field,catalog.get(field));
   var specs=new ArrayList<Map<String,Object>>();
   for(var pair:pairs){var spec=createCatalogSpec(id,catalog,pair,supplier);specs.add(spec);var bound=new LinkedHashMap<>(pair);bound.put("specId",spec.get("id"));linked.add(bound);
   }product.put("specs",specs);product.put("summary",specs.stream().map(s->text(s.get("name"))).collect(java.util.stream.Collectors.joining(" / ")));
  }
  product.put("summary",maps(product.get("specs")).stream().map(s->text(s.get("name"))).collect(java.util.stream.Collectors.joining(" / ")));var links=new ArrayList<>(maps(product.get("catalogLinks")));for(var pair:linked){var entry=Map.<String,Object>of("catalogStyleId",catalog.get("catalogStyleId"),"catalogStyleCode",catalog.get("catalogStyleCode"),"catalogSpecKey",pair.get("catalogSpecKey"),"specId",pair.get("specId"));if(!links.contains(entry))links.add(entry);}product.put("catalogLinks",links);
  store.revision(id,product);store.save(id,product);store.event(id,"catalog_listing_product",Map.of("mode",mode,"catalogStyleId",catalog.get("catalogStyleId"),"pairs",linked),request);return Map.of("productId",id,"pairs",linked);
 }
 private Map<String,Object> createCatalogSpec(long id,Map<String,Object> catalog,Map<String,Object> pair,Map<String,Object> supplier){
  String supplierCode=required(catalog.get("supplierCode"),"供应商");var spec=new LinkedHashMap<String,Object>();long sid=store.jdbc.queryForObject("insert into plm_partner_spec(record_id,code,name) values(?,?,?) returning spec_id",Long.class,id,UUID.randomUUID().toString(),text(pair.get("specName")));String code="PS-"+String.format("%06d",sid);
  spec.put("id",sid);spec.put("code",code);spec.put("name",pair.get("specName"));spec.put("specification",text(catalog.get("material"))+" "+text(catalog.get("configuration"))+" "+text(pair.get("colorName")));spec.put("phoneModelIds",pair.get("phoneModelIds"));spec.put("phones",pair.get("phones"));spec.put("websiteSku","");spec.put("internalSkuId",0L);
  for(String field:List.of("catalogStyleId","catalogStyleCode","catalogSpecKey","catalogConfigurationKey","colorName","colorId"))spec.put(field,pair.get(field));
  spec.put("supplierColors",List.of(Map.of("supplierCode",supplierCode,"colorId",pair.get("colorId"))));spec.put("sources",List.of(Map.of("supplierCode",supplierCode,"supplierName",text(supplier.get("supplierName")),"supplierItemCode",text(catalog.get("supplierItemCode")),"currency","","terms","")));
  store.jdbc.update("update plm_partner_spec set code=?,data=?::jsonb where spec_id=?",code,encode(store.json,spec),sid);return spec;
 }
 List<Map<String,Object>> selectedPhones(List<Long> ids,List<Long> previous){
  var members=phoneReference.members(ids,true);phoneReference.requireEnabledModels(ids.stream().filter(id->!previous.contains(id)).toList());
  return ids.stream().map(id->{var p=members.get(id);var snapshot=new LinkedHashMap<String,Object>();for(String key:List.of("phoneModelId","modelName","brandName","displayName","phoneModelCode"))snapshot.put(key,p.get(key));return (Map<String,Object>)snapshot;}).toList();
 }
 private String url(Object value){String s=text(value);require(s.isEmpty()||(s.length()<=2000&&s.matches("https?://[^\\s]+")),"链接须使用 http:// 或 https://");return s;}
}

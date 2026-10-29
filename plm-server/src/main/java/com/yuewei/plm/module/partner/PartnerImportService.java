package com.yuewei.plm.module.partner;

import com.yuewei.plm.module.importexport.service.TabularSheet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import lombok.RequiredArgsConstructor;
import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

@Service @RequiredArgsConstructor
public class PartnerImportService {
 private final PartnerStore store;private final PartnerProductService products;private final PartnerSalesService sales;private final SupplierProfileService suppliers;
 public static final List<String> PRODUCT_HEADERS=List.of("站点","网站商品号","网站SKU","商品名称","规格","供方代码","供方货号","参考单价","币种");
 public static final List<String> SALE_HEADERS=List.of("站点","网站订单号","客户代码","规格码","网站SKU","供方代码","数量","单价","币种","网站来源状态");
 @Transactional public Map<String,Object> preview(String kind,MultipartFile file){
  store.access.requireDomesticView();store.access.requirePermission("partner:write");require(Set.of("product","sale").contains(kind),"此对象不支持导入");
  var raw=TabularSheet.read(file);for(String h:kind.equals("product")?List.of("商品名称"):List.of("站点","网站订单号","数量","单价","币种"))require(raw.get(0).containsKey(h),"模板缺少表头："+h);
  List<Map<String,Object>> rows=new ArrayList<>();
  for(var values:raw){Map<String,Object> row=new LinkedHashMap<>();row.put("rowNumber",number(values.get("_row")));row.put("values",values);String supplier=values.getOrDefault("供方代码","");row.put("supplierCode","");
   if(!supplier.isBlank()){var matches=store.jdbc.queryForList("select supplier_code from plm_supplier_profile p where p.supplier_code=? and p.deleted_flag=0 and "+store.access.visibleSqlClause("supplier_profile","p.supplier_id"),supplier);if(matches.size()==1)row.put("supplierCode",supplier);}
   List<Map<String,Object>> specs=List.of();if(!text(values.get("规格码")).isEmpty())specs=store.jdbc.queryForList("select s.spec_id from plm_partner_spec s join plm_partner_record p on p.record_id=s.record_id where s.code=? and s.deleted_flag=0 and p.deleted_flag=0 and "+store.access.visibleSqlClause("partner","p.record_id"),values.get("规格码"));
   else if(!text(values.get("网站SKU")).isEmpty())specs=store.jdbc.queryForList("select s.spec_id from plm_partner_spec s join plm_partner_record p on p.record_id=s.record_id where s.site=? and s.website_sku=? and s.deleted_flag=0 and p.deleted_flag=0 and "+store.access.visibleSqlClause("partner","p.record_id"),values.getOrDefault("站点",""),values.get("网站SKU"));
   row.put("specId",specs.size()==1?specs.get(0).get("spec_id"):0);
   var customers=text(values.get("客户代码")).isEmpty()?List.<Map<String,Object>>of():store.jdbc.queryForList("select customer_id from plm_customer c where c.customer_code=? and c.deleted_flag=0 and "+store.access.visibleSqlClause("customer","c.customer_id"),values.get("客户代码"));row.put("customerId",customers.size()==1?customers.get(0).get("customer_id"):0);
   rows.add(row);
  }
  String token=UUID.randomUUID().toString();var data=new LinkedHashMap<String,Object>();data.put("rows",rows);store.jdbc.update("insert into plm_partner_import(token,kind,owner_user_id,data) values(?,?,?,?::jsonb)",token,kind,store.access.actorId(),encode(store.json,data));
  var checked=recheck(token,kind,rows);store.jdbc.update("update plm_partner_import set data=?::jsonb where token=?",encode(store.json,Map.of("rows",checked.get("rows"))),token);return checked;
 }
 private Map<String,Object> recheck(String token,String kind,List<Map<String,Object>> rows){
  List<Map<String,Object>> checked=new ArrayList<>();Set<String> duplicate=new HashSet<>();Map<String,String> orderParties=new HashMap<>();
  for(var row:rows){Map<String,Object> r=new LinkedHashMap<>(row);var v=map(r.get("values"));List<String> errors=new ArrayList<>();String action="new";
   try{
    if(kind.equals("product")){if(r.containsKey("phoneModelIds"))r.put("phoneModelIds",phoneIds(r.get("phoneModelIds")));required(v.get("商品名称"),"商品名称");if(!text(v.get("参考单价")).isEmpty())cents(v.get("参考单价"));if(!text(v.get("币种")).isEmpty())currency(v.get("币种"));if(!text(v.get("供方代码")).isEmpty()||!text(r.get("supplierCode")).isEmpty())suppliers.requireSupplier(required(r.get("supplierCode"),"匹配供方"));
     String sku=text(v.get("网站SKU"));if(!sku.isEmpty())require(duplicate.add(text(v.get("站点"))+"|"+sku+"|"+text(r.get("supplierCode"))),"同文件SKU／供方重复，请先合并");
     if(number(r.get("specId"))==0&&!text(v.get("站点")).isEmpty()&&!text(v.get("网站商品号")).isEmpty()){var root=store.jdbc.queryForList("select record_id,version from plm_partner_record p where p.kind='product' and p.site=? and p.external_id=? and p.deleted_flag=0 and "+store.access.visibleSqlClause("partner","p.record_id"),v.get("站点"),v.get("网站商品号"));if(!root.isEmpty()){r.put("existingId",root.get(0).get("record_id"));r.put("existingVersion",root.get(0).get("version"));action="update";}}
     if(number(r.get("specId"))>0){var existing=store.jdbc.queryForList("select record_id from plm_partner_spec where spec_id=? and deleted_flag=0",number(r.get("specId")));require(!existing.isEmpty(),"匹配规格已失效");var product=products.detail(number(existing.get(0).get("record_id")));r.put("existingId",product.get("id"));r.put("existingVersion",product.get("version"));action="update";}
     var priorSpec=number(r.get("specId"))>0?maps(products.detail(number(r.get("existingId"))).get("specs")).stream().filter(x->number(x.get("id"))==number(r.get("specId"))).findFirst().orElse(Map.of()):Map.<String,Object>of();
     if(!r.containsKey("phoneModelIds"))r.put("phoneModelIds",phoneIds(priorSpec.get("phoneModelIds")));
     products.selectedPhones(phoneIds(r.get("phoneModelIds")),phoneIds(priorSpec.get("phoneModelIds")));
     if(number(r.get("existingId"))>0){var existing=products.detail(number(r.get("existingId")));var changes=productChanges(existing,r);r.put("changes",changes);if(changes.isEmpty())action="skip_same";}
    }else{
     String site=required(v.get("站点"),"站点"),external=required(v.get("网站订单号"),"网站订单号");qty(v.get("数量"),false);cents(v.get("单价"));currency(v.get("币种"));long customer=number(r.get("customerId"));require(customer>0,"待匹配客户");store.access.requireCustomer(customer);products.snapshot(number(r.get("specId")),required(r.get("supplierCode"),"匹配供方"),false);
     String key=site+"|"+external,party=customer+"|"+text(v.get("币种"));require(!orderParties.containsKey(key)||party.equals(orderParties.get(key)),"同一订单的客户与币种须一致");orderParties.put(key,party);
     var existing=store.jdbc.queryForList("select record_id,data::text,version from plm_partner_record p where kind='sale' and site=? and external_id=? and deleted_flag=0 and "+store.access.visibleSqlClause("partner","p.record_id"),site,external);if(!existing.isEmpty()){var x=existing.get(0);r.put("existingId",x.get("record_id"));r.put("existingVersion",x.get("version"));action=Boolean.TRUE.equals(decode(store.json,x.get("data")).get("confirmed"))?"skip_confirmed":"update";}
    }
   }catch(com.yuewei.plm.common.exception.BusinessException e){errors.add(e.getMessage());action="resolve";}
   r.put("action",action);r.put("errors",errors);checked.add(r);
  }
  return Map.of("token",token,"kind",kind,"rows",checked,"errorCount",checked.stream().filter(r->!((List<?>)r.get("errors")).isEmpty()).count());
 }
 @Transactional public Map<String,Object> match(String token,List<Map<String,Object>> mappings){
  store.access.requireDomesticView();store.access.requirePermission("partner:write");var session=session(token,true);var rows=maps(decode(store.json,session.get("data")).get("rows"));
  for(var update:mappings){long n=number(update.get("rowNumber"));var row=rows.stream().filter(r->number(r.get("rowNumber"))==n).findFirst().orElseThrow(()->invalid("匹配行不存在"));for(String key:List.of("supplierCode","customerId","specId","phoneModelIds"))if(update.containsKey(key))row.put(key,update.get(key));}
  var checked=recheck(token,text(session.get("kind")),rows);store.jdbc.update("update plm_partner_import set data=?::jsonb where token=?",encode(store.json,Map.of("rows",checked.get("rows"))),token);return checked;
 }
 private Map<String,Object> session(String token,boolean lock){
  var rows=store.jdbc.queryForList("select token,kind,data::text,result::text,created_at from plm_partner_import where token=? and owner_user_id=? and kind in ('product','sale')"+(lock?" for update":""),token,store.access.actorId());require(!rows.isEmpty(),"导入预览不存在或不属于当前账号／导入类型");var session=rows.get(0);require(((java.sql.Timestamp)session.get("created_at")).toLocalDateTime().isAfter(java.time.LocalDateTime.now().minusHours(2)),"预览已过期，请重新上传核对");return session;
 }
 @Transactional public Map<String,Object> commit(String token){
  store.access.requirePermission("partner:write");var s=session(token,true);if(s.get("result")!=null)return decode(store.json,s.get("result"));
  String kind=text(s.get("kind"));var saved=maps(decode(store.json,s.get("data")).get("rows"));for(long id:saved.stream().mapToLong(x->number(x.get("existingId"))).filter(x->x>0).distinct().sorted().toArray())store.jdbc.queryForList("select record_id from plm_partner_record where record_id=? for update",id);var check=recheck(token,kind,saved);require(number(check.get("errorCount"))==0,"仍有待匹配或错误行，请先核对");
  var rows=maps(check.get("rows"));long skipped=0,applied=0;Set<String> appliedKeys=new HashSet<>();
  for(var r:rows){var prior=saved.stream().filter(x->number(x.get("rowNumber"))==number(r.get("rowNumber"))).findFirst().orElse(Map.of());require(number(prior.get("existingId"))==number(r.get("existingId")),"匹配对象已变化，请重新预览");if(prior.containsKey("existingVersion"))require(number(prior.get("existingVersion"))==number(r.get("existingVersion")),"原数据已变化，请重新预览核对");
   if(kind.equals("sale")){if(text(r.get("action")).equals("skip_confirmed")){skipped++;continue;}var v=map(r.get("values"));String site=text(v.get("站点")),external=text(v.get("网站订单号")),key=site+"|"+external;if(!appliedKeys.add(key))continue;long existing=number(r.get("existingId"));var input=new LinkedHashMap<String,Object>();input.put("saleType","order");input.put("customerId",r.get("customerId"));input.put("currency",v.get("币种"));input.put("site",site);input.put("externalId",external);input.put("sourceStatus",text(v.get("网站来源状态")));input.put("notes","表格导入；确认前须核对，来源状态不代表实际收付和发货");input.put("version",existing>0?sales.detail(existing).get("version"):0);input.put("requestId","import-"+token+"-"+r.get("rowNumber"));List<Map<String,Object>> lines=new ArrayList<>();for(var peer:rows){var pv=map(peer.get("values"));if(site.equals(text(pv.get("站点")))&&external.equals(text(pv.get("网站订单号"))))lines.add(Map.of("specId",peer.get("specId"),"supplierCode",peer.get("supplierCode"),"qty",pv.get("数量"),"unitPrice",pv.get("单价")));}input.put("lines",lines);if(existing>0)input.put("refs",sales.detail(existing).getOrDefault("refs",List.of()));sales.save(existing>0?existing:null,input);applied++;}
   else{if(text(r.get("action")).equals("skip_same")){skipped++;continue;}saveProductRow(token,r);applied++;}
  }
  var result=Map.<String,Object>of("saved",applied,"skipped",skipped,"token",token);store.jdbc.update("update plm_partner_import set result=?::jsonb where token=?",encode(store.json,result),token);return result;
 }
 private List<Map<String,Object>> productChanges(Map<String,Object> product,Map<String,Object> row){
  var v=map(row.get("values"));List<Map<String,Object>> changes=new ArrayList<>();
  compare(changes,"商品名称",product.get("name"),v.get("商品名称"));compare(changes,"站点",product.get("site"),v.get("站点"));compare(changes,"网站商品号",product.get("externalId"),v.get("网站商品号"));
  long sid=number(row.get("specId"));
  if(sid>0){var spec=maps(product.get("specs")).stream().filter(x->number(x.get("id"))==sid).findFirst().orElse(Map.of());compare(changes,"规格",spec.getOrDefault("specification",spec.get("name")),spec.containsKey("specification")?text(v.get("规格")):text(v.get("规格")).isEmpty()?"待确认":v.get("规格"));
   if(!phoneIds(spec.get("phoneModelIds")).equals(phoneIds(row.get("phoneModelIds"))))changes.add(Map.of("field","适配机型","before",phoneIds(spec.get("phoneModelIds")),"after",phoneIds(row.get("phoneModelIds"))));compare(changes,"网站SKU",spec.get("websiteSku"),v.get("网站SKU"));String code=text(row.get("supplierCode"));
   if(!code.isEmpty()){var source=maps(spec.get("sources")).stream().filter(x->code.equals(x.get("supplierCode"))).findFirst().orElse(Map.of());if(source.isEmpty())changes.add(Map.of("field","新增供方","after",code));else{compare(changes,"供方货号",source.get("supplierItemCode"),v.get("供方货号"));if(!text(v.get("参考单价")).isEmpty()&&number(source.get("priceCents"))!=cents(v.get("参考单价")))changes.add(Map.of("field","参考单价","beforeCents",number(source.get("priceCents")),"afterCents",cents(v.get("参考单价"))));if(!text(v.get("币种")).isEmpty())compare(changes,"币种",source.get("currency"),v.get("币种"));}}
  }else if(!text(v.get("规格")).isEmpty()||!text(v.get("网站SKU")).isEmpty()||!text(row.get("supplierCode")).isEmpty()||!phoneIds(row.get("phoneModelIds")).isEmpty()){require(!text(v.get("网站SKU")).isEmpty(),"已有产品新增规格须填写网站SKU或明确匹配规格，不能仅按名称猜测");changes.add(Map.of("field","新增规格","after",text(v.get("规格"))));}
  return changes;
 }
 private void compare(List<Map<String,Object>> changes,String field,Object before,Object after){if(!text(before).equals(text(after)))changes.add(Map.of("field",field,"before",text(before),"after",text(after)));}
 private void saveProductRow(String token,Map<String,Object> row){
  var v=map(row.get("values"));long existing=number(row.get("existingId"));if(existing==0&&!text(v.get("站点")).isEmpty()&&!text(v.get("网站商品号")).isEmpty()){var matches=store.jdbc.queryForList("select record_id from plm_partner_record p where kind='product' and site=? and external_id=? and deleted_flag=0 and "+store.access.visibleSqlClause("partner","p.record_id"),v.get("站点"),v.get("网站商品号"));if(!matches.isEmpty())existing=number(matches.get(0).get("record_id"));}
  Map<String,Object> input=existing>0?copy(store.json,products.detail(existing)):new LinkedHashMap<>();input.put("name",text(v.get("商品名称")));input.putIfAbsent("status","draft");input.putIfAbsent("listing","unrecorded");input.put("site",text(v.get("站点")));input.put("externalId",text(v.get("网站商品号")));input.put("requestId","import-"+token+"-"+row.get("rowNumber"));
  List<Map<String,Object>> specs=new ArrayList<>(maps(input.get("specs")));Map<String,Object> spec=number(row.get("specId"))>0?specs.stream().filter(x->number(x.get("id"))==number(row.get("specId"))).findFirst().orElseThrow(()->invalid("规格不属于匹配产品")):new LinkedHashMap<>();
  if(!text(v.get("规格")).isEmpty()||!text(row.get("supplierCode")).isEmpty()||!text(v.get("网站SKU")).isEmpty()||!phoneIds(row.get("phoneModelIds")).isEmpty()){
   spec.put("name",text(v.get("规格")).isEmpty()?"待确认":text(v.get("规格")));spec.put("phoneModelIds",phoneIds(row.get("phoneModelIds")));if(!phoneIds(row.get("phoneModelIds")).isEmpty()||spec.containsKey("specification"))spec.put("specification",text(v.get("规格")));spec.put("websiteSku",text(v.get("网站SKU")));List<Map<String,Object>> sources=new ArrayList<>(maps(spec.get("sources")));String code=text(row.get("supplierCode"));if(!code.isEmpty()){Map<String,Object> source=new LinkedHashMap<>(sources.stream().filter(x->code.equals(x.get("supplierCode"))).findFirst().orElse(Map.of()));sources.removeIf(x->code.equals(x.get("supplierCode")));source.put("supplierCode",code);source.put("supplierItemCode",text(v.get("供方货号")));if(!text(v.get("参考单价")).isEmpty())source.put("unitPrice",v.get("参考单价"));if(!text(v.get("币种")).isEmpty()||!source.containsKey("currency"))source.put("currency",text(v.get("币种")).isEmpty()?"CNY":text(v.get("币种")));sources.add(source);}spec.put("sources",sources);if(number(spec.get("id"))==0)specs.add(spec);
  }input.put("specs",specs);products.save(existing>0?existing:null,input);
 }
}

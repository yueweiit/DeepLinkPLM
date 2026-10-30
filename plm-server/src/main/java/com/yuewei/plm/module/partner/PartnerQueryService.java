package com.yuewei.plm.module.partner;
import com.yuewei.plm.module.moldreference.PhoneSelectionMetadata;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import static com.yuewei.plm.module.partner.PartnerValues.*;
@Service @RequiredArgsConstructor
public class PartnerQueryService {
 private final PartnerStore store;private final SupplierProfileService suppliers;private final PartnerCustomerService customers;
 public Map<String,Object> page(String kind,Map<String,String> query){
  store.access.requireDomesticView();store.access.requirePermission("partner:view");require(Set.of("product","sale","sample").contains(kind)||PartnerOperationsAccess.KINDS.contains(kind),"列表类型无效");
  List<String> where=new ArrayList<>(List.of("r.kind=?","r.deleted_flag=0",store.access.visibleSqlClause("partner","r.record_id")));List<Object> args=new ArrayList<>(List.of(kind));
  String keyword=text(query.get("keyword"));if(!keyword.isEmpty()){String current=kind.equals("sample")?" or "+new PartnerCatalogStyles(store).currentNameSearch("r.data->>'catalogStyleId'"):"";where.add("(r.name ilike ? or r.code ilike ? or coalesce(r.data->>'summary','') ilike ? or r.external_id ilike ? or coalesce(r.data->>'specName','') ilike ? or coalesce(r.data->>'displayLabel','') ilike ?"+current+")");for(int i=0;i<(kind.equals("sample")?7:6);i++)args.add("%"+keyword+"%");}
  String status=text(query.get("status"));if(!status.isEmpty()&&!status.equals("all")){where.add("r.status=?");args.add(status);}
  if(number(query.get("parentId"))>0){store.access.requirePartner(number(query.get("parentId")));where.add("r.parent_record_id=?");args.add(number(query.get("parentId")));}
  relations(where,args,query);if(kind.equals("sample"))SampleDomains.filter(where,args,query,"r");
  String predicate=String.join(" and ",where);long count=store.jdbc.queryForObject("select count(*) from plm_partner_record r where "+predicate,Long.class,args.toArray());long page=Math.max(1,number(query.getOrDefault("page","1"))),size=Math.max(1,Math.min(100,number(query.getOrDefault("size","30"))));
  var states=store.jdbc.queryForList("select status,count(*) as count from plm_partner_record r where "+predicate+" group by status order by status",args.toArray());List<Object> pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add((page-1)*size);
  var rows=store.jdbc.queryForList("select r.record_id as id,r.code,r.kind,r.name,r.status,r.version,r.data::text,(select f.attachment_id from plm_partner_file f join plm_attachment a on a.attachment_id=f.attachment_id where f.record_id=r.record_id and f.purpose='photo' and a.deleted_flag=0 order by f.attachment_id desc limit 1) as \"photoId\",r.updated_at as \"updatedAt\" from plm_partner_record r where "+predicate+" order by r.updated_at desc,r.record_id desc limit ? offset ?",pageArgs.toArray());
  for(var row:rows){var data=decode(store.json,row.remove("data"));data.putAll(row);row.clear();row.putAll(data);}
  if(kind.equals("sample")){SampleDomains.enrich(store,rows,"id");new PartnerCatalogStyles(store).projectCurrent(rows);}
  return Map.of("content",rows,"page",page,"size",size,"totalElements",count,"totalPages",(count+size-1)/size,"statistics",states);
 }
 public Map<String,Object> options(){
  store.access.requireDomesticView();store.access.requirePermission("partner:view");
  var specs=store.jdbc.queryForList("select s.spec_id as id,s.code,s.name,s.record_id as \"productId\",p.name as \"productName\",s.data::text from plm_partner_spec s join plm_partner_record p on p.record_id=s.record_id where s.deleted_flag=0 and p.deleted_flag=0 and p.status<>'inactive' and "+store.access.visibleSqlClause("partner","p.record_id")+" order by p.updated_at desc,s.spec_id limit 1000");
  for(var spec:specs)spec.put("details",decode(store.json,spec.remove("data")));
  var supplierOptions=store.jdbc.queryForList("select p.supplier_id as id,p.supplier_code as code,p.supplier_name as name,p.short_code as \"shortCode\",p.supplier_type as type,p.status,p.version,p.data::text from plm_supplier_profile p where p.deleted_flag=0 and "+store.access.visibleSqlClause("supplier_profile","p.supplier_id")+" order by p.supplier_name");for(var s:supplierOptions)s.put("details",decode(store.json,s.remove("data")));
  var customerOptions=customers.all();for(var c:customerOptions)c.remove("profile");
  var skus=store.jdbc.queryForList("select product_id as id,product_code as code,product_name as name from plm_product p where p.deleted_flag=0 and p.product_type='sku' and "+store.access.visibleSqlClause("product","p.product_id")+" order by product_id desc");
  PhoneSelectionMetadata.enrich(store.jdbc,skus,"id","product");
  return Map.of("internalSkus",skus,"specs",specs,"suppliers",supplierOptions,"customers",customerOptions,"canReadMoney",store.access.hasPermission("finance:read"),"canWriteMoney",store.access.hasPermission("finance:write"),"canConfirmMoney",store.access.hasPermission("finance:confirm"));
 }
 public Map<String,Object> movements(Map<String,String> q){
  store.access.requireDomesticView();store.access.requirePermission("partner:view");
  List<String> where=new ArrayList<>(List.of("r.kind='sample'","r.deleted_flag=0",store.access.visibleSqlClause("partner","r.record_id")));List<Object> args=new ArrayList<>();
  if(number(q.get("sampleId"))>0){store.access.requirePartner(number(q.get("sampleId")));where.add("r.record_id=?");args.add(number(q.get("sampleId")));}
  String keyword=text(q.get("keyword"));if(!keyword.isEmpty()){where.add("(r.name ilike ? or r.code ilike ? or coalesce(r.data->>'specName','') ilike ?)");for(int i=0;i<3;i++)args.add("%"+keyword+"%");}
  if(!text(q.get("action")).isEmpty()){where.add("e.action=?");args.add(q.get("action"));}
  relations(where,args,q);SampleDomains.filter(where,args,q,"r");String base=String.join(" and ",where);
  long count=store.jdbc.queryForObject("select count(*) from plm_partner_event e join plm_partner_record r on r.record_id=e.record_id where "+base,Long.class,args.toArray());
  long page=Math.max(1,number(q.getOrDefault("page","1"))),size=Math.max(1,Math.min(100,number(q.getOrDefault("size","50"))));args.add(size);args.add((page-1)*size);
  var rows=store.jdbc.queryForList("select e.event_id as id,e.action,e.data::text,e.created_at as time,e.created_by as operator,r.record_id as \"sampleId\",r.code,r.name,r.data->>'sourceType' as \"sourceType\",r.data->>'sampleKind' as \"sampleKind\" from plm_partner_event e join plm_partner_record r on r.record_id=e.record_id where "+base+" order by e.event_id desc limit ? offset ?",args.toArray());
  for(var r:rows)r.put("data",decode(store.json,r.get("data")));SampleDomains.enrich(store,rows,"sampleId");
  return Map.of("content",rows,"totalElements",count,"page",page,"size",size);
 }
 private void relations(List<String> where,List<Object> args,Map<String,String> query){
  long modelProduct=number(query.get("modelProductId"));if(modelProduct>0){store.access.requirePermission("product:view");store.access.requireProduct(modelProduct);where.add("r.kind='sample' and r.data->>'sourceType'='model' and r.data->>'modelProductId'=?");args.add(Long.toString(modelProduct));}
  for(String key:List.of("customerId","projectId","productId"))if(number(query.get(key))>0){long id=number(query.get(key));if(key.equals("customerId")){store.access.requireCustomer(id);where.add("(r.customer_id=? or exists(select 1 from plm_partner_event pe where pe.record_id=r.record_id and pe.data->>'customerId'=?))");args.add(id);args.add(Long.toString(id));}
   else if(key.equals("projectId")){store.access.requireProduct(id);where.add("(r.source_project_id=? or (r.kind='sample' and exists(select 1 from plm_partner_event usage where usage.record_id=r.record_id and usage.data->>'projectId'=?)))");args.add(id);args.add(Long.toString(id));}else{store.access.requirePartner(id);where.add("(r.data->>'productId'=? or coalesce(r.data->'lines','[]') @> ?::jsonb)");args.add(Long.toString(id));args.add(encode(store.json,List.of(Map.of("snapshot",Map.of("productId",id)))));}}
  String supplier=text(query.get("supplierCode"));if(!supplier.isEmpty()){suppliers.requireSupplier(supplier);where.add("(r.supplier_code=? or coalesce(r.data->'lines','[]') @> ?::jsonb or jsonb_path_exists(r.data,'$.specs[*].sources[*] ? (@.supplierCode == $code)',jsonb_build_object('code',?::text)))");args.add(supplier);args.add(encode(store.json,List.of(Map.of("supplierCode",supplier))));args.add(supplier);}
  String source=text(query.get("sourceType"));if(!source.isEmpty()){where.add("r.data->>'sourceType'=?");args.add(source);}
 }
 public Map<String,Object> relations(String type,String id){
  store.access.requireDomesticView();Map<String,String> q=new LinkedHashMap<>(Map.of("page","1","size","100"));
  if(type.equals("customer")){store.access.requireCustomer(number(id));q.put("customerId",id);}else if(type.equals("supplier")){suppliers.requireSupplier(id);q.put("supplierCode",id);}else throw invalid("关联类型无效");
  var sales=page("sale",q);var sample=page("sample",q);Map<String,Object> out=new LinkedHashMap<>(Map.of("sales",sales,"samples",sample));if(type.equals("supplier")){out.put("products",page("product",q));var summary=store.jdbc.queryForList("select r.data->>'currency' as currency,sum((x->>'payRemainingCents')::bigint) as \"payRemainingCents\",sum((x->>'feeRemainingCents')::bigint) as \"feeRemainingCents\",count(*) filter(where coalesce(x->'agreement'->>'review','false')='true') as \"reviewCount\" from plm_partner_record r cross join lateral jsonb_array_elements(coalesce(r.data->'stats'->'settlements','[]')) x where r.kind='sale' and r.deleted_flag=0 and "+store.access.visibleSqlClause("partner","r.record_id")+" and x->>'supplierCode'=? group by r.data->>'currency'",id);out.put("pending",summary);}
  else{for(var row:maps(sample.get("content"))){var loans=maps(map(row.get("balances")).get("loans"));row.put("customerOutstanding",loans.stream().filter(x->number(x.get("customerId"))==number(id)).mapToLong(x->number(x.get("outstanding"))).sum());}}
  return out;
 }
}

package com.yuewei.plm.module.partner;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.partner.PartnerValues.*;

@Service @RequiredArgsConstructor
public class PartnerSalesService {
 private final PartnerStore store;private final PartnerProductService products;private final PartnerMaterialService materials;private final SupplierProfileService suppliers;
 public Map<String,Object> detail(long id){var d=store.load(id,"sale",false);var events=store.events(id);d.put("events",events);d.put("stats",SalesLedger.stats(d,events));d.put("revisions",store.revisions(id));d.put("files",store.files(id));return d;}
 @Transactional public Map<String,Object> save(Long id,Map<String,Object> input){
  store.access.requireDomesticView();store.access.requirePermission("partner:write");String request=required(input.get("requestId"),"提交标识");boolean creating=id==null;
  if(creating){Long repeated=store.created("sale",request);if(repeated!=null)return detail(repeated);id=store.create("sale","销售草稿");store.remember("sale",request,id);}var old=store.load(id,"sale",true);if(store.repeated(id,request))return detail(id);store.check(old,creating?0L:number(input.get("version")));require(!Boolean.TRUE.equals(old.get("confirmed")),"订单已确认，请使用订单调整");
  var d=new LinkedHashMap<String,Object>();String saleType=text(input.getOrDefault("saleType","inquiry"));require(Set.of("inquiry","order").contains(saleType),"销售类型无效");d.put("saleType",saleType);d.put("name",text(input.get("name")).isEmpty()?(saleType.equals("inquiry")?"合作产品询价":"合作产品订单"):text(input.get("name")));d.put("currency",currency(input.getOrDefault("currency","CNY")));d.put("notes",text(input.get("notes")));d.put("site",text(input.get("site")));d.put("externalId",text(input.get("externalId")));d.put("sourceStatus",text(input.get("sourceStatus")));d.put("status","draft");d.put("confirmed",false);d.put("quotes",old.getOrDefault("quotes",List.of()));d.put("agreements",old.getOrDefault("agreements",List.of()));d.put("refs",store.editableFiles(id,input.get("refs"),old.get("refs")));
  long cid=number(input.get("customerId"));require(cid>0||!text(d.get("notes")).isEmpty(),"至少选择客户或填写需求说明");if(cid>0){d.put("customerId",cid);d.put("customerSnapshot",customer(cid));}
  List<Map<String,Object>> lines=new ArrayList<>();for(var raw:maps(input.get("lines"))){if(number(raw.get("specId"))<1)continue;var requested=new LinkedHashMap<>(raw);var previous=maps(old.get("lines")).stream().filter(x->text(x.get("id")).equals(text(raw.get("id")))&&number(x.get("specId"))==number(raw.get("specId"))&&text(x.get("supplierCode")).equals(text(raw.get("supplierCode")))).findFirst().orElse(Map.of());
   if(!store.access.hasPermission("finance:write")&&!previous.isEmpty()){require(text(d.get("currency")).equals(text(old.get("currency"))),"没有修改报价币种的权限");if(raw.containsKey("unitPrice"))require(cents(raw.get("unitPrice"))==number(previous.get("priceCents")),"没有修改报价的权限");requested.remove("unitPrice");}
   var saved=line(requested,false,text(d.get("currency")));if(!store.access.hasPermission("finance:write")&&!previous.isEmpty()){saved.put("priceCents",previous.get("priceCents"));saved.put("unitPrice",previous.get("unitPrice"));}lines.add(saved);}require(lines.stream().map(x->text(x.get("id"))).distinct().count()==lines.size(),"订单明细标识重复");d.put("lines",lines);d.put("summary",lines.stream().map(l->text(map(l.get("snapshot")).get("productName"))+" / "+text(map(l.get("snapshot")).get("specName"))).collect(java.util.stream.Collectors.joining("；")));d.put("stats",SalesLedger.stats(d,store.events(id)));store.save(id,d);store.event(id,"sale_save",Map.of("name",d.get("name"),"saleType",saleType),request);return detail(id);
 }
 private Map<String,Object> customer(long id){store.access.requireCustomer(id);var rows=store.jdbc.queryForList("select customer_id as id,customer_code as code,customer_name as name,customer_type as type,status,profile_data::text as profile from plm_customer where customer_id=? and deleted_flag=0 for share",id);require(!rows.isEmpty()&&!text(rows.get(0).get("status")).equals("inactive"),"客户不存在或已停用");var d=rows.get(0);d.put("profile",decode(store.json,d.get("profile")));return d;}
 private Map<String,Object> line(Map<String,Object> raw,boolean actual,String saleCurrency){
  long spec=number(raw.get("specId"));String supplier=required(raw.get("supplierCode"),"供方");var snapshot=products.snapshot(spec,supplier,actual);var source=map(snapshot.get("source"));long price=raw.containsKey("unitPrice")?cents(raw.get("unitPrice")):(saleCurrency.equals(source.get("currency"))?number(source.get("priceCents")):0);
  if(raw.containsKey("unitPrice")&&!store.access.hasPermission("finance:write"))require(price==number(source.get("priceCents")),"没有修改报价的权限");
  return new LinkedHashMap<>(Map.of("id",text(raw.get("id")).isEmpty()?UUID.randomUUID().toString():text(raw.get("id")),"specId",spec,"supplierCode",supplier,"qty",qty(raw.get("qty"),false),"priceCents",price,"unitPrice",java.math.BigDecimal.valueOf(price,2).toPlainString(),"snapshot",snapshot));
 }
 private void validateBasis(Map<String,Object> sale,long id){
  require(number(sale.get("customerId"))>0,"请明确客户");customer(number(sale.get("customerId")));require(!maps(sale.get("lines")).isEmpty(),"请明确商品明细");
  for(var l:maps(sale.get("lines"))){require(number(l.get("priceCents"))>0,"请填写有效单价");qty(l.get("qty"),false);var snapshot=map(l.get("snapshot"));long product=number(snapshot.get("productId"));store.access.requirePartner(product);
   require(Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_revision where record_id=? and revision=?)",Boolean.class,product,number(snapshot.get("productVersion")))),"来源资料版本失效");
   var supplier=suppliers.requireSupplier(text(l.get("supplierCode")));require(!text(supplier.get("status")).equals("inactive")&&!Boolean.TRUE.equals(supplier.get("needsReview"))&&!text(supplier.get("supplierType")).equals("unknown"),"供方身份仍待核对或已停用");
   for(var ref:maps(snapshot.get("refs")))store.access.requireFile(number(ref.get("id")));materials.inherit(id,snapshot.get("refs"));
  }
 }
 @Transactional public Map<String,Object> action(long id,String action,Map<String,Object> input){
  store.access.requireDomesticView();var d=store.load(id,"sale",true);String request=required(input.get("requestId"),"提交标识");if(store.repeated(id,request))return detail(id);store.check(d,number(input.get("version")));
  require(!Set.of("ship","reship").contains(action),"历史销售记录保留；新订单／新批次及原未发数量，请从委外与交付引用需求，经本批实检和工厂签字后发运");
  List<Map<String,Object>> events=store.events(id);Map<String,Object> actual=new LinkedHashMap<>();actual.put("reason",text(input.get("reason")));actual.put("currency",d.get("currency"));actual.put("refs",store.canonicalFiles(id,input.get("refs")));
  if(Set.of("quote_confirm","order_confirm","agreement","receipt","refund","supplierpay","supplierrefund","feereceipt","feerefund","offsetreverse","amend","close","reopen").contains(action)){store.access.requireFinanceConfirm();store.access.requireFinanceWrite();}
  else store.access.requirePermission("partner:write");
  if(action.equals("quote_confirm")||action.equals("order_confirm")){
   require(!Boolean.TRUE.equals(d.get("confirmed")),"订单已确认");validateBasis(d,id);actual.put("customerSnapshot",d.get("customerSnapshot"));actual.put("lines",d.get("lines"));actual.put("totalCents",SalesLedger.total(d,events));
   if(action.equals("quote_confirm")){var quotes=new ArrayList<>(maps(d.get("quotes")));String fingerprint=fingerprint(d);require(quotes.isEmpty()||!fingerprint.equals(quotes.get(quotes.size()-1).get("fingerprint")),"当前报价已经确认，无需重复");var q=new LinkedHashMap<>(actual);q.put("version",quotes.size()+1);q.put("fingerprint",fingerprint);q.put("time",java.time.LocalDateTime.now().toString());quotes.add(q);d.put("quotes",quotes);d.put("status","quoted");}
   else{if(text(d.get("saleType")).equals("inquiry")){var quotes=maps(d.get("quotes"));String fingerprint=fingerprint(d);require(!quotes.isEmpty()&&fingerprint.equals(quotes.get(quotes.size()-1).get("fingerprint")),"请先确认当前报价再转订单");}d.put("confirmed",true);d.put("saleType","order");d.put("status","confirmed");}
   store.revision(id,d);
  }else if(action.equals("reopen")){require(Boolean.TRUE.equals(d.get("confirmed"))&&text(d.get("status")).equals("closed"),"仅已关闭订单可以重新办理");actual.put("reason",required(input.get("reason"),"重新办理原因"));d.put("status","confirmed");}
  else{
   require(Boolean.TRUE.equals(d.get("confirmed"))&&!text(d.get("status")).equals("closed"),"请先确认订单；已关闭订单不能继续办理");
   if(action.equals("agreement"))agreement(d,events,input,actual);
   else if(Set.of("receipt","refund","supplierpay","supplierrefund","feereceipt","feerefund","offsetreverse").contains(action))moneyAction(d,events,action,input,actual);
   else if(Set.of("ship","reship","returngoods","cancel","shipreverse").contains(action))quantityAction(d,events,action,input,actual);
   else if(action.equals("amend")){required(input.get("reason"),"调整原因");List<Map<String,Object>> oldLines=maps(d.get("lines"));var l=oldLines.stream().filter(x->text(x.get("id")).equals(text(input.get("lineId")))).findFirst().orElseThrow(()->invalid("请选择原订单明细"));long newQty=qty(input.get("qty"),false);long shipped=SalesLedger.quantity(events,"ship",text(l.get("id")))-SalesLedger.quantity(events,"shipreverse",text(l.get("id")));require(newQty>=shipped+SalesLedger.quantity(events,"cancel",text(l.get("id"))),"调整后数量不能少于已发与已取消数量");actual.put("before",copy(store.json,l));l.put("qty",newQty);if(input.containsKey("unitPrice")){long price=cents(input.get("unitPrice"));require(price>0,"调整后单价须大于0");l.put("priceCents",price);l.put("unitPrice",text(input.get("unitPrice")));}actual.put("after",copy(store.json,l));markReview(d,null);store.revision(id,d);}
   else if(action.equals("close")){var stat=SalesLedger.stats(d,events);require(number(stat.get("customerRemainingCents"))==0,"客户货款或退款尚未处理完");require(number(stat.get("shipped"))>=number(stat.get("ordered")),"正常发货尚未完成");for(var line:maps(d.get("lines"))){String lid=text(line.get("id"));long replacements=events.stream().filter(e->"returngoods".equals(e.get("action"))&&lid.equals(map(e.get("data")).get("lineId"))&&"replace".equals(map(e.get("data")).get("disposition"))).mapToLong(e->number(map(e.get("data")).get("qty"))).sum();require(SalesLedger.quantity(events,"reship",lid)>=replacements,"换货补发尚未完成");}for(var value:maps(stat.get("settlements"))){require(!map(value.get("agreement")).isEmpty()&&!Boolean.TRUE.equals(map(value.get("agreement")).get("review"))&&number(value.get("payRemainingCents"))==0&&number(value.get("feeRemainingCents"))==0,"供方结算或佣金尚未处理完");}d.put("status","closed");}
   else throw invalid("销售动作无效");
  }
  store.event(id,action,actual,request);d.put("stats",SalesLedger.stats(d,store.events(id)));store.save(id,d);return detail(id);
 }
 private void agreement(Map<String,Object> d,List<Map<String,Object>> events,Map<String,Object> input,Map<String,Object> actual){
  String code=required(input.get("supplierCode"),"供方");require(maps(d.get("lines")).stream().anyMatch(l->code.equals(l.get("supplierCode"))),"供方不属于本单");var supplier=suppliers.requireSupplier(code);var sums=SalesLedger.settlement(d,events,code);long gross=cents(input.get("gross")),fee=cents(input.get("fee"));String mode=text(input.get("mode"));require(Set.of("net","gross").contains(mode),"结算方式无效");require(!mode.equals("net")||fee<=gross,"扣佣不能超过货款");
  require(gross>=number(sums.get("paidCents"))+number(sums.get("offsetCents"))&&fee>=number(sums.get("feeReceivedCents"))+number(sums.get("offsetCents")),"新约定低于已核销金额，请先记录实际退款、退佣或抵扣冲回");
  var prior=map(sums.get("agreement"));if(!prior.isEmpty())required(input.get("reason"),"重新核对原因");Map<String,Object> a=new LinkedHashMap<>();a.put("supplierCode",code);a.put("supplierName",supplier.get("supplierName"));a.put("grossCents",gross);a.put("feeCents",fee);a.put("mode",mode);a.put("version",number(prior.get("version"))+1);a.put("review",false);a.put("notes",text(input.get("notes")));a.put("currency",d.get("currency"));actual.put("before",prior);actual.put("after",a);
  var agreements=new ArrayList<>(maps(d.get("agreements")));agreements.removeIf(x->code.equals(x.get("supplierCode")));agreements.add(a);d.put("agreements",agreements);
 }
 private void moneyAction(Map<String,Object> d,List<Map<String,Object>> events,String action,Map<String,Object> input,Map<String,Object> actual){
  long amount=cents(input.get("amount"));require(amount>0||action.equals("supplierpay"),"本次金额须大于0");actual.put("amountCents",amount);actual.put("date",date(input.get("date")));
  if(action.equals("receipt"))require(amount<=SalesLedger.total(d,events)-SalesLedger.amount(events,"receipt",null)+SalesLedger.amount(events,"refund",null),"超过客户尚未收取的货款");
  if(action.equals("refund")){long original=number(input.get("originalEventId"));var old=SalesLedger.original(events,original,"receipt");require(amount<=number(old.get("amountCents"))-SalesLedger.appliedTo(events,"refund",original),"超过原收款可退金额");actual.put("originalEventId",original);required(input.get("reason"),"退款原因");markReview(d,null);}
  if(!Set.of("receipt","refund").contains(action)){
   String code=required(input.get("supplierCode"),"供方");var sums=SalesLedger.settlement(d,events,code);var a=map(sums.get("agreement"));require(!a.isEmpty(),"请先确认供方结算约定");actual.put("supplierCode",code);actual.put("supplierName",a.get("supplierName"));actual.put("agreementVersion",a.get("version"));
   if(action.equals("supplierpay")||action.equals("feereceipt"))require(!Boolean.TRUE.equals(a.get("review")),"结算待核对，请先重新确认约定");
   if(action.equals("supplierpay")){long offset=text(a.get("mode")).equals("net")?cents(input.getOrDefault("offset","0")):0;require(amount+offset>0&&amount+offset<=number(sums.get("payRemainingCents")),"超过供方剩余应结金额，或本次金额为零");require(offset<=number(sums.get("feeRemainingCents")),"佣金已核销或超过剩余金额");actual.put("offsetCents",offset);}
   if(action.equals("feereceipt")){require(text(a.get("mode")).equals("gross"),"扣佣方式请在付款时登记抵扣");require(amount<=number(sums.get("feeRemainingCents")),"超过剩余未收佣金");}
   if(Set.of("supplierrefund","feerefund","offsetreverse").contains(action)){String originAction=action.equals("feerefund")?"feereceipt":"supplierpay";long original=number(input.get("originalEventId"));var old=SalesLedger.original(events,original,originAction);require(code.equals(old.get("supplierCode")),"原记录与供方不符");long allowed=number(old.get(action.equals("offsetreverse")?"offsetCents":"amountCents"))-SalesLedger.appliedTo(events,action,original);require(amount<=allowed,"超过原记录尚可退回或冲回金额");required(input.get("reason"),"退回／冲回原因");actual.put("originalEventId",original);markReview(d,code);}
  }
 }
 private void quantityAction(Map<String,Object> d,List<Map<String,Object>> events,String action,Map<String,Object> input,Map<String,Object> actual){
  String lid=required(input.get("lineId"),"明细");var line=maps(d.get("lines")).stream().filter(l->lid.equals(l.get("id"))).findFirst().orElseThrow(()->invalid("明细不属于本单"));long q=qty(input.get("qty"),false),shipped=SalesLedger.quantity(events,"ship",lid)-SalesLedger.quantity(events,"shipreverse",lid),cancelled=SalesLedger.quantity(events,"cancel",lid),returned=SalesLedger.quantity(events,"returngoods",lid);actual.put("lineId",lid);actual.put("qty",q);actual.put("supplierCode",line.get("supplierCode"));actual.put("snapshot",line.get("snapshot"));actual.put("date",date(input.get("date")));actual.put("tracking",text(input.get("tracking")));
  if(action.equals("ship")||action.equals("cancel"))require(q<=number(line.get("qty"))-cancelled-shipped,"超过尚未发货数量");
  if(Set.of("returngoods","reship","shipreverse").contains(action)){
   long original=number(input.get("originalEventId"));var old=SalesLedger.original(events,original,"ship");require(lid.equals(old.get("lineId")),"原发货与明细不符");long used=events.stream().filter(e->(action.equals(e.get("action"))||(!action.equals("shipreverse")&&text(e.get("action")).equals("shipreverse"))||(action.equals("shipreverse")&&text(e.get("action")).equals("returngoods")))&&number(map(e.get("data")).get("originalEventId"))==original).mapToLong(e->number(map(e.get("data")).get("qty"))).sum();require(q<=number(old.get("qty"))-used,"超过原发货可办理数量");actual.put("originalEventId",original);
   if(action.equals("returngoods")){require(q<=shipped-returned,"超过尚未退货数量");String disposition=text(input.getOrDefault("disposition","refund"));require(Set.of("refund","replace").contains(disposition),"退货处理方式无效");actual.put("disposition",disposition);}
   if(action.equals("shipreverse"))require(shipped-q>=returned,"已有退货依赖此发货，请先核对");required(input.get("reason"),"本次原因");
  }
  if(!action.equals("ship"))required(input.get("reason"),"本次原因");if(Set.of("cancel","returngoods","shipreverse").contains(action))markReview(d,text(line.get("supplierCode")));
 }
 private String fingerprint(Map<String,Object> d){try{return store.json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(Map.of("lines",d.get("lines"),"customerId",d.get("customerId"),"currency",d.get("currency")));}catch(Exception e){throw invalid("报价依据无法编码");}}
 private void markReview(Map<String,Object> d,String code){for(var a:maps(d.get("agreements")))if(code==null||code.equals(a.get("supplierCode")))a.put("review",true);}
}

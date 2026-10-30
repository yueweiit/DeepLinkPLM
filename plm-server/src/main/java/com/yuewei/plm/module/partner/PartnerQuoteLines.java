package com.yuewei.plm.module.partner;

import java.sql.PreparedStatement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** A quote can precede a product; candidate quote rows never create stock or product identities. */
@Service @RequiredArgsConstructor
public class PartnerQuoteLines {
 private final PartnerStore store;
 private final PartnerOperationsAccess access;
 private final PartnerSelectionService selections;
 private MessageDigest importDigest(long version){try{var hash=MessageDigest.getInstance("SHA-256");hash.update((version+"\n").getBytes(StandardCharsets.UTF_8));return hash;}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private void hashRow(MessageDigest hash,Map<String,Object> row){hash.update(signature(store.json,row).getBytes(StandardCharsets.UTF_8));hash.update((byte)'\n');}
 public Map<String,Object> detail(long id){access.view("supplier_quote");var head=store.load(id,"supplier_quote",false);head.put("lineCount",store.jdbc.queryForObject("select count(*) from plm_partner_quote_line where record_id=?",Long.class,id));return head;}
 private Map<String,Object> decodeLine(Map<String,Object> raw){var d=decode(store.json,raw.get("data"));d.put("rowId",text(raw.get("id")));d.put("rowVersion",raw.get("row_version"));d.put("sortOrder",raw.get("sort_order"));return d;}
 public Map<String,Object> importedRowBaseline(long id,String rowId){access.write("supplier_quote");store.load(id,"supplier_quote",false);require(PartnerSelectionService.validRowId(rowId),"报价行标识无效");var row=find(id,rowId,false);if(row.isEmpty())require(!Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_quote_line where line_id=?::uuid)",Boolean.class,rowId)),"报价行身份已属于其他资料，请复制为新行");return row;}
 private Map<String,Object> find(long id,String rowId,boolean lock){var old=store.jdbc.queryForList("select line_id::text as id,row_version,sort_order,data::text from plm_partner_quote_line where record_id=? and line_id=?::uuid"+(lock?" for update":""),id,rowId);return old.isEmpty()?Map.of():decodeLine(old.get(0));}
 public List<String> supplierCodes(long id){return List.of(required(detail(id).get("supplierCode"),"报价供应商"));}
 private String quoteVersion(Map<String,Object> header){String version=text(header.get("quoteVersion"));return version.isEmpty()?"V1":version;}
 @Transactional(readOnly=true) public void forEachRowBatch(long id,Map<String,String> query,Consumer<List<Map<String,Object>>> sink){
  access.view("supplier_quote");var head=store.load(id,"supplier_quote",false);String supplier=text(query.get("supplierCode"));if(!supplier.isEmpty()&&!supplier.equals(text(head.get("supplierCode"))))return;
  var batch=new ArrayList<Map<String,Object>>(100);store.jdbc.query(connection->{PreparedStatement ps=connection.prepareStatement("select line_id::text,row_version,sort_order,data::text from plm_partner_quote_line where record_id=? order by sort_order,line_id");ps.setFetchSize(100);ps.setLong(1,id);return ps;},rs->{var row=new LinkedHashMap<String,Object>();row.put("id",rs.getString(1));row.put("row_version",rs.getLong(2));row.put("sort_order",rs.getLong(3));row.put("data",rs.getString(4));batch.add(new PartnerCatalogStyles(store).enrich("quote",id,decodeLine(row)));if(batch.size()==100){sink.accept(new ArrayList<>(batch));batch.clear();}});if(!batch.isEmpty())sink.accept(batch);
 }
 Map<String,Object> canonicalRow(long id,Map<String,Object> header,Map<String,Object> previous,Map<String,Object> input,PartnerCatalogPhoneResolver.ResolutionScope scope){
  previous=PartnerCatalogStyles.currentData(previous);var changes=new LinkedHashMap<>(input);String supplier=required(header.get("supplierCode"),"报价供应商"),version=quoteVersion(header);
  require(!changes.containsKey("supplierCode")||supplier.equals(text(changes.get("supplierCode"))),"报价行不能跨供应商");require(text(changes.get("quoteId")).isEmpty()||Long.toString(id).equals(text(changes.get("quoteId"))),"报价行来源不能指向另一报价");require(text(changes.get("quoteVersion")).isEmpty()||version.equals(text(changes.get("quoteVersion"))),"报价行来源版本不一致");
  long style=number(changes.remove("catalogStyleId"));changes.remove("catalogStyleCode");changes.remove("catalogStyleLabel");
  changes.put("supplierCode",supplier);changes.put("quoteId",Long.toString(id));changes.put("quoteVersion",version);var result=scope==null?selections.canonicalRow(id,previous,changes):selections.canonicalRow(id,previous,changes,scope);result.remove("catalogCurrent");if(style>0)result.put("catalogStyleId",style);return result;
 }
 private void put(long id,Map<String,Object> data,Map<String,Object> previous,String request){
  String rid=required(data.get("rowId"),"报价行标识");var saved=new LinkedHashMap<>(data);long style=number(saved.remove("catalogStyleId"));saved.remove("catalogStyleCode");saved.remove("catalogStyleLabel");saved.remove("rowVersion");saved.remove("sortOrder");saved.remove("sampleBatches");long version=previous.isEmpty()?1:number(previous.get("rowVersion"))+1;
  if(previous.isEmpty()){require(!Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_quote_line where line_id=?::uuid)",Boolean.class,rid)),"报价行身份已属于其他资料，请复制为新行");store.jdbc.update("insert into plm_partner_quote_line(line_id,record_id,sort_order,data,updated_by) values(?::uuid,?,(select coalesce(max(sort_order),0)+1 from plm_partner_quote_line where record_id=?),?::jsonb,?)",rid,id,id,encode(store.json,saved),store.access.actor().username());}
  else store.jdbc.update("update plm_partner_quote_line set data=?::jsonb,row_version=?,updated_at=now(),updated_by=? where record_id=? and line_id=?::uuid",encode(store.json,saved),version,store.access.actor().username(),id,rid);
  store.jdbc.update("insert into plm_partner_quote_line_revision(line_id,record_id,row_version,request_id,data,created_by) values(?::uuid,?,?,?,?::jsonb,?)",rid,id,version,request,encode(store.json,Map.of("before",previous,"after",saved)),store.access.actor().username());
  new PartnerCatalogStyles(store).ensure("quote",id,data,style,"国内工程确认供应商目录款式；显式复用或新分配CP",request);
  if(!previous.isEmpty())selections.refreshCatalogDrafts("quote",id,rid,saved,saved.keySet(),request);
 }
 /** One transaction publishes an import; it never allocates Product, specification or sample identities. */
 @Transactional public Map<String,Object> applyImportedRows(long id,long baseVersion,String requestId,Iterable<Map<String,Object>> incoming){return publishRows(id,baseVersion,requestId,incoming,false);}
 /** Only the durable file-job service calls this with server-normalized staging rows, never an HTTP trust flag. */
 @Transactional Map<String,Object> applyStagedRows(long id,long baseVersion,String requestId,Iterable<Map<String,Object>> incoming){return publishRows(id,baseVersion,requestId,incoming,true);}
 private Map<String,Object> publishRows(long id,long baseVersion,String requestId,Iterable<Map<String,Object>> incoming,boolean staged){
  access.write("supplier_quote");var head=store.load(id,"supplier_quote",true);String request=required(requestId,"提交标识");require(request.length()<=80,"提交标识过长");var hash=importDigest(baseVersion);var replay=store.jdbc.queryForList("select action,data::text from plm_partner_event where record_id=? and request_id=?",id,request);if(!replay.isEmpty()){for(var row:incoming)hashRow(hash,row);require("quote_import".equals(text(replay.get(0).get("action")))&&HexFormat.of().formatHex(hash.digest()).equals(text(decode(store.json,replay.get(0).get("data")).get("inputFingerprint"))),"提交标识已用于不同目录导入，请核对原提交结果");return detail(id);}
  store.check(head,baseVersion);require(text(head.get("status")).equals("draft"),"已确认报价冻结，请建立后续报价版本");head.put("quoteVersion",quoteVersion(head));long changed=0;var scope=selections.phoneScope();Set<String> seen=new HashSet<>();
  for(var input:incoming){hashRow(hash,input);String rid=required(input.get("rowId"),"导入行身份");require(PartnerSelectionService.validRowId(rid)&&seen.add(rid),"导入行身份无效或重复");var previous=find(id,rid,true);if(!previous.isEmpty())require(number(input.get("rowVersion"))==number(previous.get("rowVersion")),"导入报价行已变化，请重新预览");var changes=new LinkedHashMap<>(input);if(!previous.isEmpty())changes.entrySet().removeIf(e->text(e.getValue()).isEmpty()&&!Set.of("selected","discarded").contains(e.getKey()));var canonical=canonicalRow(id,head,previous,changes,scope);if(staged)for(String field:List.of("rawQuoteFields","currencySource","moqSource"))if(input.containsKey(field))canonical.put(field,field.equals("rawQuoteFields")?copy(store.json,input.get(field)):input.get(field));put(id,canonical,previous,request);changed++;}
  head.put("lineCount",store.jdbc.queryForObject("select count(*) from plm_partner_quote_line where record_id=?",Long.class,id));store.revision(id,head);store.save(id,head);store.event(id,"quote_import",Map.of("changed",changed,"baseVersion",baseVersion,"inputFingerprint",HexFormat.of().formatHex(hash.digest())),request);return Map.of("id",id,"version",number(head.get("version"))+1,"lineCount",head.get("lineCount"));
 }
 public Map<String,Object> page(long id,Map<String,String> q){
  access.view("supplier_quote");store.load(id,"supplier_quote",false);long page=Math.max(1,number(q.getOrDefault("page","1"))),size=Math.max(1,Math.min(100,number(q.getOrDefault("size","30"))));
  String where="record_id=?";var args=new ArrayList<Object>();args.add(id);String keyword=text(q.get("keyword"));if(!keyword.isEmpty()){where+=" and (data->>'name' ilike ? or data->>'supplierItemCode' ilike ?)";args.add("%"+keyword+"%");args.add("%"+keyword+"%");}
  long total=store.jdbc.queryForObject("select count(*) from plm_partner_quote_line where "+where,Long.class,args.toArray());args.add(size);args.add(Math.multiplyExact(page-1,size));
  var content=store.jdbc.queryForList("select line_id::text as id,row_version,sort_order,data::text from plm_partner_quote_line where "+where+" order by sort_order,line_id limit ? offset ?",args.toArray()).stream().map(r->{var d=decode(store.json,r.get("data"));d.put("rowId",r.get("id"));d.put("rowVersion",r.get("row_version"));d.put("sortOrder",r.get("sort_order"));return d;}).toList();
  return Map.of("content",content.stream().map(row->new PartnerCatalogStyles(store).enrich("quote",id,row)).toList(),"totalElements",total,"page",page,"size",size,"totalPages",(total+size-1)/size);
 }
 @Transactional public Map<String,Object> patch(long id,Map<String,Object> input){
  access.write("supplier_quote");var header=store.load(id,"supplier_quote",true);String request=required(input.get("requestId"),"提交标识");require(request.length()<=80,"提交标识过长");
  var replay=store.jdbc.queryForList("select action,data::text from plm_partner_event where record_id=? and request_id=?",id,request);if(!replay.isEmpty()){require("quote_lines".equals(replay.get(0).get("action"))&&signature(store.json,input).equals(text(decode(store.json,replay.get(0).get("data")).get("inputFingerprint"))),"提交标识已用于不同目录行修改，请核对原提交结果");return Map.of("id",id,"version",header.get("version"));}
  store.check(header,number(input.get("version")));require(text(header.get("status")).equals("draft"),"已确认报价冻结，请建立后续报价版本");require(input.get("operations") instanceof List<?>,"请指定本次修改行");long changed=0;
  String quoteVersion=text(header.get("quoteVersion"));if(quoteVersion.isEmpty())quoteVersion=text(input.getOrDefault("quoteVersion","V1"));required(quoteVersion,"来源报价版本");require(!input.containsKey("quoteVersion")||quoteVersion.equals(text(input.get("quoteVersion"))),"新报价版本请另建关联报价，编辑锁版本不等于来源版本");header.put("quoteVersion",quoteVersion);
  var phoneScope=selections.phoneScope();
  for(var operation:maps(input.get("operations"))){String rid=text(operation.get("rowId"));if(rid.isEmpty())rid=UUID.randomUUID().toString();require(PartnerSelectionService.validRowId(rid),"报价行标识无效");
   var previous=find(id,rid,true);require(number(previous.get("rowVersion"))==number(operation.get("rowVersion")),"报价行已变化，请重新核对");
   var changes=new LinkedHashMap<>(map(operation.get("changes")));changes.put("rowId",rid);
   put(id,canonicalRow(id,header,previous,changes,phoneScope),previous,request);changed++;
  }
  header.put("lineCount",store.jdbc.queryForObject("select count(*) from plm_partner_quote_line where record_id=?",Long.class,id));store.revision(id,header);store.save(id,header);store.event(id,"quote_lines",Map.of("changed",changed,"inputFingerprint",signature(store.json,input)),request);return Map.of("id",id,"version",number(header.get("version"))+1,"lineCount",header.get("lineCount"));
 }
}

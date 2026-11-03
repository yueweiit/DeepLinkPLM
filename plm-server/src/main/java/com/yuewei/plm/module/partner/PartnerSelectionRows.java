package com.yuewei.plm.module.partner;

import java.sql.PreparedStatement;
import java.util.*;
import java.util.function.Consumer;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Database-backed rows: total row count is not a request or document limit. */
final class PartnerSelectionRows {
 private final PartnerStore store;
 PartnerSelectionRows(PartnerStore store){this.store=store;}
 Map<String,Object> find(long id,String rowId,boolean lock){
  var found=store.jdbc.queryForList("select row_id::text,row_version,sort_order,data::text,confirmed from plm_partner_selection_row where record_id=? and row_id=?::uuid"+(lock?" for update":""),id,rowId);
  return found.isEmpty()?Map.of():decode(found.get(0));
 }
 private Map<String,Object> decode(Map<String,Object> r){var out=PartnerValues.decode(store.json,r.get("data"));out.put("rowId",r.get("row_id"));out.put("rowVersion",r.get("row_version"));out.put("sortOrder",r.get("sort_order"));out.put("confirmed",r.get("confirmed"));return out;}
 private record Filter(String sql,List<Object> args){}
 static String styleIdentitySql(String alias){return "coalesce('CP:'||nullif(nullif("+alias+".data->>'catalogStyleId',''),'0'),'row:'||"+alias+".row_id::text)";}
 static String activeSelectedSql(String alias){return "coalesce("+alias+".data->>'discarded','false')<>'true' and ("+alias+".confirmed or "+alias+".data->>'selected'='true')";}
 static String receivedQuantitySql(String alias){
  return SampleReceiptCorrections.effectiveSql(alias);
 }
 Map<Long,Map<String,Object>> summaries(List<Long> ids){
  if(ids.isEmpty())return Map.of();
  String marks=String.join(",",Collections.nCopies(ids.size(),"?"));
  String sql="""
   with selected as (
    select s.record_id,s.row_id,%s as style_key from plm_partner_selection_row s
    where s.record_id in (%s) and %s
   ), received as (
    select r.data->>'selectionId' as selection_id,r.data->>'selectionRowId' as row_id,%s as qty
    from plm_partner_record r where r.kind='sample' and r.deleted_flag=0
      and r.data->>'selectionId' in (%s) and (%s) and %s
   ) select s.record_id,count(distinct s.style_key) as "selectedStyleCount",
     count(distinct s.style_key) filter(where coalesce(received.qty,0)>0) as "receivedStyleCount",
     coalesce(sum(received.qty),0)::bigint as "receivedQuantity"
   from selected s left join received on received.selection_id=s.record_id::text and received.row_id=s.row_id::text
   group by s.record_id
   """.formatted(styleIdentitySql("s"),marks,activeSelectedSql("s"),receivedQuantitySql("r"),marks,store.access.visibleSqlClause("partner","r.record_id"),SampleReceiptCorrections.activeSql("r"));
  var args=new ArrayList<Object>(ids);args.addAll(ids.stream().map(String::valueOf).toList());
  var result=new HashMap<Long,Map<String,Object>>();
  for(var row:store.jdbc.queryForList(sql,args.toArray())){long id=number(row.remove("record_id"));result.put(id,row);}
  return result;
 }
 private Filter filter(long id,Map<String,String> q){
  var args=new ArrayList<Object>();args.add(id);String sql="record_id=?";
  String k=text(q.get("keyword"));if(!k.isEmpty()){String style="(select current_binding.style_id::text from plm_partner_catalog_style_source current_binding where current_binding.source_kind='selection' and current_binding.record_id=plm_partner_selection_row.record_id and current_binding.row_id=plm_partner_selection_row.row_id)";sql+=" and (data->>'name' ilike ? or data->>'supplierItemCode' ilike ? or data->>'modelText' ilike ? or data->>'color' ilike ? or data->>'productCode' ilike ? or "+new PartnerCatalogStyles(store).currentNameSearch(style)+")";for(int i=0;i<6;i++)args.add("%"+k+"%");}
  for(String key:List.of("supplierCode","quoteId"))if(!text(q.get(key)).isEmpty()){sql+=" and data->>'"+key+"'=?";args.add(q.get(key));}
  if(text(q.get("supplierMissing")).equals("true"))sql+=" and coalesce(data->>'supplierCode','')=''";
  for(String key:List.of("selected","discarded"))if(Set.of("true","false").contains(text(q.get(key)))){sql+=" and coalesce(data->>'"+key+"','false')=?";args.add(q.get(key));}
  if(Set.of("true","false").contains(text(q.get("confirmed")))){sql+=" and confirmed=?";args.add(Boolean.parseBoolean(q.get("confirmed")));}
  return new Filter(sql,args);
 }
 Map<String,Object> page(long id,Map<String,String> q){
  var f=filter(id,q);long page=Math.max(1,number(q.getOrDefault("page","1"))),size=Math.max(1,Math.min(100,number(q.getOrDefault("size","30"))));
  long total=store.jdbc.queryForObject("select count(*) from plm_partner_selection_row where "+f.sql(),Long.class,f.args().toArray());
  var args=new ArrayList<>(f.args());args.add(size);args.add(Math.multiplyExact(page-1,size));
  var rows=store.jdbc.queryForList("select row_id::text,row_version,sort_order,data::text,confirmed from plm_partner_selection_row where "+f.sql()+" order by sort_order,row_id limit ? offset ?",args.toArray());
  return Map.of("content",rows.stream().map(this::decode).toList(),"totalElements",total,"page",page,"size",size,"totalPages",(total+size-1)/size);
 }
 void scan(long id,Map<String,String> q,Consumer<Map<String,Object>> sink){
  var f=filter(id,q);
  store.jdbc.query(connection->{PreparedStatement ps=connection.prepareStatement("select row_id::text,row_version,sort_order,data::text,confirmed from plm_partner_selection_row where "+f.sql()+" order by sort_order,row_id");ps.setFetchSize(100);for(int i=0;i<f.args().size();i++)ps.setObject(i+1,f.args().get(i));return ps;},rs->{var r=new LinkedHashMap<String,Object>();r.put("row_id",rs.getString(1));r.put("row_version",rs.getLong(2));r.put("sort_order",rs.getLong(3));r.put("data",rs.getString(4));r.put("confirmed",rs.getBoolean(5));sink.accept(decode(r));});
 }
 void scanBatches(long id,Map<String,String> q,Consumer<List<Map<String,Object>>> sink){
  var batch=new ArrayList<Map<String,Object>>(100);
  scan(id,q,row->{batch.add(row);if(batch.size()==100){sink.accept(new ArrayList<>(batch));batch.clear();}});
  if(!batch.isEmpty())sink.accept(batch);
 }
 void put(long id,Map<String,Object> next,Map<String,Object> previous,String request){
  String rid=required(next.get("rowId"),"选品行标识");var data=new LinkedHashMap<>(next);data.remove("rowVersion");data.remove("sortOrder");data.remove("sampleBatches");
  long version=previous.isEmpty()?1:number(previous.get("rowVersion"))+1;
  if(previous.isEmpty()){
   require(!Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_selection_row where row_id=?::uuid)",Boolean.class,rid)),"行标识已属于其他记录，请复制为新行");
   store.jdbc.update("insert into plm_partner_selection_row(row_id,record_id,sort_order,data,confirmed,updated_by) values(?::uuid,?,(select coalesce(max(sort_order),0)+1 from plm_partner_selection_row where record_id=?),?::jsonb,?,?)",rid,id,id,encode(store.json,data),PartnerSelectionService.confirmed(next),store.access.actor().username());
  }else store.jdbc.update("update plm_partner_selection_row set row_version=?,data=?::jsonb,confirmed=?,updated_at=now(),updated_by=? where record_id=? and row_id=?::uuid",version,encode(store.json,data),PartnerSelectionService.confirmed(next),store.access.actor().username(),id,rid);
  store.jdbc.update("insert into plm_partner_selection_row_revision(record_id,row_id,row_version,request_id,data,created_by) values(?,?::uuid,?,?,?::jsonb,?)",id,rid,version,request,encode(store.json,data),store.access.actor().username());
 }
 Map<String,Object> counts(long id){return store.jdbc.queryForMap("select count(*) as \"rowCount\",count(*) filter(where confirmed) as \"confirmedCount\",count(*) filter(where confirmed or coalesce(data->>'discarded','false')='true') as \"finishedCount\" from plm_partner_selection_row where record_id=?",id);}
}

package com.yuewei.plm.module.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.operationlog.service.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static com.yuewei.plm.module.partner.PartnerValues.*;

@Component @RequiredArgsConstructor
public class PartnerStore {
 final JdbcTemplate jdbc; final ObjectMapper json; final PilotAccessService access; final OperationLogService audit;
 public Map<String,Object> load(long id,String kind,boolean lock){
  return load(id,kind,lock,false);
 }
 Map<String,Object> load(long id,String kind,boolean lock,boolean includeDeleted){
  access.requireDomesticView();access.requirePermission("partner:view");if(!includeDeleted)access.requirePartner(id);
  // A deletion retry may read its tombstone, but must retain the same object visibility rules.
  var rows=jdbc.queryForList("select record_id,kind,code,name,status,version,data::text,site,external_id,customer_id,source_project_id,supplier_code,updated_at,deleted_flag from plm_partner_record r where r.record_id=? and r.kind=?"+(includeDeleted?" and ("+access.visibleSqlClause("partner","r.record_id")+")":" and r.deleted_flag=0")+(lock?" for update":""),id,kind);
  require(!rows.isEmpty(),"记录不存在或类型不符");var row=rows.get(0);var d=decode(json,row.get("data"));if(text(d.get("supplierCode")).isEmpty()&&!text(row.get("supplier_code")).isEmpty())d.put("supplierCode",text(row.get("supplier_code")));
  d.put("id",id);d.put("code",row.get("code"));d.put("kind",kind);d.put("name",row.get("name"));d.put("status",row.get("status"));d.put("version",row.get("version"));d.put("updatedAt",row.get("updated_at").toString());if(includeDeleted)d.put("deleted",number(row.get("deleted_flag"))!=0);return d;
 }
 public Long created(String kind,String key){
  required(key,"提交标识");require(key.length()<=80,"提交标识过长");jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))",access.actorId()+":"+kind+":"+key);
  var rows=jdbc.queryForList("select object_id from plm_partner_create_request where actor_id=? and kind=? and request_id=?",access.actorId(),kind,key);return rows.isEmpty()?null:number(rows.get(0).get("object_id"));
 }
 public void remember(String kind,String key,long id){jdbc.update("insert into plm_partner_create_request(actor_id,kind,request_id,object_id) values(?,?,?,?)",access.actorId(),kind,key,id);}
 public long create(String kind,String name){
  String codePrefix=prefix(kind);
  access.requireDomesticView();access.requirePermission(Set.of("sample","sample_identity","sample_shipment").contains(kind)?"samples:write":kind.equals("listing")?"partner:listing":"partner:write");
  return insert(kind,name,codePrefix);
 }
 /** Only the validated catalogue listing action may create an internal business archive for sales. */
 long createListingProduct(String name){access.requireDomesticView();access.requirePermission("partner:view");access.requirePermission("partner:listing");return insert("product",name,prefix("product"));}
 private long insert(String kind,String name,String codePrefix){
  long id=jdbc.queryForObject("insert into plm_partner_record(kind,code,name,created_by,updated_by,owner_user_id) values(?,? ,?,?,?,?) returning record_id",Long.class,kind,UUID.randomUUID().toString(),name,access.actor().username(),access.actor().username(),access.actorId());
  jdbc.update("update plm_partner_record set code=? where record_id=?",codePrefix+"-"+String.format(Locale.ROOT,"%06d",id),id);
  if(access.isPilot())jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) values(?,'partner',?) on conflict do nothing",access.actorId(),id);
  return id;
 }
 static String prefix(String kind){return switch(kind){case "product"->"PP";case "sample"->"SP";case "sample_identity"->"SY";case "sample_shipment"->"SJ";case "sale"->"XS";case "supplier_quote"->"SQ";case "sample_fee"->"SF";case "inspection"->"QC";case "fulfilment"->"WF";case "listing"->"LS";case "selection"->"SL";default->throw invalid("业务记录类型无效");};}
 public void check(Map<String,Object> current,Long expected){require(expected!=null&&number(current.get("version"))==expected,"记录已变化，请重新载入后核对；本次输入未覆盖服务器");}
 public boolean repeated(long id,String key){return !text(key).isEmpty()&&Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_partner_event where record_id=? and request_id=?)",Boolean.class,id,key));}
 public void save(long id,Map<String,Object> data){
  jdbc.update("update plm_partner_record set name=?,status=?,data=?::jsonb,version=version+1,site=?,external_id=?,customer_id=?,source_project_id=?,supplier_code=?,parent_record_id=?,updated_at=now(),updated_by=? where record_id=?",
   required(data.get("name"),"名称"),text(data.getOrDefault("status","draft")),encode(json,data),text(data.get("site")),text(data.get("externalId")),
   nullableId(data.get("customerId")),nullableId(data.get("projectId")),text(data.get("supplierCode")).isEmpty()?null:text(data.get("supplierCode")),nullableId(data.get("parentId")),access.actor().username(),id);
 }
 private Long nullableId(Object value){long n=number(value);return n>0?n:null;}
 public void event(long id,String action,Map<String,Object> data,String key){
  require(!text(key).isEmpty()&&key.length()<=80,"提交标识缺失，请重新打开办理窗口");
  jdbc.update("insert into plm_partner_event(record_id,action,data,request_id,created_by,actor_id) values(?,?,?::jsonb,?,?,?)",id,action,encode(json,data),key,access.actor().displayName(),access.actorId());
  audit.logRequired(OperationLogCreateCommand.builder().action("PARTNER_"+action.toUpperCase(Locale.ROOT)).businessType("PARTNER").businessId(Long.toString(id)).detailJson(encode(json,data)).build(),access.actor());
 }
 public List<Map<String,Object>> events(long id){
  var rows=jdbc.queryForList("select event_id as id,action,data::text,created_at as time,created_by as operator from plm_partner_event where record_id=? order by event_id",id);
  for(var row:rows)row.put("data",decode(json,row.get("data")));return rows;
 }
 public void revision(long id,Map<String,Object> data){jdbc.update("insert into plm_partner_revision(record_id,revision,data,created_by) select record_id,version+1,?::jsonb,? from plm_partner_record where record_id=?",encode(json,data),access.actor().displayName(),id);}
 public List<Map<String,Object>> revisions(long id){var r=jdbc.queryForList("select revision_id as id,revision,data::text,created_at as time,created_by as operator from plm_partner_revision where record_id=? order by revision desc",id);for(var x:r)x.put("data",decode(json,x.get("data")));return r;}
 public void grantFile(long id,long file){jdbc.update("insert into plm_partner_file(record_id,attachment_id) values(?,?) on conflict do nothing",id,file);}
 public List<Map<String,Object>> files(long id){return jdbc.queryForList("select a.attachment_id as id,a.file_name as name,a.version_no as version,a.content_type as type,a.file_size as size from plm_partner_file f join plm_attachment a on a.attachment_id=f.attachment_id where f.record_id=? and a.deleted_flag=0 and "+access.visibleSqlClause("file","a.attachment_id")+" order by a.attachment_id",id);}
 public List<Map<String,Object>> canonicalFiles(long id,Object references){
  return canonicalFiles(id,references,true);
 }
 List<Map<String,Object>> canonicalFiles(long id,Object references,boolean lock){
  requireFiles(id,references,lock);List<Map<String,Object>> out=new ArrayList<>();Set<Long> seen=new HashSet<>();
  for(var f:maps(references)){long fid=number(f.get("id"));if(!seen.add(fid))continue;var actual=jdbc.queryForMap("select attachment_id as id,'file' as type,file_name as name,version_no as version from plm_attachment where attachment_id=? and deleted_flag=0",fid);out.add(actual);}
  return out;
 }
 public List<Map<String,Object>> editableFiles(long id,Object references,Object previous){
  var out=new ArrayList<>(canonicalFiles(id,references));
  if(!access.hasPermission("finance:read"))for(var f:maps(previous))if(financialFile(number(f.get("id"))))out.add(f);
  return out;
 }
 public boolean financialFile(long id){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_partner_file where attachment_id=? and purpose='financial')",Boolean.class,id));}
 public void requireFiles(long id,Object references){requireFiles(id,references,true);}
 private void requireFiles(long id,Object references,boolean lock){for(var f:maps(references)){long fid=number(f.get("id"));require(fid>0,"资料引用无效");access.requireFile(fid);if(lock)jdbc.queryForList("select attachment_id from plm_attachment where attachment_id=? for update",fid);require(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_partner_file where record_id=? and attachment_id=?)",Boolean.class,id,fid)),"资料不属于当前业务记录");}}
}

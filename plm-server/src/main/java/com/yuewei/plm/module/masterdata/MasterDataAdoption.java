package com.yuewei.plm.module.masterdata;

import com.yuewei.plm.common.exception.BusinessException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared new-use boundary. Call inside the business writer's transaction so FOR SHARE
 * serializes adoption with the existing master lifecycle FOR UPDATE lock. */
public final class MasterDataAdoption {
 private MasterDataAdoption() {}
 public static void requireInventory(JdbcTemplate jdbc,Long id,String code,Long previousId,String previousCode){
  String normalized=clean(code);
  if(id==null&&normalized==null)return;
  var rows=id!=null?jdbc.queryForList("select inventory_id,inventory_code,lifecycle_status,deleted_flag from plm_inventory where inventory_id=? for share",id)
   :jdbc.queryForList("select inventory_id,inventory_code,lifecycle_status,deleted_flag from plm_inventory where inventory_code=? for share",normalized);
  if(rows.isEmpty()){
   if(id!=null)throw invalid("所选物料不存在，请重新选择");
   return; // Existing free-text ERP snapshots retain their own identity; do not invent a master.
  }
  if(rows.size()!=1)throw invalid("物料身份存在冲突，请先核对原编码");
  var row=rows.get(0);
  if(((Number)row.get("deleted_flag")).intValue()!=0)throw invalid("物料已删除，不能重新采用");
  boolean unchanged=id!=null?Objects.equals(id,previousId):previousId==null&&Objects.equals(normalized,clean(previousCode));
  if(!unchanged&&!"active".equals(row.get("lifecycle_status")))throw invalid("物料已停用，不能用于新的业务关联");
 }
 public static void requireCode(JdbcTemplate jdbc,String type,String code){
  if(clean(code)==null)return;
  var rows=jdbc.queryForList("select status,deleted_flag from plm_code_item where code_type=? and code_value=? for share",type,clean(code));
  if(rows.size()!=1||((Number)rows.get(0).get("deleted_flag")).intValue()!=0||!"enabled".equals(rows.get(0).get("status")))
   throw invalid("所选"+("color".equals(type)?"颜色":"手机型号")+"编码不存在或已停用");
 }
 /** A mapped source may never recreate a retired identity or reactivate it implicitly. */
 static void requireSourceObject(JdbcTemplate jdbc,String table,String key,long id){
  if(!Set.of("plm_product","plm_inventory","plm_code_item","plm_phone_brand","plm_phone_model_member").contains(table)
   ||!Set.of("product_id","inventory_id","code_item_id","id").contains(key))throw new IllegalArgumentException("source object");
  var rows=jdbc.queryForList("select to_jsonb(o)::text as data from "+table+" o where "+key+"=? for share",id);
  if(rows.size()!=1)throw invalid("原来源对象不存在，请人工核对，不能自动重建");
  try{
   var data=new com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.get(0).get("data").toString());
   if(data.path("deleted_flag").asInt()!=0)throw invalid("原来源档案已删除，不能由同步重新创建");
   String state=table.equals("plm_product")?data.path("master_data_status").asText("active"):
    table.equals("plm_inventory")?data.path("lifecycle_status").asText("active"):data.path("status").asText("enabled");
   if(Set.of("disabled","inactive").contains(state))throw invalid("原来源档案已停用，请先人工核对，本次导入未写入");
  }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid stored master",e);}
 }
 private static String clean(String value){return value==null||value.isBlank()?null:value.trim();}
 private static BusinessException invalid(String message){return new BusinessException(40001,message);}
}

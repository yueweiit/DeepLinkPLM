package com.yuewei.plm.module.partner;

import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** One ownership/cost boundary for durable selection and supplier-source files. */
@Component @RequiredArgsConstructor
public class PartnerFileSourceAccess {
 private final PartnerStore store;
 private final PartnerOperationsAccess access;
 public Map<String,Object> read(long id,boolean lock){
  access.engineer();store.access.requirePartner(id);
  var rows=store.jdbc.queryForList("select kind from plm_partner_record where record_id=? and deleted_flag=0",id);
  require(!rows.isEmpty(),"资料不存在或无权查看");String kind=text(rows.get(0).get("kind"));
  require(Set.of("selection","supplier_quote").contains(kind),"文件任务仅支持选品资料或供应商目录来源");
  if(kind.equals("supplier_quote"))access.view(kind);
  return store.load(id,kind,lock);
 }
 public Map<String,Object> write(long id,boolean lock){
  var source=read(id,lock);
  if(text(source.get("kind")).equals("selection")){PartnerSelectionService.requireSelectionPurpose(source);require("my_selection".equals(text(source.get("purpose"))),"这份旧资料用途尚未明确，仅保留只读原件；请从供应商目录选择本次需要的款式及规格");}
  else require(text(source.get("status")).equals("draft"),"已确认报价冻结，请建立后续报价版本");
  return source;
 }
}

package com.yuewei.plm.module.partner;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class PartnerVisibility {
 private final com.yuewei.plm.module.pilot.PilotAccessService access;
 private final org.springframework.jdbc.core.JdbcTemplate jdbc;
 private static final Set<String> MONEY_KEYS=Set.of("priceCents","unitPrice","amountCents","offsetCents","grossCents","feeCents","payRemainingCents","feeRemainingCents","paidCents","feeReceivedCents","totalCents","agreements","terms","paymentTerm","quoteNote","单价","参考单价");
 private static final Set<String> MONEY_ACTIONS=Set.of("receipt","refund","supplierpay","supplierrefund","feereceipt","feerefund","offsetreverse","agreement","quote_confirm");
 public Object redact(Object value){
  if(access.hasPermission("finance:read"))return value;
  if(value instanceof Map<?,?> map){Map<String,Object> out=new LinkedHashMap<>();for(var e:map.entrySet()){String key=e.getKey().toString();if(!MONEY_KEYS.contains(key)&&!key.endsWith("Cents"))out.put(key,redact(Set.of("details","nodeFacts").contains(key)?com.yuewei.plm.module.project.development.FinancialSnapshotRedaction.withoutMoney(e.getValue()):e.getValue()));}return out;}
  if(value instanceof List<?> list)return list.stream().filter(x->!privateEntry(x)).map(this::redact).toList();
  return value;
 }
 private boolean privateEntry(Object value){
  if(!(value instanceof Map<?,?> m))return false;
  if(MONEY_ACTIONS.contains(Objects.toString(m.get("action"),"")))return true;
  if("financial".equals(m.get("purpose"))||"参考单价".equals(m.get("field")))return true;
  if("file_add".equals(m.get("action")) && m.get("data") instanceof Map<?,?> d && "financial".equals(d.get("purpose")))return true;
  Object raw=m.get("fileId");
  if(raw==null && ("file".equals(m.get("type")) || (m.containsKey("name")&&m.containsKey("version")&&!m.containsKey("kind")&&!m.containsKey("code"))))raw=m.get("id");
  long id=PartnerValues.number(raw);
  if(id>0&&!access.canMaintainEngineeringData()&&Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_partner_file where attachment_id=? and purpose='cooperation_cost')",Boolean.class,id)))return true;
  return id>0 && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_partner_file where attachment_id=? and purpose='financial')",Boolean.class,id));
 }
}

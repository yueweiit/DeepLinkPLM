package com.yuewei.plm.module.partner;

import java.util.*;
import java.util.regex.Pattern;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Readable supplier code over the existing SY identity, never a second sequence or stock identity. */
final class SampleCodes {
 private static final Pattern READABLE=Pattern.compile("SY-([A-Z0-9][A-Z0-9-]*)-([0-9]{6,})");
 private SampleCodes() {}

 static String display(Object original,Object supplierShortCode){
  String code=text(original),supplier=text(supplierShortCode).toUpperCase(Locale.ROOT);
  return supplier.isEmpty()||!code.startsWith("SY-")?code:"SY-"+supplier+"-"+code.substring(3);
 }

 static void project(Map<String,Object> identity,Object supplierShortCode){
  identity.put("originalSampleCode",identity.get("code"));
  identity.put("sampleCode",display(identity.get("code"),supplierShortCode));
 }

 static List<Map<String,Object>> lookup(PartnerStore store,String submitted){
  String value=required(submitted,"样品码").toUpperCase(Locale.ROOT);var match=READABLE.matcher(value);
  boolean readable=match.matches();require(readable||value.matches("(?:SY|SP)-[0-9]{6,}"),"样品码格式无效");
  String original=readable?"SY-"+match.group(2):value;
  var args=new ArrayList<Object>();args.add(original);
  String where="r.code=? and r.deleted_flag=0 and r.kind in ('sample_identity','sample')";
  if(readable){
   String supplier="coalesce(nullif(r.supplier_code,''),r.data->>'supplierCode')";
   where+=" and (exists(select 1 from plm_supplier_profile p where p.supplier_code="+supplier+" and upper(p.short_code)=?) or exists(select 1 from plm_supplier_short_code_reservation c where c.supplier_code="+supplier+" and upper(c.short_code)=?))";
   args.add(match.group(1));args.add(match.group(1));
  }
  return store.jdbc.queryForList("select r.record_id,r.kind,r.sample_identity_id from plm_partner_record r where "+where,args.toArray());
 }
}

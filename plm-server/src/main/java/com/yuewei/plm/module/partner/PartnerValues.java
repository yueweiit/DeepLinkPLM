package com.yuewei.plm.module.partner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class PartnerValues {
 private PartnerValues(){}
 public static BusinessException invalid(String message){return new BusinessException(40001,message);}
 public static void require(boolean ok,String message){if(!ok)throw invalid(message);}
 public static String text(Object value){return Objects.toString(value,"").trim();}
 @SuppressWarnings("unchecked") public static Map<String,Object> map(Object value){return value instanceof Map<?,?>?(Map<String,Object>)value:new LinkedHashMap<>();}
 public static List<Map<String,Object>> maps(Object value){return value instanceof List<?> list?list.stream().map(PartnerValues::map).toList():List.of();}
 public static long number(Object value){try{return new BigDecimal(text(value).isEmpty()?"0":text(value)).longValueExact();}catch(Exception e){throw invalid("编号或数量须为整数");}}
 public static List<Long> phoneIds(Object value){
  if(value==null)return List.of();require(value instanceof List<?>,"适配机型须从机型库选择");var list=(List<?>)value;require(list.size()<=100,"适配机型不能超过100个");
  return list.stream().map(PartnerValues::number).peek(id->require(id>0,"机型编号无效")).distinct().sorted().toList();
 }
 public static long qty(Object value,boolean zero){long n=number(value);require(n>=(zero?0:1)&&n<=100000000,"数量须为"+(zero?"非负":"正")+"整数且不超过一亿");return n;}
 public static long cents(Object value){try{BigDecimal b=new BigDecimal(text(value));long n=b.movePointRight(2).longValueExact();require(n>=0&&n<=1000000000000L,"金额超出允许范围");return n;}catch(BusinessException e){throw e;}catch(Exception e){throw invalid("金额须为非负数，最多两位小数");}}
 public static long add(long a,long b){try{return Math.addExact(a,b);}catch(ArithmeticException e){throw invalid("金额或数量超出范围");}}
 public static long multiply(long a,long b){try{long v=Math.multiplyExact(a,b);require(v<=9000000000000000L,"金额超出范围");return v;}catch(ArithmeticException e){throw invalid("金额超出范围");}}
 public static String date(Object value){try{var d=LocalDate.parse(text(value));require(!d.isAfter(LocalDate.now()),"实际日期不能晚于今天");return d.toString();}catch(BusinessException e){throw e;}catch(Exception e){throw invalid("请填写有效实际日期");}}
 public static String currency(Object v){String c=text(v);require(Set.of("CNY","USD","MXN","EUR","HKD").contains(c),"请明确选择币种");return c;}
 public static String required(Object v,String label){String s=text(v);require(!s.isBlank(),label+"不能为空");return s;}
 public static String signature(ObjectMapper json,Object value){try{return json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value);}catch(Exception e){throw invalid("资料摘要无法编码");}}
 public static String encode(ObjectMapper json,Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw invalid("资料无法编码");}}
 public static Map<String,Object> decode(ObjectMapper json,Object v){try{return json.readValue(text(v),new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){throw invalid("资料无法读取");}}
 public static Map<String,Object> copy(ObjectMapper json,Object v){return decode(json,encode(json,v));}
}

package com.yuewei.plm.module.partner;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;
import com.yuewei.plm.module.importexport.service.TabularSheet;
class PartnerLedgerTest {
 private final List<Map<String,Object>> events=new ArrayList<>();
 private void sample(String action,Map<String,Object> facts){var input=new LinkedHashMap<>(facts);input.put("date","2026-01-01");var d=SampleLedger.prepare(SampleLedger.replay(events),"SP-1",action,input);events.add(Map.of("id",events.size()+1L,"action",action,"data",d));}
 @Test void retainedDamagedAndPartialReturnStayIndependent(){
  sample("receive",Map.of("qty",10,"location","A"));sample("retain",Map.of("qty",1,"location","A"));sample("loan",Map.of("qty",3,"location","A","person","client","due","2026-01-05"));
  assertEquals(7L,SampleLedger.replay(events).view().get("onhand"));assertEquals(6L,SampleLedger.replay(events).view().get("available"));
  sample("return",Map.of("qty",2,"loanId",3L,"location","B","damaged",1));var v=SampleLedger.replay(events).view();assertEquals(9L,v.get("onhand"));assertEquals(7L,v.get("available"));assertEquals(1L,v.get("borrowed"));
  assertThrows(RuntimeException.class,()->sample("return",Map.of("qty",2,"loanId",3L,"location","A")));assertEquals(v,SampleLedger.replay(events).view());
  assertThrows(RuntimeException.class,()->sample("gift",Map.of("qty",7,"location","A","reason","recipient")));
 }
 @Test void correctionCannotEraseDependentLoanOrMoveHistory(){
  sample("receive",Map.of("qty",10,"location","A"));sample("loan",Map.of("qty",3,"location","A","person","client","due","2026-01-05"));sample("return",Map.of("qty",2,"loanId",2L,"location","A"));
  assertThrows(RuntimeException.class,()->sample("undo",Map.of("targetEventId",2L,"reason","wrong loan")));
  sample("undo",Map.of("targetEventId",3L,"reason","wrong return"));sample("undo",Map.of("targetEventId",2L,"reason","wrong loan"));assertEquals(10L,SampleLedger.replay(events).view().get("available"));
  assertThrows(RuntimeException.class,()->sample("undo",Map.of("targetEventId",2L,"reason","twice")));
 }
 @Test void settlementReversalsRestoreOnlyOriginalBalances(){
  var s=Map.<String,Object>of("lines",List.of(Map.of("id","L","qty",100,"priceCents",1000,"supplierCode","S")),"agreements",List.of(Map.of("supplierCode","S","grossCents",100000,"feeCents",10000,"mode","net")));
  var e=new ArrayList<Map<String,Object>>();e.add(Map.of("id",1,"action","supplierpay","data",Map.of("supplierCode","S","amountCents",90000,"offsetCents",10000)));
  assertEquals(0L,SalesLedger.settlement(s,e,"S").get("payRemainingCents"));e.add(Map.of("id",2,"action","offsetreverse","data",Map.of("supplierCode","S","amountCents",2000,"originalEventId",1)));e.add(Map.of("id",3,"action","supplierrefund","data",Map.of("supplierCode","S","amountCents",3000,"originalEventId",1)));
  assertEquals(5000L,SalesLedger.settlement(s,e,"S").get("payRemainingCents"));assertEquals(2000L,SalesLedger.settlement(s,e,"S").get("feeRemainingCents"));
 }
 @Test void csvHandlesBomQuotesAndMultilineWithoutChangingFacts(){
  var rows=TabularSheet.csv("\uFEFFname,note\r\n\"C&T\",\"a, b\nquote \"\"inside\"\"\"\r\n");assertEquals(List.of("C&T","a, b\nquote \"inside\""),rows.get(1));assertThrows(RuntimeException.class,()->TabularSheet.csv("a\n\"unfinished"));
 }
 @Test void amountsRejectFloatingRoundingAndOverflow(){assertEquals(100001L,cents("1000.01"));assertThrows(RuntimeException.class,()->cents("0.001"));assertThrows(RuntimeException.class,()->cents("-1"));assertThrows(RuntimeException.class,()->multiply(Long.MAX_VALUE,2));}
 @Test void custodyRowsDoNotReceiveTheSameSamplesAgain(){sample("receive",Map.of("qty",3,"location","公司"));var before=SampleLedger.replay(events).view();events.add(Map.of("id",2L,"action","custody","data",Map.of("custody",List.of(Map.of("location","公司","custodian","按实际人员","qty",3)))));assertEquals(before,SampleLedger.replay(events).view());assertThrows(RuntimeException.class,()->sample("undo",Map.of("targetEventId",2,"reason","不能把保管元数据冲成实物")));}
}

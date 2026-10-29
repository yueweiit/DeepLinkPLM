package com.yuewei.plm.module.moldsales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Every write is confined to the inherited random schema on a named local QA database. */
@EnabledIf("com.yuewei.plm.module.moldsales.MoldSalesLocalFixture#databaseConfigured")
class MoldSalesArchiveLocalIntegrationTest extends MoldSalesLocalFixture {
    @TempDir Path sourceRoot;
    MoldSalesArchiveStore store(){return new MoldSalesArchiveStore(jdbc,json,audit,new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()))),access);}
    ObjectNode line(int id,String company,String date,String code,String qty,String unit){
        var r=json.createObjectNode();r.put("kind","line");r.put("id",String.format("%064x",id));r.put("recordKind","detail");r.put("company",company);r.put("businessDate",date);r.put("internalOrderId",date.replace("-","")+"00001");r.put("orderNo","EXTERNAL-REFERENCE");r.put("code",code);if(qty==null)r.putNull("quantity");else r.put("quantity",qty);if(unit==null)r.putNull("unit");else r.put("unit",unit);r.put("statuses","完成");r.put("cancellationNote",false);
        r.set("source",json.valueToTree(Map.of("file","original.xlsx","sha256","b".repeat(64),"sheet","原订单明细","row",id+1,"canonicalFingerprint","f".repeat(64),"occurrence",id,"sourceCount",1)));r.set("raw",json.valueToTree(Map.of("item_name","Historical Phone","spec","Black","memo","保留原备注","raw_date_no",date.replace("-","/")+" -1")));return r;
    }
    MoldSalesArchiveStore.ImportRequest prepared(List<? extends JsonNode> rows,boolean complete)throws Exception{
        return prepared(rows,complete,Map.of());
    }
    MoldSalesArchiveStore.ImportRequest prepared(List<? extends JsonNode> rows,boolean complete,Map<String,Object> footerOverrides)throws Exception{
        Files.createDirectories(sourceRoot.resolve("archive"));Path temporary=Files.createTempFile(sourceRoot.resolve("archive"),"qa-",".tmp");
        long detail=rows.stream().filter(r->r.path("recordKind").asText().equals("detail")).count(),master=rows.size()-detail,preDetail=rows.stream().filter(r->r.path("recordKind").asText().equals("detail")&&r.path("businessDate").asText().compareTo("2025-01-01")<0).count(),preMaster=rows.stream().filter(r->r.path("recordKind").asText().equals("master")&&r.path("businessDate").asText().compareTo("2025-01-01")<0).count();
        var header=new LinkedHashMap<String,Object>();header.put("kind","header");header.put("format",1);header.put("sourceType","eccount");header.put("cutoff","2025-01-01");header.put("rows",rows.size());header.put("detailRows",detail);header.put("masterRows",master);header.put("sourceRows",rows.size());header.put("pre2025DetailRows",preDetail);header.put("pre2025MasterRows",preMaster);header.put("capturedAt","2026-10-27T00:00:00Z");header.put("sourceManifestSha256","c".repeat(64));header.put("companies",List.of("YW","Lemos","UV"));header.put("manifest",List.of(Map.of("file","original.xlsx","sha256","b".repeat(64),"bytes",123)));
        var footer=new LinkedHashMap<String,Object>();footer.put("kind","footer");footer.put("complete",true);footer.put("rows",rows.size());footer.put("detailRows",detail);footer.put("masterRows",master);footer.put("pre2025DetailRows",preDetail);footer.put("pre2025MasterRows",preMaster);footer.putAll(footerOverrides);
        try(var output=new GZIPOutputStream(Files.newOutputStream(temporary))){output.write((json.writeValueAsString(header)+"\n").getBytes(StandardCharsets.UTF_8));for(var row:rows)output.write((json.writeValueAsString(row)+"\n").getBytes(StandardCharsets.UTF_8));if(complete)output.write((json.writeValueAsString(footer)+"\n").getBytes(StandardCharsets.UTF_8));}
        var digest=MessageDigest.getInstance("SHA-256");try(var in=new DigestInputStream(Files.newInputStream(temporary),digest)){in.transferTo(java.io.OutputStream.nullOutputStream());}String sha=HexFormat.of().formatHex(digest.digest()),file="eccount-orders-"+sha+".jsonl.gz";Files.move(temporary,sourceRoot.resolve("archive").resolve(file),StandardCopyOption.REPLACE_EXISTING);return new MoldSalesArchiveStore.ImportRequest(file,sha);
    }
    Map<String,Object> imported(List<? extends JsonNode> rows)throws Exception{return store().importSource(sourceRoot.toString(),prepared(rows,true),actor,Set.of());}
    @SuppressWarnings("unchecked") Map<String,Object> breakdown(Map<String,Object> row){return (Map<String,Object>)row.get("sourceBreakdown");}
    @SuppressWarnings("unchecked") List<Map<String,Object>> records(Object value){return (List<Map<String,Object>>)value;}
    BigDecimal amount(Object value){return new BigDecimal(value.toString());}

    @Test void threeCompaniesAndRealRepeatedRowsCountWhileMasterAndOverlappingExcelOnlyStayEvidence()throws Exception{
        var raw=new ArrayList<ObjectNode>();raw.add(line(1,"YW","2024-12-31","NFA4030005701","50","Pieza"));raw.add(line(2,"YW","2024-12-31","NFA4030005701","50","Pieza"));raw.add(line(3,"Lemos","2024-12-31","NFA4030005701","70","Pieza"));raw.add(line(4,"UV","2024-12-31","NFA4030005701","80","Pieza"));
        raw.add(line(5,"YW","2024-12-31","NFA4030005701","1000","PAQ"));var suspect=line(6,"YW","2024-12-31","NFA4030005701","1000","Pieza");suspect.put("cancellationNote",true);raw.add(suspect);raw.add(line(7,"YW","2024-12-31","NFA4030005701",null,"Pieza"));raw.add(line(8,"YW","2024-12-31","NFA1020005701","1000","Pieza"));raw.add(line(9,"YW","2025-01-01","NFA4030005701","9999","Pieza"));var master=line(10,"YW","2024-12-31","NFA4030005701","99999","Pieza");master.put("recordKind","master");raw.add(master);
        var result=imported(raw);assertThat(result).containsEntry("rows",10L).containsEntry("pre2025Rows",8L).containsEntry("reviewRows",3L);assertThat(jdbc.queryForObject("select count(*) from plm_mold_sales_archive_line",Long.class)).isEqualTo(10L);
        var row=first("sales","all");assertThat(amount(row.get("salesQuantity"))).isEqualByComparingTo("350");assertThat(amount(breakdown(row).get("eccount"))).isEqualByComparingTo("250");assertThat(amount(breakdown(row).get("crm"))).isEqualByComparingTo("100");assertThat(row).containsEntry("historyReviewRows",3L);assertThat(costOf(row)).containsEntry("totalCost",new BigDecimal("2000")).containsEntry("perUnitCost",new BigDecimal("5.7143")).containsEntry("breakEvenUnits",new BigDecimal("400"));assertThat(amount(costOf(row).get("remainingUnits"))).isEqualByComparingTo("50");
        var delivery=(Map<?,?>)row.get("delivery");assertThat(amount(delivery.get("pendingQuantity"))).isEqualByComparingTo("0");assertThat(delivery.get("historyDeliveryUnknown")).isEqualTo(true);
        var detail=service.detail(101,1,100,"sales");assertThat(detail).containsEntry("totalElements",9L);assertThat(records(detail.get("orders"))).filteredOn(r->r.get("quantity")==null).singleElement().satisfies(r->assertThat(r).containsEntry("source","eccount").containsEntry("company","YW").containsEntry("unit","Pieza").containsEntry("sourceFile","original.xlsx"));
        var matrix=service.matrix(1,100,"","all",null,null,"sales");assertThat(amount(matrix.get("totalOrderQuantity"))).isEqualByComparingTo("350");assertThat(amount(breakdown(matrix).get("eccount"))).isEqualByComparingTo("250");assertThat(matrix).containsEntry("historyReviewRows",3L);verify(audit).logRequired(argThat(c->c.getAction().equals("MOLD_SALES_ARCHIVE_IMPORT")),eq(actor));
    }
    @Test void duplicateHashIsIdempotentAndAnotherFileCannotSilentlyAppendOrReplace()throws Exception{
        var request=prepared(List.of(line(1,"YW","2024-01-01","NFA4030005701","50","Pieza")),true);var s=store();s.importSource(sourceRoot.toString(),request,actor,Set.of());assertThat(s.importSource(sourceRoot.toString(),request,actor,Set.of())).containsEntry("alreadyImported",true);assertThat(jdbc.queryForObject("select count(*) from plm_mold_sales_archive_line",Long.class)).isEqualTo(1L);verify(audit,times(1)).logRequired(any(),eq(actor));
        var other=prepared(List.of(line(2,"YW","2024-01-01","NFA4030005701","60","Pieza")),true);assertThatThrownBy(()->s.importSource(sourceRoot.toString(),other,actor,Set.of())).hasMessageContaining("不同来源须先核对差异");assertThat(amount(first("sales","all").get("salesQuantity"))).isEqualByComparingTo("150");
    }
    @Test void lowerCaseAndPaddedHistoricalFinalCodesKeepTheirOriginalTextButUseTheSameSharedIdentity()throws Exception{
        jdbc.update("insert into plm_product(product_id,product_type,parent_product_id,finished_product_code) values(2,'sku',1,'NFA4020005701')");
        var raw=line(1,"Lemos","2024-01-01"," nfa4020005701 ","50","Pieza");var request=prepared(List.of(raw),true);store().importSource(sourceRoot.toString(),request,actor,Set.of("FA4020"));
        assertThat(amount(first("sales","all").get("salesQuantity"))).isEqualByComparingTo("150");assertThat(jdbc.queryForObject("select item_code from plm_mold_sales_archive_line",String.class)).isEqualTo(" nfa4020005701 ");assertThat(jdbc.queryForObject("select phone_code from plm_mold_sales_archive_line",String.class)).isEqualTo("0057");
    }
    @Test void auditFailureAndIncompleteFooterRollbackEvenPreviouslyWrittenThousandRowChunks()throws Exception{
        var rows=new ArrayList<ObjectNode>();for(int i=1;i<=1001;i++)rows.add(line(i,"YW","2024-01-01","NFA4030005701","1","Pieza"));
        var request=prepared(rows,true);doThrow(new IllegalStateException("audit unavailable")).when(audit).logRequired(any(),eq(actor));assertThatThrownBy(()->store().importSource(sourceRoot.toString(),request,actor,Set.of())).hasMessageContaining("audit unavailable");assertThat(jdbc.queryForObject("select count(*) from plm_mold_sales_archive_batch",Long.class)).isZero();assertThat(jdbc.queryForObject("select count(*) from plm_mold_sales_archive_line",Long.class)).isZero();
        reset(audit);var truncated=prepared(rows,false);assertThatThrownBy(()->store().importSource(sourceRoot.toString(),truncated,actor,Set.of())).isInstanceOf(BusinessException.class).hasMessageContaining("不完整");assertThat(jdbc.queryForObject("select count(*) from plm_mold_sales_archive_line",Long.class)).isZero();verifyNoInteractions(audit);
    }
    @Test void malformedOriginAndDuplicateSourceIdNeverPublishAFirstHalf()throws Exception{
        var invalid=line(1,"YW","2024-01-01","NFA4030005701","20","Pieza");((ObjectNode)invalid.path("source")).put("sha256","d".repeat(64));var request=prepared(List.of(invalid),true);assertThatThrownBy(()->store().importSource(sourceRoot.toString(),request,actor,Set.of())).hasMessageContaining("原件行");assertThat(jdbc.queryForObject("select count(*) from plm_mold_sales_archive_batch",Long.class)).isZero();
        var duplicate=line(2,"YW","2024-01-01","NFA4030005701","20","Pieza");var duplicates=prepared(List.of(duplicate,duplicate.deepCopy()),true);assertThatThrownBy(()->store().importSource(sourceRoot.toString(),duplicates,actor,Set.of())).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(jdbc.queryForObject("select count(*) from plm_mold_sales_archive_line",Long.class)).isZero();
    }
    @Test void aContradictoryFooterPartitionCountRollsBackWithoutClaimingCompleteness()throws Exception{
        var request=prepared(List.of(line(1,"YW","2024-01-01","NFA4030005701","20","Pieza")),true,Map.of("pre2025DetailRows",0));
        assertThatThrownBy(()->store().importSource(sourceRoot.toString(),request,actor,Set.of())).hasMessageContaining("尾部分期计数");assertThat(jdbc.queryForObject("select count(*) from plm_mold_sales_archive_batch",Long.class)).isZero();assertThat(jdbc.queryForObject("select count(*) from plm_mold_sales_archive_line",Long.class)).isZero();verifyNoInteractions(audit);
    }
    @Test void crmRefreshRetainsArchiveAndManualActualCostWithoutDoubleImport()throws Exception{
        imported(List.of(line(1,"Lemos","2024-01-01","NFA4030005701","50","Pieza")));service.updateCost(101,request(service.cost(101),amounts("1000")));Path source=sourceRoot.resolve("crm-sales-qa-refresh.jsonl.gz"),cost=sourceRoot.resolve("cost-baseline.json");Files.writeString(cost,budgetJson());String h="{\"kind\":\"header\",\"format\":1,\"organizations\":1,\"organization\":\"QA\",\"rows\":1,\"capturedAt\":\"2026-10-26T00:00:00Z\",\"periodStart\":\"2025-01-01\",\"periodEnd\":\"2026-10-26\"}",r="{\"kind\":\"line\",\"businessDate\":\"2026-10-20\",\"id\":\"qa-refresh-line\",\"orderId\":\"qa-order\",\"orderNo\":\"QA\",\"status\":\"closed\",\"code\":\"NFA4030005701\",\"quantity\":\"200\"}";
        try(var out=new GZIPOutputStream(Files.newOutputStream(source))){out.write((h+"\n"+r+"\n{\"kind\":\"footer\",\"complete\":true,\"rows\":1}\n").getBytes(StandardCharsets.UTF_8));}service.importSnapshot(source,cost,actor);service.importSnapshot(source,cost,actor);
        assertThat(jdbc.queryForObject("select count(*) from plm_mold_sales_archive_line",Long.class)).isEqualTo(1L);var row=first("sales","all");assertThat(amount(row.get("salesQuantity"))).isEqualByComparingTo("250");assertThat(amount(breakdown(row).get("eccount"))).isEqualByComparingTo("50");assertThat(amount(breakdown(row).get("crm"))).isEqualByComparingTo("200");assertThat(costOf(row)).containsEntry("source","actual").containsEntry("totalCost",new BigDecimal("1125.00")).containsEntry("perUnitCost",new BigDecimal("4.5000"));assertThat(service.cost(101)).containsEntry("version",1);
    }
    @Test void newHistoricalNamesDoNotInvalidateThePreviouslyReviewedCrmAlias()throws Exception{
        jdbc.update("insert into plm_mold_sales_line values('alias-line',(select snapshot_id from plm_mold_sales_snapshot),'alias-order','ALIAS','closed','NFA4030005801','FA','0058',20,'eligible','{\"businessDate\":\"2025-01-01\",\"name\":\"Reviewed CRM alias\"}')");
        String decisions="[{\"styleCode\":\"FA\",\"phoneCode\":\"0058\",\"decision\":\"linked\",\"targetItemId\":101,\"targetBasis\":{\"phoneCode\":\"0057\",\"modelName\":\"Phone A\"},\"sourceNames\":[\"Reviewed CRM alias\"]}]";
        jdbc.update("insert into plm_mold_sales_reconciliation(batch_id,batch_hash,source_hash,catalog_url,catalog_captured_at,catalog,decisions,created_by) values(?,?,?,'https://qa.invalid',now(),'{}',?::jsonb,'qa')",UUID.randomUUID(),"d".repeat(64),"a".repeat(64),decisions);
        imported(List.of(line(1,"Lemos","2024-01-01","NFA4030005801","50","Pieza")));assertThat(amount(first("sales","all").get("salesQuantity"))).isEqualByComparingTo("170");assertThat(((Collection<?>)first("sales","all").get("phoneCodes")).contains("0058")).isTrue();assertThat(service.unmatched("sales")).isEmpty();
    }
    @Test void unmappedExactCodesRemainVisibleAndRestrictedUsersGetNoGlobalCountsOrCosts()throws Exception{
        imported(List.of(line(1,"Lemos","2024-01-01","NFA4030999901","50","Pieza"),line(2,"YW","2024-01-01","NFA4030005701","40","Pieza")));assertThat(service.unmatched("sales")).singleElement().satisfies(r->{assertThat(r).containsEntry("phoneCode","9999");assertThat(amount(r.get("quantity"))).isEqualByComparingTo("50");});
        when(access.actor()).thenReturn(new CurrentUser(2L,"scoped","Scoped",false));financeRead=false;var page=service.page(1,50,"","all",null,null,"sales");assertThat(page).doesNotContainKeys("unassignedQuantity","ignoredQuantity");assertThat(page.toString()).doesNotContain("totalCost","manufacturingCost");assertThat(((Map<?,?>)((Map<?,?>)page.get("status")).get("archive")).keySet().stream().map(Object::toString).toList()).containsExactlyInAnyOrder("imported","importedAt");
    }
}

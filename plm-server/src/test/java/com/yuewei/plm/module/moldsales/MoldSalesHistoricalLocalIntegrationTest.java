package com.yuewei.plm.module.moldsales;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuewei.plm.common.security.CurrentUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic source rows in the existing fixture's random local schema; no production connection. */
@EnabledIf("com.yuewei.plm.module.moldsales.MoldSalesLocalFixture#databaseConfigured")
class MoldSalesHistoricalLocalIntegrationTest extends MoldSalesLocalFixture {
    UUID archive=UUID.randomUUID(),batch=UUID.randomUUID();
    @TempDir Path files;
    @BeforeEach void historicalRows()throws Exception{
        jdbc.update("insert into plm_product(product_id,product_specific_code,product_type,product_name,status)values(48,'RU','product_line','三合一UsoRudo','archived'),(51,'BA','product_line','亮甲 2.0','published')");
        jdbc.update("insert into plm_code_item values(2,'0058','Phone A / Phone B','phone_model',0)");
        jdbc.update("insert into plm_mold_code_source values(10,'MRU100058',false),(11,'MRU200058',false)");
        jdbc.update("insert into plm_collaboration_item(item_id,style_name,compatible_models,product_line_id,phone_model_code_item_id)values(151,'亮甲 2.0','Phone A / Phone B',51,2)");visible.add(151L);
        jdbc.update("insert into plm_mold_sales_archive_batch(batch_id,source_hash,source_file,captured_at,row_count,detail_rows,master_rows,pre2025_detail_rows,pre2025_master_rows,manifest,source_summary,created_by)values(?,?,'synthetic.jsonl.gz',now(),7,7,0,7,0,'[]','{}','qa')",archive,"b".repeat(64));
        String snapshot="{\"groups\":5,\"rows\":7,\"quantity\":321,\"categories\":{\"pending\":{\"groups\":4,\"rows\":6,\"quantity\":271},\"coded_history\":{\"groups\":1,\"rows\":1,\"quantity\":50},\"purchased\":{\"groups\":0,\"rows\":0,\"quantity\":0}}}";
        jdbc.update("insert into plm_mold_sales_history_batch(batch_id,policy_hash,archive_hash,report_hash,recommendation_hash,source_file,approval_text,reason,summary,created_by)values(?,?,?,?,?,'synthetic-policy.json','QA synthetic','QA',?::jsonb,'qa')",batch,"c".repeat(64),"b".repeat(64),"d".repeat(64),"e".repeat(64),snapshot);
        archiveLine(1,"RU","0058","三合一",100,"Phone A / Phone B","02");archiveLine(2,"RU","0058","三合一",25,"Phone A / Phone B","07");archiveLine(3,"RU","0059","三合一",40,"Original Phone C","02");
        archiveLine(4,"AB","0058","AirBag Pintura羽绒服喷油",60,"Phone A / Phone B","02");archiveLine(5,"AB","0058","AirBag Liso羽绒服透明",30,"Phone A / Phone B","30");archiveLine(6,"AB","0058","Rainbow Transparente亮甲透色",20,"Phone A / Phone B","31");archiveLine(7,"AB","0058","Double Diseño",46,"Phone A / Phone B","02");
        jdbc.update("insert into plm_mold_sales_line values('active-missing',(select snapshot_id from plm_mold_sales_snapshot),'CURRENT','CURRENT','closed','NFA4030999902','FA','9999',11,'eligible','{\"businessDate\":\"2026-10-20\",\"name\":\"New current phone\"}')");
        var costs=(ObjectNode)json.readTree(budgetJson());var ru=costs.withArray("rows").addObject();ru.put("styleCode","RU");ru.put("moldManufacturing","18354.00");ru.put("freight","0");ru.put("customs","0");ru.put("fuel","0");ru.put("landedTotal","18354.00");ru.put("currency","CNY");ru.put("referenceSellingPrice","0");ru.put("referenceUnitProductionCost","0");ru.put("referenceOnly",true);ru.put("sourceKind","user_confirmation");ru.putArray("unknownFields").add("freight").add("customs").add("fuel").add("referenceSellingPrice").add("referenceUnitProductionCost");
        jdbc.update("update plm_mold_sales_snapshot set costs=?::jsonb",json.writeValueAsString(costs));
    }
    void archiveLine(int id,String style,String phone,String product,int quantity,String name,String color)throws Exception{
        String source=String.format("%064x",id),code="N"+style+"4030"+phone+color;
        var data=json.createObjectNode();data.put("name",name);data.put("spec",color.equals("31")?"透明红":"原色");data.putObject("raw").put("product",product);data.putObject("source").put("file","original.xlsx").put("sheet","原订单").put("row",id);
        jdbc.update("insert into plm_mold_sales_archive_line values(?,?,'detail','UV','2024-10-30','2024103000001','ORIGINAL',?,?,?,?, 'Pieza','完成','eligible',?::jsonb)",source,archive,code,style,phone,new BigDecimal(quantity),json.writeValueAsString(data));
        jdbc.update("insert into plm_mold_sales_history_group values(?,?,?,'pending',?,1,?::jsonb)on conflict do nothing",batch,style,phone,new BigDecimal(quantity),"{\"styleName\":\""+style+"\",\"phoneName\":\""+name+"\"}");
        jdbc.update("insert into plm_mold_sales_history_line values(?,?,?,?,null,null,null)",source,batch,style,phone);
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    @SuppressWarnings("unchecked") Map<String,Object> map(Object value){return value instanceof com.fasterxml.jackson.databind.JsonNode?json.convertValue(value,Map.class):(Map<String,Object>)value;}
    BigDecimal amount(Object value){return new BigDecimal(value.toString());}
    Map<String,Object> live(){return map(map(service.status().get("historyReview")).get("summary"));}
    BigDecimal quantity(Map<String,Object> summary,String kind){return amount(map(map(summary.get("categories")).get(kind)).get("quantity"));}
    String originalArchive(){return jdbc.queryForObject("select jsonb_agg(to_jsonb(a)order by source_id)::text from plm_mold_sales_archive_line a",String.class);}

    @Test void restoresRuWithoutCreatingMoldsAndCountsSharedCombinationAndReferenceCostOnce(){
        String before=originalArchive();var page=service.page(1,50,"","all",48L,null,"sales",null,null);var groups=rows(page.get("content"));assertThat(groups).hasSize(2);assertThat(amount(page.get("totalSalesQuantity"))).isEqualByComparingTo("165");
        var combined=groups.stream().filter(g->g.get("sourceGroupKey").equals("history:RU:0058")).findFirst().orElseThrow();assertThat(combined).containsEntry("groupId",null).containsEntry("physicalSets",null).containsEntry("readonly",true).containsEntry("canWriteCost",false);
        assertThat(amount(combined.get("salesQuantity"))).isEqualByComparingTo("125");var cost=map(combined.get("cost"));assertThat(amount(cost.get("totalCost"))).isEqualByComparingTo("18354");assertThat(amount(cost.get("perUnitCost"))).isEqualByComparingTo("146.8320");assertThat(cost).containsEntry("costSets",null).containsEntry("singleSetReference",true).containsEntry("breakEvenUnits",null);assertThat(map(cost.get("basis"))).containsEntry("freight",null).containsEntry("referenceSellingPrice",null);
        var matrix=service.matrix(1,50,"","all",null,List.of(48L),"sales");assertThat(amount(matrix.get("totalSalesQuantity"))).isEqualByComparingTo("165");assertThat(rows(matrix.get("content"))).filteredOn(r->r.get("phoneCodes").toString().contains("0058")).singleElement().satisfies(r->assertThat(amount(r.get("totalSalesQuantity"))).isEqualByComparingTo("125"));
        assertThat(originalArchive()).isEqualTo(before);assertThat(jdbc.queryForObject("select count(*)from plm_collaboration_item where product_line_id=48",Long.class)).isZero();assertThat(jdbc.queryForObject("select count(*)from plm_collaboration_mold_set",Long.class)).isEqualTo(2L);assertThat(jdbc.queryForObject("select status from plm_product where product_id=48",String.class)).isEqualTo("archived");verifyNoInteractions(audit);
    }
    @Test void sourceDetailPaginatesOnlyItsExactSealedRowsAndKeepsTheExistingGroupCost(){
        var listed=rows(service.page(1,50,"","all",48L,null,"sales",null,null).get("content")).stream().filter(g->g.get("sourceGroupKey").equals("history:RU:0058")).findFirst().orElseThrow();
        var first=service.sourceDetail("history:RU:0058",1,1,"sales");var second=service.sourceDetail("history:RU:0058",2,1,"sales");
        assertThat(first).containsEntry("totalElements",2L);assertThat(second).containsEntry("totalElements",2L);assertThat(map(first.get("group")).get("cost")).isEqualTo(listed.get("cost"));assertThat(rows(first.get("orders"))).hasSize(1);assertThat(rows(second.get("orders"))).hasSize(1);assertThat(rows(first.get("orders")).get(0).get("sourceId")).isNotEqualTo(rows(second.get("orders")).get(0).get("sourceId"));
        assertThat(rows(first.get("orders"))).allSatisfy(r->assertThat(r).containsEntry("orderDate","2024-10-30").containsEntry("businessDate","2024-10-30").containsEntry("phoneCode","0058"));
        assertThat(map(first.get("status"))).doesNotContainKey("historyReview");assertThat(map(service.status().get("historyReview"))).containsKeys("initialSummary","liveSummary");
        try{archiveLine(8,"AB","0058","AirBag Pintura羽绒服喷油",7,"Unsealed copy","02");}catch(Exception e){throw new IllegalStateException(e);}jdbc.update("delete from plm_mold_sales_history_line where source_id=?",String.format("%064x",8));
        var paint=service.sourceDetail("history:AB:airbag-pintura:0058",1,20,"sales");assertThat(paint).containsEntry("totalElements",1L);assertThat(amount(map(paint.get("group")).get("salesQuantity"))).isEqualByComparingTo("60");assertThat(rows(paint.get("orders"))).singleElement().satisfies(r->assertThat(r).containsEntry("quantity",new BigDecimal("60")).containsEntry("originalProductName","AirBag Pintura羽绒服喷油"));
        verifyNoInteractions(audit);
    }
    @Test void splitsAbByExactOriginalProductAndAttributesTransparentSalesToRealBaWithoutChangingColorOrCode(){
        var all=service.page(1,100,"","all",null,null,"sales",null,null);var groups=rows(all.get("content"));assertThat(amount(all.get("totalSalesQuantity"))).isEqualByComparingTo("375");
        var ab=groups.stream().filter(g->g.get("productLineId")==null).toList();assertThat(ab).hasSize(2);assertThat(ab).extracting(g->g.get("productKey")).containsExactlyInAnyOrder("history:AB:airbag-pintura","history:AB:airbag-liso");
        var transparent=service.detail(151,1,20,"sales");assertThat(amount(map(transparent.get("group")).get("salesQuantity"))).isEqualByComparingTo("20");assertThat(rows(transparent.get("orders"))).singleElement().satisfies(r->assertThat(r).containsEntry("itemCode","NAB4030005831").containsEntry("specification","透明红").containsEntry("originalProductName","Rainbow Transparente亮甲透色").containsEntry("source","eccount"));
        var paint=ab.stream().filter(g->g.get("productKey").equals("history:AB:airbag-pintura")).findFirst().orElseThrow();var detail=service.sourceDetail(paint.get("sourceGroupKey").toString(),1,20,"sales");assertThat(rows(detail.get("orders"))).singleElement().satisfies(r->assertThat(r).containsEntry("originalProductName","AirBag Pintura羽绒服喷油").containsEntry("quantity",new BigDecimal("60")));
        var matrix=service.matrix(1,50,"","all",null,null,"sales",List.of("product:51","history:AB:airbag-pintura","history:AB:airbag-liso"));assertThat(amount(matrix.get("totalSalesQuantity"))).isEqualByComparingTo("110");assertThat(rows(service.options().get("products"))).filteredOn(p->p.get("productLineId")==null).hasSize(2);
    }
    @Test void currentPendingRecalculatesAfterRealLinkWhileUntraceableHistoricalRowsAndSnapshotRemainReadable(){
        var initial=map(map(service.status().get("historyReview")).get("initialSummary"));assertThat(quantity(initial,"pending")).isEqualByComparingTo("271");assertThat(quantity(live(),"pending")).isEqualByComparingTo("11");assertThat(quantity(live(),"historical_reference")).isEqualByComparingTo("46");
        var active=rows(service.historyReviewPage(1,30,"","pending").get("content"));assertThat(active).singleElement().satisfies(r->{assertThat(r).containsEntry("styleCode","FA").containsEntry("productKey",null).containsEntry("productLineId",null);assertThat(map(r.get("evidence"))).containsEntry("styleName","骑士");});
        var reference=service.historyReviewPage(1,30,"Double Diseño","historical_reference");assertThat(reference).containsEntry("totalElements",1);var row=rows(reference.get("content")).get(0);assertThat(amount(row.get("quantity"))).isEqualByComparingTo("46");assertThat(rows(service.historyReviewDetail("AB","0058",row.get("sourceGroupKey").toString(),1,20).get("orders"))).singleElement().satisfies(r->assertThat(r).containsEntry("originalProductName","Double Diseño"));
        jdbc.update("insert into plm_collaboration_item(item_id,style_name,compatible_models,product_line_id,phone_model_code)values(103,'骑士','New current phone',1,'9999')");visible.add(103L);
        assertThat(quantity(live(),"pending")).isZero();assertThat(service.historyReviewPage(1,30,"","pending")).containsEntry("totalElements",0);assertThat(map(map(service.status().get("historyReview")).get("initialSummary"))).isEqualTo(initial);assertThat(service.unmatched("sales")).isEmpty();
    }
    @Test void scopeAndFinancePermissionsAreAppliedBeforeReturningHistoricalGroupsOrGlobalCounts(){
        when(access.actor()).thenReturn(new CurrentUser(2L,"scoped","Scoped",false));financeRead=false;
        when(access.visibleSqlClause(anyString(),anyString())).thenAnswer(i->{String kind=i.getArgument(0),column=i.getArgument(1);return kind.equals("product")?column+"=48":kind.equals("item")?"false":"false";});
        var result=service.page(1,100,"","all",null,null,"sales",null,null);assertThat(rows(result.get("content"))).hasSize(2).allSatisfy(r->assertThat(r).containsEntry("productLineId",48L));assertThat(result).doesNotContainKeys("unassignedQuantity","ignoredQuantity");assertThat(result.toString()).doesNotContain("totalCost=","initialSummary=","AirBag");
        assertThatThrownBy(()->service.sourceDetail("history:AB:airbag-pintura:0058",1,20,"sales")).hasMessageContaining("未获授权");
    }
    @Test void confirmedTransparentAttributionIsAlsoVisibleUnderItsRealBaProductOrSingleItemPermission(){
        var administrator=service.detail(151,1,20,"sales");var expected=rows(administrator.get("orders"));assertThat(amount(map(administrator.get("group")).get("salesQuantity"))).isEqualByComparingTo("20");
        when(access.actor()).thenReturn(new CurrentUser(3L,"ba-scoped","BA scoped",false));financeRead=false;visible.clear();visible.add(151L);
        when(access.visibleSqlClause(anyString(),anyString())).thenAnswer(i->{String kind=i.getArgument(0),column=i.getArgument(1);return kind.equals("product")?column+"=51":kind.equals("item")?column+"=151":"false";});
        var productOnly=service.detail(151,1,20,"sales");assertThat(amount(map(productOnly.get("group")).get("salesQuantity"))).isEqualByComparingTo("20");assertThat(rows(productOnly.get("orders"))).isEqualTo(expected);assertThat(productOnly.toString()).doesNotContain("AirBag Pintura","totalCost=","initialSummary=");
        when(access.visibleSqlClause(anyString(),anyString())).thenAnswer(i->i.getArgument(0).equals("item")?i.getArgument(1)+"=151":"false");
        var itemOnly=service.detail(151,1,20,"sales");assertThat(amount(map(itemOnly.get("group")).get("salesQuantity"))).isEqualByComparingTo("20");assertThat(rows(itemOnly.get("orders"))).isEqualTo(expected);assertThat(rows(service.page(1,100,"","all",null,null,"sales",null,null).get("content"))).singleElement().satisfies(g->assertThat(g).containsEntry("groupId",151L));
    }
    @Test void aChangedCostBaselineRefreshesTheSameSnapshotOnceWithoutReimportingOrders()throws Exception{
        Path source=files.resolve("crm-sales-same.jsonl.gz"),cost=files.resolve("cost-baseline.json");String header="{\"kind\":\"header\",\"format\":1,\"organizations\":1,\"organization\":\"QA\",\"rows\":1,\"capturedAt\":\"2026-10-26T00:00:00Z\",\"periodStart\":\"2025-01-01\",\"periodEnd\":\"2026-10-26\"}";String line="{\"kind\":\"line\",\"businessDate\":\"2026-10-20\",\"id\":\"same-source\",\"orderId\":\"SAME\",\"orderNo\":\"SAME\",\"status\":\"closed\",\"code\":\"NFA4030005701\",\"quantity\":\"10\"}";
        try(var out=new GZIPOutputStream(Files.newOutputStream(source))){out.write((header+"\n"+line+"\n{\"kind\":\"footer\",\"complete\":true,\"rows\":1}\n").getBytes(StandardCharsets.UTF_8));}Files.writeString(cost,budgetJson());var first=service.importSnapshot(source,cost,actor);String before=jdbc.queryForObject("select jsonb_agg(to_jsonb(l))::text from plm_mold_sales_line l",String.class);var changed=(ObjectNode)json.readTree(budgetJson());((ObjectNode)changed.withArray("rows").get(0)).put("moldManufacturing","800").put("landedTotal","1100");Files.writeString(cost,json.writeValueAsString(changed));
        var updated=service.importSnapshot(source,cost,actor);assertThat(updated).containsEntry("snapshotId",first.get("snapshotId")).containsEntry("costsChanged",true);assertThat(jdbc.queryForObject("select jsonb_agg(to_jsonb(l))::text from plm_mold_sales_line l",String.class)).isEqualTo(before);assertThat(service.importSnapshot(source,cost,actor)).containsEntry("costsChanged",false);verify(audit,times(1)).logRequired(argThat(c->c.getAction().equals("MOLD_SALES_RECALCULATE")&&c.getDetailJson().contains("previousCosts")),eq(actor));
        String stored=jdbc.queryForObject("select costs::text from plm_mold_sales_snapshot where snapshot_id=?",String.class,first.get("snapshotId"));((ObjectNode)changed.withArray("rows").get(0)).put("moldManufacturing","900").put("landedTotal","1200");Files.writeString(cost,json.writeValueAsString(changed));doThrow(new IllegalStateException("audit unavailable")).when(audit).logRequired(any(),eq(actor));assertThatThrownBy(()->service.importSnapshot(source,cost,actor)).hasMessageContaining("audit unavailable");assertThat(jdbc.queryForObject("select costs::text from plm_mold_sales_snapshot where snapshot_id=?",String.class,first.get("snapshotId"))).isEqualTo(stored);
    }
}

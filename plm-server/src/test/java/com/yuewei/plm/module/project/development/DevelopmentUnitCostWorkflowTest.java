package com.yuewei.plm.module.project.development;

import com.yuewei.plm.module.bom.service.BomCostCalculator;
import com.yuewei.plm.module.bom.service.ProductBomWorkflowService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DevelopmentUnitCostWorkflowTest {
    private Map<String,Object> imported(String currency,String output) {
        return imported(currency,output,Map.of());
    }
    private Map<String,Object> imported(String currency,String output,Map<String,Object> previous) {
        var jdbc=mock(JdbcTemplate.class);var service=mock(ProductDevelopmentService.class);
        var reference=Map.<String,Object>of("type","bom","id",10L,"versionNo","MES-1","content",Map.of("header",Map.of("quantity",output)));
        when(service.snapshotReferences(eq(20L),anyList())).thenReturn(List.of(reference));
        when(jdbc.queryForList(startsWith("select product_bom_route_id,process_id"),eq(10L),eq(31L))).thenReturn(List.of(Map.of("product_bom_route_id",31L,"process_id",41L)));
        var header=new LinkedHashMap<String,Object>();header.put("quantity",new BigDecimal(output));header.put("currency_code",currency);
        when(jdbc.queryForMap(startsWith("select quantity,currency_code"),eq(10L))).thenReturn(header);
        var line=new LinkedHashMap<String,Object>();line.put("product_bom_item_id",100L);line.put("item_name","原物料");line.put("quantity",new BigDecimal("0.00000001"));line.put("unit_cost_snapshot",null);line.put("loss_rate",null);
        when(jdbc.queryForList(startsWith("select product_bom_item_id,line_no"),eq(31L))).thenReturn(List.of(line));
        var workflow=new DevelopmentUnitCostWorkflow(mock(PilotAccessService.class),new UnitCostSheetCalculator(new BomCostCalculator()),mock(ProductBomWorkflowService.class),jdbc,mock(DevelopmentWorkflowStore.class),mock(com.yuewei.plm.module.development.DevelopmentFinanceService.class));
        var state=new LinkedHashMap<String,Object>();if(!previous.isEmpty())state.put("unitCostDraft",previous);
        workflow.action(20L,state,"unit_cost_from_bom",new LinkedHashMap<>(Map.of("bomId",10L,"routeId",31L,"replaceDraft",true)),service);
        var draft=DevelopmentWorkflowStore.map(state.get("unitCostDraft"));assertThat(draft.get("references")).isEqualTo(List.of(reference));return draft;
    }
    @Test void importedBatchUsageAndKnownCurrencyRetainEvidenceWithoutInventingPerUnitUsage(){
        var draft=imported("USD","10.0000");assertThat(draft.get("currency")).isEqualTo("USD");
        var material=LaunchReviewWorkflow.records(draft.get("rows")).get(0);assertThat(material.get("quantity")).isNull();assertThat(material.get("sourceQuantity")).isEqualTo(new BigDecimal("0.00000001"));assertThat(material.get("sourceOutputQty")).isEqualTo(new BigDecimal("10.0000"));assertThat(material.get("warning").toString()).contains("单件用量");assertThat(draft.get("complete")).isEqualTo(false);
    }
    @Test void importedSingleUnitPreservesExactUsageWhileUnknownPriceLossAndCurrencyStayMissing(){
        var draft=imported(null,"1.0000");assertThat(draft.get("currency")).isEqualTo("");
        var material=LaunchReviewWorkflow.records(draft.get("rows")).get(0);assertThat(material.get("quantity")).isEqualTo(new BigDecimal("0.00000001"));assertThat(material.get("unitPrice")).isNull();assertThat(material.get("lossPct")).isNull();assertThat(draft.get("complete")).isEqualTo(false);assertThat(draft.get("missing").toString()).contains("币种待确认");
    }
    @Test void rebuildingFromBomKeepsColumnDefinitionsButDoesNotGuessWhichOldCellBelongsToANewBomLine(){
        var columns=List.of(Map.of("id","supplier","label","供方","type","text"),Map.of("id","weight","label","重量","type","number","hidden",true));
        var previous=Map.<String,Object>of("extraColumns",columns,"visibleOptionalColumns",List.of("taxRate","remark"),"rows",List.of(Map.of("id","bom-100","extraValues",Map.of("supplier","旧行资料"))));
        var draft=imported("CNY","1",previous);
        assertThat(draft).containsEntry("extraColumns",columns).containsEntry("visibleOptionalColumns",List.of("taxRate","remark"));
        assertThat(LaunchReviewWorkflow.records(draft.get("rows"))).allSatisfy(row->assertThat(row).doesNotContainKey("extraValues"));
        assertThat(LaunchReviewWorkflow.records(previous.get("rows")).get(0)).containsEntry("extraValues",Map.of("supplier","旧行资料"));
    }
    @Test void savingExtraCellsRoundTripsThroughTheExistingStoreWithoutChangingCostCalculation(){
        var access=mock(PilotAccessService.class);var actor=mock(com.yuewei.plm.common.security.CurrentUser.class);when(actor.displayName()).thenReturn("工程岗位");when(access.actor()).thenReturn(actor);
        var service=mock(ProductDevelopmentService.class);when(service.snapshotReferences(anyLong(),anyList())).thenReturn(List.of());
        var store=new DevelopmentWorkflowStore(mock(JdbcTemplate.class),new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        var workflow=new DevelopmentUnitCostWorkflow(access,new UnitCostSheetCalculator(new BomCostCalculator()),mock(ProductBomWorkflowService.class),mock(JdbcTemplate.class),store,mock(com.yuewei.plm.module.development.DevelopmentFinanceService.class));
        var row=Map.<String,Object>of("id","r1","category","material","name","材料","mode","unit","unitPrice",2,"extraValues",Map.of("supplier","合成供应商","weight",0));
        var input=Map.<String,Object>of("title","合成成本","currency","CNY","taxBasis","source","rows",List.of(row),"extraColumns",List.of(Map.of("id","supplier","label","供方","type","text"),Map.of("id","weight","label","重量","type","number")),"visibleOptionalColumns",List.of("remark"));
        var state=new LinkedHashMap<String,Object>();workflow.action(20L,state,"unit_cost_save",new LinkedHashMap<>(input),service);
        var restored=DevelopmentWorkflowStore.map(store.read(store.encode(state)));
        var draft=DevelopmentWorkflowStore.map(restored.get("unitCostDraft"));
        assertThat(draft).containsEntry("extraColumns",input.get("extraColumns")).containsEntry("visibleOptionalColumns",List.of("remark"));
        var saved=LaunchReviewWorkflow.records(draft.get("rows")).get(0);assertThat(saved).containsEntry("extraValues",row.get("extraValues")).containsEntry("unitCost",2);
        verify(access).requireFinanceWrite();
    }
    @Test void purchasedFinishedProductUsesExistingQuoteHistoryWithoutInventingAManufacturingBom(){
        var access=mock(PilotAccessService.class);var actor=mock(com.yuewei.plm.common.security.CurrentUser.class);when(actor.displayName()).thenReturn("成本工程师");when(access.actor()).thenReturn(actor);
        var bomWriter=mock(ProductBomWorkflowService.class);var service=mock(ProductDevelopmentService.class);
        when(service.snapshotReferences(anyLong(),anyList())).thenReturn(List.of());
        when(service.eventData(20L,7L,"quote")).thenReturn(Map.of("supplierCode","S1","supplierName","供应商","amount",12,"currency","CNY","unit","每件"));
        var workflow=new DevelopmentUnitCostWorkflow(access,new UnitCostSheetCalculator(new BomCostCalculator()),bomWriter,mock(JdbcTemplate.class),mock(DevelopmentWorkflowStore.class),mock(com.yuewei.plm.module.development.DevelopmentFinanceService.class));
        var rows=new ArrayList<Map<String,Object>>();for(String group:UnitCostSheetCalculator.BASE_GROUPS)rows.add(Map.of("id",group,"category",group,"name",group,"mode","unit","unitPrice",group.equals("material")?12:0,"taxIncluded",false,"taxRate",0));
        var data=new HashMap<String,Object>(Map.of("title","供方成品单件核算","currency","CNY","taxBasis","net","basisType","supplier_quote","quoteEventId",7L,"quoteUnitNote","每件未税，无币种及数量换算","reason","本次已核对供方报价","rows",rows));
        data.put("extraColumns",List.of(Map.of("id","supplier","label","供方","type","text")));
        data.put("visibleOptionalColumns",List.of("taxRate"));
        var state=new HashMap<String,Object>(Map.of("plan",Map.of("method","direct","needsMold",false)));
        workflow.action(20L,state,"unit_cost_confirm",data,service);
        var version=LaunchReviewWorkflow.records(state.get("unitCostVersions")).get(0);assertThat(version).containsEntry("basisType","supplier_quote").containsKey("adoptedQuote").doesNotContainKeys("bomId","routeId","bomCostSnapshotId");assertThat(version).containsEntry("extraColumns",data.get("extraColumns")).containsEntry("visibleOptionalColumns",List.of("taxRate"));verifyNoInteractions(bomWriter);
        state.put("plan",Map.of("method","own_mold","needsMold",true));assertThatThrownBy(()->workflow.action(20L,state,"unit_cost_confirm",data,service)).hasMessageContaining("仅适用于");
    }

    private Map<String,Object> costs(String currency,Map<String,Integer> amounts){
        return Map.of("investment",Map.of("rows",List.of(),"currencyGroups",List.of(Map.of("currency",currency,"categories",amounts.entrySet().stream().map(e->Map.<String,Object>of("category",e.getKey(),"knownTotal",e.getValue(),"missingCount",0)).toList()))));
    }
    @Test void linkedUnitCostsReadSavedProjectCostsOnceAndKeepOldRowsAsEvidence(){
        var access=mock(PilotAccessService.class);var finance=mock(com.yuewei.plm.module.development.DevelopmentFinanceService.class);
        when(finance.summary(eq("budget"),any())).thenReturn(costs("CNY",Map.of("mold",1000,"transport",100,"customs",20,"design",30,"sample",10,"other",0)));
        when(finance.summary(eq("expense"),any())).thenReturn(costs("CNY",Map.of("mold",500)));
        var workflow=new DevelopmentUnitCostWorkflow(access,new UnitCostSheetCalculator(new BomCostCalculator()),mock(ProductBomWorkflowService.class),mock(JdbcTemplate.class),mock(DevelopmentWorkflowStore.class),finance);
        var rows=new ArrayList<Map<String,Object>>();rows.add(Map.of("id","material","category","material","mode","unit","unitPrice",2));rows.add(Map.of("id","process","category","process","mode","unit","unitPrice",1));
        for(String group:List.of("tooling","tool_transport","development"))rows.add(Map.of("id",group,"category",group,"mode","divide","unitPrice",99999,"allocationQty",100));
        rows.add(Map.of("id","shared-old","category","tooling","mode","divide","unitPrice",99999,"allocationQty",100));
        var input=new LinkedHashMap<String,Object>(Map.of("currency","CNY","taxBasis","source","useProjectInvestment",true,"rows",rows));
        var result=workflow.preview(20L,input);
        assertThat(LaunchReviewWorkflow.records(result.get("rows"))).hasSize(5);
        var tooling=LaunchReviewWorkflow.records(result.get("rows")).stream().filter(r->"tooling".equals(r.get("category"))).findFirst().orElseThrow();
        assertThat((BigDecimal)tooling.get("unitPrice")).isEqualByComparingTo("500");assertThat((BigDecimal)tooling.get("unitCost")).isEqualByComparingTo("5");
        assertThat((BigDecimal)DevelopmentWorkflowStore.map(result.get("totals")).get("base")).isEqualByComparingTo("9.6");
        assertThat(LaunchReviewWorkflow.records(result.get("investmentOriginalRows"))).hasSize(4);
        assertThat(rows.get(2)).containsEntry("unitPrice",99999);
        verify(finance).summary(eq("budget"),argThat(q->Objects.equals(q.getProjectId(),20L)));
        when(finance.summary(eq("expense"),any())).thenReturn(costs("CNY",Map.of("mold",600)));
        var recalculated=workflow.preview(20L,result);
        assertThat((BigDecimal)DevelopmentWorkflowStore.map(recalculated.get("totals")).get("base")).isEqualByComparingTo("10.6");
        assertThat((BigDecimal)DevelopmentWorkflowStore.map(result.get("totals")).get("base")).isEqualByComparingTo("9.6");
        assertThat(LaunchReviewWorkflow.records(recalculated.get("investmentOriginalRows"))).hasSize(4);
        var added=LaunchReviewWorkflow.records(recalculated.get("rows"));var editableRows=new ArrayList<Map<String,Object>>(added);editableRows.add(Map.of("id","late-extra","category","tooling","name","后补说明","mode","divide","unitPrice",300,"allocationQty",100,"extraValues",Map.of("supplier","后补资料保留")));
        editableRows.stream().filter(r->"project-investment-tooling".equals(r.get("id"))).findFirst().orElseThrow().put("extraValues",Map.of("memo","托管行补充资料"));
        recalculated.put("rows",editableRows);var preserved=workflow.preview(20L,recalculated);
        assertThat(LaunchReviewWorkflow.records(preserved.get("investmentOriginalRows"))).hasSize(5).anySatisfy(r->assertThat(r).containsEntry("extraValues",Map.of("supplier","后补资料保留")));
        assertThat(LaunchReviewWorkflow.records(preserved.get("rows"))).anySatisfy(r->assertThat(r).containsEntry("extraValues",Map.of("memo","托管行补充资料")));
        var jsonStore=new DevelopmentWorkflowStore(mock(JdbcTemplate.class),new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        var reopened=workflow.preview(20L,DevelopmentWorkflowStore.map(jsonStore.read(jsonStore.encode(preserved))));
        assertThat(LaunchReviewWorkflow.records(reopened.get("investmentOriginalRows"))).hasSize(5).anySatisfy(r->assertThat(r).containsEntry("extraValues",Map.of("supplier","后补资料保留")));
    }
    @Test void linkedUnitCostsDoNotInventAnAllocationQuantityOrConvertAnotherCurrency(){
        var finance=mock(com.yuewei.plm.module.development.DevelopmentFinanceService.class);
        when(finance.summary(eq("budget"),any())).thenReturn(costs("USD",Map.of("mold",1000,"transport",100,"customs",20,"design",0,"sample",0,"other",0)));
        when(finance.summary(eq("expense"),any())).thenReturn(costs("CNY",Map.of("mold",0)));
        var workflow=new DevelopmentUnitCostWorkflow(mock(PilotAccessService.class),new UnitCostSheetCalculator(new BomCostCalculator()),mock(ProductBomWorkflowService.class),mock(JdbcTemplate.class),mock(DevelopmentWorkflowStore.class),finance);
        var result=workflow.preview(20L,new LinkedHashMap<>(Map.of("currency","CNY","taxBasis","source","useProjectInvestment",true,"rows",List.of(Map.of("id","old","category","tooling","mode","unit","unitPrice",0.3)))));
        var rows=LaunchReviewWorkflow.records(result.get("rows"));assertThat(rows).hasSize(3);assertThat(rows).allSatisfy(r->assertThat(r.get("allocationQty")).isNull());
        assertThat((BigDecimal)rows.get(0).get("unitPrice")).isEqualByComparingTo("0");assertThat(rows.get(1).get("unitPrice")).isNull();
        assertThat(DevelopmentWorkflowStore.map(result.get("totals")).get("base")).isNull();
    }
}

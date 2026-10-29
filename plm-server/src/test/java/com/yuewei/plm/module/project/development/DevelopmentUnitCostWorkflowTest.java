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
        var jdbc=mock(JdbcTemplate.class);var service=mock(ProductDevelopmentService.class);
        var reference=Map.<String,Object>of("type","bom","id",10L,"versionNo","MES-1","content",Map.of("header",Map.of("quantity",output)));
        when(service.snapshotReferences(eq(20L),anyList())).thenReturn(List.of(reference));
        when(jdbc.queryForList(startsWith("select product_bom_route_id,process_id"),eq(10L),eq(31L))).thenReturn(List.of(Map.of("product_bom_route_id",31L,"process_id",41L)));
        var header=new LinkedHashMap<String,Object>();header.put("quantity",new BigDecimal(output));header.put("currency_code",currency);
        when(jdbc.queryForMap(startsWith("select quantity,currency_code"),eq(10L))).thenReturn(header);
        var line=new LinkedHashMap<String,Object>();line.put("product_bom_item_id",100L);line.put("item_name","原物料");line.put("quantity",new BigDecimal("0.00000001"));line.put("unit_cost_snapshot",null);line.put("loss_rate",null);
        when(jdbc.queryForList(startsWith("select product_bom_item_id,line_no"),eq(31L))).thenReturn(List.of(line));
        var workflow=new DevelopmentUnitCostWorkflow(mock(PilotAccessService.class),new UnitCostSheetCalculator(new BomCostCalculator()),mock(ProductBomWorkflowService.class),jdbc,mock(DevelopmentWorkflowStore.class));
        var state=new LinkedHashMap<String,Object>();workflow.action(20L,state,"unit_cost_from_bom",new LinkedHashMap<>(Map.of("bomId",10L,"routeId",31L)),service);
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
    @Test void purchasedFinishedProductUsesExistingQuoteHistoryWithoutInventingAManufacturingBom(){
        var access=mock(PilotAccessService.class);var actor=mock(com.yuewei.plm.common.security.CurrentUser.class);when(actor.displayName()).thenReturn("成本工程师");when(access.actor()).thenReturn(actor);
        var bomWriter=mock(ProductBomWorkflowService.class);var service=mock(ProductDevelopmentService.class);
        when(service.snapshotReferences(anyLong(),anyList())).thenReturn(List.of());
        when(service.eventData(20L,7L,"quote")).thenReturn(Map.of("supplierCode","S1","supplierName","供应商","amount",12,"currency","CNY","unit","每件"));
        var workflow=new DevelopmentUnitCostWorkflow(access,new UnitCostSheetCalculator(new BomCostCalculator()),bomWriter,mock(JdbcTemplate.class),mock(DevelopmentWorkflowStore.class));
        var rows=new ArrayList<Map<String,Object>>();for(String group:UnitCostSheetCalculator.BASE_GROUPS)rows.add(Map.of("id",group,"category",group,"name",group,"mode","unit","unitPrice",group.equals("material")?12:0,"taxIncluded",false,"taxRate",0));
        var data=new HashMap<String,Object>(Map.of("title","供方成品单件核算","currency","CNY","taxBasis","net","basisType","supplier_quote","quoteEventId",7L,"quoteUnitNote","每件未税，无币种及数量换算","reason","本次已核对供方报价","rows",rows));
        var state=new HashMap<String,Object>(Map.of("plan",Map.of("method","direct","needsMold",false)));
        workflow.action(20L,state,"unit_cost_confirm",data,service);
        var version=LaunchReviewWorkflow.records(state.get("unitCostVersions")).get(0);assertThat(version).containsEntry("basisType","supplier_quote").containsKey("adoptedQuote").doesNotContainKeys("bomId","routeId","bomCostSnapshotId");verifyNoInteractions(bomWriter);
        state.put("plan",Map.of("method","own_mold","needsMold",true));assertThatThrownBy(()->workflow.action(20L,state,"unit_cost_confirm",data,service)).hasMessageContaining("仅适用于");
    }

}

package com.yuewei.plm.module.productionmetadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductSalesPolicyTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final ProductSalesPolicy policy=new ProductSalesPolicy(jdbc);
    Map<String,Object> line(String sales,String mode,String status,String lock,int stage){
        return Map.of("product_id",54L,"product_name","透甲 2.0","sales_status",sales,"development_mode",mode,"status",status,"lock_status",lock,"current_step_no",stage);
    }
    void rows(Map<String,Object> row){when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(row));}
    @Test void prelaunchResearchUsesTheSameFillingEligibilityAsTheLockedWriteCheck() {
        var row=line("unconfirmed","launch_v1","draft","unlocked",1);rows(row);
        var eligibility=ProductSalesPolicy.moldApplicationEligibility(row);
        assertThat(eligibility.eligible()).isTrue();assertThat(eligibility.kind()).isEqualTo("research");
        assertThatCode(()->policy.requireMoldDevelopmentEligible(List.of(54L))).doesNotThrowAnyException();
        assertThat(new ObjectMapper().valueToTree(eligibility).path("eligible").asBoolean()).isTrue();
        assertThat(row).containsEntry("sales_status","unconfirmed");verify(jdbc).queryForList(contains("for share of p"),any(Object[].class));verifyNoMoreInteractions(jdbc);
    }
    @Test void anOrdinaryRetiredProductCannotBorrowTheResearchException() {
        var row=line("off_sale","standard","developing","unlocked",2);rows(row);
        var eligibility=ProductSalesPolicy.moldApplicationEligibility(row);assertThat(eligibility.eligible()).isFalse();assertThat(eligibility.reason()).contains("已停售");
        assertThatThrownBy(()->policy.requireMoldDevelopmentEligible(List.of(54L))).hasMessage(eligibility.reason());
    }
    @Test void stoppedResearchStaysBlockedEvenIfSalesMetadataWasMarkedOnSale() {
        for(var row:List.of(line("on_sale","launch_v1","developing","frozen",2),line("unconfirmed","launch_v1","developing","abandoned",3))){
            rows(row);var eligibility=ProductSalesPolicy.moldApplicationEligibility(row);assertThat(eligibility.eligible()).isFalse();
            assertThatThrownBy(()->policy.requireMoldDevelopmentEligible(List.of(54L))).hasMessage(eligibility.reason());
        }
    }
    @Test void releasedOrArchivedResearchMustUseTheActualSalesCatalog() {
        for(String status:List.of("released","archived")){
            var row=line("unconfirmed","launch_v1",status,"unlocked",4);rows(row);
            assertThat(ProductSalesPolicy.moldApplicationEligibility(row).eligible()).isFalse();assertThatThrownBy(()->policy.requireMoldDevelopmentEligible(List.of(54L))).hasMessageContaining("在售状态尚未确认");
        }
        var row=line("on_sale","launch_v1","released","unlocked",4);rows(row);assertThat(ProductSalesPolicy.moldApplicationEligibility(row).kind()).isEqualTo("catalog");assertThatCode(()->policy.requireMoldDevelopmentEligible(List.of(54L))).doesNotThrowAnyException();
    }
    @Test void missingProductsCannotBeConsideredEligibleByAStaleClientSelection() {
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());assertThatThrownBy(()->policy.requireMoldDevelopmentEligible(List.of(54L))).hasMessageContaining("产品不存在");
    }
}

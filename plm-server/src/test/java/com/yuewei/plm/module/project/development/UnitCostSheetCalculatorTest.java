package com.yuewei.plm.module.project.development;

import com.yuewei.plm.module.bom.service.BomCostCalculator;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class UnitCostSheetCalculatorTest {
    final UnitCostSheetCalculator calculator=new UnitCostSheetCalculator(new BomCostCalculator());
    Map<String,Object> line(String category,String name,Object price){var row=new LinkedHashMap<String,Object>();row.put("id",name);row.put("category",category);row.put("name",name);row.put("mode","unit");row.put("unitPrice",price);return row;}
    Map<String,Object> divide(String category,String name,Object price,Object qty){var row=line(category,name,price);row.put("mode","divide");row.put("allocationQty",qty);return row;}
    Map<String,Object> source(){var sheet=new LinkedHashMap<String,Object>();sheet.put("currency","CNY");sheet.put("displayCurrency","MXN");sheet.put("exchangeRate",new BigDecimal("2.72"));sheet.put("taxBasis","source");sheet.put("rows",new ArrayList<>(List.of(divide("material","PC",new BigDecimal("13.8"),16),divide("material","TPU",16,33),line("material","配件及包装",new BigDecimal("12.302")),line("process","加工",new BigDecimal("3.7")),divide("tooling","模具",17000,30000),divide("tool_transport","模具运输",7489,30000),line("development","开发",new BigDecimal("0.1")),line("freight_sea","海运",new BigDecimal("0.67")),line("freight_air","空运",new BigDecimal("6.678")))));return sheet;}
    @SuppressWarnings("unchecked") Map<String,Object> totals(Map<String,Object> sheet){return (Map<String,Object>)sheet.get("totals");}
    @Test void toujiaWorkbookUsesOriginalPrecisionAndSeparateSeaAndAirScenarios(){
        var result=calculator.calculate(source());
        assertThat((BigDecimal)totals(result).get("manufacturing")).isCloseTo(new BigDecimal("17.3493484848485"),within(new BigDecimal("0.00000001")));
        var base=(BigDecimal)totals(result).get("base");assertThat(base).isCloseTo(new BigDecimal("18.2656484848485"),within(new BigDecimal("0.00000001")));
        assertThat((BigDecimal)totals(result).get("freight_sea")).isEqualByComparingTo(base.add(new BigDecimal("0.67")));
        assertThat((BigDecimal)totals(result).get("freight_air")).isEqualByComparingTo(base.add(new BigDecimal("6.678")));
        assertThat(LaunchReviewWorkflow.records(result.get("rows")).get(0).get("displayCost")).isEqualTo(new BigDecimal("2.34600000000000"));
        assertThat(result.get("bomResult")).isInstanceOf(BomCostCalculator.Result.class);
    }
    @Test void missingAllocationOrPriceStaysUnknownWhileExplicitZeroIsValid(){
        var input=source();var rows=LaunchReviewWorkflow.records(input.get("rows"));rows.get(4).put("allocationQty",null);input.put("rows",rows);
        var result=calculator.calculate(input);assertThat(totals(result).get("base")).isNull();assertThat(result.get("complete")).isEqualTo(false);
        rows.get(4).put("allocationQty",30000);rows.get(4).put("unitPrice",0);result=calculator.calculate(input);assertThat(result.get("complete")).isEqualTo(true);
    }
    @Test void missingCostCategoryDoesNotBecomeAnImplicitZero(){var input=source();var rows=LaunchReviewWorkflow.records(input.get("rows"));rows.removeIf(r->"development".equals(r.get("category")));input.put("rows",rows);var result=calculator.calculate(input);assertThat(totals(result).get("base")).isNull();assertThat(result.get("missing").toString()).contains("开发费用未填写");}
    @Test void rejectsZeroDivisorNegativeAmountsAndExecutableFormulaStrings(){var input=source();var rows=LaunchReviewWorkflow.records(input.get("rows"));rows.get(0).put("allocationQty",0);input.put("rows",rows);assertThatThrownBy(()->calculator.calculate(input)).hasMessageContaining("大于0");rows.get(0).put("allocationQty",16);rows.get(0).put("unitPrice",-1);assertThatThrownBy(()->calculator.calculate(input)).hasMessageContaining("非负数");rows.get(0).put("unitPrice","=13.8/16");assertThatThrownBy(()->calculator.calculate(input)).hasMessageContaining("有效数值");}
    @Test void taxNormalizationUsesExplicitRatesAndDoesNotGuessMissingTax(){
        var input=source();var rows=LaunchReviewWorkflow.records(input.get("rows"));rows.forEach(r->{r.put("taxIncluded",false);r.put("taxRate",0);});rows.get(2).put("unitPrice",new BigDecimal("11.3"));rows.get(2).put("taxIncluded",true);rows.get(2).put("taxRate",13);input.put("rows",rows);input.put("taxBasis","net");
        var result=calculator.calculate(input);assertThat(LaunchReviewWorkflow.records(result.get("rows")).get(2).get("unitCost")).isEqualTo(new BigDecimal("10.000000000000"));
        rows.get(2).put("taxRate",null);result=calculator.calculate(input);assertThat(totals(result).get("base")).isNull();
    }
    @Test void sourceEstimatesCannotConfirmAndForeignCurrencyNeedsDatedEvidence(){
        var input=source();assertThatThrownBy(()->calculator.requireConfirmable(calculator.calculate(input))).hasMessageContaining("统一");
        input.put("taxBasis","net");var rows=LaunchReviewWorkflow.records(input.get("rows"));rows.forEach(r->{r.put("taxIncluded",false);r.put("taxRate",0);});input.put("rows",rows);
        assertThatThrownBy(()->calculator.requireConfirmable(calculator.calculate(input))).hasMessageContaining("汇率依据日期");input.put("exchangeRateDate","2026-10-16");assertThatCode(()->calculator.requireConfirmable(calculator.calculate(input))).doesNotThrowAnyException();
        rows.get(0).put("warning","金额待核对");assertThatThrownBy(()->calculator.requireConfirmable(calculator.calculate(input))).hasMessageContaining("待核对");
    }
    @Test void quantityAndLossMustBothBeExplicitAndIdsCannotDuplicate(){var input=source();var rows=LaunchReviewWorkflow.records(input.get("rows"));rows.get(0).put("mode","usage");rows.get(0).put("quantity",1);input.put("rows",rows);assertThat(totals(calculator.calculate(input)).get("base")).isNull();rows.get(0).put("lossPct",0);assertThat(totals(calculator.calculate(input)).get("base")).isNotNull();rows.get(1).put("id",rows.get(0).get("id"));assertThatThrownBy(()->calculator.calculate(input)).hasMessageContaining("重复");}
    @Test void missingSourceCurrencyStaysAnIncompleteDraftInsteadOfBecomingCny(){var input=source();input.remove("currency");var result=calculator.calculate(input);assertThat(result.get("currency")).isEqualTo("");assertThat(result.get("complete")).isEqualTo(false);assertThat(totals(result).get("base")).isNull();assertThat(result.get("missing").toString()).contains("币种待确认");assertThatThrownBy(()->calculator.requireConfirmable(result)).hasMessageContaining("待补");}
    @Test void aSingleCurrencyCostNeedsNoForeignRateOrDate(){
        var input=source();input.put("taxBasis","net");input.put("displayCurrency","");input.remove("exchangeRateDate");
        var rows=LaunchReviewWorkflow.records(input.get("rows"));rows.forEach(r->{r.put("taxIncluded",false);r.put("taxRate",0);});input.put("rows",rows);
        var sheet=calculator.calculate(input);calculator.requireConfirmable(sheet);
        assertThat(LaunchReviewWorkflow.records(sheet.get("rows")).get(0).get("displayCost")).isNull();
    }

}

package com.yuewei.plm.module.moldsales;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
class MoldSalesRulesTest {
    final ObjectMapper json=new ObjectMapper();
    ObjectNode line(){var r=json.createObjectNode();r.put("quantity","100");r.put("code","NBA4030114402");r.put("status","closed");r.put("deleted",0);return r;}
    @ParameterizedTest @ValueSource(strings={"closed","deal","confirmed","partial_shipped","fully_shipped","fully_arrived","confirmed_deposit_push_production","pending"})
    void countsRegisteredOrdersRegardlessOfDeliveryState(String status){var r=line();r.put("status",status);assertEquals("eligible",MoldSalesRules.identity(r).eligibility());}
    @Test void excludesOnlyCancellationDeletionAndInvalidFacts(){var r=line();r.put("status","cancelled");assertEquals("cancelled",MoldSalesRules.identity(r).eligibility());r=line();r.put("deleted",1);assertEquals("deleted",MoldSalesRules.identity(r).eligibility());for(String status:new String[]{"draft","pending","unknown-new-stage"}){r=line();r.put("status",status);assertEquals("eligible",MoldSalesRules.identity(r).eligibility());}}
    @Test void resolvesOnlyExactFinishedItemIdentityAndIgnoresColor(){var r=line();var a=MoldSalesRules.identity(r);r.put("code","NBA4030114407");var b=MoldSalesRules.identity(r);assertEquals(a,b);assertEquals("BA",a.style());assertEquals("1144",a.phone());r.put("code","MBA201144");assertEquals("not_finished_code",MoldSalesRules.identity(r).eligibility());}
    @Test void rejectsExplicitPhoneOrStyleConflicts(){var r=line();r.put("phoneCode","1217");assertEquals("identity_conflict",MoldSalesRules.identity(r).eligibility());r.put("phoneCode","1144");r.put("seriesCode","NFA4020");assertEquals("identity_conflict",MoldSalesRules.identity(r).eligibility());}
    @Test void invalidOrFractionalQuantitiesNeverProveSales(){for(String value:new String[]{"-1","0","1.5"}){var r=line();r.put("quantity",value);assertEquals("quantity_review",MoldSalesRules.identity(r).eligibility());}}
    ObjectNode basis(){var b=json.createObjectNode();b.put("landedTotal","21702.30");b.put("referenceSellingPrice","8");b.put("referenceUnitProductionCost","4.8");b.put("currency","CNY");return b;}
    @Test void sharedModelsUseOneCostAndCombinedSales(){var result=MoldSalesRules.costs(basis(),new BigDecimal("3000"),1);assertEquals(new BigDecimal("21702.30"),result.get("totalCost"));assertEquals(new BigDecimal("7.2341"),result.get("perUnitCost"));assertEquals(new BigDecimal("6782"),result.get("breakEvenUnits"));assertEquals(new BigDecimal("3782"),result.get("remainingUnits"));}
    @Test void countsActualAdditionalSetsButNeverDuplicatesSales(){var result=MoldSalesRules.costs(basis(),new BigDecimal("3000"),2);assertEquals(new BigDecimal("43404.60"),result.get("totalCost"));assertEquals(new BigDecimal("14.4682"),result.get("perUnitCost"));}
    @Test void noSalesOrNonpositiveContributionDoesNotProduceAFalseRecovery(){assertNull(MoldSalesRules.costs(basis(),BigDecimal.ZERO,1).get("perUnitCost"));var b=basis();b.put("referenceUnitProductionCost","8");assertNull(MoldSalesRules.costs(b,new BigDecimal("100"),1).get("breakEvenUnits"));}
    @Test void orderedCasesEstablishEachComponentWithoutAddingManualTotals(){var r=MoldSalesRules.productionTotals(new BigDecimal("100"),new BigDecimal("80"),new BigDecimal("120"),new BigDecimal("100"));assertEquals(new BigDecimal("100"),r.get("pcTotal"));assertEquals(new BigDecimal("120"),r.get("tpuTotal"));assertEquals(new BigDecimal("100"),r.get("finishedTotal"));assertEquals(new BigDecimal("80"),r.get("manualPcTotal"));}
    @Test void repeatedOrderBasisIsStableAndNeverAccumulates(){var r=MoldSalesRules.productionTotals(new BigDecimal("100"),new BigDecimal("100"),new BigDecimal("100"),new BigDecimal("100"));assertEquals(new BigDecimal("100"),r.get("finishedTotal"));assertEquals(r,MoldSalesRules.productionTotals(new BigDecimal("100"),new BigDecimal("100"),new BigDecimal("100"),new BigDecimal("100")));}
    @Test void missingSourceRemainsUnknownAndConfirmedManualZeroIsPreserved(){var unknown=MoldSalesRules.productionTotals(null,null,null,null);assertNull(unknown.get("pcTotal"));assertNull(unknown.get("finishedTotal"));var zero=MoldSalesRules.productionTotals(null,BigDecimal.ZERO,null,null);assertEquals(BigDecimal.ZERO,zero.get("pcTotal"));assertNull(zero.get("tpuTotal"));}
    @Test void sharedArchiveManualTotalsUseMaximumInsteadOfSum(){assertEquals(new BigDecimal("120"),MoldSalesRules.maximum(new BigDecimal("80"),new BigDecimal("120")));}
    @Test void soldIntermediateStagesDoNotBecomeFinishedCases(){for(String stage:new String[]{"1010","1020","2030","3010","3020"}){var r=line();r.put("code","NBA"+stage+"114402");var fact=MoldSalesRules.identity(r);assertEquals("not_finished_stage",fact.eligibility());assertEquals("1144",fact.phone());}}
    @Test void finalStepUsesExistingProductScopeRatherThanAUniversal4020Rule(){var r=line();r.put("code","NFA4020114402");assertEquals("eligible",MoldSalesRules.identity(r,Set.of("FA4020")).eligibility());r.put("code","NBD4020114402");assertEquals("not_finished_stage",MoldSalesRules.identity(r,Set.of("FA4020")).eligibility());}
    @Test void catalogueFinalStepNeverOverridesCancellationOrIdentityConflict(){var r=line();r.put("code","NFA4020114402");r.put("status","cancelled");assertEquals("cancelled",MoldSalesRules.identity(r,Set.of("FA4020")).eligibility());r.put("status","closed");r.put("phoneCode","1217");assertEquals("identity_conflict",MoldSalesRules.identity(r,Set.of("FA4020")).eligibility());assertThrows(IllegalArgumentException.class,()->MoldSalesRules.finishedSql(Set.of("FA'4020")));}
    @Test void finalCasePreservesLeadingZeroPhoneIdentity(){var r=line();r.put("code","NFA4020005701");var fact=MoldSalesRules.identity(r,Set.of("FA4020"));assertEquals("0057",fact.phone());assertEquals("eligible",fact.eligibility());}
    @Test void manualGroupCostIncludesEveryFeeWithoutMultiplyingSetsOrModels(){
        var actual=new MoldActualCostStore.Amounts(new BigDecimal("1000.00"),new BigDecimal("200.00"),new BigDecimal("30.00"),new BigDecimal("5.00")).values();
        var result=MoldSalesRules.costs(basis(),new BigDecimal("100"),3,actual);
        assertEquals("actual",result.get("source"));assertEquals(new BigDecimal("1235.00"),result.get("totalCost"));assertEquals(new BigDecimal("12.3500"),result.get("perUnitCost"));assertEquals(new BigDecimal("65106.90"),result.get("budgetTotalCost"));assertEquals(basis(),result.get("basis"));
    }
    @Test void manualCostWorksWithoutBudgetButDoesNotInventReferenceProfit(){
        var actual=new MoldActualCostStore.Amounts(BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO).values();
        var result=MoldSalesRules.costs(null,new BigDecimal("2"),9,actual);
        assertEquals(new BigDecimal("5.0000"),result.get("perUnitCost"));assertNull(result.get("basis"));assertNull(result.get("budgetTotalCost"));assertNull(result.get("breakEvenUnits"));assertNull(result.get("remainingUnits"));assertNull(result.get("referenceCoverage"));
    }
    @Test void confirmedZeroActualCostOverridesBudgetInsteadOfFallingBack(){
        var actual=new MoldActualCostStore.Amounts(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO).values();
        var result=MoldSalesRules.costs(basis(),new BigDecimal("5"),1,actual);assertEquals("actual",result.get("source"));assertEquals(BigDecimal.ZERO,result.get("totalCost"));assertEquals(new BigDecimal("0.0000"),result.get("perUnitCost"));assertNull(result.get("referenceCoverage"));
    }
    @Test void actualCostValidationRequiresAllFourNonnegativeTwoDecimalAmounts(){
        assertThrows(com.yuewei.plm.common.exception.BusinessException.class,()->MoldActualCostStore.validate(null));
        for(BigDecimal value:new BigDecimal[]{null,new BigDecimal("-1"),new BigDecimal("0.001"),new BigDecimal("10000000000000000")})assertThrows(com.yuewei.plm.common.exception.BusinessException.class,()->MoldActualCostStore.validate(new MoldActualCostStore.Amounts(value,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO)));
        assertEquals(new BigDecimal("1.20"),MoldActualCostStore.validate(new MoldActualCostStore.Amounts(new BigDecimal("1.2000"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO)).manufacturingCost());
    }
    @Test void groupTokenIsOrderIndependentButChangesWithActualMembershipAndScope(){
        var group=new java.util.LinkedHashMap<String,Object>(java.util.Map.of("itemIds",java.util.List.of(2L,1L),"styleCode","FA","productLineId",1L,"groupId",1L,"phoneCodes",java.util.List.of("0057","1297"),"physicalSets",1));
        String before=MoldActualCostStore.token(group);group.put("itemIds",java.util.List.of(1L,2L));group.put("phoneCodes",java.util.List.of("1297","0057"));assertEquals(before,MoldActualCostStore.token(group));
        group.put("phoneCodes",java.util.List.of("1297"));assertNotEquals(before,MoldActualCostStore.token(group));group.put("phoneCodes",java.util.List.of("0057","1297"));group.put("physicalSets",2);assertNotEquals(before,MoldActualCostStore.token(group));
    }
    ObjectNode archived(){var r=json.createObjectNode();r.put("quantity","100");r.put("code","NFA4020005701");r.put("unit","Pieza");r.put("statuses","完成");r.put("cancellationNote",false);return r;}
    @ParameterizedTest @ValueSource(strings={"Pieza","PIEZA","piezas","PCS","个","件","个 PIEZA","个PIEZA"})
    void historicalUnitsRequireAnExplicitIndividualPieceWord(String unit){var r=archived();r.put("unit",unit);var fact=MoldSalesRules.archiveIdentity(r,Set.of("FA4020"));assertEquals("eligible",fact.eligibility());assertEquals("0057",fact.phone());}
    @ParameterizedTest @ValueSource(strings={"","PAQ","Paq","套 Juego","台 PIEZA","张 PIEZA","瓶 Pieza","台"})
    void packagingAndUnknownOrMixedHistoricalUnitsAreNotInventedAsCases(String unit){var r=archived();r.put("unit",unit);assertEquals("unit_review",MoldSalesRules.archiveIdentity(r,Set.of("FA4020")).eligibility());}
    @Test void historicalBlankQuantityStaysUnknownAndOtherInvalidQuantitiesStayRetained(){
        var r=archived();r.putNull("quantity");var missing=MoldSalesRules.archiveIdentity(r,Set.of("FA4020"));assertNull(missing.quantity());assertEquals("missing_quantity",missing.eligibility());
        for(String value:new String[]{"0","-2","0.5"}){r.put("quantity",value);var fact=MoldSalesRules.archiveIdentity(r,Set.of("FA4020"));assertEquals(new BigDecimal(value),fact.quantity());assertEquals("quantity_review",fact.eligibility());}
    }
    @Test void historicalOfficialStatusAndCancellationNotesDoNotBecomeShipmentFacts(){
        var r=archived();r.put("statuses","进行中");assertEquals("eligible",MoldSalesRules.archiveIdentity(r,Set.of("FA4020")).eligibility());r.put("cancellationNote",true);assertEquals("cancellation_review",MoldSalesRules.archiveIdentity(r,Set.of("FA4020")).eligibility());r.put("statuses","cancelled");assertEquals("cancelled",MoldSalesRules.archiveIdentity(r,Set.of("FA4020")).eligibility());r.put("statuses","");r.put("cancellationNote",false);assertEquals("status_review",MoldSalesRules.archiveIdentity(r,Set.of("FA4020")).eligibility());
    }
    @Test void historicalCodeParsingReusesCurrentFinalProfilesWithoutChangingOriginalIdentity(){
        var r=archived();r.put("code","NBD4020005701");assertEquals("not_finished_stage",MoldSalesRules.archiveIdentity(r,Set.of("FA4020")).eligibility());r.put("code","NBA4030005701");assertEquals("eligible",MoldSalesRules.archiveIdentity(r,Set.of()).eligibility());r.put("code","MBA100057");assertEquals("not_finished_code",MoldSalesRules.archiveIdentity(r,Set.of()).eligibility());
    }

    @Test void ordersByFourDigitCodeBeforeQuantityAndKeepsMissingCodesLast(){
        var old=new HashMap<String,Object>(Map.of("sourceGroupKey","old","phoneCodes",List.of("0007"),"salesQuantity",900));
        var shared=new HashMap<String,Object>(Map.of("sourceGroupKey","shared","phoneCodes",List.of("0041","1298"),"salesQuantity",1));
        var unknown=new HashMap<String,Object>(Map.of("sourceGroupKey","unknown","phoneCodes",List.of(),"salesQuantity",9999));
        var alias=new HashMap<String,Object>(Map.of("sourceGroupKey","alias","phoneCodes",List.of("9999"),"canonicalPhoneCodes",List.of("1297"),"salesQuantity",2));
        var rows=new ArrayList<Map<String,Object>>(List.of(old,unknown,alias,shared));
        MoldSalesRules.sortRows(rows,"phoneCodeDesc",false);
        assertEquals(List.of(shared,alias,old,unknown),rows);assertEquals(List.of("0007"),old.get("phoneCodes"));
        MoldSalesRules.sortRows(rows,"quantityDesc",false);assertEquals(unknown,rows.get(0));
    }
    @Test void openingDateUsesRecordedDateBeforeFirstOrderAndKeepsUnknownEmpty(){
        var group=new HashMap<String,Object>();MoldSalesRules.openingDate(group,LocalDate.parse("2026-10-20"),LocalDate.parse("2024-01-01"));
        assertEquals("2026-10-20",group.get("openedDate"));assertEquals("recorded",group.get("openedDateSource"));
        MoldSalesRules.openingDate(group,null,LocalDate.parse("2024-01-01"));assertEquals("2024-01-01",group.get("openedDate"));assertEquals("first_order",group.get("openedDateSource"));
        MoldSalesRules.openingDate(group,null,null);assertNull(group.get("openedDate"));assertNull(group.get("openedDateSource"));
    }
    @Test void openingDateSortingHasStableTiesAndDoesNotPromoteUnknownDates(){
        var a=new HashMap<String,Object>(Map.of("sourceGroupKey","a","openedDate","2026-10-20"));var b=new HashMap<String,Object>(Map.of("sourceGroupKey","b","openedDate","2026-10-20"));var old=new HashMap<String,Object>(Map.of("sourceGroupKey","old","openedDate","2024-01-01"));var empty=new HashMap<String,Object>(Map.of("sourceGroupKey","empty"));
        var rows=new ArrayList<Map<String,Object>>(List.of(empty,b,old,a));MoldSalesRules.sortRows(rows,"openedDateDesc",false);assertEquals(List.of(a,b,old,empty),rows);
        MoldSalesRules.mergeOpeningDate(a,Map.of("openedDate","2025-01-01","openedDateSource","first_order"));MoldSalesRules.mergeOpeningDate(a,Map.of("openedDate","2026-05-01","openedDateSource","recorded"));assertEquals("2026-05-01",a.get("openedDate"));
    }
}

package com.yuewei.plm.module.marketwatch;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MoldOpportunityProjectionTest {
    Map<String,Object> phone(Map<String,Object>... groups){var row=new LinkedHashMap<String,Object>();row.put("phoneModelId",18L);row.put("matchState","matched");row.put("coverages",List.of(groups));row.put("salesProducts",java.util.stream.LongStream.rangeClosed(1,7).mapToObj(i->Map.<String,Object>of("productLineId",i,"productName","款式"+i)).toList());return row;}
    Map<String,Object> group(long id,String key,Map<String,Object> evidence){return Map.of("productLineId",id,key,List.of(evidence));}
    void status(Map<String,Object> row,String expected){MoldOpportunityProjection.apply(row);assertThat(row.get("moldStatus")).isEqualTo(expected);assertThat(row.get("opportunityViews")).isEqualTo(List.of(expected));}
    @Test void validLeadingZeroCodeIsHistoricalApplicationEvidenceAndNeverAllProducts(){var row=phone();row.put("phoneModelCode","0017");status(row,"applied");assertThat(row.get("appliedProductCount")).isEqualTo(0L);assertThat(row.get("gapProductCount")).isEqualTo(7L);assertThat(MoldOpportunityProjection.matches(row,"to_apply")).isFalse();row.put("phoneModelCode","待分配");status(row,"to_apply");}
    @Test void onlyValidSkuEvidenceOpensItsOwnOnSaleProductAndDoesNotAssertFit(){var row=phone(group(1L,"products",Map.of("historicalSkuEvidence",true)),group(8L,"products",Map.of("historicalSkuEvidence",true)));status(row,"partially_molded");assertThat(row.get("openedProductCount")).isEqualTo(1L);assertThat(row.get("coverageTotal")).isEqualTo(7);assertThat(MoldOpportunityProjection.matches(row,"opportunities")).isTrue();var fake=phone(group(1L,"products",Map.of("productId",7L,"historicalSkuEvidence",false)));status(fake,"to_apply");}
    @Test void sevenProductsAreCompleteIndependentlyOfPhysicalFit(){var row=phone(java.util.stream.LongStream.rangeClosed(1,7).mapToObj(i->group(i,"products",Map.of("historicalSkuEvidence",true))).toArray(Map[]::new));status(row,"molded");}
    @Test void aDraftIsNotSubmittedAndEndedApplicationsRetainHistory(){for(String state:List.of("draft","rejected","withdrawn","terminated","paused"))status(phone(group(1L,"applications",Map.of("businessStatus",state,"status",state,"instanceId","oa"))),"to_apply");status(phone(group(1L,"applications",Map.of("businessStatus","planned","status","submitted","instanceId","real-oa"))),"applied");}
    @Test void startedAndDeliveredFactsAreSeparateFromCompatibility(){status(phone(group(1L,"molds",Map.of("moldSetId",2L,"state","partial","openedDate","2026-10-01","pcState","unknown","tpuState","unknown"))),"making");status(phone(group(1L,"molds",Map.of("moldSetId",2L,"state","partial","actualShipDate","2026-10-01","pcState","unknown","tpuState","unknown","reusable",false))),"partially_molded");status(phone(group(1L,"molds",Map.of("state","planned"))),"to_apply");}
    @Test void incompleteFitDoesNotHideEitherPartsProductionOrAllowNoPlan() {
        for(String part:List.of("pcState","tpuState"))for(String fact:List.of("making","confirmed")) {
            var row=phone(group(1L,"molds",Map.of("state","partial",part,fact,"reusable",false)));
            row.put("moldPlanStatus","not_planned");status(row,"making");
            assertThat(row.get("hasActiveExecution")).isEqualTo(true);
            assertThat(row.get("openedProductCount")).isEqualTo(0L);
        }
    }
    @Test void selectedProductKeepsWholePhoneSevenProductSummary(){var row=phone(group(1L,"products",Map.of("historicalSkuEvidence",true)));row.put("phoneModelCode","0017");row.put("selectedProductLineId",2L);status(row,"to_apply");assertThat(row.get("wholePhoneStatus")).isEqualTo("partially_molded");assertThat(row.get("openedProductCount")).isEqualTo(1L);}
    @Test void noPlanHasReasonAndCannotHideCurrentExecution(){var row=phone(group(1L,"products",Map.of("historicalSkuEvidence",true)));row.put("moldPlanStatus","not_planned");status(row,"not_planned");var active=phone(group(1L,"molds",Map.of("openedDate","2026-10-01")));active.put("moldPlanStatus","not_planned");status(active,"making");}
    @Test void historicalAssociationIsNotAnUnfinishedExecutionButRunningApprovalStillBlocksNoPlan(){var app=new LinkedHashMap<String,Object>(Map.of("businessStatus","planned","status","submitted","instanceId","historic-oa","historicalAssociationOnly",true));var row=phone(group(1L,"applications",app));row.put("moldPlanStatus","not_planned");status(row,"not_planned");assertThat(row.get("hasActiveExecution")).isEqualTo(false);app.put("approvalRunning",true);status(row,"applied");assertThat(row.get("hasActiveExecution")).isEqualTo(true);}
    @Test void engineeringPauseAllowsNoPlanWithoutErasingStartedFacts(){var group=Map.<String,Object>of("productLineId",1L,"applications",List.of(Map.of("businessStatus","paused","status","converted","instanceId","oa","moldSetId",2L,"executionStatus","paused")),"molds",List.of(Map.of("moldSetId",2L,"openedDate","2026-10-01")));var row=phone(group);row.put("moldPlanStatus","not_planned");status(row,"not_planned");assertThat(row.get("makingProductCount")).isEqualTo(1L);}
    @Test void ambiguousIdentityIsNotClassifiedAsPending(){var row=phone();row.remove("phoneModelId");status(row,"identity_unresolved");assertThat(MoldOpportunityProjection.matches(row,"opportunities")).isFalse();assertThat(MoldOpportunityProjection.matches(row,"all")).isTrue();assertThat(MoldOpportunityProjection.matches(row,"needs_confirmation")).isTrue();}
}

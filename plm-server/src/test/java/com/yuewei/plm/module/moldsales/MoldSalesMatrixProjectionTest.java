package com.yuewei.plm.module.moldsales;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MoldSalesMatrixProjectionTest {
    Map<String,Object> product(long id,String name){return Map.of("productLineId",id,"productName",name);}
    Map<String,Object> phone(long id,long brand,String name){return Map.of("phoneModelId",id,"brandId",brand,"brandName","Brand "+brand,"modelName",name,"phoneCode","0057");}
    Map<String,Object> group(long group,long product,String key,String name,String qty){
        var g=new LinkedHashMap<String,Object>();g.put("groupId",group);g.put("itemIds",List.of(group));g.put("productLineId",product);g.put("productName",product==1?"骑士":"超级队长");g.put("styleName",g.get("productName"));g.put("styleCode","FA");g.put("rowKey",key);g.put("modelNames",List.of(name));g.put("phoneCodes",List.of("0057"));g.put("canonicalPhoneCodes",List.of("0057"));g.put("phoneModelIds",List.of(7L));g.put("brandIds",List.of(10L));g.put("brandNames",List.of("Brand 10"));g.put("modelKind","single");g.put("salesQuantity",new BigDecimal(qty));return g;
    }
    List<Map<String,Object>> rows(List<Map<String,Object>> groups,Map<Long,List<Map<String,Object>>> coverage,List<Map<String,Object>> phones,List<Map<String,Object>> products,List<Long> selected,Long brand,String keyword,String filter){return MoldSalesMatrixProjection.rows(groups,coverage,phones,products,selected,brand,keyword,filter,true);}
    @SuppressWarnings("unchecked") List<Map<String,Object>> cells(Map<String,Object> row){return (List<Map<String,Object>>)row.get("cells");}
    @Test void sharedQuantityIsPlacedOnceRatherThanCopiedToEachMember(){
        var a=group(101,1,"code:0057","Phone A / Phone B","300");a.put("phoneModelIds",List.of(7L,8L));a.put("modelKind","shared");
        var b=group(201,2,"code:0057","Phone A / Phone B","70");b.put("phoneModelIds",List.of(7L,8L));b.put("modelKind","shared");
        var result=rows(List.of(a,b,a),Map.of(),List.of(phone(7,10,"Phone A"),phone(8,10,"Phone B")),List.of(product(1,"骑士"),product(2,"超级队长")),null,null,"","all");
        assertThat(result).hasSize(1);assertThat(result.get(0)).containsEntry("totalSalesQuantity",new BigDecimal("370"));assertThat(cells(result.get(0))).extracting(c->c.get("salesQuantity")).containsExactly(new BigDecimal("300"),new BigDecimal("70"));
    }
    @Test void historicalCombinationDoesNotBecomePureCanonicalPhone(){
        var historical=group(1059,1,"history:1059","A04 / A04S / A05S（历史订单通用款）","392");historical.put("phoneModelIds",List.of());historical.put("modelKind","shared");
        var standard=group(2,2,"phone:7","A04","9");
        var result=rows(List.of(historical,standard),Map.of(),List.of(),List.of(product(1,"亮甲 2.0"),product(2,"骑士")),null,null,"","all");
        assertThat(result).hasSize(2);assertThat(result).extracting(r->r.get("totalSalesQuantity")).containsExactly(new BigDecimal("392"),new BigDecimal("9"));
    }
    @Test void crossBrandRowIsNotClonedByBrandFilterAndRetainsFullScope(){
        var g=group(101,1,"code:0057","Phone A / Phone B","300");g.put("brandIds",List.of(10L,20L));g.put("brandNames",List.of("A","B"));g.put("modelKind","shared");
        for(long brand:List.of(10L,20L)){var result=rows(List.of(g),Map.of(),List.of(),List.of(product(1,"骑士")),null,brand,"","all");assertThat(result).hasSize(1);assertThat(result.get(0)).containsEntry("totalSalesQuantity",new BigDecimal("300")).containsEntry("brandNames",List.of("A","B"));}
        assertThat(rows(List.of(g),Map.of(),List.of(),List.of(product(1,"骑士")),null,99L,"","all")).isEmpty();
    }
    @Test void unknownBrandsAreNeverGuessedFromNames(){
        var g=group(1,1,"archive:1","SM Galaxy A05S","12");g.put("brandIds",List.of());g.put("brandNames",List.of());
        assertThat(rows(List.of(g),Map.of(),List.of(),List.of(product(1,"骑士")),null,null,"","all").get(0).get("brandNames")).isEqualTo(List.of());assertThat(rows(List.of(g),Map.of(),List.of(),List.of(product(1,"骑士")),null,10L,"","all")).isEmpty();
    }
    @Test void newOwnProductWithoutPhoneRelationshipIsAvailableButDoesNotInventRows(){
        var result=rows(List.of(group(1,1,"phone:7","Phone A","100")),Map.of(),List.of(phone(7,10,"Phone A")),List.of(product(1,"骑士"),product(19252,"透甲2.0")),List.of(1L,19252L),null,"","all");
        assertThat(result).hasSize(1);assertThat(cells(result.get(0))).filteredOn(c->c.get("productLineId").equals(19252L)).singleElement().satisfies(c->{assertThat(c).containsEntry("state","uncovered").containsEntry("salesQuantity",null);assertThat((Collection<?>)c.get("groupIds")).isEmpty();});
        assertThat(rows(List.of(),Map.of(),List.of(),List.of(product(19252,"透甲2.0")),null,null,"","all")).isEmpty();
    }
    @Test void actualDraftApplicationHasStatusWithoutPretendingPhysicalStockOrSales(){
        var coverage=Map.of(7L,List.<Map<String,Object>>of(Map.of("productLineId",19252L,"productLineName","透甲2.0","state","draft","registeredSets",0L,"applications",List.of(Map.of("applicationId",900L)),"molds",List.of())));
        var result=rows(List.of(),coverage,List.of(phone(7,10,"Phone A")),List.of(product(19252,"透甲2.0")),null,null,"","all");
        assertThat(result).hasSize(1);assertThat(cells(result.get(0))).singleElement().satisfies(c->{assertThat(c).containsEntry("state","draft").containsEntry("stateLabel","申请草稿").containsEntry("salesQuantity",BigDecimal.ZERO).containsEntry("registeredSets",0L);assertThat(MoldSalesMatrixProjection.ids(c.get("applicationIds"))).contains(900L);});
        assertThat(rows(List.of(),coverage,List.of(phone(7,10,"Phone A")),List.of(product(19252,"透甲2.0")),null,null,"","withSales")).isEmpty();
    }
    @Test void registeredButUnverifiedHistoryNeverImpliesReusable(){
        var result=rows(List.of(group(101,1,"phone:7","Phone A","100")),Map.of(),List.of(),List.of(product(1,"骑士")),null,null,"","all");assertThat(cells(result.get(0)).get(0)).containsEntry("state","historical");
        var coverage=Map.of(7L,List.<Map<String,Object>>of(Map.of("productLineId",1L,"molds",List.of(Map.of("itemId",101L,"state","reusable","registeredSets",2L)),"applications",List.of())));
        result=rows(List.of(group(101,1,"phone:7","Phone A","100")),coverage,List.of(),List.of(product(1,"骑士")),null,null,"","all");assertThat(cells(result.get(0)).get(0)).containsEntry("state","reusable").containsEntry("registeredSets",2L);
    }
    @Test void selectedAndAuthorizedProductsFilterCellsAndTotalsTogether(){
        var groups=List.of(group(1,1,"phone:7","Phone A","100"),group(2,2,"phone:7","Phone A","200"),group(3,3,"phone:7","Secret phone","9000"));
        var result=rows(groups,Map.of(),List.of(),List.of(product(1,"骑士"),product(2,"超级队长")),List.of(2L,3L),null,"","all");assertThat(result).hasSize(1);assertThat(result.get(0)).containsEntry("totalSalesQuantity",new BigDecimal("200"));assertThat(cells(result.get(0))).extracting(c->c.get("productLineId")).containsExactly(2L);assertThat(result.toString()).doesNotContain("9000","Secret");
        assertThat(rows(groups,Map.of(),List.of(),List.of(product(1,"骑士")),List.of(),null,"","all")).isEmpty();
    }
    @Test void productSearchUsesSameGroupPredicateAsDetailRatherThanAddingAllOtherCells(){
        var groups=List.of(group(1,1,"phone:7","Phone A","100"),group(2,2,"phone:7","Phone A","200"));var result=rows(groups,Map.of(),List.of(),List.of(product(1,"骑士"),product(2,"超级队长")),null,null,"骑士","all");assertThat(result).hasSize(1);assertThat(result.get(0)).containsEntry("totalSalesQuantity",new BigDecimal("100"));assertThat(groups.stream().filter(g->MoldSalesMatrixProjection.matches(g,"骑士","all")).map(g->g.get("salesQuantity"))).containsExactly(new BigDecimal("100"));
    }
    @Test void matrixDoesNotCarryFinancialOrHiddenUnderlyingRecords(){
        var g=group(1,1,"phone:7","Phone A","100");g.put("cost",Map.of("totalCost",999999));g.put("productionRecords",List.of(Map.of("notes","private")));var result=rows(List.of(g),Map.of(),List.of(),List.of(product(1,"骑士")),null,null,"","all");assertThat(result.toString()).doesNotContain("999999","private","totalCost","productionRecords");
    }    @Test void secondExplicitSharedMemberIsSearchableByItsStandardNameWithoutCopyingQuantity(){
        var g=group(101,1,"code:0057+0088","Old archive name","300");g.put("canonicalModelNames",List.of("Phone A","Phone B Ultra"));g.put("canonicalPhoneCodes",List.of("0057","0088"));g.put("phoneModelIds",List.of(7L,8L));g.put("modelKind","shared");
        var result=rows(List.of(g),Map.of(),List.of(),List.of(product(1,"骑士")),null,null,"Phone B Ultra","all");assertThat(result).hasSize(1);assertThat(result.get(0)).containsEntry("totalSalesQuantity",new BigDecimal("300"));assertThat((Collection<String>)result.get(0).get("modelNames")).contains("Old archive name","Phone B Ultra");assertThat(MoldSalesMatrixProjection.matches(g,"Phone B Ultra","all")).isTrue();
    }
    @Test void coverageOnlyLinksToExistingSharedArchiveDoNotCreateASecondZeroQuantityRow(){
        var g=group(101,1,"code:0057","Phone A / Phone B","300");g.put("phoneModelIds",List.of());g.put("modelKind","shared");
        var cover=Map.of(7L,List.<Map<String,Object>>of(Map.of("productLineId",1L,"productLineName","骑士","state","needs_review","molds",List.of(Map.of("itemId",101L)),"applications",List.of())));
        var result=rows(List.of(g),cover,List.of(phone(7,10,"Phone A")),List.of(product(1,"骑士")),null,null,"","all");assertThat(result).hasSize(1);assertThat(result.get(0)).containsEntry("totalSalesQuantity",new BigDecimal("300"));
    }
    @Test void disconnectedSnapshotPreservesUnknownQuantityInNestedGroupChoices(){
        var g=group(101,1,"phone:7","Phone A","0");g.put("salesQuantity",null);var result=MoldSalesMatrixProjection.rows(List.of(g),Map.of(),List.of(),List.of(product(1,"骑士")),null,null,"","all",false);
        assertThat(result.get(0)).containsEntry("totalSalesQuantity",null);var cell=cells(result.get(0)).get(0);assertThat(cell).containsEntry("salesQuantity",null);assertThat((List<Map<String,Object>>)cell.get("groups")).singleElement().satisfies(source->assertThat(source).containsEntry("salesQuantity",null));
    }
    @Test void disconnectedProductFootersStayUnknownAndConnectedKnownZeroStaysZero(){
        var g=group(101,1,"phone:7","Phone A","0");g.put("salesQuantity",null);var unknown=MoldSalesMatrixProjection.rows(List.of(g),Map.of(),List.of(),List.of(product(1,"骑士"),product(2,"超级队长")),null,null,"","all",false);
        assertThat(MoldSalesMatrixProjection.productTotals(unknown,false)).hasSize(2).allSatisfy(total->assertThat(total).containsEntry("salesQuantity",null));
        g.put("salesQuantity",BigDecimal.ZERO);var zero=rows(List.of(g),Map.of(),List.of(),List.of(product(1,"骑士"),product(19252,"透甲2.0")),null,null,"","all");var knownTotals=MoldSalesMatrixProjection.productTotals(zero,true);assertThat(knownTotals).filteredOn(total->Long.valueOf(1L).equals(total.get("productLineId"))).singleElement().satisfies(total->assertThat(total).containsEntry("salesQuantity",BigDecimal.ZERO));assertThat(knownTotals).filteredOn(total->Long.valueOf(19252L).equals(total.get("productLineId"))).singleElement().satisfies(total->assertThat(total).containsEntry("salesQuantity",null));
        var sold=rows(List.of(group(101,1,"phone:7","Phone A","15"),group(102,1,"phone:8","Phone B","7")),Map.of(),List.of(),List.of(product(1,"骑士"),product(19252,"透甲2.0")),null,null,"","all");var soldTotals=MoldSalesMatrixProjection.productTotals(sold,true);assertThat(soldTotals).filteredOn(total->Long.valueOf(1L).equals(total.get("productLineId"))).singleElement().satisfies(total->assertThat(total).containsEntry("salesQuantity",new BigDecimal("22")));assertThat(soldTotals).filteredOn(total->Long.valueOf(19252L).equals(total.get("productLineId"))).singleElement().satisfies(total->assertThat(total).containsEntry("salesQuantity",null));
    }

}

package com.yuewei.plm.module.moldsales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MoldSalesCatalogProjectionTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);final PilotAccessService access=mock(PilotAccessService.class);
    final List<Map<String,Object>> codeRows=new ArrayList<>(),phones=new ArrayList<>(),products=new ArrayList<>();
    final List<String> queries=new ArrayList<>();
    MoldSalesCatalogProjection catalog(){
        when(access.visibleSqlClause(anyString(),anyString())).thenAnswer(i->i.getArgument(0).equals("product")?"p.product_id in (1,19252)":"i.item_id in (974,1059)");
        when(jdbc.queryForList(anyString())).thenAnswer(i->{String sql=i.getArgument(0);queries.add(sql);if(sql.startsWith("select p.product_id"))return products;if(sql.startsWith("select m.id"))return phones;if(sql.startsWith("select c.code_value"))return codeRows;return List.of();});
        return new MoldSalesCatalogProjection(jdbc,access);
    }
    Map<String,Object> group(long item,String code,String name){var g=new LinkedHashMap<String,Object>();g.put("groupId",item);g.put("productLineId",1L);g.put("styleName","亮甲 2.0");g.put("modelNames",List.of(name));g.put("itemIds",List.of(item));g.put("phoneCodes",new TreeSet<>(List.of(code)));g.put("productionRecords",List.of(Map.of("itemId",item,"phoneCode",code)));return g;}
    Map<String,Object> root(long id,String status){var p=new LinkedHashMap<String,Object>();p.put("productLineId",id);p.put("productName",id==19252?"透甲2.0":"亮甲2.0");p.put("status",status);p.put("styleCode",null);return p;}
    @Test void correctedXsUsesTarget0069NotTheOriginal0070DictionaryIdentity()throws Exception{
        products.add(root(1,"archived"));phones.add(Map.of("phoneModelId",69L,"phoneCode","0069","brandId",1L,"brandName","Apple","modelName","iPhone XS"));
        codeRows.add(Map.of("phoneCode","0069","modelName","Apple iPhone X/10G/XS","brandId",1L,"brandName","Apple"));codeRows.add(Map.of("phoneCode","0070","modelName","iPhone XR","brandId",99L,"brandName","Wrong alias brand"));
        var g=group(974,"0069","Apple iPhone X/10G/XS");((Collection<String>)g.get("phoneCodes")).add("0070");var alias=new ObjectMapper().readTree("{\"phoneCode\":\"0070\",\"targetBasis\":{\"phoneCode\":\"0069\"}}");g.put("salesAliases",List.of(alias));
        catalog().enrich(List.of(g));assertThat((Collection<String>)g.get("canonicalPhoneCodes")).containsExactly("0069");assertThat(MoldSalesMatrixProjection.ids(g.get("brandIds"))).containsExactly(1L);assertThat(g.get("brandNames").toString()).doesNotContain("Wrong");assertThat(g.get("rowKey")).isEqualTo("code:0069");assertThat(g.get("modelKind")).isEqualTo("shared");assertThat(g.get("modelNames").toString()).contains("XS").doesNotContain("XR");
    }
    @Test void humanConfirmedHistoryStaysCombinedEvenIfCanonicalCodeHasOneStandardMember(){
        codeRows.add(Map.of("phoneCode","0770","modelName","Galaxy A04","brandId",10L,"brandName","Samsung"));phones.add(Map.of("phoneModelId",7L,"phoneCode","0770","brandId",10L,"brandName","Samsung","modelName","Galaxy A04"));
        var g=group(1059,"0770","A04/A04S / A05S（历史订单通用款）");catalog().enrich(List.of(g));assertThat(g).containsEntry("rowKey","history:1059").containsEntry("modelKind","shared");assertThat(MoldSalesMatrixProjection.ids(g.get("phoneModelIds"))).isEmpty();assertThat(MoldSalesMatrixProjection.ids(g.get("brandIds"))).containsExactly(10L);
    }
    @Test void draftAndUncodedOwnProductRemainSelectableAndUseOriginalObjectScope(){
        products.add(root(1,"archived"));products.add(root(19252,"draft"));var catalog=catalog();var options=catalog.options(List.of());
        assertThat((List<Map<String,Object>>)options.get("products")).extracting(p->p.get("productLineId")).containsExactly(1L,19252L);assertThat((List<?>)options.get("defaultProductLineIds")).hasSize(2);
        assertThat(queries).anySatisfy(sql->{assertThat(sql).contains("p.product_type='product_line'","p.deleted_flag=0","p.product_id in (1,19252)","master_data_status").doesNotContain("status='active'","product_specific_code is not null");});
    }
    @Test void canonicalMultiBrandGroupPreservesBothRealBrandIdentitiesWithoutSplitting(){
        codeRows.add(Map.of("phoneCode","0057","modelName","Phone A / Phone B","brandId",10L,"brandName","A"));codeRows.add(Map.of("phoneCode","0057","modelName","Phone A / Phone B","brandId",20L,"brandName","B"));
        var g=group(974,"0057","Phone A / Phone B");catalog().enrich(List.of(g));assertThat(g).containsEntry("rowKey","code:0057").containsEntry("modelKind","shared");assertThat(MoldSalesMatrixProjection.ids(g.get("brandIds"))).containsExactly(10L,20L);assertThat((Collection<String>)g.get("brandNames")).containsExactly("A","B");
    }    @Test void explicitSalesOnlyCommonScopeIsNotCollapsedIntoTheTargetSinglePhone()throws Exception{
        codeRows.add(Map.of("phoneCode","1136","modelName","OPPO A60 4G","brandId",10L,"brandName","OPPO"));phones.add(Map.of("phoneModelId",7L,"phoneCode","1136","brandId",10L,"brandName","OPPO","modelName","OPPO A60"));
        var g=group(969,"1136","OPPO A60 4G");g.put("salesAliases",List.of(new ObjectMapper().readTree("{\"phoneCode\":\"1102\",\"scopeEvidence\":{\"confirmedModels\":[\"OPPO A40\",\"OPPO A60\"]}}")));
        catalog().enrich(List.of(g));assertThat(g).containsEntry("rowKey","history:969").containsEntry("modelKind","shared").containsEntry("salesScopeOnly",true);assertThat(g.get("modelNames").toString()).contains("OPPO A40","OPPO A60");assertThat(MoldSalesMatrixProjection.ids(g.get("phoneModelIds"))).isEmpty();
    }
    @Test void standardNamesComeFromOnlyTheExplicitSecondSharedMemberId(){
        codeRows.add(Map.of("phoneCode","0057","modelName","Phone A","brandId",10L,"brandName","Brand"));codeRows.add(Map.of("phoneCode","0088","modelName","Shared code","brandId",10L,"brandName","Brand"));
        phones.add(Map.of("phoneModelId",7L,"phoneCode","0088","brandId",10L,"brandName","Brand","modelName","Phone B Ultra"));phones.add(Map.of("phoneModelId",8L,"phoneCode","0088","brandId",10L,"brandName","Brand","modelName","Different phone sharing code"));
        var g=group(974,"0057","Old Phone A");((Collection<String>)g.get("phoneCodes")).add("0088");var catalog=catalog();when(jdbc.queryForList(startsWith("with product_members"))).thenReturn(List.of(Map.of("item_id",974L,"phone_id",7L)));
        catalog.enrich(List.of(g));assertThat((Collection<String>)g.get("canonicalModelNames")).containsExactly("Phone B Ultra");assertThat(MoldSalesMatrixProjection.matches(g,"Phone B Ultra","all")).isTrue();assertThat(MoldSalesMatrixProjection.matches(g,"Different phone sharing code","all")).isFalse();
    }
    @Test void defaultColumnsUseCombinedRealSalesRatherThanAlphabeticalActiveProducts(){
        for(var entry:Map.of(1L,"A small",2L,"Z high across groups",3L,"Y highest",4L,"X high",5L,"W medium",19252L,"New draft").entrySet())products.add(new LinkedHashMap<>(Map.of("productLineId",entry.getKey(),"productName",entry.getValue(),"status","draft")));
        var groups=new ArrayList<Map<String,Object>>();for(var sale:List.of(new long[]{1,1},new long[]{2,200},new long[]{2,400},new long[]{3,900},new long[]{4,700},new long[]{5,400}))groups.add(Map.of("productLineId",sale[0],"salesQuantity",sale[1],"brandIds",List.of(),"brandNames",List.of()));
        var options=catalog().options(groups);assertThat((List<Long>)options.get("defaultProductLineIds")).containsExactly(3L,4L,2L,5L);assertThat((List<Map<String,Object>>)options.get("products")).extracting(p->p.get("productLineId")).contains(1L,19252L);assertThat(groups).extracting(g->g.get("salesQuantity")).containsExactly(1L,200L,400L,900L,700L,400L);
    }
    @Test void equalSalesDefaultsHaveStableNameThenRealIdOrder(){
        products.add(Map.of("productLineId",3L,"productName","Z"));products.add(Map.of("productLineId",2L,"productName","A"));products.add(Map.of("productLineId",1L,"productName","A"));
        var groups=new ArrayList<Map<String,Object>>();for(long id:List.of(1L,2L,3L))groups.add(Map.of("productLineId",id,"salesQuantity",100L,"brandIds",List.of(),"brandNames",List.of()));
        assertThat((List<Long>)catalog().options(groups).get("defaultProductLineIds")).containsExactly(1L,2L,3L);
    }

}

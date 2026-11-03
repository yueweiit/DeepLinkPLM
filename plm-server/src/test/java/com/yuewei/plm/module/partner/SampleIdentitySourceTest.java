package com.yuewei.plm.module.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SampleIdentitySourceTest {
 @Test void largePreviewReadsEachStyleOnceButRetainsEveryVersionCheckAndDoesNotCacheAcrossRequests(){
  var jdbc=mock(JdbcTemplate.class);var access=mock(PilotAccessService.class);when(access.actor()).thenReturn(new CurrentUser(1L,"qa","工程",false));var store=spy(new PartnerStore(jdbc,new ObjectMapper(),access,mock(OperationLogService.class)));
  var samples=new PartnerSampleService(store,mock(PartnerProductService.class),mock(PartnerMaterialService.class));var catalogue=mock(PartnerCatalogStyles.class);ReflectionTestUtils.setField(samples,"catalogStyles",catalogue);
  doReturn(Map.of("id",5L,"version",7L,"purpose","my_selection")).when(store).load(5L,"selection",false);
  String sql="select row_id::text,row_version,sort_order,data::text,confirmed";
  when(jdbc.queryForList(startsWith(sql),eq(5L),anyString())).thenAnswer(call->List.of(Map.of("row_id",call.getArgument(2),"row_version",2L,"sort_order",0,"data","{}","confirmed",true)));
  var phones=java.util.stream.LongStream.rangeClosed(1,62).mapToObj(id->Map.<String,Object>of("phoneModelId",id,"displayName","Phone "+id)).toList();
  when(catalogue.receiptSnapshot(eq(5L),anyString(),eq(false))).thenReturn(Map.of("catalogStyleId",7L,"name","合成款","phones",phones,"phoneModelIds",java.util.stream.LongStream.rangeClosed(1,62).boxed().toList(),"receiptColorMode","at_receipt","selectedPairs",List.of(),"receiptColors",List.of(Map.of("id",8L,"name","黑色"))));
  var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<1709;i++)rows.add(new LinkedHashMap<>(Map.of("clientRowId","local-"+i,"rowId",UUID.nameUUIDFromBytes(("style-"+(i%28)).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),"rowVersion",2L,"phoneModelId",i/28+1L,"colorName","黑色")));
  var out=samples.receiptPreview(5L,Map.of("version",7L,"rows",rows));assertThat(PartnerValues.maps(out.get("rows"))).hasSize(1709);verify(catalogue,times(28)).receiptSnapshot(eq(5L),anyString(),eq(false));verify(jdbc,times(28)).queryForList(startsWith(sql),eq(5L),anyString());
  rows.get(28).put("rowVersion",3L);out=samples.receiptPreview(5L,Map.of("version",7L,"rows",rows));assertThat(PartnerValues.maps(out.get("rows")).get(28).get("messages").toString()).contains("选样资料已更新");verify(catalogue,times(56)).receiptSnapshot(eq(5L),anyString(),eq(false));verify(store,never()).create(anyString(),anyString());
 }
 @Test void catalogueTypeCandidatesNeedNoInventedReceiptDatePositionOrKeeper(){
  var jdbc=mock(JdbcTemplate.class);var access=mock(PilotAccessService.class);when(access.actor()).thenReturn(new CurrentUser(1L,"qa","工程",false));var store=new PartnerStore(jdbc,new ObjectMapper(),access,mock(OperationLogService.class));var samples=new PartnerSampleService(store,mock(PartnerProductService.class),mock(PartnerMaterialService.class));
  var catalogue=mock(PartnerCatalogStyles.class);var supplier=mock(SupplierProfileService.class);ReflectionTestUtils.setField(samples,"catalogStyles",catalogue);ReflectionTestUtils.setField(samples,"suppliers",supplier);
  var source=new LinkedHashMap<String,Object>();source.putAll(Map.of("catalogStyleId",7L,"catalogStyleCode","CP-000007","supplierCode","SUP-A","name","实际目录款","phoneModelIds",List.of(12L),"phones",List.of(Map.of("phoneModelId",12L,"displayName","Phone A")),"selectedPairs",List.of(),"receiptColorMode","at_receipt","receiptColors",List.of(Map.of("id",3L,"name","供应商黑")),"configKey","结构版A"));
  when(catalogue.receiptSnapshot(5L,"source-row",false)).thenReturn(source);when(supplier.requireSupplier("SUP-A")).thenReturn(Map.of("status","active","needsReview",false));
  var result=samples.identitySource(Map.of("sourceType","catalog","selectionId",5L,"selectionRowId","source-row","phoneModelId",12L,"colorId",3L));assertThat(result).containsEntry("catalogStyleId",7L).containsEntry("phoneModelId",12L).containsEntry("colorId",3L);assertThat(result).doesNotContainKeys("custodian","receivedQty","receivedDate","location");verifyNoInteractions(jdbc);
 }

 @Test void receiptOptionsExposeTheAuthoritativeSelectedColoursBeforeAnyReceiptFactsAreFilled(){
  var jdbc=mock(JdbcTemplate.class);var access=mock(PilotAccessService.class);when(access.actor()).thenReturn(new CurrentUser(1L,"qa","工程",false));var store=spy(new PartnerStore(jdbc,new ObjectMapper(),access,mock(OperationLogService.class)));
  var samples=new PartnerSampleService(store,mock(PartnerProductService.class),mock(PartnerMaterialService.class));var catalogue=mock(PartnerCatalogStyles.class);ReflectionTestUtils.setField(samples,"catalogStyles",catalogue);
  String rowId="c91d7046-187f-46ba-969c-793e2f0caf23";
  doReturn(Map.of("id",5L,"version",7L,"purpose","my_selection")).when(store).load(5L,"selection",false);
  when(jdbc.queryForList(startsWith("select row_id::text,row_version,sort_order,data::text,confirmed"),eq(5L),eq(rowId))).thenReturn(List.of(Map.of("row_id",rowId,"row_version",2L,"sort_order",0,"data","{}","confirmed",true)));
  var source=Map.<String,Object>of("name","选样款","sampleColors",List.of("黑色","白色"),"sourceColors",List.of("原目录粉"),"receiptColors",List.of(Map.of("id",9,"name","供应商通用蓝")),"phones",List.of(Map.of("phoneModelId",12L,"displayName","Phone A")),"receiptColorMode","at_receipt","selectedPairs",List.of());
  when(catalogue.receiptSnapshot(5L,rowId,false)).thenReturn(source);
  var result=samples.receiptPreview(5L,Map.of("version",7L,"rows",List.of(Map.of("clientRowId","local-line","rowId",rowId,"rowVersion",2L,"sampleColors",List.of("不能冒充源选色")))));
  var row=com.yuewei.plm.module.partner.PartnerValues.maps(result.get("rows")).get(0);
  assertThat(row).containsEntry("sampleColors",List.of("黑色","白色")).containsEntry("sourceColors",List.of("原目录粉")).containsEntry("receiptColorMode","at_receipt").containsEntry("qty","");assertThat(result.get("canSave")).isEqualTo(false);
  verify(store,never()).create(anyString(),anyString());
 }
 @Test void historicalPairColoursAreExposedPerActualPhoneAndNeverAsAnUnassignedUnion(){
  var jdbc=mock(JdbcTemplate.class);var access=mock(PilotAccessService.class);when(access.actor()).thenReturn(new CurrentUser(1L,"qa","工程",false));var store=spy(new PartnerStore(jdbc,new ObjectMapper(),access,mock(OperationLogService.class)));
  var samples=new PartnerSampleService(store,mock(PartnerProductService.class),mock(PartnerMaterialService.class));var catalogue=mock(PartnerCatalogStyles.class);ReflectionTestUtils.setField(samples,"catalogStyles",catalogue);
  String rowId="c91d7046-187f-46ba-969c-793e2f0caf23";
  doReturn(Map.of("id",5L,"version",7L,"purpose","my_selection")).when(store).load(5L,"selection",false);
  when(jdbc.queryForList(startsWith("select row_id::text,row_version,sort_order,data::text,confirmed"),eq(5L),eq(rowId))).thenReturn(List.of(Map.of("row_id",rowId,"row_version",2L,"sort_order",0,"data","{}","confirmed",true)));
  var source=Map.<String,Object>of("name","历史款","sourceColors",List.of("不能套用的整款色"),"receiptColorMode","selected_pairs","selectedPairs",List.of(Map.of("phoneModelId",12,"colorId",8,"colorName","型号12黑"),Map.of("phoneModelId",13,"colorId",9,"colorName","型号13白")));
  when(catalogue.receiptSnapshot(5L,rowId,false)).thenReturn(source);
  var requested=List.of(Map.of("clientRowId","p12","rowId",rowId,"rowVersion",2L,"phoneModelId",12),Map.of("clientRowId","p13","rowId",rowId,"rowVersion",2L,"phoneModelId",13),Map.of("clientRowId","unassigned","rowId",rowId,"rowVersion",2L));
  var result=samples.receiptPreview(5L,Map.of("version",7L,"rows",requested));var rows=com.yuewei.plm.module.partner.PartnerValues.maps(result.get("rows"));
  assertThat(rows.get(0)).containsEntry("sourceColors",List.of("型号12黑")).containsEntry("receiptColorMode","selected_pairs");
  assertThat(rows.get(1)).containsEntry("sourceColors",List.of("型号13白"));assertThat(rows.get(2)).containsEntry("sourceColors",List.of());
  verify(store,never()).create(anyString(),anyString());
 }
}

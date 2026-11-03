package com.yuewei.plm.module.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.yuewei.plm.module.partner.PartnerValues.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SampleIdentityQueryTest {
 JdbcTemplate jdbc;PartnerStore store;SampleIdentityQuery query;boolean mexico=true,cached=false,basisOnly=false,missingSupplierProfile=false;String recordedSupplierName="",currentSupplierName="历史停用供方";final ObjectMapper json=new ObjectMapper();final List<String> sql=new ArrayList<>();
 @BeforeEach void setup(){
  jdbc=mock(JdbcTemplate.class);var access=mock(PilotAccessService.class);when(access.access()).thenAnswer(i->new PilotAccessService.Access(new CurrentUser(2L,"angel","Angel",false),mexico?"mexico":"domestic",List.of("task:arrival","partner:view","file:read"),false,true));when(access.actorId()).thenReturn(2L);when(access.visibleSqlClause(anyString(),anyString())).thenReturn("true");
  store=new PartnerStore(jdbc,json,access,mock(OperationLogService.class));query=new SampleIdentityQuery(store,"true");doAnswer(this::rows).when(jdbc).queryForList(anyString());doAnswer(this::rows).when(jdbc).queryForList(anyString(),any(Object[].class));
 }
 private List<Map<String,Object>> rows(InvocationOnMock call){
  String statement=call.getArgument(0);sql.add(statement);
  if(statement.contains("r.kind='sample_identity'")){var heads=List.of(head(1,10,12,21),head(2,10,13,21),head(3,11,14,22));if(statement.contains("r.record_id=?")){long id=number(call.getArgument(1));return heads.stream().filter(h->number(h.get("id"))==id).toList();}return heads;}
  if(statement.contains("from plm_supplier_profile"))return missingSupplierProfile?List.of():List.of(d("supplier_code","SUP-A","supplier_name",currentSupplierName,"short_code","PJ"));
  if(statement.contains("b.kind='sample'")){var batches=List.of(d("id",101L,"code","SP-000101","version",4L,"sample_identity_id",1L,"data","{}"),d("id",102L,"code","SP-000102","version",1L,"sample_identity_id",2L,"data","{}"),d("id",103L,"code","SP-000103","version",1L,"sample_identity_id",3L,"data","{}"));if(cached)for(var b:batches)b.put("data",encode(json,d("balances",d("positions",List.of(d("name","mx","good",7,"retained",0,"damaged",0,"onhand",7,"available",7)),"loans",List.of(),"transits",List.of()))));return batches;}
  if(statement.contains("from plm_partner_event"))return List.of(event(1,101,"receive",d("qty",3,"location","cn")),event(2,101,"receive",d("qty",5,"location","MX")),event(3,101,"loan",d("qty",2,"location","mx","person","当地借用人","due","2026-11-10")),event(4,101,"dispatch",d("qty",1,"location","cn","shipmentId",501,"shipmentRowId","one","destination","MX")),event(5,102,"receive",d("qty",1,"location","mx")),event(6,103,"receive",d("qty",9,"location","cn")));
  if(statement.contains("join plm_attachment"))return List.of(d("record_id",101L,"id",901L,"name","实际照片.png","type","image/png"));
  if(statement.contains("kind='sample_shipment'"))return List.of(d("record_id",501L,"direction","mexico_outbound"));
  return List.of();
 }
 private Map<String,Object> head(long id,long style,long phone,long color){var value=d("name","真实款式","catalogStyleId",style,"supplierCode","SUP-A","phoneModelId",phone,"colorId",color,"priceCents",999,"sourceSnapshot",d("private","CN source"));if(!recordedSupplierName.isEmpty())value.put("supplierName",recordedSupplierName);if(basisOnly){value.remove("catalogStyleId");value.remove("colorId");value.put("identityBasis",d("catalogStyleId",style,"colorId",color,"technicalMaterial","private-basis"));}return d("id",id,"code","SY-"+id,"name","真实款式","status","active","version",1L,"data",encode(json,value));}
 private Map<String,Object> event(long id,long batch,String action,Map<String,Object> data){data.put("date","2026-11-03");return d("id",id,"record_id",batch,"action",action,"data",encode(json,data));}
 private static Map<String,Object> d(Object... values){var out=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
 @Test void groupedLocalInventorySeparatesBothCountriesInsideOneSpAndKeepsOnlyLocalLoanAndInboundQuantity(){
  var result=query.page(Map.of("groupBy","style"));assertThat(result).containsEntry("totalElements",1L).containsEntry("totalSamples",2);var group=maps(result.get("content")).get(0);
  assertThat(group).containsEntry("sampleCount",2).containsEntry("phoneCount",2).containsEntry("colorCount",1).containsEntry("mxOnhand",4L).doesNotContainKey("cnOnhand");
  assertThat(map(group.get("balances"))).containsEntry("onhand",4L).containsEntry("borrowed",2L).containsEntry("inTransit",1L);assertThat(maps(group.get("positions"))).allSatisfy(p->assertThat(p).containsEntry("site","MX"));
  assertThat(result.toString()).doesNotContain("sourceSnapshot","CN source","priceCents","destination","receiverUserId");assertThat(sql).hasSizeLessThanOrEqualTo(8);assertThat(sql.stream().filter(s->s.startsWith("select event_id as id,record_id,action,data::text from plm_partner_event ")).count()).isEqualTo(1);
 }
 @Test void supplierShortNameSearchAndGroupExpansionFilterBeforePagination(){
  var grouped=query.page(Map.of("groupBy","style","keyword","pj"));String key=text(maps(grouped.get("content")).get(0).get("groupKey"));var detail=query.page(Map.of("groupKey",key,"page","2","size","1"));assertThat(detail).containsEntry("totalElements",2L);assertThat(maps(detail.get("content"))).hasSize(1);assertThat(query.options(Map.of("keyword","PJ")).toString()).contains("历史停用供方","SUP-A");
 }
 @Test void supplierRenameUsesCurrentProfileForDisplayAndOptionsWhileMainSearchRetainsTheRecordedName(){
  recordedSupplierName="历史旧供方";currentSupplierName="当前新供方";
  for(String keyword:List.of("当前新供方","PJ","历史旧供方")){var result=query.page(Map.of("groupBy","style","keyword",keyword));assertThat(result).containsEntry("totalElements",1L);assertThat(maps(result.get("content")).get(0)).containsEntry("supplierCode","SUP-A").containsEntry("supplierName","当前新供方");}
  assertThat(query.options(Map.of("keyword","当前新供方")).toString()).contains("SUP-A","当前新供方");assertThat(query.options(Map.of("keyword","PJ")).toString()).contains("SUP-A","当前新供方");
  verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
 @Test void missingSupplierProfileKeepsTheHistoricalNameWithoutAllocatingOrRenamingAnyIdentity(){
  recordedSupplierName="仅存的历史供方";missingSupplierProfile=true;var result=query.page(Map.of("groupBy","style","keyword","仅存的历史供方"));assertThat(result).containsEntry("totalElements",1L);assertThat(maps(result.get("content")).get(0)).containsEntry("supplierCode","SUP-A").containsEntry("supplierName","仅存的历史供方");assertThat(query.options(Map.of("keyword","历史供方")).toString()).contains("SUP-A","仅存的历史供方");verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
 @Test void localUnlocatedAndForeignLocationFilterDoNotTurnBorrowedOrTransitIntoStoredPieces(){
  var unlocated=query.page(Map.of("unlocated","true"));assertThat(unlocated).containsEntry("totalSamples",2);for(var row:maps(unlocated.get("content")))assertThat(map(row.get("balances"))).containsEntry("borrowed",0L).containsEntry("inTransit",0L);
  assertThat(query.page(Map.of("locationId","999"))).containsEntry("totalElements",0L);assertThatThrownBy(()->query.page(Map.of("site","CN"))).hasMessageContaining("仅限墨西哥");
 }
 @Test void stableIdentityGroupingNeverUsesMatchingDisplayNames(){
  assertThat(SampleIdentityQuery.groupKey(d("sampleIdentityId",1,"name","同名"))).isNotEqualTo(SampleIdentityQuery.groupKey(d("sampleIdentityId",2,"name","同名")));
  assertThat(SampleIdentityQuery.groupKey(d("sampleIdentityId",1,"catalogStyleId",10,"supplierCode","A"))).isNotEqualTo(SampleIdentityQuery.groupKey(d("sampleIdentityId",2,"catalogStyleId",10,"supplierCode","B")));
  assertThat(SampleIdentityQuery.groupKey(d("sampleIdentityId",1,"productId",10,"supplierCode","A"))).isEqualTo(SampleIdentityQuery.groupKey(d("sampleIdentityId",2,"productId",10,"supplierCode","A")));
 }
 @Test void missingHeaderIdsUseValidatedBasisScalarsWithoutReturningTheBasis(){
  basisOnly=true;var local=query.page(Map.of("groupBy","style"));assertThat(local).containsEntry("totalElements",1L);assertThat(maps(local.get("content")).get(0)).containsEntry("catalogStyleId",10L).containsEntry("colorCount",1).containsEntry("unconfirmedColorCount",0);assertThat(local.toString()).doesNotContain("identityBasis","private-basis");
  for(var row:maps(query.page(Map.of()).get("content")))assertThat(row).containsEntry("catalogStyleId",10L).containsEntry("colorId",21L).doesNotContainKey("identityBasis");
  mexico=false;assertThat(query.page(Map.of()).toString()).doesNotContain("identityBasis","private-basis");
 }
 @Test void legacyNamedColoursAreReportedSeparatelyWithExactDisplayOnlyDeduplication(){
  var rows=List.of(d("groupKey","one","name","款式","colorId",21L,"colorName","Blue"),d("groupKey","one","name","款式","colorId",21L,"colorName","Blue"),d("groupKey","one","name","款式","colorName","Blue"),d("groupKey","one","name","款式","colorId",0L,"colorName","Blue"),d("groupKey","one","name","款式","colorName","blue"),d("groupKey","one","name","款式","colorName"," Blue"),d("groupKey","one","name","款式","colorName"," "));
  var grouped=SampleIdentityQuery.groups(rows,false);assertThat(grouped).singleElement().satisfies(group->assertThat(group).containsEntry("colorCount",1).containsEntry("unconfirmedColorCount",3));assertThat(rows.get(2)).doesNotContainKey("colorId");
 }
 @Test void existingDerivedBalancesAvoidReplayingAllHistoryForEachPage(){cached=true;var result=query.page(Map.of("groupBy","style"));assertThat(result).containsEntry("totalSamples",3);assertThat(sql).noneMatch(s->s.startsWith("select event_id as id,record_id,action,data::text from plm_partner_event "));}
 @Test void narrowPhotoAuthorizationChecksBothTheLocalSampleAndTheExactPhotoReference(){var registry=new SampleIdentityRegistry(store);registry.requirePhoto(1,901);assertThatThrownBy(()->registry.requirePhoto(1,902)).hasMessageContaining("照片不存在或未获授权");assertThatThrownBy(()->registry.requirePhoto(3,901)).hasMessageContaining("主码不存在或未获授权");}
 @Test void listPhotosAreOnlyImagePurposePhotoAndCannotBeCostOrFinancialReferences(){query.page(Map.of());assertThat(sql.stream().filter(s->s.contains("join plm_attachment")).findFirst().orElseThrow()).contains("pf.purpose='photo'","a.content_type like 'image/%'","'financial','cooperation_cost'");}
}

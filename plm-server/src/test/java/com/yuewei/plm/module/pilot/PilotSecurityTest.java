package com.yuewei.plm.module.pilot;
import com.fasterxml.jackson.databind.ObjectMapper;import com.yuewei.plm.common.exception.*;import com.yuewei.plm.common.security.*;import com.yuewei.plm.module.auth.service.TokenSessionService;
import java.util.*;import org.junit.jupiter.api.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.test.web.servlet.*;import org.springframework.test.web.servlet.setup.MockMvcBuilders;import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PilotSecurityTest {
 JdbcTemplate jdbc;ObjectMapper json=new ObjectMapper().findAndRegisterModules();PilotAccessService access;MockMvc mvc;String role;List<String> permissions;boolean allObjects,pilot,active;
 @BeforeEach void start(){jdbc=mock(JdbcTemplate.class);role="supplier";permissions=PilotAccessService.ROLE_PERMISSIONS.get(role);allObjects=false;pilot=true;active=true;
  when(jdbc.queryForList(anyString(),eq(7L))).thenAnswer(i->{String sql=i.getArgument(0);if(sql.contains("from sys_user"))return active?List.of(Map.of("user_id",7L,"username","real-supplier","display_name","真实供应商","all_permissions",false)):List.of();return pilot?List.of(Map.of("role_code",role,"permissions",json.writeValueAsString(permissions),"all_objects",allObjects)):List.of();});
  when(jdbc.queryForObject(anyString(),eq(Long.class),anyLong())).thenAnswer(i->((Number)i.getArgument(2)).longValue()==1L?1L:0L);
  access=new PilotAccessService(jdbc,json);CurrentUserContext.set(new CurrentUser(7L,"forged-admin","冒用",true));
  mvc=MockMvcBuilders.standaloneSetup(new Fixture()).addInterceptors(new PilotSecurityInterceptor(access)).setControllerAdvice(new GlobalExceptionHandler(),new PilotMoneyResponseAdvice(access,json),new PilotMoneyRequestAdvice(access,json)).build();
 }
 @AfterEach void clear(){CurrentUserContext.clear();}
 @Test void masterDataReadonlyCannotWriteEvenWithAllObjectDomesticGrant()throws Exception{
  role="domestic";allObjects=true;permissions=List.of("product:view","inventory:view","collaboration:view");
  mvc.perform(get("/api/v1/code-items")).andExpect(status().isOk());
  mvc.perform(get("/api/v1/inventories")).andExpect(status().isOk());
  mvc.perform(post("/api/v1/code-items")).andExpect(status().isForbidden());
  mvc.perform(put("/api/v1/code-items/1")).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/inventories/1/actions/delete")).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/products")).andExpect(status().isForbidden());
  mvc.perform(patch("/api/v1/products/1/basic-info")).andExpect(status().isForbidden());
 }
 @Test void engineeringAssignmentAllowsMasterWritesButDoesNotGrantOtherPermissions()throws Exception{
  role="domestic";allObjects=false;permissions=List.of("product:view","inventory:view","collaboration:view");
  when(jdbc.queryForObject(contains("plm_collaboration_assignment"),eq(Long.class),eq(7L))).thenReturn(1L);
  mvc.perform(post("/api/v1/code-items")).andExpect(status().isOk());
  mvc.perform(post("/api/v1/inventories")).andExpect(status().isOk());
  permissions=List.of("collaboration:view");
  mvc.perform(post("/api/v1/inventories")).andExpect(status().isForbidden());
  role="supplier";permissions=List.of("product:view","inventory:view","collaboration:view");
  mvc.perform(get("/api/v1/code-items")).andExpect(status().isForbidden());
 }
 @Test void quoteHistoryAndConfirmBaselineDoNotLeakOrRequireWritePermission() throws Exception {
  var node=json.readTree("{\"history\":[{\"fieldName\":\"quoteBasis\",\"before\":\"USD 900\",\"after\":\"USD 1000\",\"reason\":\"USD 1000\"}],\"quotes\":[{\"currency\":\"USD\",\"minAmount\":900}],\"stockState\":\"pending\"}");
  PilotMoneyResponseAdvice.redact(node);assertThat(node.has("quotes")).isFalse();assertThat(node.path("history").get(0).has("before")).isFalse();assertThat(node.path("history").get(0).has("reason")).isFalse();assertThat(node.path("stockState").asText()).isEqualTo("pending");
  role="domestic";permissions=List.of("collaboration:view","finance:confirm");
  String payload="{\"changes\":[{\"field\":\"quoteConfirmed\",\"before\":false,\"after\":true}],\"expectedValues\":{\"quoteAmount\":0,\"quoteCurrency\":\"USD\",\"quoteBasis\":\"真实厂家报价\"}}";
  mvc.perform(post("/api/v1/collaboration-plans/items/1/patch").contentType("application/json").content(payload)).andExpect(status().isOk());
  permissions=List.of("collaboration:view","finance:write");mvc.perform(post("/api/v1/collaboration-plans/items/1/patch").contentType("application/json").content(payload)).andExpect(status().isForbidden());
 }
 @RestController static class Fixture{
  @RequestMapping("/api/v1/**") Map<String,Object> any(){return Map.of("safe","visible","amount",999,"nested",Map.of("projectName","A","unitCost",8,"phonePrice",12));}
  @PostMapping("/api/v1/boms/{id}/items") Map<String,Object> bomItem(@RequestBody com.yuewei.plm.module.bom.dto.ProductBomItemDTO dto){return Map.of("itemName",Objects.toString(dto.getItemName(),""));}
  @PostMapping("/api/v1/collaboration-plans/items/{id}/patch") Map<String,Object> patch(@RequestBody Map<String,Object> dto){return dto;}
 }
 @Test void scopedFileBrowserUsesExistingReadPermissionWithoutOpeningExternalOrWriteAccess()throws Exception{
  role="domestic";permissions=List.of("file:read");
  mvc.perform(get("/api/v1/file-center/reference-options/page")).andExpect(status().isOk());
  mvc.perform(get("/api/v1/file-center/reference-options/phones")).andExpect(status().isOk());
  mvc.perform(post("/api/v1/file-center/reference-options/page")).andExpect(status().isForbidden());
  permissions=List.of("project:view");mvc.perform(get("/api/v1/file-center/reference-options/page")).andExpect(status().isForbidden());
  role="supplier";permissions=List.of("file:read");mvc.perform(get("/api/v1/file-center/reference-options/page")).andExpect(status().isForbidden());
 }
 @Test void partnerPhoneSelectionDoesNotGrantReferenceMaintenanceOrHistory()throws Exception{
  role="domestic";permissions=List.of("partner:view");
  mvc.perform(get("/api/v1/molds/reference-data/phone-models")).andExpect(status().isOk());
  mvc.perform(get("/api/v1/molds/reference-data/phone-models/options")).andExpect(status().isOk());
  mvc.perform(get("/api/v1/molds/reference-data/brands")).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/molds/reference-data/phone-models/1/history")).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/molds/reference-data/phone-models")).andExpect(status().isForbidden());
  permissions=List.of("customer:view");mvc.perform(get("/api/v1/molds/reference-data/phone-models")).andExpect(status().isForbidden());
  role="supplier";permissions=List.of("partner:view");mvc.perform(get("/api/v1/molds/reference-data/phone-models")).andExpect(status().isForbidden());
 }
 @Test void scopedSelectionCollectionsAndExportsUsePartnerReadWithoutGrantingExternalAccess()throws Exception{
  role="domestic";permissions=List.of("partner:view");allObjects=false;
  mvc.perform(get("/api/v1/partner-selections")).andExpect(status().isOk());
  mvc.perform(get("/api/v1/partner-selections/1/rows")).andExpect(status().isOk());
  mvc.perform(get("/api/v1/partner-selections/1/export")).andExpect(status().isOk());
  permissions=List.of();mvc.perform(get("/api/v1/partner-selections")).andExpect(status().isForbidden());
  permissions=List.of("partner:view");for(String external:List.of("supplier","mexico")){role=external;mvc.perform(get("/api/v1/partner-selections/1/rows")).andExpect(status().isForbidden());}
 }
 @Test void supplierCatalogHasAnExactDomesticReadBoundaryForEngineersAndReadonlyUsers()throws Exception{
  role="domestic";allObjects=false;
  for(var allowed:List.of(List.of("partner:view","partner:write"),List.of("partner:view"))){permissions=allowed;
   mvc.perform(get("/api/v1/partner-catalog/suppliers")).andExpect(status().isOk());
   mvc.perform(get("/api/v1/partner-catalog/styles")).andExpect(status().isOk());
   mvc.perform(get("/api/v1/partner-catalog/styles/selection/1/row")).andExpect(status().isOk());
  }
  mvc.perform(get("/api/v1/partner-catalogue/suppliers")).andExpect(status().isForbidden());
  permissions=List.of();mvc.perform(get("/api/v1/partner-catalog/suppliers")).andExpect(status().isForbidden());
  permissions=List.of("partner:view");for(String external:List.of("supplier","mexico")){role=external;mvc.perform(get("/api/v1/partner-catalog/suppliers")).andExpect(status().isForbidden());}
 }
 @Test void sidebarFeatureFlagReadsDoNotOpenMarketWatchDataOrWrites()throws Exception{
  role="domestic";permissions=List.of("collaboration:view");allObjects=false;
  mvc.perform(get("/api/v1/market-watch/config")).andExpect(status().isOk());
  mvc.perform(post("/api/v1/market-watch/config")).andExpect(status().isForbidden());
  mvc.perform(head("/api/v1/market-watch/config")).andExpect(status().isForbidden());
  for(String path:List.of("/api/v1/market-watch/configuration","/api/v1/market-watch/config/extra","/api/v1/market-watch/summary","/api/v1/market-watch/items"))mvc.perform(get(path)).andExpect(status().isForbidden());
  permissions=List.of("partner:view");mvc.perform(get("/api/v1/market-watch/config")).andExpect(status().isForbidden());
  permissions=List.of("collaboration:view");for(String external:List.of("supplier","mexico")){role=external;mvc.perform(get("/api/v1/market-watch/config")).andExpect(status().isForbidden());}
 }
 @Test void identityComesFromLiveAccountNotClaimedRoleAndDisabledSessionsFail(){assertThat(access.actor().username()).isEqualTo("real-supplier");assertThat(access.actor().allPermissions()).isFalse();active=false;assertThatThrownBy(access::actor).isInstanceOf(BusinessException.class).hasMessageContaining("失效");}
 @Test void supplierCannotEscapeThroughOldCollectionsOrUnassignedDownloads()throws Exception{
  mvc.perform(get("/api/v1/molds")).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/file-center/attachments")).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/attachments/2/download")).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/attachments/1/download")).andExpect(status().isOk()).andExpect(jsonPath("$.amount").doesNotExist());
 }
 @Test void mexicoCannotWriteBomOrFileEvenWithAssignedObjects()throws Exception{role="mexico";permissions=PilotAccessService.ROLE_PERMISSIONS.get(role);mvc.perform(post("/api/v1/boms/1/publish")).andExpect(status().isForbidden());mvc.perform(post("/api/v1/attachments/1/versions")).andExpect(status().isForbidden());mvc.perform(get("/api/v1/pilot/tasks")).andExpect(status().isOk());}
 @Test void allObjectDomesticGrantsDoNotBypassCustomPublishPermission()throws Exception{role="domestic";allObjects=true;permissions=List.of("product:view","project:view","file:read","bom:write","documents:write");mvc.perform(post("/api/v1/boms/1/publish")).andExpect(status().isForbidden());mvc.perform(post("/api/v1/attachments/1/release")).andExpect(status().isForbidden());mvc.perform(post("/api/v1/attachments/1/versions")).andExpect(status().isOk());}
 @Test void financialActionsRemainIndependentAndLegacyUsersDoNotInheritMoney()throws Exception{role="finance";allObjects=true;permissions=List.of("finance:confirm");mvc.perform(get("/api/v1/development/budgets")).andExpect(status().isForbidden());mvc.perform(post("/api/v1/development/budgets/1/confirm")).andExpect(status().isOk());mvc.perform(get("/api/v1/development/export")).andExpect(status().isForbidden());pilot=false;mvc.perform(get("/api/v1/projects/1/cost-summary")).andExpect(status().isForbidden());mvc.perform(get("/api/v1/collaboration-plans")).andExpect(status().isOk());}
 @Test void assignedDomesticAccountsCannotUseUnscopedLegacyCollections()throws Exception{role="domestic";permissions=List.of("project:view","product:view");mvc.perform(get("/api/v1/projects")).andExpect(status().isForbidden());mvc.perform(get("/api/v1/projects/1")).andExpect(status().isOk());mvc.perform(get("/api/v1/projects/2")).andExpect(status().isForbidden());mvc.perform(get("/api/v1/boms/1")).andExpect(status().isOk());mvc.perform(get("/api/v1/boms/2")).andExpect(status().isForbidden());verify(jdbc).queryForObject(contains("from plm_product_bom"),eq(Long.class),eq(1L));}
 @Test void assignedDomesticAccountsCanUseOnlyCollectionsWithServerSideObjectFiltering()throws Exception{
  role="domestic";permissions=List.of("collaboration:view");
  mvc.perform(get("/api/v1/collaboration-plans")).andExpect(status().isOk());
  mvc.perform(get("/api/v1/collaboration-plans/options")).andExpect(status().isOk());
  mvc.perform(get("/api/v1/molds")).andExpect(status().isOk());
  mvc.perform(post("/api/v1/collaboration-plans/batch-save")).andExpect(status().isOk());
  mvc.perform(get("/api/v1/projects")).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/collaboration-plans/imports/token/commit")).andExpect(status().isForbidden());
 }
 @Test void oldBomDtoAndUnchangedMoneyPatchesCannotBypassIndependentFinanceWrite()throws Exception{
  role="domestic";permissions=List.of("product:view","bom:write","collaboration:view");
  mvc.perform(post("/api/v1/boms/1/items").contentType("application/json").content("{\"itemName\":\"PC\",\"unitCost\":99}")).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/boms/1/items").contentType("application/json").content("{\"itemName\":\"PC\",\"unitCost\":0}")).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/boms/1/costs/recalculate").contentType("application/json").content("[]")).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/collaboration-plans/items/1/patch").contentType("application/json").content("{\"changes\":[{\"field\":\"unitCost\",\"before\":8,\"after\":8}]}")).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/boms/1/items").contentType("application/json").content("{\"itemName\":\"PC\",\"quantity\":2,\"unitCost\":null}")).andExpect(status().isOk());
  mvc.perform(post("/api/v1/collaboration-plans/items/1/patch").contentType("application/json").content("{\"phonePrice\":1999,\"priceCurrency\":\"MXN\",\"changes\":[{\"field\":\"phonePrice\",\"after\":1999}]}")).andExpect(status().isOk()).andExpect(jsonPath("$.phonePrice").value(1999)).andExpect(jsonPath("$.priceCurrency").value("MXN"));
  pilot=false;mvc.perform(post("/api/v1/boms/1/items").contentType("application/json").content("{\"itemName\":\"PC\",\"unitCost\":99}")).andExpect(status().isForbidden());
 }
 @Test void scopeSqlRejectsUntrustedIdentifiersAndOnlyBoundPartnerFilesInherit(){assertThat(access.visibleSqlClause("file","a.attachment_id")).contains("entity_type='file'", "plm_partner_file pf", "pf.attachment_id=a.attachment_id", "purpose='financial'");assertThat(access.visibleSqlClause("mold","ms.mold_set_id")).contains("entity_type='item'");assertThatThrownBy(()->access.visibleSqlClause("mold","id);drop table sys_user")).isInstanceOf(IllegalArgumentException.class);}
 @Test void legacyNestedMoneyIsRemovedWithoutHidingOperationalFields(){var node=json.createObjectNode();node.put("styleName","A");node.put("phonePrice",1999);node.put("priceCurrency","MXN");node.putObject("detail").put("unitCost",99).put("amount",8).put("status","making");PilotMoneyResponseAdvice.redact(node);assertThat(node.path("detail").has("unitCost")).isFalse();assertThat(node.path("detail").has("amount")).isFalse();assertThat(node.path("detail").path("status").asText()).isEqualTo("making");assertThat(node.path("phonePrice").asInt()).isEqualTo(1999);assertThat(node.path("priceCurrency").asText()).isEqualTo("MXN");}
 @Test void ssoMissingConfigurationAndUnmappedUsersNeverCreateSessions(){var tokens=mock(TokenSessionService.class);var sso=new PilotDingTalkSsoService(jdbc,json,tokens,access);assertThatThrownBy(()->sso.login("temporary-code")).isInstanceOf(BusinessException.class).hasMessageContaining("尚未配置");when(jdbc.queryForList(anyString(),eq("unmapped"))).thenReturn(List.of());assertThatThrownBy(()->sso.mappedSession("unmapped")).isInstanceOf(BusinessException.class).hasMessageContaining("未关联");verifyNoInteractions(tokens);}
 @Test void engineeringSupplierAmountsDoNotExpandLegacyFinancialAuthority(){
  var engineer=mock(PilotAccessService.class);when(engineer.hasPermission("partner:write")).thenReturn(true);when(engineer.canMaintainEngineeringData()).thenReturn(true);
  var supplierMethod=mock(org.springframework.core.MethodParameter.class);doReturn(com.yuewei.plm.module.partner.PartnerOperationsController.class).when(supplierMethod).getContainingClass();
  var legacyMethod=mock(org.springframework.core.MethodParameter.class);doReturn(Fixture.class).when(legacyMethod).getContainingClass();
  var input=new PilotMoneyRequestAdvice(engineer,json);var output=new PilotMoneyResponseAdvice(engineer,json);var body=Map.of("amount",100,"currency","MXN","name","供应商实际报价");var converter=org.springframework.http.converter.json.MappingJackson2HttpMessageConverter.class;
  assertThat(input.afterBodyRead(body,null,supplierMethod,Map.class,converter)).isSameAs(body);
  assertThatThrownBy(()->input.afterBodyRead(body,null,legacyMethod,Map.class,converter)).isInstanceOf(BusinessException.class);
  assertThat(output.beforeBodyWrite(body,supplierMethod,null,converter,null,null)).isSameAs(body);
  for(Class<?> owner:List.of(com.yuewei.plm.module.partner.PartnerSelectionController.class,com.yuewei.plm.module.partner.PartnerQuoteLinesController.class,com.yuewei.plm.module.partner.PartnerCatalogController.class)){
   doReturn(owner).when(supplierMethod).getContainingClass();var selection=Map.of("unitPrice","12.30","currency","","quoteVersion","V1","quoteId","1");
   assertThat(input.afterBodyRead(selection,null,supplierMethod,Map.class,converter)).isSameAs(selection);
   assertThat(output.beforeBodyWrite(selection,supplierMethod,null,converter,null,null)).isSameAs(selection);
  }
  var redacted=(com.fasterxml.jackson.databind.JsonNode)output.beforeBodyWrite(body,legacyMethod,null,converter,null,null);assertThat(redacted.has("amount")).isFalse();assertThat(redacted.has("currency")).isFalse();assertThat(redacted.path("name").asText()).isEqualTo("供应商实际报价");
  assertThat(PilotAccessService.ROLE_PERMISSIONS.get("overseas_sales")).contains("partner:listing","partner:delivery").doesNotContain("partner:write","finance:write","finance:read");
 }
 @Test void readonlyCatalogNeverGainsTheEngineeringCostException(){
  var reader=mock(PilotAccessService.class);when(reader.hasPermission("partner:view")).thenReturn(true);
  var method=mock(org.springframework.core.MethodParameter.class);doReturn(com.yuewei.plm.module.partner.PartnerCatalogController.class).when(method).getContainingClass();
  var input=new PilotMoneyRequestAdvice(reader,json);var output=new PilotMoneyResponseAdvice(reader,json);var body=Map.of("unitPrice","12.00","priceOptions",List.of(Map.of("label","磁吸款","unitPrice","15.00")),"name","供方原款");var converter=org.springframework.http.converter.json.MappingJackson2HttpMessageConverter.class;
  assertThatThrownBy(()->input.afterBodyRead(body,null,method,Map.class,converter)).isInstanceOf(BusinessException.class);
  var redacted=(com.fasterxml.jackson.databind.JsonNode)output.beforeBodyWrite(body,method,null,converter,null,null);assertThat(redacted.has("unitPrice")).isFalse();assertThat(redacted.has("priceOptions")).isFalse();assertThat(redacted.path("name").asText()).isEqualTo("供方原款");
 }
}

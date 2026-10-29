package com.yuewei.plm.module.pilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.config.AppProperties;
import com.yuewei.plm.common.exception.GlobalExceptionHandler;
import com.yuewei.plm.common.security.*;
import com.yuewei.plm.module.auth.service.TokenSessionService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real token filter + live-account access resolver + actual central interceptor, no admin bypass. */
class SampleRouteSecurityTest {
 MockMvc mvc;JdbcTemplate jdbc;PilotAccessService access;String role="domestic";List<String> permissions=List.of("partner:view");boolean active=true,pilot=true,allObjects=false;
 @BeforeEach void setup(){
  CurrentUserContext.clear();jdbc=mock(JdbcTemplate.class);var json=new ObjectMapper().findAndRegisterModules();
  when(jdbc.queryForList(anyString(),eq(7L))).thenAnswer(i->{String sql=i.getArgument(0);if(sql.contains("from sys_user"))return active?List.of(Map.of("user_id",7L,"username","actual-sample-reader","display_name","实际样品账号","all_permissions",false)):List.of();return pilot?List.of(Map.of("role_code",role,"permissions",json.writeValueAsString(permissions),"all_objects",allObjects)):List.of();});
  when(jdbc.queryForObject(anyString(),eq(Long.class),anyLong())).thenAnswer(i->((Number)i.getArgument(2)).longValue()==1?1L:0L);
  access=new PilotAccessService(jdbc,json);var tokens=mock(TokenSessionService.class);when(tokens.getCurrentUser(anyString())).thenAnswer(i->"valid".equals(i.getArgument(0))?Optional.of(new CurrentUser(7L,"forged-admin","冒用管理员声明",true)):Optional.empty());
  var properties=new AppProperties();properties.getSecurity().setEnabled(true);var filter=new SimpleTokenAuthenticationFilter(properties,tokens,json);
  mvc=MockMvcBuilders.standaloneSetup(new Fixture(access)).addFilters(filter).addInterceptors(new PilotSecurityInterceptor(access)).setControllerAdvice(new GlobalExceptionHandler()).build();
 }
 @AfterEach void clear(){CurrentUserContext.clear();}
 MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request){return request.header("Authorization","Bearer valid");}
 void ok(MockHttpServletRequestBuilder request)throws Exception{mvc.perform(auth(request)).andExpect(status().isOk()).andExpect(jsonPath("$.allPermissions").value(false));}
 void no(MockHttpServletRequestBuilder request)throws Exception{mvc.perform(auth(request)).andExpect(status().isForbidden());}
 @Test void anonymousAndExpiredTokensCannotReachAnyNewSurface()throws Exception{
  for(String path:List.of("/api/v1/sample-identities","/api/v1/sample-shipments","/api/v1/sample-scan")){
   mvc.perform(get(path)).andExpect(status().isUnauthorized());mvc.perform(get(path).header("Authorization","Bearer expired")).andExpect(status().isUnauthorized());
  }
 }
 @Test void limitedDomesticReaderCanReadReviewedRoutesButCannotMutate()throws Exception{
  for(String path:List.of("/api/v1/sample-identities","/api/v1/sample-identities/1","/api/v1/sample-identities/resolve","/api/v1/sample-identities/candidates","/api/v1/sample-shipments","/api/v1/sample-shipments/1","/api/v1/sample-shipments/options","/api/v1/sample-scan"))ok(get(path));
  for(String path:List.of("/api/v1/sample-identities","/api/v1/sample-identities/1/actions/loan","/api/v1/sample-identities/batches/1/identity","/api/v1/sample-shipments","/api/v1/sample-shipments/1/actions/receive","/api/v1/sample-shipments/1/files"))no(post(path));no(put("/api/v1/sample-shipments/1"));no(delete("/api/v1/sample-shipments/1"));
 }
 @Test void samplesWriteIsRequiredEvenWhenAllObjectsAreGranted()throws Exception{
  allObjects=true;no(post("/api/v1/sample-shipments/1/actions/dispatched"));permissions=List.of("partner:view","samples:write");
  for(String path:List.of("/api/v1/sample-identities","/api/v1/sample-identities/1/actions/loan","/api/v1/sample-identities/1/actions/return","/api/v1/sample-identities/batches/1/identity","/api/v1/sample-shipments","/api/v1/sample-shipments/1/actions/dispatched","/api/v1/sample-shipments/1/actions/tracking","/api/v1/sample-shipments/1/actions/receive","/api/v1/sample-shipments/1/actions/loss","/api/v1/sample-shipments/1/actions/cancel","/api/v1/sample-shipments/1/actions/undo","/api/v1/sample-shipments/1/files"))ok(post(path));ok(put("/api/v1/sample-shipments/1"));ok(delete("/api/v1/sample-shipments/1"));
 }
 @Test void mexicoHasOnlyAssignedReadAndReceiveEntryPoints()throws Exception{
  role="mexico";permissions=List.of("task:arrival");
  for(String path:List.of("/api/v1/sample-scan","/api/v1/sample-shipments","/api/v1/sample-shipments/1"))ok(get(path));ok(post("/api/v1/sample-shipments/1/actions/receive"));
  for(String path:List.of("/api/v1/sample-identities","/api/v1/sample-identities/1","/api/v1/sample-identities/candidates","/api/v1/sample-identities/resolve","/api/v1/sample-shipments/options","/api/v1/sample-shipments/1/files","/api/v1/sample-scan/extra"))no(get(path));
  for(String path:List.of("/api/v1/sample-identities","/api/v1/sample-shipments","/api/v1/sample-shipments/1/files","/api/v1/sample-shipments/1/actions/dispatched","/api/v1/sample-shipments/1/actions/tracking","/api/v1/sample-shipments/1/actions/loss","/api/v1/sample-shipments/1/actions/cancel","/api/v1/sample-shipments/1/actions/undo"))no(post(path));no(put("/api/v1/sample-shipments/1"));no(delete("/api/v1/sample-shipments/1"));
 }
 @Test void mexicoCannotUseAnotherPermissionOrAHeadRequestAsArrival()throws Exception{
  role="mexico";permissions=List.of("partner:view","samples:write");no(get("/api/v1/sample-scan"));no(get("/api/v1/sample-shipments"));no(post("/api/v1/sample-shipments/1/actions/receive"));permissions=List.of("task:arrival");no(head("/api/v1/sample-scan"));no(post("/api/v1/sample-scan"));
 }
 @Test void supplierAndOverseasSalesDoNotGainThisWorkflowEvenWithCustomDomesticPermissionNames()throws Exception{
  permissions=List.of("partner:view","samples:write","task:arrival");for(String external:List.of("supplier","overseas_sales")){role=external;no(get("/api/v1/sample-identities"));no(get("/api/v1/sample-shipments"));no(get("/api/v1/sample-scan"));no(post("/api/v1/sample-shipments/1/actions/receive"));}
 }
 @Test void unknownSubpathsAndMethodsRemainClosed()throws Exception{
  permissions=List.of("partner:view","samples:write");for(String path:List.of("/api/v1/sample-identities/1/secret","/api/v1/sample-shipments/options/private","/api/v1/sample-shipments/1/actions/receive/extra","/api/v1/sample-scan/private"))no(get(path));no(post("/api/v1/sample-identities/1/actions/print"));no(post("/api/v1/sample-shipments/1/actions/arbitrary"));no(patch("/api/v1/sample-shipments/1"));no(put("/api/v1/sample-identities/1"));
 }
 @Test void admittingAPathDoesNotGrantUnassignedObjectsAndLegacyHasNoAutomaticPartnerRead()throws Exception{
  ok(get("/api/v1/sample-shipments/1"));no(get("/api/v1/sample-shipments/2"));pilot=false;no(get("/api/v1/sample-identities"));no(get("/api/v1/sample-shipments"));
 }
 @RestController static class Fixture {
  private final PilotAccessService access;Fixture(PilotAccessService access){this.access=access;}
  @RequestMapping("/api/v1/**") Map<String,Object> safe(){return Map.of("allPermissions",access.actor().allPermissions());}
  @GetMapping("/api/v1/sample-shipments/{id:[0-9]+}") Map<String,Object> scoped(@PathVariable long id){if(!access.access().role().equals("mexico"))access.requirePartner(id);return safe();}
 }
}

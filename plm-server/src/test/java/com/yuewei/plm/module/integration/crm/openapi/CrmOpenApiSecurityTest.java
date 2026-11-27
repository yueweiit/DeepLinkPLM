package com.yuewei.plm.module.integration.crm.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.config.AppProperties;
import com.yuewei.plm.common.config.SecurityConfig;
import com.yuewei.plm.common.exception.GlobalExceptionHandler;
import com.yuewei.plm.common.filter.RequestContextFilter;
import com.yuewei.plm.common.security.SimpleTokenAuthenticationFilter;
import com.yuewei.plm.module.auth.service.TokenSessionService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Both real security chains coexist; no reliance on the old global security switch. */
class CrmOpenApiSecurityTest {
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    CrmOpenApiProperties properties;
    @BeforeEach void setup(){
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();
        properties=context.getBean(CrmOpenApiProperties.class);
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(new RequestContextFilter(),context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void close(){context.close();}
    @Test void crmRemainsAuthenticatedWithOldSecurityDisabled()throws Exception {
        mvc.perform(get("/api/integrations/v3/crm/phone-models")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/integrations/v3/crm/phone-models").header("Authorization","Bearer human-session")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/integrations/v3/crm/phone-models").header("Authorization","Bearer crm-test-token")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray()).andExpect(header().exists("X-Request-Id"));
    }
    @Test void oldLoginFilterDoesNotConsumeCrmToken()throws Exception {
        context.getBean(AppProperties.class).getSecurity().setEnabled(true);
        mvc.perform(get("/api/integrations/v3/crm/phone-models").header("Authorization","Bearer crm-test-token")).andExpect(status().isOk());
        verifyNoInteractions(context.getBean(TokenSessionService.class));
        mvc.perform(get("/api/v1/mold-schedules").header("Authorization","Bearer crm-test-token")).andExpect(status().isUnauthorized());
    }
    @Test void disabledApiAndMissingScopeAreClosedWithCrmErrors()throws Exception {
        properties.setEnabled(false);mvc.perform(get("/api/integrations/v3/crm/phone-models")).andExpect(status().isServiceUnavailable());
        properties.setEnabled(true);properties.setScopes(List.of("crm:mold-schedule:read"));
        mvc.perform(get("/api/integrations/v3/crm/phone-models").header("Authorization","Bearer crm-test-token")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({SecurityConfig.class,CrmOpenApiSecurityConfig.class,CrmOpenApiController.class,CrmApiErrors.class,GlobalExceptionHandler.class})
    static class Config {
        @Bean AppProperties app(){var p=new AppProperties();p.getSecurity().setEnabled(false);return p;}
        @Bean ObjectMapper json(){return new ObjectMapper().findAndRegisterModules();}
        @Bean TokenSessionService tokens(){return mock(TokenSessionService.class);}
        @Bean SimpleTokenAuthenticationFilter login(AppProperties app,TokenSessionService tokens,ObjectMapper json){return new SimpleTokenAuthenticationFilter(app,tokens,json);}
        @Bean CrmOpenApiProperties properties(){var p=new CrmOpenApiProperties();p.setEnabled(true);p.setServiceUserId(7);p.setSigningSecret("crm-test-secret-at-least-32-characters");p.setTokenDigests(List.of(CrmTokenCodec.hash("crm-test-token")));return p;}
        @Bean JdbcTemplate jdbc(){var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForList(anyString(),eq(7L))).thenReturn(List.of(Map.of("user_id",7L,"username","crm","display_name","CRM","all_permissions",false,"account_ready",true)));return jdbc;}
        @Bean CrmQueryService queries(){var query=mock(CrmQueryService.class);when(query.page(eq("PHONE_MODEL"),anyMap())).thenReturn(Map.of("items",List.of(),"hasMore",false));return query;}
        @Bean CrmScheduleDateService dates(){return mock(CrmScheduleDateService.class);}
        @Bean CrmSyncStore sync(){return mock(CrmSyncStore.class);}
    }
}

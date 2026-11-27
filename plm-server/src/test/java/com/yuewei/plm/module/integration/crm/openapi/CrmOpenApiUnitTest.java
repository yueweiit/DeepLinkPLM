package com.yuewei.plm.module.integration.crm.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrmOpenApiUnitTest {
    final ObjectMapper json=new ObjectMapper();
    CrmOpenApiProperties properties(){var p=new CrmOpenApiProperties();p.setEnabled(true);p.setServiceUserId(7);p.setSigningSecret("12345678901234567890123456789012");p.setTokenDigests(List.of(CrmTokenCodec.hash("test-only-high-entropy-token")));return p;}
    @Test void productsAndSkusAreSeparateAndDoNotGuessCodesOrLoseLeadingZeros(){
        var line=Map.<String,Object>of("product_id",35L,"product_code","MBA","product_name","亮甲2.0","product_type","product_line","is_sku",false);
        var project=Map.<String,Object>of("product_id",36L,"product_code","PRD-NEW","product_name","在研型号","product_type","model_variant","parent_product_id",35L,"is_sku",false);
        var sku=new java.util.HashMap<String,Object>();sku.putAll(Map.of("product_id",37L,"product_code","SOURCE-001","product_name","具体颜色SKU","product_type","model_variant","parent_product_id",35L,"is_sku",true));
        sku.putAll(Map.of("finished_product_code","NBA4030018501","phone_model_code","0185","color_code","01","master_data_status","inactive"));
        var result=CrmQueryService.splitProductAssociations(List.of(line,project,sku));
        assertThat(result.get("products")).hasSize(2).allSatisfy(p->assertThat(p).doesNotContainKey("skuCode"));
        assertThat(result.get("skus")).singleElement().satisfies(p->assertThat(p)
            .containsEntry("skuId","37").containsEntry("skuCode","NBA4030018501")
            .containsEntry("phoneModelCode","0185").containsEntry("colorCode","01")
            .containsEntry("parentProductId","35").containsEntry("masterDataStatus","inactive"));
        sku.put("finished_product_code",null);sku.put("phone_model_code",null);
        assertThat(CrmQueryService.splitProductAssociations(List.of(sku)).get("skus").get(0))
            .containsEntry("skuCode","SOURCE-001").containsEntry("phoneModelCode",null);
        assertThat(CrmQueryService.splitProductAssociations(List.of())).containsEntry("products",List.of()).containsEntry("skus",List.of());
    }
    @Test void onlyThreeDatesAreWritableAndNullIsDifferentFromAbsent(){
        var patch=CrmScheduleDateService.parse("{\"dates\":{\"plannedAcceptanceDate\":null},\"reason\":\"取消\",\"crmReference\":\"1\"}",json);
        assertThat(patch.dates()).containsOnlyKeys("plannedAcceptanceDate");assertThat(patch.dates().get("plannedAcceptanceDate").isNull()).isTrue();
        for(String dates:List.of("{}","{\"deliveryStatus\":\"delivered\"}","{\"plannedAcceptanceDate\":\"\"}","{\"actualMexicoArrivalDate\":\"2026-02-30\"}","{\"plannedProductionDate\":123}"))
            assertThatThrownBy(()->CrmScheduleDateService.parse("{\"dates\":"+dates+",\"reason\":\"r\",\"crmReference\":\"1\"}",json)).isInstanceOf(CrmApiException.class);
    }
    @Test void rejectDuplicateAndUnknownFieldsWithoutChangingGlobalMapper(){
        assertThatThrownBy(()->CrmScheduleDateService.parse("{\"dates\":{\"plannedAcceptanceDate\":null,\"plannedAcceptanceDate\":\"2026-11-09\"},\"reason\":\"r\",\"crmReference\":\"1\"}",json)).isInstanceOf(CrmApiException.class);
        assertThatThrownBy(()->CrmScheduleDateService.parse("{\"dates\":{\"plannedAcceptanceDate\":null},\"reason\":\"r\",\"crmReference\":\"1\",\"owner\":7}",json)).isInstanceOf(CrmApiException.class);
        assertThat(json.getFactory().isEnabled(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)).isFalse();
    }
    @Test void cursorIsSignedBoundToSubjectEpochAndExpiry(){
        var codec=new CrmTokenCodec(properties(),json);String token=codec.encode(Map.of("kind","change","subject","crm","epoch","e1","expires",Instant.now().plusSeconds(60).getEpochSecond(),"last",12));
        assertThat(codec.decode(token,"change","crm","e1").get("last").asInt()).isEqualTo(12);
        assertThatThrownBy(()->codec.decode(token+"x","change","crm","e1")).isInstanceOf(CrmApiException.class);
        assertThatThrownBy(()->codec.decode(token,"change","other","e1")).isInstanceOf(CrmApiException.class);
        assertThatThrownBy(()->codec.decode(token,"change","crm","e2")).isInstanceOfSatisfying(CrmApiException.class,e->assertThat(e.status()).isEqualTo(410));
    }
    @Test void canonicalRequestDoesNotDependOnJsonKeyOrder()throws Exception {
        assertThat(CrmScheduleDateService.canonical(json.readTree("{\"a\":null,\"b\":\"0185\"}"))).isEqualTo(CrmScheduleDateService.canonical(json.readTree("{\"b\":\"0185\",\"a\":null}")));
        assertThat(CrmScheduleDateService.canonical(json.readTree("{\"a\":null}"))).isNotEqualTo(CrmScheduleDateService.canonical(json.readTree("{}")));
    }
    @Test void filterFailsClosedWhenDisabledAndRejectsHumanToken()throws Exception {
        var p=properties();var jdbc=mock(JdbcTemplate.class);FilterChain chain=mock(FilterChain.class);var request=new MockHttpServletRequest("GET",CrmOpenApiSecurityConfig.PREFIX+"/phone-models");var response=new MockHttpServletResponse();
        p.setEnabled(false);new CrmOpenApiSecurityConfig.ServiceFilter(p,jdbc,json).doFilter(request,response,chain);assertThat(response.getStatus()).isEqualTo(503);verifyNoInteractions(chain,jdbc);
        p.setEnabled(true);request=new MockHttpServletRequest("GET",CrmOpenApiSecurityConfig.PREFIX+"/phone-models");request.addHeader("Authorization","Bearer human-session");response=new MockHttpServletResponse();
        new CrmOpenApiSecurityConfig.ServiceFilter(p,jdbc,json).doFilter(request,response,chain);assertThat(response.getStatus()).isEqualTo(401);verifyNoInteractions(chain,jdbc);
    }
    @Test void serviceFilterClearsThreadIdentity()throws Exception {
        var p=properties();var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),eq(7L))).thenReturn(List.of(Map.of("user_id",7L,"username","crm-service","display_name","CRM","all_permissions",false,"account_ready",true,"writer_ready",true)));
        var request=new MockHttpServletRequest("GET",CrmOpenApiSecurityConfig.PREFIX+"/phone-models");request.addHeader("Authorization","Bearer test-only-high-entropy-token");var response=new MockHttpServletResponse();
        try {new CrmOpenApiSecurityConfig.ServiceFilter(p,jdbc,json).doFilter(request,response,(r,s)->{assertThat(CurrentUserContext.get()).isPresent();assertThat(CrmServicePrincipal.from((jakarta.servlet.http.HttpServletRequest)r).userId()).isEqualTo(7);});assertThat(CurrentUserContext.get()).isEmpty();}finally{CurrentUserContext.clear();}
    }
    @Test void adminOrUnscopedServiceAccountsAreRejected()throws Exception {
        var p=properties();var jdbc=mock(JdbcTemplate.class);FilterChain chain=mock(FilterChain.class);
        for(var account:List.of(Map.of("all_permissions",true,"account_ready",true),Map.of("all_permissions",false,"account_ready",false))){
            when(jdbc.queryForList(anyString(),eq(7L))).thenReturn(List.of(new java.util.HashMap<String,Object>(account)));
            var r=new MockHttpServletRequest("GET",CrmOpenApiSecurityConfig.PREFIX+"/phone-models");r.addHeader("Authorization","Bearer test-only-high-entropy-token");var s=new MockHttpServletResponse();
            new CrmOpenApiSecurityConfig.ServiceFilter(p,jdbc,json).doFilter(r,s,chain);assertThat(s.getStatus()).isEqualTo(401);
        }
        verifyNoInteractions(chain);
    }
}

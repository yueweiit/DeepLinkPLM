package com.yuewei.plm.module.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.bom.service.ProductionConfirmationService;
import com.yuewei.plm.common.util.ProductCodeGenerator;
import com.yuewei.plm.module.code.service.CodeItemService;
import com.yuewei.plm.repository.ProductRepository;
import com.yuewei.plm.service.ProductService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.yuewei.plm.module.manufacturing.ManufacturingIdentityService;
import java.util.LinkedHashMap;

class ResearchProductCodingServiceTest {
    @Test void confirmedSharedMaterialReusesTheOriginalCodesWithoutCreatingAVariantSkuOrPrefix() {
        var jdbc=mock(JdbcTemplate.class);var products=mock(ProductRepository.class);var productService=mock(ProductService.class);
        var colors=mock(CodeItemService.class);var production=mock(ProductionConfirmationService.class);var productCodes=mock(ProductCodeGenerator.class);
        var access=mock(PilotAccessService.class);var identities=mock(ManufacturingIdentityService.class);
        var service=new ResearchProductCodingService(jdbc,new ObjectMapper(),products,productService,colors,production,productCodes,access);
        ReflectionTestUtils.setField(service,"manufacturingIdentities",identities);
        var relation=new LinkedHashMap<String,Object>(Map.of("status","reuse","sharedMaterial",true,"holeType","large","structureDescription","磁吸",
            "relationId",7L,"relationVersion",3L,"manufacturingProductId",100L,"manufacturingPhoneCode","1145",
            "businessPhone",Map.of("phoneModelId",18L,"phoneModelCode","1218","modelName","Apple iPhone 18 Pro Max")));
        when(identities.requireResolved(any())).thenReturn(relation);
        when(identities.resolvedSkus(relation)).thenReturn(List.of(Map.of("id",101L,"code","NFA4030114502","name","原1145黑色成品",
            "phoneModelCode","1145","colorCode","02","operationCode","4030","productSpecificCode","FA")));
        var result=service.provisionManufacturing(50,18,99L,"real-engineer",relation);
        assertThat(result).containsEntry("status","ready").containsEntry("manufacturingPhoneCode","1145").containsEntry("productSpecificCode","FA");
        assertThat(result.get("generatedProductCodes")).isEqualTo(List.of("NFA4030114502"));
        var row=((List<Map<String,Object>>)result.get("products")).get(0);
        assertThat(row).containsEntry("phoneModelId",18L).containsEntry("businessPhoneModelCode","1218").containsEntry("manufacturingPhoneCode","1145").containsEntry("productId",101L);
        verifyNoInteractions(jdbc,products,productService,colors,production,productCodes);
        verify(identities).requireResolved(new ManufacturingIdentityService.ResolveInput(50,18,"large","磁吸",7L,3L));
        relation.put("sharedMaterial",false);clearInvocations(identities);
        assertThatThrownBy(()->service.provisionManufacturing(50,18,99L,"real-engineer",relation)).hasMessageContaining("不能生成成品编码");
        verifyNoInteractions(identities);
    }
    @Test void aNewManufacturingRequestCannotGenerateAPrefixBeforeItsHoleSpecificationIsExplicit() {
        var jdbc=mock(JdbcTemplate.class);var products=mock(ProductRepository.class);var productService=mock(ProductService.class);
        var service=new ResearchProductCodingService(jdbc,new ObjectMapper(),products,productService,mock(CodeItemService.class),mock(ProductionConfirmationService.class),mock(ProductCodeGenerator.class),mock(PilotAccessService.class));
        assertThatThrownBy(()->service.provision(50,18,99L,"engineer",null,"")).hasMessageContaining("明确孔型");
        assertThatThrownBy(()->service.provision(50,18,99L,"engineer","other","")).hasMessageContaining("结构说明");
        verifyNoInteractions(jdbc,products,productService);
    }
    @Test void onlyAnExplicitFinalStateMakesAnOperationAvailableEvenWhenItsCodeIs4030() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
            Map.of("code", "4030", "name", "未确认用途", "finished_product_flag", false, "metadata", "{}"),
            Map.of("code", "4020", "name", "已有产品最终工序", "finished_product_flag", true, "metadata", "{}"),
            Map.of("code", "4050", "name", "已配终态", "finished_product_flag", false,
                "metadata", "{\"defaultProcessParamJson\":{\"materialStatusCode\":\"40\"}}")));
        var service = new ResearchProductCodingService(jdbc, new ObjectMapper(), mock(ProductRepository.class),
            mock(ProductService.class), mock(CodeItemService.class), mock(ProductionConfirmationService.class), mock(ProductCodeGenerator.class), mock(PilotAccessService.class));
        assertThat(service.operationOptions()).extracting(option -> option.get("code")).containsExactly("4020", "4050");
        assertThatThrownBy(() -> service.requireOperationCode("4030")).hasMessageContaining("已启用主数据");
        assertThat(service.requireOperationCode("4020")).isEqualTo("4020");
        assertThatThrownBy(() -> service.requireOperationCode("9999")).hasMessageContaining("已启用主数据");
    }
}

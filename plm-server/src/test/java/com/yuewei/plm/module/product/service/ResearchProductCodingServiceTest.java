package com.yuewei.plm.module.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

class ResearchProductCodingServiceTest {
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

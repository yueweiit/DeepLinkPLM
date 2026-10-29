package com.yuewei.plm.module.project.development;

import com.yuewei.plm.common.exception.GlobalExceptionHandler;
import com.yuewei.plm.module.attachment.service.DocumentReferenceBrowser;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EightStepClientContractTest {
    final ProductDevelopmentService service=mock(ProductDevelopmentService.class);
    final MockMvc mvc=MockMvcBuilders.standaloneSetup(new ProductDevelopmentController(mock(DocumentReferenceBrowser.class),service,
        mock(ProductResearchBusinessService.class),mock(com.yuewei.plm.service.ProductService.class)))
        .setControllerAdvice(new GlobalExceptionHandler()).build();
    final String workflow="{\"action\":\"launch_draft\",\"version\":1,\"data\":{\"stage\":2}}";

    @Test void oldPagesCannotWriteOrDeleteWithMissingOrObsoleteModelHeaders()throws Exception{
        mvc.perform(post("/api/v1/product-research/42/workflow").contentType(MediaType.APPLICATION_JSON).content(workflow))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message",containsString("流程已更新")));
        mvc.perform(post("/api/v1/product-research/42/workflow").header("X-PLM-Research-Model","four_phase").contentType(MediaType.APPLICATION_JSON).content(workflow))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/product-research/42").contentType(MediaType.APPLICATION_JSON).content("{\"productName\":\"透甲\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message",containsString("流程已更新")));
        mvc.perform(delete("/api/v1/product-research/42").param("version","1"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message",containsString("流程已更新")));
        verifyNoInteractions(service);
    }

    @Test void theCurrentPageHeaderReachesTheExistingWorkflowWriter()throws Exception{
        when(service.workflowAction(eq(42L),any())).thenReturn(Map.of("productId",42L));
        mvc.perform(post("/api/v1/product-research/42/workflow").header("X-PLM-Research-Model","eight_step").contentType(MediaType.APPLICATION_JSON).content(workflow))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.productId").value(42));
        verify(service).workflowAction(eq(42L),argThat(input->"launch_draft".equals(input.action())&&input.version()==1L));
    }

    @Test void readsAndReadOnlyReadinessAndCostPreviewRemainAvailableWithoutAWriteModelHeader()throws Exception{
        when(service.detail(42L)).thenReturn(Map.of("productId",42L));
        when(service.stageReadiness(eq(42L),any())).thenReturn(Map.of("ready",false));
        when(service.previewUnitCost(eq(42L),anyMap())).thenReturn(Map.of("complete",false));
        mvc.perform(get("/api/v1/product-research/42")).andExpect(status().isOk()).andExpect(jsonPath("$.data.productId").value(42));
        mvc.perform(post("/api/v1/product-research/42/stage-readiness").contentType(MediaType.APPLICATION_JSON).content("{\"stage\":2}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.ready").value(false));
        mvc.perform(post("/api/v1/product-research/42/unit-cost/preview").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.complete").value(false));
        verify(service,never()).workflowAction(anyLong(),any());verify(service,never()).deleteProjectDraft(anyLong(),anyLong());
    }
}

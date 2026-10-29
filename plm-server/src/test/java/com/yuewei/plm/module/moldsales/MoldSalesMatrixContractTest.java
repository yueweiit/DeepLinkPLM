package com.yuewei.plm.module.moldsales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MoldSalesMatrixContractTest {
    @Test void missingProductFilterIsAllButExplicitEmptyMeansNoProducts()throws Exception{
        var service=mock(MoldSalesService.class);var mvc=MockMvcBuilders.standaloneSetup(new MoldSalesController(service)).build();
        mvc.perform(get("/api/v1/mold-sales/matrix")).andExpect(status().isOk());verify(service).matrix(1,50,"","all",null,null,"sales");
        mvc.perform(get("/api/v1/mold-sales/matrix").param("productLineIds","")).andExpect(status().isOk());verify(service).matrix(1,50,"","all",null,List.of(),"sales");
        mvc.perform(get("/api/v1/mold-sales").param("productLineIds","19252,1").param("brandId","10")).andExpect(status().isOk());verify(service).page(1,50,"","all",null,null,"sales",10L,List.of(19252L,1L));
    }
    @Test void newReadEndpointsUseTheExistingDomesticPermissionGate(){
        var access=mock(PilotAccessService.class);var jdbc=mock(JdbcTemplate.class);doThrow(new IllegalStateException("no domestic access")).when(access).requireDomesticView();
        var service=new MoldSalesService(jdbc,new ObjectMapper(),access,mock(OperationLogService.class),mock(PlatformTransactionManager.class));
        try{assertThatThrownBy(service::options).hasMessage("no domestic access");assertThatThrownBy(()->service.matrix(1,50,"","all",null,null,"sales")).hasMessage("no domestic access");verifyNoInteractions(jdbc);}finally{service.close();}
    }
}

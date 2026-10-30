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
        mvc.perform(get("/api/v1/mold-sales/matrix")).andExpect(status().isOk());verify(service).matrix(1,50,"","all",null,null,"sales",null);
        mvc.perform(get("/api/v1/mold-sales/matrix").param("productLineIds","")).andExpect(status().isOk());verify(service).matrix(1,50,"","all",null,List.of(),"sales",null);
        mvc.perform(get("/api/v1/mold-sales").param("productLineIds","19252,1").param("brandId","10")).andExpect(status().isOk());verify(service).page(1,50,"","all",null,null,"sales",10L,List.of(19252L,1L),null);
    }
    @Test void namespacedHistoricalKeysRemainStringsAndDoNotBecomeItemIds()throws Exception{
        var service=mock(MoldSalesService.class);var mvc=MockMvcBuilders.standaloneSetup(new MoldSalesController(service)).build();
        mvc.perform(get("/api/v1/mold-sales/matrix").param("productKeys","product:48,history:AB:airbag-pintura")).andExpect(status().isOk());verify(service).matrix(1,50,"","all",null,null,"sales",List.of("product:48","history:AB:airbag-pintura"));
        mvc.perform(get("/api/v1/mold-sales/source-groups/history:RU:0052")).andExpect(status().isOk());verify(service).sourceDetail("history:RU:0052",1,20,"sales");
        mvc.perform(get("/api/v1/mold-sales/history-review/orders").param("styleCode","AB").param("phoneCode","0052").param("sourceGroupKey","review:AB:0052:named_history:history-AB-airbag-pintura")).andExpect(status().isOk());verify(service).historyReviewDetail("AB","0052","review:AB:0052:named_history:history-AB-airbag-pintura",1,20);
    }
    @Test void newReadEndpointsUseTheExistingDomesticPermissionGate(){
        var access=mock(PilotAccessService.class);var jdbc=mock(JdbcTemplate.class);doThrow(new IllegalStateException("no domestic access")).when(access).requireDomesticView();
        var service=new MoldSalesService(jdbc,new ObjectMapper(),access,mock(OperationLogService.class),mock(PlatformTransactionManager.class));
        try{assertThatThrownBy(service::options).hasMessage("no domestic access");assertThatThrownBy(()->service.matrix(1,50,"","all",null,null,"sales")).hasMessage("no domestic access");verifyNoInteractions(jdbc);}finally{service.close();}
    }
}

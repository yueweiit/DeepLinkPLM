package com.yuewei.plm.module.moldreference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PhoneSelectionBrandsTest {
    final PhoneModelReferenceRepository repository=mock(PhoneModelReferenceRepository.class);
    final PilotAccessService access=mock(PilotAccessService.class);
    final PhoneModelReferenceService service=new PhoneModelReferenceService(mock(JdbcTemplate.class),
        new ObjectMapper(),repository,access,mock(OperationLogService.class));

    @Test void usesTheExistingPhoneSelectionPermissionWithoutRequiringMaintenanceAccess() {
        var brands=List.<Map<String,Object>>of(Map.of("id",1L,"brandId",1L,"name","Apple","status","enabled"));
        when(repository.selectionBrands()).thenReturn(brands);
        assertThat(service.selectionBrands()).isSameAs(brands);
        var order=inOrder(access,repository);order.verify(access).requirePhoneSelectionRead();order.verify(repository).selectionBrands();
        verifyNoMoreInteractions(access,repository);
    }
    @Test void deniesDictionaryReadsBeforeQueryingWhenTheSelectionGuardRejectsTheActor() {
        doThrow(new BusinessException(40103,"此账号仅可查看已分配的手机任务")).when(access).requirePhoneSelectionRead();
        assertThatThrownBy(service::selectionBrands).isInstanceOf(BusinessException.class).hasMessageContaining("已分配");
        verifyNoInteractions(repository);
    }
    @Test void staticBrandsPathCannotBeMistakenForAPhoneId() throws Exception {
        var httpService=mock(PhoneModelReferenceService.class);
        when(httpService.selectionBrands()).thenReturn(List.of(Map.of("brandId",1L,"name","Apple","status","enabled")));
        var mvc=MockMvcBuilders.standaloneSetup(new PhoneModelReferenceController(httpService,mock(PhoneModelCoverageService.class))).build();
        mvc.perform(get("/api/v1/molds/reference-data/phone-models/brands"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].brandId").value(1));
        verify(httpService).selectionBrands();verifyNoMoreInteractions(httpService);
    }
}

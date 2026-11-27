package com.yuewei.plm.module.moldsharing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import com.yuewei.plm.module.manufacturing.ManufacturingIdentityService;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MoldShareMappingRegistryTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    PilotAccessService access=mock(PilotAccessService.class);
    ManufacturingIdentityService manufacturing=mock(ManufacturingIdentityService.class);
    PhoneModelReferenceService phones=mock(PhoneModelReferenceService.class);
    OperationLogService audit=mock(OperationLogService.class);
    MoldShareMappingRegistryService service=new MoldShareMappingRegistryService(jdbc,new ObjectMapper(),access,manufacturing,phones,audit,new ProductBusinessCodeGenerator());
    Map<String,Object> row=new HashMap<>(Map.of("id",9L,"version",2L,"productLineId",1L,"sourcePhoneModelId",2L,"referencePhoneModelId",3L,"materialCode","10","holeType","precision","structureDescription","","status","active"));
    @BeforeEach void setup(){
        when(access.requireEngineeringMaintenance()).thenReturn(new CurrentUser(1L,"engineer","工程",true));
        when(access.visibleSqlClause(anyString(),anyString())).thenReturn("true");
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenAnswer(call->{
            String sql=call.getArgument(0);
            if(sql.startsWith("select master_data_status"))return List.of(Map.of("master_data_status","active","deleted_flag",0));
            if(sql.contains("from plm_mold_share_mapping m"))return List.of(row);
            return List.of();
        });
    }
    MoldShareMappingRegistryService.WriteInput input(long version,long phone,String status){return new MoldShareMappingRegistryService.WriteInput(version,1,phone,3,"10","precision","",44,8,"工程确认","停用旧依据",status);}
    @Test void staleVersionCannotWrite(){
        assertThrows(BusinessException.class,()->service.save(9L,input(1,2,"active"),null));
        verify(jdbc,never()).update(anyString(),any(Object[].class));verifyNoInteractions(audit,manufacturing);
    }
    @Test void deactivateDoesNotResolveStaleEngineeringEvidence(){
        service.save(9L,input(2,2,"inactive"),null);
        verify(jdbc).update(startsWith("update plm_mold_share_mapping set status='inactive'"),any(Object[].class));
        verifyNoInteractions(manufacturing,phones);verify(audit).logRequired(any(),any());
    }
    @Test void deactivateCannotChangeScope(){
        assertThrows(BusinessException.class,()->service.save(9L,input(2,99,"inactive"),null));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void engineeringPermissionIsRequiredBeforeAnyDataAccess(){
        when(access.requireEngineeringMaintenance()).thenThrow(new BusinessException(40301,"无工程维护权限"));
        assertThrows(BusinessException.class,()->service.save(null,input(2,2,"active"),null));verifyNoInteractions(jdbc,audit,manufacturing,phones);
    }
    @Test void auditFailureIsPropagatedToTransactionalBoundary(){
        doThrow(new IllegalStateException("audit unavailable")).when(audit).logRequired(any(),any());
        assertThrows(IllegalStateException.class,()->service.save(9L,input(2,2,"inactive"),null));
        assertNotNull(Arrays.stream(service.getClass().getMethods()).filter(m->m.getName().equals("save")).findFirst().orElseThrow().getAnnotation(org.springframework.transaction.annotation.Transactional.class));
    }
}

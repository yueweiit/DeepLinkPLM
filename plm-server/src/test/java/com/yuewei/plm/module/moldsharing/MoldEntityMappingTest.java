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

class MoldEntityMappingTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final PilotAccessService access=mock(PilotAccessService.class);
    final ManufacturingIdentityService manufacturing=mock(ManufacturingIdentityService.class);
    final PhoneModelReferenceService phones=mock(PhoneModelReferenceService.class);
    final OperationLogService audit=mock(OperationLogService.class);
    final ProductBusinessCodeGenerator generator=new ProductBusinessCodeGenerator();
    final MoldShareMappingService service=new MoldShareMappingService(jdbc,new ObjectMapper(),access,manufacturing,phones,audit,generator);
    final Map<String,Object> relation=new HashMap<>(Map.of("productLineId",50L,"version",3L,"phoneModelId",121L,"holeType","precision","structureDescription","","itemId",40L,"moldSetId",44L));
    final List<Object[]> writes=new ArrayList<>();
    MoldShareMappingService.EntityMappingInput input(String hole){return new MoldShareMappingService.EntityMappingInput(50,9,3,122,"工程已人工核对","复用原模具",hole);}
    @BeforeEach void setup(){
        when(access.requireEngineeringMaintenance()).thenReturn(new CurrentUser(1L,"engineer","工程",true));
        when(manufacturing.detail(9)).thenReturn(relation);
        when(manufacturing.requireResolved(any())).thenReturn(Map.of("parts",List.of(Map.of("partType","TPU","entityPrimaryCode","MBA100121","catalogCodeId",1L),Map.of("partType","PC","entityPrimaryCode","MBA200121","catalogCodeId",2L))));
        when(phones.requireEnabledModels(anyList())).thenReturn(Map.of(121L,Map.of("phoneModelCode","0121"),122L,Map.of("phoneModelCode","0122")));
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenAnswer(call->{String sql=call.getArgument(0);
            if(sql.startsWith("select master_data_status"))return List.of(Map.of("master_data_status","active","deleted_flag",0));
            if(sql.startsWith("select primary_code")){long entity=call.getArgument(1);return List.of(Map.of("primary_code",entity==10?"MBA100121":"MBA200121","primary_product_mold_code_id",entity==10?1L:2L));}
            return List.of();
        });
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenAnswer(call->{String sql=call.getArgument(0);
            if(sql.startsWith("insert into plm_mold_entity_identity"))return "10".equals(call.getArgument(5))?10L:20L;
            if(sql.startsWith("insert into plm_mold_entity_code_mapping")){writes.add(call.getArguments());return 100L+writes.size();}
            return 0L;
        });
    }
    @Test void previewGeneratesBothPhonesAndDoesNotWriteOrReserve(){
        var result=service.previewEntityMapping(input("precision"));String text=result.toString();
        for(String code:List.of("MBA100121","MBA200121","MBA10J0121","MBA20J0121","MBA10J0122","MBA20J0122"))assertTrue(text.contains(code),code);
        verify(jdbc,never()).update(anyString(),any(Object[].class));verify(jdbc,never()).execute(anyString());assertTrue(writes.isEmpty());verifyNoInteractions(audit);
    }
    @Test void confirmsLegacySourceAndTargetAgainstSamePhysicalMold(){
        service.confirmEntityMapping(input("precision"),null);assertEquals(4,writes.size());
        var aliases=new HashSet<String>();for(var args:writes){assertEquals(50L,args[3]);assertEquals("precision",args[6]);aliases.add((String)args[8]);}
        assertEquals(Set.of("MBA10J0121","MBA20J0121","MBA10J0122","MBA20J0122"),aliases);
        verify(jdbc,never()).update(startsWith("update plm_product_mold_code"),any(Object[].class));verify(audit).logRequired(any(),any());
    }
    @Test void historicalOtherRequiresExplicitHoleAndAddsNewRelationWithoutChangingHistory(){
        relation.put("holeType","other");relation.put("structureDescription","历史待明确");
        assertThrows(BusinessException.class,()->service.previewEntityMapping(input(null)));
        service.confirmEntityMapping(input("mixed"),null);
        verify(jdbc,times(2)).update(startsWith("insert into plm_manufacturing_relation"),any(Object[].class));
        verify(jdbc,never()).update(startsWith("update plm_manufacturing_relation"),any(Object[].class));
    }
    @Test void knownHoleMismatchCannotWrite(){assertThrows(BusinessException.class,()->service.confirmEntityMapping(input("large"),null));assertTrue(writes.isEmpty());verifyNoInteractions(audit);}
    @Test void v2PrimaryIsReusedAndHoleMarkerIsChecked(){
        var codes=MoldShareMappingService.entityCodes(generator,"MBA10J0121","10","0121","0122","precision");assertEquals("MBA10J0121",codes.get("sourceMappingCode"));
        assertThrows(BusinessException.class,()->MoldShareMappingService.entityCodes(generator,"MBA10D0121","10","0121","0122","precision"));
    }
    @Test void incompletePrefixAndIncorrectMaterialAreRejected(){
        assertThrows(BusinessException.class,()->MoldShareMappingService.entityCodes(generator,"MBA10","10","0121","0122","precision"));
        assertThrows(BusinessException.class,()->MoldShareMappingService.entityCodes(generator,"MBA200121","10","0121","0122","precision"));
    }
    @Test void aStaleSourceVersionCannotGenerateCodes(){relation.put("version",4L);assertThrows(BusinessException.class,()->service.previewEntityMapping(input("precision")));verifyNoInteractions(phones);}
    @Test void duplicateConfirmationReusesAliasesAndDoesNotInsertRelations(){
        when(jdbc.queryForList(startsWith("select mapping_code_id"),any(Object[].class))).thenReturn(List.of(Map.of("mapping_code_id",88L)));
        when(jdbc.queryForList(startsWith("select item_id,mold_set_id"),any(Object[].class))).thenReturn(List.of(Map.of("item_id",40L,"mold_set_id",44L)));
        service.confirmEntityMapping(input("precision"),null);assertTrue(writes.isEmpty());
        verify(jdbc,never()).update(startsWith("insert into plm_manufacturing_relation"),any(Object[].class));
    }
    @Test void catalogOccupationAndCrossEntityCodesAreRejected(){
        when(jdbc.queryForObject(startsWith("select (select count(*)"),eq(Long.class),any(Object[].class))).thenReturn(1L);
        assertThrows(BusinessException.class,()->service.confirmEntityMapping(input("precision"),null));assertTrue(writes.isEmpty());verifyNoInteractions(audit);
    }
    @Test void aDifferentExistingTargetMoldCannotBeOverwritten(){
        when(jdbc.queryForList(startsWith("select item_id,mold_set_id"),any(Object[].class))).thenReturn(List.of(Map.of("item_id",40L,"mold_set_id",45L)));
        assertThrows(BusinessException.class,()->service.confirmEntityMapping(input("precision"),null));
        verify(jdbc,never()).update(startsWith("insert into plm_manufacturing_relation"),any(Object[].class));verifyNoInteractions(audit);
    }
}

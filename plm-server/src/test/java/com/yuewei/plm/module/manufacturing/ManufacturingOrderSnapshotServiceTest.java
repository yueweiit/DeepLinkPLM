package com.yuewei.plm.module.manufacturing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManufacturingOrderSnapshotServiceTest {
    private ManufacturingIdentityService identities;
    private PilotAccessService access;
    private ManufacturingOrderSnapshotService service;
    private Map<String,Object> relation;
    private ManufacturingOrderSnapshotService.Input input(long sku,long version) {
        return new ManufacturingOrderSnapshotService.Input(55,18,"large","标准磁吸款",7,version,sku,
            "isolated-store","ORDER-QA-1","LINE-QA-1","SPEC-18PM-PINK","iPhone 18 Pro Max · 粉色 · 大孔");
    }
    @BeforeEach void setup() {
        identities=mock(ManufacturingIdentityService.class);access=mock(PilotAccessService.class);
        service=new ManufacturingOrderSnapshotService(identities,access,new ObjectMapper());
        relation=new LinkedHashMap<>(Map.of("relationId",7L,"relationVersion",3L,"productLineId",55L,
            "itemId",1145L,"moldSetId",99L,"manufacturingProductId",100L,"manufacturingPhoneCode","1145",
            "sharedMaterial",true,"holeType","large","structureDescription","标准磁吸款"));
        relation.put("businessPhone",Map.of("phoneModelId",18L,"modelName","Apple iPhone 18 Pro Max","phoneModelCode","1218"));
        relation.put("parts",List.of(Map.of("partType","TPU","code","MBA101145"),Map.of("partType","PC","code","MBA201145")));
        when(identities.requireResolved(any())).thenReturn(relation);
        when(identities.resolvedSkus(anyMap())).thenReturn(List.of(Map.of("id",101L,"code","NBA4030114501",
            "name","17/18 Pro Max 大孔粉色","phoneModelCode","1145","colorCode","01","operationCode","4030")));
    }
    @Test void customerEighteenAndOriginalManufacturingSkuRemainSeparateWithoutSavingAnOrderOrStock() {
        var result=service.preview(input(101,3));JsonNode snapshot=(JsonNode)result.get("snapshot");
        assertEquals("1218",snapshot.path("customerPhone").path("phoneModelCode").asText());
        assertEquals("Apple iPhone 18 Pro Max",snapshot.path("customerPhone").path("modelName").asText());
        assertEquals("1145",snapshot.path("relation").path("manufacturingPhoneCode").asText());
        assertEquals("NBA4030114501",snapshot.path("manufacturingSku").path("code").asText());
        assertEquals("iPhone 18 Pro Max · 粉色 · 大孔",snapshot.path("customerChoiceText").asText());
        assertEquals(false,result.get("orderSaved"));assertEquals(false,result.get("productionWriteEnabled"));assertEquals(false,result.get("externalListingConnected"));
        verify(identities).requireResolved(new ManufacturingIdentityService.ResolveInput(55,18,"large","标准磁吸款",7L,3L));
    }
    @Test void moldOnlyCompatibilityCannotAuthorizeAFinishedProductShipment() {
        relation.put("sharedMaterial",false);
        assertTrue(assertThrows(BusinessException.class,()->service.preview(input(101,3))).getMessage().contains("未确认共用生产物料"));
        verify(identities,never()).resolvedSkus(anyMap());
    }
    @Test void aSkuOutsideTheConfirmedMaterialScopeCannotBeSilentlySubstituted() {
        assertTrue(assertThrows(BusinessException.class,()->service.preview(input(999,3))).getMessage().contains("不在本次已确认范围"));
    }
    @Test void staleRelationOrDeniedReaderProducesNoSnapshot() {
        when(identities.requireResolved(any())).thenThrow(new BusinessException(40901,"关系版本已变化"));
        assertTrue(assertThrows(BusinessException.class,()->service.preview(input(101,2))).getMessage().contains("版本"));
        verify(identities,never()).resolvedSkus(anyMap());
        reset(identities);when(access.isExternal()).thenReturn(true);
        assertEquals(40103,assertThrows(BusinessException.class,()->service.preview(input(101,3))).getCode());verifyNoInteractions(identities);
    }
    @Test void snapshotIsADetachedVersionedValueAndRepeatReadsHaveTheSameContentHash() throws Exception {
        var first=service.preview(input(101,3));var second=service.preview(input(101,3));
        assertEquals(first.get("snapshotSha256"),second.get("snapshotSha256"));
        assertEquals(new ObjectMapper().readTree(first.get("snapshot").toString()),new ObjectMapper().readTree((String)first.get("snapshotJson")));
        relation.put("manufacturingPhoneCode","1218");
        assertEquals("1145",((JsonNode)first.get("snapshot")).path("relation").path("manufacturingPhoneCode").asText());
    }
    @Test void missingSalesSpecificationCannotBecomeAnUntraceableManufacturingInstruction() {
        var source=input(101,3);
        var noSpec=new ManufacturingOrderSnapshotService.Input(source.productLineId(),source.customerPhoneModelId(),source.holeType(),source.structureDescription(),
            source.relationId(),source.relationVersion(),source.manufacturingSkuId(),source.externalSource(),source.externalOrderId(),source.externalLineId(),"",source.customerChoiceText());
        assertTrue(assertThrows(BusinessException.class,()->service.preview(noSpec)).getMessage().contains("销售规格"));verifyNoInteractions(identities);
    }
}

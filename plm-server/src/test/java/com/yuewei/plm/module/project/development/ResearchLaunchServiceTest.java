package com.yuewei.plm.module.project.development;

import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.productionmetadata.ProductionMetadataService;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResearchLaunchServiceTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final PilotAccessService access=mock(PilotAccessService.class);
    final ProductionMetadataService catalog=mock(ProductionMetadataService.class);
    final ResearchLaunchService service=new ResearchLaunchService(jdbc,access,catalog);
    @BeforeEach void allow(){when(access.hasPermission(anyString())).thenReturn(true);when(access.canMaintainEngineeringData()).thenReturn(true);}
    Map<String,Object> identity(String prefix,long count){return Map.of("productId",42L,"productCode","PRD-42","productSpecificCode",prefix,"salesStatus","unconfirmed","codeCount",count);}
    void identity(Map<String,Object> row){when(jdbc.queryForList(anyString(),eq(42L))).thenReturn(List.of(row));}
    @Test void firstAdmissionReusesTheCatalogWriterWithTheSameProductAndRelease(){
        identity(identity("ZZ",1));service.admitFirstLaunch(42L,8L);verify(catalog).admitResearchProduct(42L,8L,null);verify(access).requireEngineeringMaintenance();verify(access).requirePermission("documents:publish");
    }
    @Test void missingFormalIdentityIsReturnedAsAnActionableGapAndCannotAdmit(){
        var row=new HashMap<>(identity("",0));row.put("productCode","");identity(row);var detail=new HashMap<String,Object>();detail.put("workflow",Map.of());service.enrich(42L,detail);
        assertThat(detail).containsEntry("canLaunch",false);assertThat(detail.get("launchIssues").toString()).contains("正式产品档案编码","两位产品特定编码","product-identity");
        assertThatThrownBy(()->service.admitFirstLaunch(42L,8L)).hasMessageContaining("系统不会猜码");verifyNoInteractions(catalog);
    }
    @Test void conflictingPrefixCannotBeAdmittedEvenIfALegacyDatabaseContainsIt(){
        identity(identity("ZZ",2));assertThatThrownBy(()->service.admitFirstLaunch(42L,8L)).hasMessageContaining("编码存在冲突");verifyNoInteractions(catalog);
    }
    @Test void aPermissionFailureStopsBeforeReadingOrChangingTheCatalog(){
        doThrow(new BusinessException(40103,"没有发布权限")).when(access).requirePermission("documents:publish");
        assertThatThrownBy(()->service.admitFirstLaunch(42L,8L)).hasMessageContaining("发布权限");verifyNoInteractions(jdbc,catalog);
        when(access.hasPermission("documents:publish")).thenReturn(false);assertThat(service.canLaunch()).isFalse();
    }
    @Test void anExistingLaunchCanAdjustItsFactsWithoutDemandingAnotherCatalogAdmission(){
        identity(identity("",0));var detail=new HashMap<String,Object>();detail.put("workflow",Map.of("launched",Map.of("actualDate","2026-10-22")));service.enrich(42L,detail);
        assertThat(detail).containsEntry("canLaunch",true).containsEntry("launchIssues",List.of());verifyNoInteractions(catalog);
    }
    @Test void identityCompletionCapabilityMatchesTheNarrowLifecycleAndEmptyCodeRules(){
        var row=new HashMap<String,Object>(identity("",0));row.put("status","released");row.put("lockStatus","unlocked");identity(row);var detail=new HashMap<String,Object>();service.enrich(42L,detail);assertThat(detail).containsEntry("canMaintainProductIdentity",true);
        for(String lock:List.of("frozen","abandoned")){row.put("lockStatus",lock);service.enrich(42L,detail);assertThat(detail).containsEntry("canMaintainProductIdentity",false);}
        row.put("lockStatus","unlocked");row.put("productSpecificCode","ZZ");service.enrich(42L,detail);assertThat(detail).containsEntry("canMaintainProductIdentity",false);
    }
}

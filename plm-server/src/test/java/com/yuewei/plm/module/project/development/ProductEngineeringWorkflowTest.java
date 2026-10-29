package com.yuewei.plm.module.project.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.attachment.repository.AttachmentRepository;
import com.yuewei.plm.module.inventory.service.InventorySupplierService;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.repository.ProductRepository;
import com.yuewei.plm.repository.entity.Product;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductEngineeringWorkflowTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final PilotAccessService access=mock(PilotAccessService.class);
    final ProductRepository products=mock(ProductRepository.class);
    final DevelopmentWorkflowStore store=mock(DevelopmentWorkflowStore.class);
    final EngineeringBasisService engineering=mock(EngineeringBasisService.class);
    final InventorySupplierService inventory=mock(InventorySupplierService.class);
    final ProductDevelopmentService service=spy(new ProductDevelopmentService(jdbc,new ObjectMapper(),access,products,mock(AttachmentRepository.class),mock(OperationLogService.class),store,inventory,mock(LaunchDevelopmentWorkflow.class),mock(LaunchReviewWorkflow.class),mock(DevelopmentUnitCostWorkflow.class)));

    Map<String,Object> state(){
        var product=new Product();product.setProductId(42L);product.setProductType("product_line");product.setDevelopmentMode("launch_v1");product.setStatus("draft");product.setCurrentStepNo(1);product.setDeletedFlag(0);
        when(products.selectById(42L)).thenReturn(product);
        var state=new LinkedHashMap<String,Object>();state.put("version",3L);state.put("profileDraft",Map.of("references",List.of(Map.of("type","bom","id",10L))));when(store.load(42L)).thenReturn(state);
        when(access.actor()).thenReturn(new CurrentUser(1L,"engineer","工程师",false));
        ReflectionTestUtils.setField(service,"engineering",engineering);doReturn(Map.of("productId",42L)).when(service).detail(42L);
        return state;
    }
    DevelopmentModels.WorkflowInput confirm(Map<String,Object> data){return new DevelopmentModels.WorkflowInput("engineering_confirm",3L,"engineer-confirm-1",data);}

    @Test void confirmationReadsTheSavedStageReferencesAndReusesTheOriginalWorkflowTransaction(){
        var state=state();var refs=List.<Map<String,Object>>of(Map.of("type","bom","id",10L,"versionNo","V1"));
        products.selectById(42L).setCurrentStepNo(7);
        state.put("releaseDraft",Map.of("references",List.of(Map.of("type","bom","id",10L))));
        doReturn(refs).when(service).snapshotReferences(eq(42L),anyList());
        when(engineering.confirm(eq(42L),same(state),same(refs),eq(""))).thenReturn(new LinkedHashMap<>(Map.of("revision",1L)));
        assertThat(service.workflowAction(42L,confirm(Map.of("stage",7)))).containsEntry("productId",42L);
        verify(service).snapshotReferences(42L,List.of(new DevelopmentModels.MaterialReference("bom",10L)));
        verify(engineering).confirm(42L,state,refs,"");verify(store).save(42L,state);
        verify(store).event(eq(42L),eq("engineering_confirm"),argThat(value->DevelopmentWorkflowStore.map(value).get("sourceStage").equals(7)),eq("engineer-confirm-1"),eq("工程师"));
        verifyNoInteractions(inventory);
    }

    @Test void initiationCannotBeForcedToConfirmBomAndProcessVersions(){
        state();assertThatThrownBy(()->service.workflowAction(42L,confirm(Map.of("stage",1)))).hasMessageContaining("试产步骤确认");
        verify(engineering,never()).confirm(anyLong(),anyMap(),anyList(),anyString());verify(store,never()).save(anyLong(),anyMap());
    }

    @Test void callerCannotSubmitASecondBomOrSupplierListInTheConfirmationCommand(){
        state();assertThatThrownBy(()->service.workflowAction(42L,confirm(Map.of("stage",1,"references",List.of(Map.of("type","bom","id",99L)))))).hasMessageContaining("本阶段已保存");
        verify(engineering,never()).confirm(anyLong(),anyMap(),anyList(),anyString());verify(store,never()).save(anyLong(),anyMap());
    }

    @Test void duplicateRequestReturnsTheExistingResultWithoutAnotherEngineeringRevision(){
        state();when(store.repeated(42L,"engineer-confirm-1")).thenReturn(true);
        assertThat(service.workflowAction(42L,confirm(Map.of("stage",1)))).containsEntry("productId",42L);
        verify(store,never()).check(anyMap(),anyLong());verify(store,never()).save(anyLong(),anyMap());verifyNoInteractions(engineering);
    }

    @Test void versionConflictAndChangedStageCannotConfirmStaleInputs(){
        var state=state();doThrow(new BusinessException(40001,"项目已被其他操作更新")).when(store).check(state,3L);
        assertThatThrownBy(()->service.workflowAction(42L,confirm(Map.of("stage",1)))).hasMessageContaining("其他操作更新");
        verifyNoInteractions(engineering);verify(store,never()).save(anyLong(),anyMap());
        doNothing().when(store).check(state,3L);
        assertThatThrownBy(()->service.workflowAction(42L,confirm(Map.of("stage",4)))).hasMessageContaining("阶段已变化");verifyNoInteractions(engineering);
    }

    @Test void optionsDoNotLoadTheWholeSupplierInventoryOrRequireSupplierAccessForOwners(){
        ReflectionTestUtils.setField(service,"engineering",engineering);when(engineering.supplierOptions()).thenReturn(List.of());
        when(jdbc.queryForList(startsWith("select user_id"))).thenReturn(List.of(Map.of("userId",1L,"name","工程师")));
        assertThat(service.ownerOptions()).containsEntry("canSelectSupplier",false).containsEntry("suppliers",List.of());
        verifyNoInteractions(inventory);verify(access,never()).requirePermission("supplier:view");
    }

    @Test void missingEngineeringBeanCannotSilentlyPermitFinalPublication(){
        assertThatThrownBy(()->service.validateEngineeringFinal(42L,Map.of(),List.of())).isInstanceOf(NullPointerException.class).hasMessageContaining("required for final");
        ReflectionTestUtils.setField(service,"engineering",engineering);service.validateEngineeringFinal(42L,Map.of(),List.of());
        verify(engineering).requireFinal(42L,Map.of(),List.of());
    }

    @Test void supplierChangeUsesTheOriginalWorkflowLockVersionAndAuditedEvent(){
        var state=state();var input=Map.<String,Object>of("stage",1,"previousSupplierCode","SUP-A","supplierCode","SUP-B","role","component","scope","壳体","reason","原供方技术无法实现");
        var evidence=Map.<String,Object>of("stage",1,"reason","原供方技术无法实现","before",Map.of("supplierAssignments",List.of(Map.of("supplierCode","SUP-A"))),"after",Map.of("supplierAssignments",List.of(Map.of("supplierCode","SUP-B"))));
        when(engineering.changeSupplier(42L,state,1,input)).thenReturn(evidence);
        service.workflowAction(42L,new DevelopmentModels.WorkflowInput("supplier_change",3L,"supplier-change-1",input));
        verify(store).check(state,3L);verify(store).save(42L,state);verify(engineering).changeSupplier(42L,state,1,input);
        verify(store).event(42L,"supplier_change",evidence,"supplier-change-1","工程师");verifyNoInteractions(inventory);
    }

    @Test void supplierChangeCannotUseAStaleStageOrADocumentationOnlyPublishedRevision(){
        var state=state();var input=Map.<String,Object>of("stage",3,"reason","技术无法实现");
        assertThatThrownBy(()->service.workflowAction(42L,new DevelopmentModels.WorkflowInput("supplier_change",3L,"supplier-change-2",input))).hasMessageContaining("阶段已变化");
        verify(engineering,never()).changeSupplier(anyLong(),anyMap(),anyInt(),anyMap());
        var product=products.selectById(42L);product.setStatus("released");state.put("revision",Map.of("stage",1,"technical",false));
        doReturn(product).when(service).requireDevelopmentEditable(42L);
        assertThatThrownBy(()->service.workflowAction(42L,new DevelopmentModels.WorkflowInput("supplier_change",3L,"supplier-change-3",Map.of("stage",1)))).hasMessageContaining("技术修订");
        verify(store,never()).save(anyLong(),anyMap());
    }
}

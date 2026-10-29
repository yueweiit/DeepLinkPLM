package com.yuewei.plm.module.project.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.repository.ProductRepository;
import com.yuewei.plm.repository.entity.Product;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductDesignReadinessTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final PilotAccessService access=mock(PilotAccessService.class);
    final DevelopmentWorkflowStore store=mock(DevelopmentWorkflowStore.class);
    final ProductRepository products=mock(ProductRepository.class);
    final LaunchReviewWorkflow reviews=new LaunchReviewWorkflow(jdbc,access,store);
    final LaunchDevelopmentWorkflow launch=new LaunchDevelopmentWorkflow(jdbc,new ObjectMapper(),access,store,mock(com.yuewei.plm.module.collaboration.MoldScheduleService.class),mock(com.yuewei.plm.module.development.DevelopmentFinanceService.class),reviews,mock(com.yuewei.plm.module.moldreference.PhoneModelReferenceService.class));
    final ProductDevelopmentService service=new ProductDevelopmentService(jdbc,new ObjectMapper(),access,products,mock(com.yuewei.plm.module.attachment.repository.AttachmentRepository.class),mock(com.yuewei.plm.module.operationlog.service.OperationLogService.class),store,mock(com.yuewei.plm.module.inventory.service.InventorySupplierService.class),launch,reviews,mock(DevelopmentUnitCostWorkflow.class));
    void product(Map<String,Object> state){
        var product=new Product();product.setProductId(42L);product.setProductType("product_line");product.setDevelopmentMode("launch_v1");product.setCurrentStepNo(1);product.setStatus("draft");product.setDeletedFlag(0);
        when(products.selectById(42L)).thenReturn(product);when(store.load(42L)).thenReturn(state);
        when(jdbc.queryForObject(startsWith("select count(*) from sys_user"),eq(Long.class),eq(7L))).thenReturn(1L);
        when(jdbc.queryForList(startsWith("select m.id"),eq(10L))).thenReturn(List.of(Map.of("phoneModelId",10L)));
        when(access.actorId()).thenReturn(7L);
        when(access.actor()).thenReturn(new com.yuewei.plm.common.security.CurrentUser(7L,"developer","产品开发负责人",false));
    }
    DevelopmentModels.ProfileInput profile(List<Long> phones,Long owner){return new DevelopmentModels.ProfileInput("透甲2.0","","透明PC背板与TPU包边",null,null,phones,"",null,null,null,owner,List.of(),1L,null);}
    @SuppressWarnings("unchecked") List<DevelopmentRules.ReadinessCheck> checks(Map<String,Object> result){return (List<DevelopmentRules.ReadinessCheck>)result.get("checks");}
    @Test void toujiaLikeDraftReturnsAllActionableGapsWithoutChangingBusinessStateOrInventingLaterFacts() throws Exception {
        var state=new LinkedHashMap<String,Object>();state.put("version",1L);state.put("unitCostDraft",Map.of("rows",List.of(Map.of("name","PC原料"))));product(state);
        String before=new ObjectMapper().writeValueAsString(state);
        var result=service.designReadiness(42,new DevelopmentModels.DesignDraftInput(profile(List.of(),null),Map.of("phoneModelIds",List.of(),"method","own_mold","needsMold",true)));
        assertThat(result.get("ready")).isEqualTo(false);
        assertThat(checks(result).stream().filter(c->!c.complete())).extracting(DevelopmentRules.ReadinessCheck::code).containsExactly("OWNER","INIT_OWNER_CONFIRMATION");
        assertThat(checks(result)).extracting(DevelopmentRules.ReadinessCheck::code).doesNotContain("PHONES","DESIGN_BASIS","MATERIAL_BASIS","DESIGN_DRAWING","DESIGN_REVIEW");
        assertThat(new ObjectMapper().writeValueAsString(state)).isEqualTo(before);
        verify(store).load(42L);assertThat(mockingDetails(store).getInvocations()).allMatch(i->Set.of("load","events").contains(i.getMethod().getName()));
        assertThat(mockingDetails(jdbc).getInvocations()).noneMatch(i->Set.of("update","execute","batchUpdate").contains(i.getMethod().getName()));
    }
    @Test void ownerCanInitiateWithUnknownDatesAndWithoutPhonesReviewsOrProductionDocuments(){
        var phones=List.<Long>of();var plan=Map.<String,Object>of("phoneModelIds",phones,"method","supplier","needsMold",true);
        var state=new LinkedHashMap<String,Object>();state.put("version",1L);state.put("plan",plan);state.put("planDraft",plan);state.put("requirementVersion",1);
        state.put("requirement",Map.of("productName","透甲2.0","model","","requirements","透明PC背板与TPU包边","ownerUserId",7L,"phoneModelIds",phones));
        product(state);
        var result=service.designReadiness(42,new DevelopmentModels.DesignDraftInput(profile(phones,7L),plan));
        assertThat(result.get("ready")).isEqualTo(true);assertThat(checks(result)).allMatch(DevelopmentRules.ReadinessCheck::complete);
        assertThat(checks(result)).extracting(DevelopmentRules.ReadinessCheck::code).doesNotContain("MATERIAL_BASIS","DESIGN_DRAWING","DESIGN_REVIEW","REPRESENTATIVE_PHONES");
        var handover=new LinkedHashMap<>(state);handover.put("profileDraft",state.get("requirement"));launch.confirmStage(42,1,handover,service);
        assertThat(handover).containsKey("stage1Basis");
    }
    @Test void returningATechnicalRevisionToInitiationKeepsReviewDraftsOutsideItsGate(){
        var state=new LinkedHashMap<String,Object>();state.put("version",1L);state.put("revision",Map.of("stage",1,"revisionNo",3,"technical",true));
        state.put("reviewRecords",List.of(Map.of("id",9,"gate","design","status","draft","materialBasis","待复核的PC与TPU方案","references",List.of())));product(state);
        var result=service.designReadiness(42,new DevelopmentModels.DesignDraftInput(profile(List.of(10L),7L),Map.of("phoneModelIds",List.of(10L),"method","own_mold","needsMold",true)));
        assertThat(checks(result)).extracting(DevelopmentRules.ReadinessCheck::code).doesNotContain("MATERIAL_BASIS","DESIGN_REVIEW","DESIGN_DRAWING");
        assertThat(result.get("ready")).isEqualTo(true);
        assertThat(DevelopmentWorkflowStore.map(((List<?>)state.get("reviewRecords")).get(0))).containsEntry("status","draft");
    }
}

package com.yuewei.plm.module.project.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.partner.SupplierProfileService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EngineeringBasisServiceTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final PilotAccessService access=mock(PilotAccessService.class);
    final SupplierProfileService suppliers=mock(SupplierProfileService.class);
    final EngineeringBasisService policy=new EngineeringBasisService(jdbc,access,suppliers);
    EngineeringBasisServiceTest(){doCallRealMethod().when(suppliers).requireSupplierForNewUse(anyString());}

    Map<String,Object> assignment(String mode,String code,String role){
        return Map.of("mode",mode,"supplierCode",code,"internalName","internal".equals(mode)?"工程车间":"","role",role,"scope","透明背板加工","basis","已确认打样与技术资料");
    }
    Map<String,Object> state(boolean direct){
        var plan=Map.<String,Object>of("method",direct?"direct":"own_mold","needsMold",!direct,"phoneModelIds",List.of(8L),"supplierAssignments",List.of(assignment(direct?"supplier":"internal",direct?"SUP-A":"",direct?"finished_product":"processing")));
        return new LinkedHashMap<>(Map.of("version",2L,"planDraft",plan,"plan",plan));
    }
    List<Map<String,Object>> references(){return List.of(reference("bom",10L),reference("process",20L));}
    Map<String,Object> reference(String type,long id){return Map.of("type",type,"id",id,"versionNo","V1","content",Map.of("technical","原始方案"));}
    void actor(){var actor=mock(CurrentUser.class);when(actor.displayName()).thenReturn("工程师");when(access.actor()).thenReturn(actor);}
    void supplier(String status){when(suppliers.requireSupplier("SUP-A")).thenReturn(Map.of("supplierId",1L,"supplierCode","SUP-A","supplierName","真实供方","version",3,"status",status,"needsReview",false));}
    void route(boolean formal){when(jdbc.queryForList(contains("from plm_product_bom b"),eq(42L),eq(42L),eq(10L),eq(20L))).thenReturn(List.of(Map.of("bomStatus",formal?"released":"draft","bomType","mbom","processStatus",formal?"locked":"draft","currentFormal",formal)));}

    Map<String,Object> replacement(){return Map.of("previousSupplierCode","SUP-A","supplierCode","SUP-B","role","finished_product","scope","透明背板加工","reason","原供应商无法实现透明件精度，新供方继续验证");}
    void replacementSuppliers(){supplier("active");when(suppliers.requireSupplier("SUP-B")).thenReturn(Map.of("supplierId",2L,"supplierCode","SUP-B","supplierName","新技术供方","version",4,"status","active","needsReview",false));}

    @ParameterizedTest @ValueSource(ints={1,2,3,4})
    void replacingSupplierReusesTheCurrentPlanAndKeepsHistoricalFacts(int stage){
        actor();replacementSuppliers();var state=state(true);var oldPlan=state.get("plan");
        state.put("stage1Basis",Map.of("plan",oldPlan));state.put("stage3Basis",Map.of("sample",Map.of("supplierCode","SUP-A","result","passed")));
        state.put("engineeringBasis",Map.of("revision",3L,"supplierAssignments",DevelopmentWorkflowStore.map(oldPlan).get("supplierAssignments")));state.put("finalized",Map.of("sample","原样品"));
        var historical=state.get("stage1Basis");var engineering=state.get("engineeringBasis");
        var result=policy.changeSupplier(42L,state,stage,replacement());
        assertThat(result).containsEntry("stage",stage).containsEntry("reason",replacement().get("reason"));
        assertThat(DevelopmentWorkflowStore.map(result.get("before")).toString()).contains("真实供方","SUP-A","supplierId=1");
        assertThat(DevelopmentWorkflowStore.map(result.get("after")).toString()).contains("新技术供方","SUP-B","supplierId=2");
        assertThat(state.get("planDraft").toString()).contains("SUP-B","basis=").doesNotContain("SUP-A","已确认打样与技术资料");
        assertThat(state.get("plan").toString()).contains(stage==1?"SUP-A":"SUP-B");
        assertThat(state.get("stage1Basis")).isSameAs(historical);assertThat(state.get("engineeringBasis")).isSameAs(engineering);assertThat(state.get("stage3Basis").toString()).contains("SUP-A","passed");assertThat(state).doesNotContainKey("finalized");
        verify(access).requireEngineeringMaintenance();verify(access,atLeastOnce()).requirePermission("supplier:view");
    }

    @Test void aSupplierFailureRequiresAReasonAndARealDifferentScopedReplacement(){
        replacementSuppliers();var state=state(true);var before=new LinkedHashMap<>(state);var missing=new LinkedHashMap<>(replacement());missing.put("reason","  ");
        assertThatThrownBy(()->policy.changeSupplier(42L,state,3,missing)).hasMessageContaining("更换原因");assertThat(state).isEqualTo(before);
        var same=new LinkedHashMap<>(replacement());same.put("supplierCode","SUP-A");assertThatThrownBy(()->policy.changeSupplier(42L,state,3,same)).hasMessageContaining("不同");
        when(suppliers.requireSupplier("SUP-B")).thenThrow(new BusinessException(40103,"新供应商未获授权"));
        assertThatThrownBy(()->policy.changeSupplier(42L,state,3,replacement())).hasMessageContaining("未获授权");assertThat(state).isEqualTo(before);
    }

    @Test void anInactiveReplacementOrChangedAssignmentCannotModifyTheOriginalPlan(){
        replacementSuppliers();var state=state(true);var before=new LinkedHashMap<>(state);
        when(suppliers.requireSupplier("SUP-B")).thenReturn(Map.of("status","inactive"));
        assertThatThrownBy(()->policy.changeSupplier(42L,state,2,replacement())).hasMessageContaining("停用");assertThat(state).isEqualTo(before);
        var stale=new LinkedHashMap<>(replacement());stale.put("scope","其他部件");assertThatThrownBy(()->policy.changeSupplier(42L,state,2,stale)).hasMessageContaining("已变化");assertThat(state).isEqualTo(before);
    }

    @Test void supplierDraftReplacementNeedsAReasonButFirstSelectionAndCompletingUnknownScopeDoNot(){
        replacementSuppliers();var previous=DevelopmentWorkflowStore.map(state(true).get("planDraft"));
        var input=new LinkedHashMap<>(previous);input.put("supplierAssignments",List.of(assignment("supplier","SUP-B","finished_product")));
        assertThatThrownBy(()->policy.preparePlan(input,previous)).hasMessageContaining("更换原因");
        input.put("reason",replacement().get("reason"));var prepared=policy.preparePlan(input,previous);
        assertThat(policy.supplierChangeEvidence(previous,prepared,1).toString()).contains("真实供方","新技术供方",replacement().get("reason").toString());
        assertThat(policy.preparePlan(Map.of("supplierAssignments",List.of(Map.of("mode","supplier","role","component","supplierCode","SUP-A"))),Map.of())).containsKey("supplierAssignments");
        var incomplete=Map.<String,Object>of("supplierAssignments",List.of(Map.of("mode","supplier","role","component","supplierCode","SUP-A")));
        assertThat(policy.preparePlan(Map.of("supplierAssignments",List.of(assignment("supplier","SUP-A","component"))),incomplete)).containsKey("supplierAssignments");
    }

    @Test void onlyTheSelectedRoleAndScopeAreReassignedAndSupplierLabelsCannotBeSpoofed(){
        replacementSuppliers();var state=state(true);var plan=new LinkedHashMap<>(DevelopmentWorkflowStore.map(state.get("plan")));var other=new LinkedHashMap<>(assignment("supplier","SUP-A","component"));other.put("scope","TPU包边");
        plan.put("supplierAssignments",List.of(assignment("supplier","SUP-A","finished_product"),other));state.put("plan",plan);state.put("planDraft",plan);
        var input=new LinkedHashMap<>(replacement());input.put("supplierName","伪造新名");input.put("supplierId",999);
        var result=policy.changeSupplier(42L,state,3,input);
        assertThat(state.get("plan").toString()).contains("SUP-A","TPU包边","SUP-B");assertThat(result.toString()).doesNotContain("伪造新名","999");
    }

    @Test void explicitPendingReplacementRemovesOnlyTheChosenAssignmentAndKeepsHistoricalBasis(){
        supplier("active");var state=state(true);var plan=new LinkedHashMap<>(DevelopmentWorkflowStore.map(state.get("plan")));
        var other=new LinkedHashMap<>(assignment("supplier","SUP-A","component"));other.put("scope","TPU包边");
        plan.put("supplierAssignments",List.of(assignment("supplier","SUP-A","finished_product"),other));state.put("plan",plan);state.put("planDraft",plan);
        state.put("stage5Basis",Map.of("plan",plan));state.put("engineeringBasis",Map.of("identity",plan));state.put("finalized",Map.of("old",true));
        var input=new LinkedHashMap<>(replacement());input.put("supplierCode","");input.put("replacementPending",true);
        var evidence=policy.changeSupplier(42L,state,5,input);
        assertThat(evidence).containsEntry("replacementPending",true);
        assertThat(DevelopmentWorkflowStore.map(evidence.get("after")).get("supplierAssignments")).isEqualTo(List.of());
        for(String key:List.of("plan","planDraft"))assertThat(DevelopmentWorkflowStore.map(state.get(key)).get("supplierAssignments")).isEqualTo(List.of(other));
        assertThat(state.get("stage5Basis").toString()).contains("finished_product");assertThat(state.get("engineeringBasis").toString()).contains("finished_product");assertThat(state).doesNotContainKey("finalized");
        verify(suppliers,never()).requireSupplier("");
    }

    @Test void pendingReplacementRequiresAnExplicitFlagAndAnExactCurrentTarget(){
        supplier("active");var state=state(true);var before=new LinkedHashMap<>(state);var input=new LinkedHashMap<>(replacement());input.put("supplierCode","");
        assertThatThrownBy(()->policy.changeSupplier(42L,state,3,input)).hasMessageContaining("新供应商");
        input.put("replacementPending",true);input.put("scope","错误范围");
        assertThatThrownBy(()->policy.changeSupplier(42L,state,3,input)).hasMessageContaining("分工已变化");assertThat(state).isEqualTo(before);
        input.put("supplierCode","SUP-B");assertThatThrownBy(()->policy.changeSupplier(42L,state,3,input)).hasMessageContaining("同时选择");
    }

    @Test void supplierOptionsReuseTheScopedMasterClassificationProjection(){
        when(suppliers.options()).thenReturn(List.of(Map.of("supplierCode","SUP-A","businessCategories",List.of("mold"))));
        assertThat(policy.supplierOptions()).singleElement().satisfies(row->assertThat(row.get("businessCategories")).isEqualTo(List.of("mold")));verifyNoInteractions(jdbc);
    }

    @Test void onlyNewMoldUseRequiresClassificationIncludingAnExistingComponentSupplier(){
        supplier("active");var mold=Map.<String,Object>of("supplierAssignments",List.of(assignment("supplier","SUP-A","mold")),"reason","增加开模职责");
        assertThatThrownBy(()->policy.preparePlan(mold,Map.of())).hasMessageContaining("开模供应商");
        var component=Map.<String,Object>of("supplierAssignments",List.of(assignment("supplier","SUP-A","component")));
        assertThatCode(()->policy.preparePlan(component,Map.of())).doesNotThrowAnyException();
        assertThatThrownBy(()->policy.preparePlan(mold,component)).hasMessageContaining("开模供应商");
        assertThat(policy.preparePlan(mold,mold)).containsEntry("supplierAssignments",mold.get("supplierAssignments"));
        var classified=new LinkedHashMap<>(suppliers.requireSupplier("SUP-A"));classified.put("businessCategories",List.of("mold","partner_selection"));when(suppliers.requireSupplier("SUP-A")).thenReturn(classified);
        assertThatCode(()->policy.preparePlan(mold,Map.of())).doesNotThrowAnyException();
    }

    @Test void replacingAndConfirmingMoldSuppliersCannotBypassTheirBusinessClassification(){
        replacementSuppliers();var original=assignment("supplier","SUP-A","mold");var plan=Map.<String,Object>of("supplierAssignments",List.of(original));var state=new LinkedHashMap<String,Object>(Map.of("planDraft",plan));
        var input=new LinkedHashMap<>(replacement());input.put("role","mold");
        assertThatThrownBy(()->policy.changeSupplier(42L,state,1,input)).hasMessageContaining("开模供应商");
        assertThatThrownBy(()->policy.requireMoldSupplier(plan)).hasMessageContaining("开模供应商");
        var classified=new LinkedHashMap<>(suppliers.requireSupplier("SUP-A"));classified.put("businessCategories",List.of("mold"));when(suppliers.requireSupplier("SUP-A")).thenReturn(classified);
        assertThatCode(()->policy.requireMoldSupplier(plan)).doesNotThrowAnyException();
    }

    @Test void aDraftCanBeIncompleteAndOlderClientsCannotEraseTheSingleSupplierPlan(){
        var previous=Map.<String,Object>of("supplierAssignments",List.of(assignment("internal","","processing")));
        assertThat(policy.preparePlan(Map.of("method","supplier"),previous)).containsEntry("supplierAssignments",previous.get("supplierAssignments"));
        var input=Map.<String,Object>of("supplierAssignments",List.of(Map.of("mode","supplier","role","component","supplierName","伪造名称","supplierId",999)));
        var saved=policy.preparePlan(input,Map.of());
        assertThat(saved.toString()).doesNotContain("伪造名称","999");verifyNoInteractions(suppliers);
    }

    @Test void changingSupplierDraftUsesItsRealScopedIdentityAndCannotSpoofASupplier(){
        var input=Map.<String,Object>of("supplierAssignments",List.of(assignment("supplier","SUP-A","component")));
        doThrow(new BusinessException(40103,"没有权限")).when(access).requirePermission("supplier:view");
        assertThatThrownBy(()->policy.preparePlan(input,Map.of())).hasMessageContaining("权限");verifyNoInteractions(suppliers);
        reset(access);when(suppliers.requireSupplier("SUP-A")).thenThrow(new BusinessException(40103,"供应商不存在或未获授权"));
        assertThatThrownBy(()->policy.preparePlan(input,Map.of())).hasMessageContaining("未获授权");
    }

    @Test void engineeringConfirmationAdoptsDraftVersionsWithoutPublishingAnything(){
        actor();route(false);var state=state(false);var refs=references();
        var confirmed=policy.confirm(42,state,refs,"");
        assertThat(confirmed).containsEntry("revision",1L).containsEntry("confirmedBy","工程师");assertThat(confirmed.get("references")).isEqualTo(refs);
        assertThat(policy.summary(42,state,refs)).containsEntry("current",true);
        verify(access).requireEngineeringMaintenance();assertThat(mockingDetails(jdbc).getInvocations()).noneMatch(i->Set.of("update","execute","batchUpdate").contains(i.getMethod().getName()));
        assertThatThrownBy(()->policy.requireFinal(42,state,refs)).hasMessageContaining("正式BOM");
    }

    @Test void finalEngineeringRequiresAnActiveFormalBomAndItsLockedMatchingProcess(){
        actor();route(true);var state=state(false);var refs=references();policy.confirm(42,state,refs,"");
        assertThatCode(()->policy.requireFinal(42,state,refs)).doesNotThrowAnyException();
        when(jdbc.queryForList(contains("from plm_product_bom b"),eq(42L),eq(42L),eq(10L),eq(20L))).thenReturn(List.of(Map.of("bomStatus","released","bomType","mbom","processStatus","locked","currentFormal",false)));
        assertThatThrownBy(()->policy.requireFinal(42,state,refs)).hasMessageContaining("当前工程确认");
        when(jdbc.queryForList(contains("from plm_product_bom b"),eq(42L),eq(42L),eq(10L),eq(20L))).thenReturn(List.of());
        assertThatThrownBy(()->policy.requireFinal(42,state,refs)).hasMessageContaining("未关联同一产品");
    }

    @Test void changingBomContentOrSelectedVersionRequiresAReasonAndReconfirmation(){
        actor();route(false);var state=state(false);policy.confirm(42,state,references(),"");
        var changed=new ArrayList<>(references());changed.set(0,Map.of("type","bom","id",10L,"versionNo","V1","content",Map.of("technical","变更部件")));
        assertThat(policy.summary(42,state,changed)).containsEntry("current",false);
        assertThatThrownBy(()->policy.confirm(42,state,changed,"")).hasMessageContaining("变更原因");
        assertThat(policy.confirm(42,state,changed,"更换验证后的部件方案")).containsEntry("revision",2L);
        assertThat(policy.summary(42,state,changed)).containsEntry("current",true);
    }

    @Test void publishingTheSameTechnicalVersionsOrUpdatingPricesDoesNotRequireDuplicateAdoption(){
        actor();route(false);var state=state(false);
        var initial=new ArrayList<>(references());initial.set(0,Map.of("type","bom","id",10L,"versionNo","V1","content",Map.of("header",Map.of("status","draft","updated_at","before","unit_cost",10,"quantity",2),"items",List.of(Map.of("item_code","PC","quantity",1)))));
        policy.confirm(42,state,initial,"");
        var released=new ArrayList<>(references());released.set(0,Map.of("type","bom","id",10L,"versionNo","V1","content",Map.of("header",Map.of("status","released","updated_at","after","unit_cost",12,"quantity",2L),"items",List.of(Map.of("item_code","PC","quantity",1L)))));
        route(true);assertThat(policy.summary(42,state,released)).containsEntry("current",true);assertThatCode(()->policy.requireFinal(42,state,released)).doesNotThrowAnyException();
    }

    @Test void confirmedEngineeringRemainsCurrentAfterTheWorkflowJsonRoundTrip() throws Exception{
        actor();route(true);var state=state(false);policy.confirm(42,state,references(),"");
        var mapper=new ObjectMapper();Map<String,Object> restored=mapper.readValue(mapper.writeValueAsString(state),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        assertThat(policy.summary(42,restored,references())).containsEntry("current",true);
        assertThatCode(()->policy.requireFinal(42,restored,references())).doesNotThrowAnyException();
    }

    @Test void invalidSupplierAndIncompleteAssignmentRemainActionableGaps(){
        actor();supplier("inactive");var state=state(true);
        assertThatThrownBy(()->policy.confirm(42,state,List.of(),"")).hasMessageContaining("供应商尚未启用");
        var plan=new LinkedHashMap<>(DevelopmentWorkflowStore.map(state.get("planDraft")));plan.put("supplierAssignments",List.of(Map.of("mode","internal","internalName","工程车间","role","processing")));state.put("planDraft",plan);
        assertThatThrownBy(()->policy.confirm(42,state,List.of(),"")).hasMessageContaining("范围");
        assertThat(state).doesNotContainKey("engineeringBasis");
    }

    @Test void directPurchaseDoesNotInventManufacturingBomButRequiresTheSameConfirmedFinishedSupplierAsCost(){
        actor();supplier("active");var state=state(true);policy.confirm(42,state,List.of(),"");
        state.put("releaseDraft",Map.of("unitCostVersionId",7L));state.put("unitCostVersions",List.of(Map.of("id",7L,"basisType","supplier_quote","adoptedQuote",Map.of("supplierCode","SUP-B"))));
        assertThatThrownBy(()->policy.requireFinal(42,state,List.of())).hasMessageContaining("供应商与已确认成品供方不一致");
        state.put("unitCostVersions",List.of(Map.of("id",7L,"basisType","supplier_quote","adoptedQuote",Map.of("supplierCode","SUP-A"))));
        assertThatCode(()->policy.requireFinal(42,state,List.of())).doesNotThrowAnyException();verifyNoInteractions(jdbc);
    }

    @Test void internalProductionNeedsAnExplicitRealMakerAndNoSupplierViewPermission(){
        actor();route(false);var state=state(false);policy.confirm(42,state,references(),"");
        verify(access,never()).requirePermission("supplier:view");verifyNoInteractions(suppliers);
        var plan=new LinkedHashMap<>(DevelopmentWorkflowStore.map(state.get("planDraft")));plan.put("supplierAssignments",List.of(assignment("internal","","processing"),assignment("internal","","processing")));state.put("planDraft",plan);
        assertThatThrownBy(()->policy.confirm(42,state,references(),"重复")).hasMessageContaining("不能重复");
    }

    @Test void switchingBrandsOrReorderingReferenceListsDoesNotCreateDuplicateEngineeringState(){
        actor();route(false);var state=state(false);policy.confirm(42,state,references(),"");
        var refs=new ArrayList<>(references());Collections.reverse(refs);assertThat(policy.summary(42,state,refs)).containsEntry("current",true);
        var plan=new LinkedHashMap<>(DevelopmentWorkflowStore.map(state.get("planDraft")));plan.put("phoneModelIds",List.of(9L));state.put("planDraft",plan);
        assertThat(policy.summary(42,state,refs)).containsEntry("current",false);
    }

    @Test void supplierSnapshotsAndNestedConfirmationIdentityHonorSupplierObjectPermissions(){
        var original=Map.of("basis",Map.of("supplierAssignments",List.of(assignment("supplier","SUP-A","component"),assignment("internal","","processing"))));
        doThrow(new BusinessException(40103,"没有权限")).when(access).requirePermission("supplier:view");
        String masked=policy.redact(original).toString();assertThat(masked).doesNotContain("SUP-A").contains("suppliersRestricted=true","工程车间");assertThat(original.toString()).contains("SUP-A");
    }

    @Test void reopeningAnIncompleteSupplierDraftRemainsEditableAndRepeatedSnapshotsUseOneScopeCheck(){
        var draft=Map.of("supplierAssignments",List.of(Map.of("mode","supplier","role","component","supplierCode","")));
        assertThat(policy.redact(draft)).isEqualTo(draft);verifyNoInteractions(suppliers);
        supplier("active");var snapshot=Map.of("supplierAssignments",List.of(assignment("supplier","SUP-A","component")));
        assertThat(policy.redact(Map.of("planDraft",snapshot,"plan",snapshot,"history",List.of(snapshot)))).isNotNull();
        verify(suppliers,times(1)).requireSupplier("SUP-A");
    }
}

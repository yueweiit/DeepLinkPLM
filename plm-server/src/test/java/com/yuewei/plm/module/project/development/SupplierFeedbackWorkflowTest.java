package com.yuewei.plm.module.project.development;

import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.partner.SupplierProfileService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupplierFeedbackWorkflowTest {
    final PilotAccessService access=mock(PilotAccessService.class);
    final SupplierProfileService suppliers=mock(SupplierProfileService.class);
    final ProductDevelopmentService host=mock(ProductDevelopmentService.class);
    final SupplierFeedbackWorkflow feedback=new SupplierFeedbackWorkflow(access,suppliers);

    SupplierFeedbackWorkflowTest(){
        doCallRealMethod().when(suppliers).requireSupplierForNewUse(anyString());
        when(suppliers.requireSupplier(anyString())).thenAnswer(call->Map.of("supplierId","SUP-A".equals(call.getArgument(0))?1L:2L,"supplierCode",call.getArgument(0),"supplierName","真实供方"+call.getArgument(0),"version",3L,"status","active"));
        when(host.refs(any())).thenCallRealMethod();when(host.snapshotReferences(eq(42L),anyList())).thenAnswer(call->((List<DevelopmentModels.MaterialReference>)call.getArgument(1)).stream().map(ref->Map.<String,Object>of("type","file","id",ref.id(),"name","照片.png","versionNo","V1")).toList());
    }
    Map<String,Object> input(String code,String result){var value=new LinkedHashMap<String,Object>();value.put("supplierCode",code);value.put("result",result);value.put("scope","透明背板");value.put("reason","技术上无法实现薄壁透明件");value.put("references",List.of());return value;}
    Map<String,Object> event(long id,Map<String,Object> data){return Map.of("eventId",id,"kind","supplier_feedback","data",data,"createdAt","2026-10-23T12:00:00","createdBy","工程师");}
    Map<String,Object> prepare(Map<String,Object> input,List<Map<String,Object>> events){return feedback.prepare(42L,1,input,events,host);}
    List<Map<String,Object>> items(List<Map<String,Object>> events){return (List<Map<String,Object>>)feedback.projection(events).get("items");}
    Map<String,Object> summary(List<Map<String,Object>> events,Map<String,Object> plan){return feedback.summary(events,plan,Map.of("SUP-A",1L,"SUP-B",2L),true);}
    Map<String,Object> change(long id,String role,boolean pending){return Map.of("eventId",id,"kind","supplier_change","data",Map.of("replacementPending",pending,"before",Map.of("supplierAssignments",List.of(Map.of("mode","supplier","supplierCode","SUP-A","supplierId",1L,"role",role))),"after",Map.of("supplierAssignments",List.of())));}

    @Test void listSummaryCountsLatestUnvoidedLogicalFeedbackAndDistinctIdsRatherThanNames(){
        var one=prepare(input("SUP-A","cannot_accept"),List.of());var two=prepare(input("SUP-B","cannot_accept"),List.of());
        DevelopmentWorkflowStore.map(two.get("supplierSnapshot")).put("supplierName",DevelopmentWorkflowStore.map(one.get("supplierSnapshot")).get("supplierName"));
        var events=new ArrayList<>(List.of(event(10L,one),event(11L,two),event(12L,one)));
        assertThat(summary(events,Map.of())).containsEntry("cannotAcceptSupplierCount",2L).containsEntry("nextSupplierPending",false).containsEntry("hasFeedback",true);
        var correction=input("SUP-A","can_accept");correction.put("feedbackId",10L);correction.put("changeReason","仅修正第一轮方案");events.add(event(13L,prepare(correction,events)));
        assertThat(summary(events,Map.of())).containsEntry("cannotAcceptSupplierCount",2L);
        events.add(event(14L,prepare(Map.of("feedbackId",12L,"voided",true,"changeReason","重复轮次作废"),events)));
        assertThat(summary(events,Map.of())).containsEntry("cannotAcceptSupplierCount",1L);
    }

    @Test void pendingUsesLatestRealMoldChangeAndNeverInfersItFromNegativeFeedback(){
        var negative=event(10L,prepare(input("SUP-A","cannot_accept"),List.of()));
        assertThat(summary(List.of(negative),Map.of())).containsEntry("nextSupplierPending",false);
        var events=List.of(negative,change(11L,"mold",true),change(12L,"component",false));
        assertThat(summary(events,Map.of())).containsEntry("nextSupplierPending",true);
        assertThat(summary(List.of(change(20L,"mold",false),change(11L,"mold",true)),Map.of())).containsEntry("nextSupplierPending",false);
        assertThat(summary(events,Map.of("supplierAssignments",List.of(Map.of("mode","supplier","role","mold","supplierCode","SUP-B"))))).containsEntry("nextSupplierPending",false);
    }

    @Test void onlyAnActualMoldUndertakerSuppressesPendingIncludingExistingInternalAssignments(){
        var events=List.of(change(10L,"mold",true));
        for(var row:List.of(Map.of("mode","internal","role","mold","internalName","工程组"),Map.of("mode","supplier","role","mold","supplierCode","SUP-B")))
            assertThat(summary(events,Map.of("supplierAssignments",List.of(row)))).containsEntry("nextSupplierPending",false);
        for(var row:List.of(Map.of("mode","internal","role","mold","internalName",""),Map.of("mode","internal","role","component","internalName","工程组"),Map.of("mode","internal","role","processing","internalName","工程组")))
            assertThat(summary(events,Map.of("supplierAssignments",List.of(row)))).containsEntry("nextSupplierPending",true);
    }

    @Test void voidingAllFeedbackDoesNotEraseTheRealPendingChange(){
        var one=event(10L,prepare(input("SUP-A","cannot_accept"),List.of()));
        var voided=event(12L,prepare(Map.of("feedbackId",10L,"voided",true,"changeReason","误录"),List.of(one)));
        assertThat(summary(List.of(one,voided,change(11L,"mold",true)),Map.of())).containsEntry("cannotAcceptSupplierCount",0L).containsEntry("hasFeedback",false).containsEntry("nextSupplierPending",true);
    }

    @Test void restrictedSummaryNeverReturnsPartialCountsOrPendingAndPerformsNoSupplierLookups(){
        var events=List.of(event(10L,prepare(input("SUP-A","cannot_accept"),List.of())),event(11L,prepare(input("SUP-B","cannot_accept"),List.of())),change(12L,"mold",true));
        clearInvocations(suppliers,access);
        var hidden=Map.of("cannotAcceptSupplierCount",0L,"nextSupplierPending",false,"hasFeedback",false,"restricted",true);
        assertThat(feedback.summary(events,Map.of(),Map.of("SUP-A",1L),true)).isEqualTo(hidden);
        assertThat(feedback.summary(events,Map.of(),Map.of("SUP-A",1L,"SUP-B",2L),false)).isEqualTo(hidden);
        assertThat(feedback.summary(List.of(change(12L,"mold",true)),Map.of(),Map.of("SUP-B",2L),true)).isEqualTo(hidden);
        verifyNoInteractions(suppliers,access);
    }

    @Test void emptyAndPositiveFeedbackHaveNeutralSummariesAndForeignSupplierIdentityIsRestricted(){
        assertThat(summary(List.of(),Map.of())).isEqualTo(Map.of("cannotAcceptSupplierCount",0L,"nextSupplierPending",false,"hasFeedback",false,"restricted",false));
        var positive=event(10L,prepare(input("SUP-A","can_accept"),List.of()));
        assertThat(summary(List.of(positive),Map.of())).containsEntry("cannotAcceptSupplierCount",0L).containsEntry("hasFeedback",true);
        assertThat(feedback.summary(List.of(positive),Map.of(),Map.of("SUP-A",99L),true)).containsEntry("restricted",true).containsEntry("hasFeedback",false);
    }

    @Test void blankCurrentPlanPlaceholdersAreNotSupplierPermissionFailures(){
        for(String code:Arrays.asList("",null,"   ")){
            var placeholder=new LinkedHashMap<String,Object>(Map.of("mode","supplier","role","component","scope","","basis","","internalName",""));placeholder.put("supplierCode",code);
            var plan=Map.<String,Object>of("supplierAssignments",List.of(placeholder));var original=new LinkedHashMap<>(placeholder);
            assertThat(summary(List.of(),plan)).isEqualTo(Map.of("cannotAcceptSupplierCount",0L,"nextSupplierPending",false,"hasFeedback",false,"restricted",false));
            assertThat(placeholder).isEqualTo(original);
        }
        verifyNoInteractions(access,suppliers);
    }

    @Test void blankPlaceholderDoesNotHideRealFeedbackOrChangeMoldUndertakingRules(){
        var placeholder=Map.<String,Object>of("mode","supplier","role","component","supplierCode"," ");
        var events=List.of(event(10L,prepare(input("SUP-A","cannot_accept"),List.of())),event(11L,prepare(input("SUP-B","cannot_accept"),List.of())),change(12L,"mold",true));
        assertThat(summary(events,Map.of("supplierAssignments",List.of(placeholder)))).isEqualTo(Map.of("cannotAcceptSupplierCount",2L,"nextSupplierPending",true,"hasFeedback",true,"restricted",false));
        for(var undertaker:List.of(Map.<String,Object>of("mode","supplier","role","mold","supplierCode","SUP-B"),Map.<String,Object>of("mode","internal","role","mold","internalName","工程组")))
            assertThat(summary(events,Map.of("supplierAssignments",List.of(placeholder,undertaker)))).containsEntry("cannotAcceptSupplierCount",2L).containsEntry("nextSupplierPending",false).containsEntry("restricted",false);
    }

    @Test void ignoringAnEmptyPlanRowNeverRelaxesFeedbackChangeOrNonemptySupplierVisibility(){
        var placeholder=Map.<String,Object>of("mode","supplier","role","component","supplierCode","");
        var plan=Map.<String,Object>of("supplierAssignments",List.of(placeholder));
        var hidden=Map.of("cannotAcceptSupplierCount",0L,"nextSupplierPending",false,"hasFeedback",false,"restricted",true);
        assertThat(feedback.summary(List.of(),plan,Map.of(),false)).isEqualTo(hidden);
        assertThat(summary(List.of(),Map.of("supplierAssignments",List.of(placeholder,Map.of("mode","supplier","role","component","supplierCode","UNAUTHORIZED"))))).isEqualTo(hidden);
        var saved=prepare(input("SUP-A","cannot_accept"),List.of());
        for(var invalid:List.of(Map.<String,Object>of("supplierCode","UNAUTHORIZED","supplierId",99L),Map.<String,Object>of("supplierCode","SUP-A","supplierId",99L),Map.<String,Object>of("supplierCode","","supplierId",1L),Map.<String,Object>of())){
            var data=new LinkedHashMap<>(saved);data.put("supplierSnapshot",invalid);
            assertThat(summary(List.of(event(10L,data)),plan)).isEqualTo(hidden);
        }
        assertThat(feedback.summary(List.of(change(12L,"mold",true)),plan,Map.of("SUP-B",2L),true)).isEqualTo(hidden);
    }

    @Test void twoSuppliersCanBothDeclineWithoutInventingNextSupplierDateOrSample(){
        var a=prepare(input("SUP-A","cannot_accept"),List.of());var b=prepare(input("SUP-B","cannot_accept"),List.of(event(10L,a)));
        var rows=items(List.of(event(11L,b),event(10L,a)));
        assertThat(rows).hasSize(2);assertThat(rows.get(0)).containsEntry("feedbackId",10L).containsEntry("revision",1L).containsEntry("recordedBy","工程师").containsEntry("feedbackDate",null);
        assertThat(rows.get(1).toString()).contains("SUP-B","真实供方SUP-B");assertThat(rows.toString()).doesNotContain("sampleRoundId","nextSupplier");
        assertThat(a).containsEntry("releaseCurrent",false);verify(access,times(2)).requireProduct(42L);
    }

    @Test void correctionsAndVoidingAppendRevisionsAndKeepOriginalSupplierAndAttachmentOwnership(){
        var original=input("SUP-A","cannot_accept");original.put("references",List.of(Map.of("type","file","id",9L)));original.put("feedbackDate","2026-10-22");
        var one=event(10L,prepare(original,List.of()));var changed=input("SUP-A","can_accept");changed.put("feedbackId",10L);changed.put("changeReason","确认实际限制后修正结论");changed.put("reason","需要加厚后可承接");
        var two=event(12L,prepare(changed,List.of(one)));var remove=Map.<String,Object>of("feedbackId",10L,"voided",true,"changeReason","误录其他方案反馈");
        var three=event(13L,prepare(remove,List.of(two,one)));var latest=items(List.of(one,three,two)).get(0);
        assertThat(latest).containsEntry("feedbackId",10L).containsEntry("eventId",13L).containsEntry("revision",3L).containsEntry("voided",true).containsEntry("result","can_accept");
        var history=(List<Map<String,Object>>)latest.get("history");assertThat(history).hasSize(3);assertThat(history.get(0).toString()).contains("照片.png","2026-10-22","cannot_accept");
        assertThat(history.get(1)).containsEntry("previousEventId",10L).containsEntry("changeReason","确认实际限制后修正结论");
        assertThat(DevelopmentWorkflowStore.map(one.get("data"))).doesNotContainKey("history").containsEntry("revision",1L);
        assertThatThrownBy(()->prepare(remove,List.of(one,two,three))).hasMessageContaining("已作废");
    }

    @Test void feedbackCannotChangeSupplierOrEditAnotherProjectsEventAndCorrectionsRequireReasons(){
        var original=event(10L,prepare(input("SUP-A","pending"),List.of()));var changed=input("SUP-B","cannot_accept");changed.put("feedbackId",10L);changed.put("changeReason","修改");
        assertThatThrownBy(()->prepare(changed,List.of(original))).hasMessageContaining("归属供应商不能更改");
        changed.put("feedbackId",99L);assertThatThrownBy(()->prepare(changed,List.of(original))).hasMessageContaining("不属于本项目");
        changed.put("feedbackId",10L);changed.put("supplierCode","SUP-A");changed.remove("changeReason");assertThatThrownBy(()->prepare(changed,List.of(original))).hasMessageContaining("更正／作废原因");
    }

    @Test void negativeResultRequiresTechnicalReasonAndDateMustBeRealButUnknownDateCanStayNull(){
        var input=input("SUP-A","cannot_accept");input.put("reason","");assertThatThrownBy(()->prepare(input,List.of())).hasMessageContaining("技术原因");
        input.put("reason","无法成型");input.put("feedbackDate","2026-02-30");assertThatThrownBy(()->prepare(input,List.of())).hasMessageContaining("有效的反馈日期");
        input.put("feedbackDate",null);assertThat(prepare(input,List.of())).containsEntry("feedbackDate",null);
    }

    @Test void attachmentsUseTheExistingProjectReferenceResolverAndRejectBomAndMalformedRefs(){
        var input=input("SUP-A","pending");input.put("references",List.of(Map.of("type","bom","id",9L)));
        assertThatThrownBy(()->prepare(input,List.of())).hasMessageContaining("仅支持本项目");verify(host,never()).snapshotReferences(anyLong(),anyList());
        input.put("references","forged");assertThatThrownBy(()->prepare(input,List.of())).hasMessageContaining("附件格式");
        input.put("references",List.of(Map.of("type","file","id",9L)));when(host.snapshotReferences(eq(42L),anyList())).thenThrow(new BusinessException(40000,"资料未关联本项目或没有访问权限"));
        assertThatThrownBy(()->prepare(input,List.of())).hasMessageContaining("没有访问权限");
    }

    @Test void onlyExplicitNewNegativeFeedbackMayRequestCurrentSupplierRelease(){
        var input=input("SUP-A","can_accept");input.put("releaseCurrent",true);assertThatThrownBy(()->prepare(input,List.of())).hasMessageContaining("仅无法承接");
        input.put("result","cannot_accept");var saved=prepare(input,List.of());assertThat(saved).containsEntry("releaseCurrent",true);
        input.put("feedbackId",10L);input.put("changeReason","更正");assertThatThrownBy(()->prepare(input,List.of(event(10L,saved)))).hasMessageContaining("不会重新变更");
    }

    @Test void missingSupplierPermissionHidesWholeFeedbackIncludingReasonsAndPhotosInDetailAndRawLogs(){
        var original=input("SUP-A","cannot_accept");original.put("references",List.of(Map.of("type","file","id",9L)));var event=event(10L,prepare(original,List.of()));
        doThrow(new BusinessException(40103,"没有权限")).when(access).requirePermission("supplier:view");
        assertThat(feedback.projection(List.of(event))).containsEntry("items",List.of()).containsEntry("restricted",true);
        assertThat(feedback.redact(event).toString()).contains("supplierFeedbackRestricted=true").doesNotContain("SUP-A","薄壁透明件","照片.png");
        var release=Map.of("supplierFeedbackEventId",10L,"reason","技术上无法实现薄壁透明件","before",Map.of("supplierAssignments",List.of(Map.of("supplierCode","SUP-A"))),"after",Map.of("supplierAssignments",List.of()));
        assertThat(feedback.redact(release).toString()).contains("supplierFeedbackRestricted=true").doesNotContain("SUP-A","薄壁透明件");
    }

    @Test void lostFilePermissionMasksPhotosInCurrentAndHistoricalRevisionsWithoutMutatingStoredFacts(){
        var original=input("SUP-A","pending");original.put("references",List.of(Map.of("type","file","id",9L)));var event=event(10L,prepare(original,List.of()));
        doThrow(new BusinessException(40103,"附件未授权")).when(access).requireFile(9L);
        var row=items(List.of(event)).get(0);assertThat(row).containsEntry("references",List.of()).containsEntry("referencesRestricted",true);
        assertThat(row.toString()).doesNotContain("照片.png");assertThat(event.toString()).contains("照片.png");
    }

    @Test void omittedReferencesOnCorrectionPreserveTheOpaqueExistingAttachmentSnapshots(){
        var original=input("SUP-A","pending");original.put("references",List.of(Map.of("type","file","id",9L)));var event=event(10L,prepare(original,List.of()));
        var correction=input("SUP-A","cannot_accept");correction.put("feedbackId",10L);correction.put("changeReason","收到进一步技术反馈");correction.remove("references");
        var changed=prepare(correction,List.of(event));assertThat(changed.get("references")).isEqualTo(DevelopmentWorkflowStore.map(event.get("data")).get("references"));
        verify(host,times(1)).snapshotReferences(eq(42L),anyList());
    }

    @Test void standaloneSupplierChangeRequiresAccessToBothOldAndNewSuppliersButInternalChangesRemainVisible(){
        var change=Map.of("reason","原供方薄壁技术无法实现","before",Map.of("supplierAssignments",List.of(Map.of("mode","supplier","supplierCode","SUP-A"))),"after",Map.of("supplierAssignments",List.of(Map.of("mode","supplier","supplierCode","SUP-B"))));
        assertThat(feedback.redact(change)).isEqualTo(change);
        when(suppliers.requireSupplier("SUP-B")).thenThrow(new BusinessException(40103,"新供方未授权"));
        assertThat(feedback.redact(change).toString()).contains("suppliersRestricted=true").doesNotContain("薄壁技术","SUP-A","SUP-B");
        var internal=Map.of("reason","内部工位调整","before",Map.of("supplierAssignments",List.of(Map.of("mode","internal","internalName","工程车间"))),"after",Map.of("supplierAssignments",List.of()));
        doThrow(new BusinessException(40103,"供方权限缺失")).when(access).requirePermission("supplier:view");assertThat(feedback.redact(internal)).isEqualTo(internal);
        assertThat(change.toString()).contains("薄壁技术");
    }
}

package com.yuewei.plm.module.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MoldSchedulePolicyTest {
    private final ObjectMapper json=new ObjectMapper();
    private Map<String,Object> row(String style) {Map<String,Object> r=new LinkedHashMap<>();r.put("styleName",style);r.put("moldSetId",1L);r.put("assetNumber","MS-000001");r.put("compatibleModels","Apple iPhone");r.put("holidayDays",5);r.put("extraDays",0);r.put("openedDate","2026-09-28");r.put("pcAcceptanceStatus","pending");r.put("tpuAcceptanceStatus","pending");r.put("pcStatus","making");r.put("tpuStatus","making");r.put("deliveryStatus","making");return r;}
    @Test void calendarSuggestionsUseExactOriginalStyleCyclesAndDoNotOverwriteConfirmedDates() {
        Map<String,String> expected=Map.of("MB冰盾","2026-10-15","MHA超队","2026-10-16","MBA亮甲2.0","2026-10-17");
        expected.forEach((style,date)->{var r=row(style);r.put("plannedFactoryArrivalDate","2026-10-30");MoldScheduleTypes.enrich(r);assertThat(r).containsEntry("suggestedDeliveryDate",date).containsEntry("plannedFactoryArrivalDate","2026-10-30").containsEntry("suggestedAcceptanceDate","2026-10-31").containsEntry("suggestedProductionDate","2026-11-03");});
        var unknown=row("亮甲别名");MoldScheduleTypes.enrich(unknown);assertThat(unknown.get("suggestedDeliveryDate")).isNull();
    }
    @Test void forecastPlanAndActualStaySeparateAndExtraDaysRemainExplicit() {
        var r=row("MG金刚甲");r.put("extraDays",2);r.put("forecastDeliveryDate","2026-10-21");r.put("actualMexicoArrivalDate","2026-10-05");MoldScheduleTypes.enrich(r);
        assertThat(r).containsEntry("suggestedDeliveryDate","2026-10-17").containsEntry("suggestedFactoryDate","2026-10-28").containsEntry("forecastDeliveryDate","2026-10-21").doesNotContainKey("plannedMoldDeliveryDate").containsEntry("marketEligible",false);
    }
    @Test void passedBothPartsWithValidDatesIsRequiredForExplicitMarketConfirmation() {
        var r=row("MB冰盾");r.put("marketReadyDate","2026-10-21");assertThatThrownBy(()->MoldScheduleTypes.validate(Map.of(),r,List.of())).hasMessageContaining("均有效到厂");
        for(String p:List.of("pc","tpu")) {r.put(p+"AcceptanceStatus","passed");r.put(p+"FactoryArrivalDate","2026-10-20");r.put(p+"AcceptanceDate","2026-10-21");}
        assertThatCode(()->MoldScheduleTypes.validate(Map.of(),r,List.of())).doesNotThrowAnyException();r.put("marketReadyDate","2026-10-20");assertThatThrownBy(()->MoldScheduleTypes.validate(Map.of(),r,List.of())).hasMessageContaining("不得早于");
    }
    @Test void dateCorrectionsNeedReasonAndDoNotAddAModelOrBomWrite() {
        var c=new CollaborationTypes.CellChange("actualMexicoArrivalDate",json.valueToTree("2026-10-20"),json.valueToTree("2026-10-21"),null);
        assertThatThrownBy(()->MoldScheduleTypes.validate(Map.of("actualMexicoArrivalDate","2026-10-20"),Map.of("actualMexicoArrivalDate","2026-10-21"),List.of(c))).isInstanceOf(BusinessException.class).hasMessageContaining("更正已保存日期");
        var plan=new CollaborationTypes.CellChange("plannedMoldDeliveryDate",json.valueToTree("2026-10-20"),json.valueToTree("2026-10-21"),null);
        assertThatCode(()->MoldScheduleTypes.validate(Map.of("plannedMoldDeliveryDate","2026-10-20"),Map.of("plannedMoldDeliveryDate","2026-10-21"),List.of(plan))).doesNotThrowAnyException();
    }
    @Test void signedDatesUseTheSetAuthorityNotThePcTpuPartTable() {
        var f=CollaborationFields.field("mold_set","pcSignedDate");assertThat(CollaborationFields.partField(f)).isFalse();assertThat(f.group()).isEqualTo("mexico");
        assertThat(CollaborationFields.partField(CollaborationFields.field("mold_set","pcFactoryArrivalDate"))).isTrue();
        assertThat(CollaborationFields.MOLD).extracting(CollaborationFields.Field::key).doesNotHaveDuplicates();
    }
    @Test void overdueTasksDependOnConfirmedDueDatesNotFormulaSuggestionsOrProjectArchive() {
        var r=row("MB冰盾");MoldScheduleTypes.enrich(r);assertThat(MoldSchedulePolicy.tasksFor(r,LocalDate.parse("2026-11-01"))).noneMatch(MoldScheduleTypes.Task::overdue);
        r.put("plannedMoldDeliveryDate","2026-10-15");r.put("projectStatus","archived");var tasks=MoldSchedulePolicy.tasksFor(r,LocalDate.parse("2026-11-01"));assertThat(tasks).anySatisfy(t->{assertThat(t.taskKey()).isEqualTo("mold_complete");assertThat(t.overdue()).isTrue();assertThat(t.dueField()).isEqualTo("plannedMoldDeliveryDate");assertThat(t.overdueDays()).isEqualTo(17);});
    }
    @Test void pausedExecutionDoesNotGenerateTasksOrOverdueRemindersAndResumingRetainsThePlan() {
        var r=row("MB冰盾");r.put("plannedMoldDeliveryDate","2026-10-01");r.put("schedulePaused",true);
        var today=LocalDate.parse("2026-10-14");MoldSchedulePolicy.enrichTasks(r,today);
        assertThat(r.get("tasks")).isEqualTo(List.of());assertThat(r.get("overdueTasks")).isEqualTo(List.of());
        assertThat(MoldSchedulePolicy.tasksFor(r,today,"all")).isEmpty();
        assertThat(r).containsEntry("plannedMoldDeliveryDate","2026-10-01");r.put("schedulePaused",false);
        assertThat(MoldSchedulePolicy.tasksFor(r,today)).anyMatch(MoldScheduleTypes.Task::overdue);
    }
    @Test void deadlineTodayIsNotOverdueAndCompletingSavedNodesClearsOnlyThoseNodes() {
        var r=row("MB冰盾");r.put("plannedMoldDeliveryDate","2026-10-13");var today=LocalDate.parse("2026-10-13");
        assertThat(MoldSchedulePolicy.tasksFor(r,today)).noneMatch(MoldScheduleTypes.Task::overdue);
        r.put("plannedMoldDeliveryDate","2026-10-12");r.put("pcStatus","ready");
        assertThat(MoldSchedulePolicy.tasksFor(r,today)).anyMatch(t->t.taskKey().equals("mold_complete")&&t.overdue());
        r.put("tpuStatus","ready");r.put("actualShipDate","2026-10-12");
        assertThat(MoldSchedulePolicy.tasksFor(r,today)).noneMatch(MoldScheduleTypes.Task::overdue);
    }
    @Test void countryArrivalAndOnePassedPartDoNotCompleteFactoryOrAcceptanceTasks() {
        var r=row("MB冰盾");r.put("actualMexicoArrivalDate","2026-10-10");r.put("plannedFactoryArrivalDate","2026-10-11");r.put("plannedAcceptanceDate","2026-10-12");
        r.put("pcFactoryArrivalDate","2026-10-11");r.put("pcAcceptanceStatus","passed");r.put("pcAcceptanceDate","2026-10-12");
        MoldSchedulePolicy.enrichTasks(r,LocalDate.parse("2026-10-13"));
        @SuppressWarnings("unchecked") var overdue=(List<MoldScheduleTypes.Task>)r.get("overdueTasks");
        assertThat(overdue).extracting(MoldScheduleTypes.Task::taskKey).containsExactly("factory_arrival","acceptance");
        r.put("tpuFactoryArrivalDate","2026-10-12");r.put("tpuAcceptanceStatus","failed");r.put("tpuAcceptanceDate","2026-10-12");
        assertThat(MoldSchedulePolicy.tasksFor(r,LocalDate.parse("2026-10-13"))).anyMatch(t->t.taskKey().equals("acceptance")&&t.overdue()).noneMatch(t->t.taskKey().equals("factory_arrival"));
    }
    @Test void missingPlansShareThePolicyButNeverAcquireInventedDeadlinesOrChangeLegacyTasks() {
        var r=row("MB冰盾");r.put("itemId",9L);r.put("plannedMoldDeliveryDate","2020-01-01");r.remove("openedDate");
        var today=LocalDate.parse("2026-10-13");
        assertThat(MoldSchedulePolicy.tasksFor(r,today)).hasSize(5).allMatch(t->t.category().equals("milestone"));
        var missing=MoldSchedulePolicy.tasksFor(r,today,"missing");
        assertThat(missing).extracting(MoldScheduleTypes.Task::taskKey).containsExactly("opening_plan","factory_plan","acceptance_plan","production_plan");
        assertThat(missing).allSatisfy(task->{assertThat(task.dueDate()).isNull();assertThat(task.dueField()).isNull();assertThat(task.overdue()).isFalse();assertThat(task.overdueDays()).isZero();assertThat(task.itemId()).isEqualTo(9L);});
        r.put("openedDate","2019-12-31");r.put("plannedFactoryArrivalDate","2020-01-08");r.put("plannedAcceptanceDate","2020-01-09");r.put("plannedProductionDate","2020-01-12");
        assertThat(MoldSchedulePolicy.tasksFor(r,today,"missing")).isEmpty();
        assertThatThrownBy(()->MoldSchedulePolicy.tasksFor(r,today,"unknown")).isInstanceOf(BusinessException.class);
    }
    @Test void arrivalReviewAndCountryArrivalFillInActivateFromSavedValuesAndDisappearOnlyWhenConfirmed() {
        var r=row("MB冰盾");var today=LocalDate.parse("2026-10-13");
        assertThat(MoldSchedulePolicy.tasksFor(r,today,"all")).noneMatch(t->Set.of("arrival_review","mexico_arrival").contains(t.taskKey()));
        r.put("plannedMoldDeliveryDate","2026-10-01");r.put("plannedFactoryArrivalDate","2026-10-08");r.put("actualShipDate","2026-10-01");
        assertThat(MoldSchedulePolicy.tasksFor(r,today,"all")).filteredOn(t->Set.of("arrival_review","mexico_arrival").contains(t.taskKey())).hasSize(2).allSatisfy(t->{assertThat(t.overdue()).isFalse();assertThat(t.dueDate()).isNull();});
        r.put("arrivalConfirmedBasisDate","2026-10-01");r.put("actualMexicoArrivalDate","2026-10-07");
        assertThat(MoldSchedulePolicy.tasksFor(r,today,"all")).noneMatch(t->Set.of("arrival_review","mexico_arrival").contains(t.taskKey())).anyMatch(t->t.taskKey().equals("factory_arrival"));
        r.put("plannedMoldDeliveryDate","2026-10-02");
        assertThat(MoldSchedulePolicy.tasksFor(r,today,"review")).singleElement().satisfies(t->assertThat(t.taskKey()).isEqualTo("arrival_review"));
    }
    @Test void taskDefinitionsExposeRegisteredEditableInputsAndKeepReadonlyReviewContextSeparate() {
        var definitions=MoldSchedulePolicy.definitions();assertThat(definitions).hasSize(11).extracting(MoldScheduleTypes.TaskDefinition::taskKey).doesNotHaveDuplicates();
        definitions.forEach(definition->{
            assertThat(definition.inputFields()).isNotEmpty();
            definition.inputFields().forEach(key->assertThatCode(()->CollaborationFields.field("mold_set",key)).doesNotThrowAnyException());
            definition.supportFields().stream().filter(key->!key.equals("arrivalConfirmedBasisDate")).forEach(key->assertThatCode(()->CollaborationFields.field("mold_set",key)).doesNotThrowAnyException());
            if(!definition.category().equals("milestone")) assertThat(definition.dueField()).isNull();
        });
        assertThat(MoldSchedulePolicy.taskValuesSql()).contains("null::date","s.actual_ship_date is not null","s.arrival_confirmed_basis_date is not distinct from s.planned_mold_delivery_date");
        assertThat(MoldSchedulePolicy.overdueSql()).doesNotContain("arrival_confirmed_basis_date","actual_mexico_arrival_date");
        var row=row("原始款式别名");row.put("productLineName","当前产品平台名");row.put("phoneModelCodeName","当前型号编码名");
        assertThat(MoldSchedulePolicy.tasksFor(row,LocalDate.parse("2026-10-13"))).allSatisfy(task->{assertThat(task.styleName()).isEqualTo("当前产品平台名");assertThat(task.compatibleModels()).isEqualTo("当前型号编码名");});
        assertThat(row).containsEntry("styleName","原始款式别名").containsEntry("compatibleModels","Apple iPhone");
    }
}

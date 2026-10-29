package com.yuewei.plm.module.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MoldScheduleSheetPolicyTest {
    ObjectMapper json=new ObjectMapper();
    Map<String,Object> current(){return new LinkedHashMap<>(Map.of("openedDate","2026-10-01","plannedMoldDeliveryDate","2026-10-18","forecastDeliveryDate","2026-10-18","forecastFactoryDate","2026-10-25","holidayDays",0,"extraDays",0));}
    MoldScheduleSheetParser.SourceRow row(){return new MoldScheduleSheetParser.SourceRow(3,"key","style","brand","model",Map.of("plannedMoldDeliveryDate","2026-10-15","forecastFactoryDate","2026-10-22","actualShipDate","2026-10-17"),"",true,List.of());}
    @Test void initial_source_authority_replaces_old_dates_but_does_not_certify_arrival(){var result=MoldScheduleSheetPolicy.decide(current(),row(),json.createObjectNode());assertThat(result.values()).containsEntry("plannedMoldDeliveryDate","2026-10-15").containsEntry("forecastFactoryDate","2026-10-22").doesNotContainKeys("plannedFactoryArrivalDate","pcAcceptanceDate");}
    @Test void protected_manual_plan_drives_its_forecast_and_dependent_arrival(){ObjectNode state=json.createObjectNode();state.withObject("/plannedMoldDeliveryDate").put("manual",true);var current=current();current.put("plannedMoldDeliveryDate","2026-10-20");var result=MoldScheduleSheetPolicy.decide(current,row(),state);assertThat(result.values()).doesNotContainKey("plannedMoldDeliveryDate").containsEntry("forecastDeliveryDate","2026-10-20").containsEntry("forecastFactoryDate","2026-10-27");assertThat(result.protectedFields()).contains("plannedMoldDeliveryDate");}
    @Test void manually_cleared_date_stays_cleared_and_is_not_reintroduced_through_a_formula(){ObjectNode state=json.createObjectNode();state.withObject("/plannedMoldDeliveryDate").put("manual",true);var current=current();current.put("plannedMoldDeliveryDate",null);var result=MoldScheduleSheetPolicy.decide(current,row(),state);assertThat(result.values()).doesNotContainKey("plannedMoldDeliveryDate").containsEntry("forecastDeliveryDate",null).containsEntry("forecastFactoryDate",null);}
    @Test void independent_manual_arrival_and_actual_dates_are_protected(){ObjectNode state=json.createObjectNode();for(String field:List.of("forecastFactoryDate","actualShipDate"))state.withObject("/"+field).put("manual",true);var result=MoldScheduleSheetPolicy.decide(current(),row(),state);assertThat(result.values()).doesNotContainKeys("forecastFactoryDate","actualShipDate");assertThat(result.protectedFields()).containsExactlyInAnyOrder("forecastFactoryDate","actualShipDate");}
    @Test void repeated_source_has_no_fact_changes_and_restoring_follow_requires_removing_protection(){var current=current();current.putAll(row().dates());current.put("forecastDeliveryDate","2026-10-15");assertThat(MoldScheduleSheetPolicy.decide(current,row(),json.createObjectNode()).values()).isEmpty();}
}

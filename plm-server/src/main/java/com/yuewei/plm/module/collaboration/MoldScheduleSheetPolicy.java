package com.yuewei.plm.module.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.yuewei.plm.module.collaboration.CollaborationFields.same;

/** One field policy for initial adoption, manual refresh and scheduled refresh. */
public final class MoldScheduleSheetPolicy {
    private MoldScheduleSheetPolicy() {}
    public static final List<String> KEYS=List.of("openedDate","plannedMoldDeliveryDate","forecastDeliveryDate","forecastFactoryDate","plannedFactoryArrivalDate","actualShipDate","actualMexicoArrivalDate");
    public record Decision(Map<String,Object> values,List<String> protectedFields) {}
    public static Decision decide(Map<String,Object> current,MoldScheduleSheetParser.SourceRow row,JsonNode state) {
        Map<String,Object> incoming=new LinkedHashMap<>(row.dates());
        // The source plan is also the current forecast unless that forecast was manually maintained.
        if(incoming.containsKey("plannedMoldDeliveryDate"))incoming.put("forecastDeliveryDate",incoming.get("plannedMoldDeliveryDate"));
        List<String> protectedFields=new ArrayList<>();
        for(String key:KEYS)if(state.path(key).path("manual").asBoolean()) {
            if(incoming.containsKey(key))protectedFields.add(key);
            incoming.remove(key);
        }
        if(state.path("plannedMoldDeliveryDate").path("manual").asBoolean()&&!state.path("forecastDeliveryDate").path("manual").asBoolean())
            incoming.put("forecastDeliveryDate",current.get("plannedMoldDeliveryDate"));
        // A formula based on the old source delivery must follow the effective system input.
        boolean manualDelivery=state.path("plannedMoldDeliveryDate").path("manual").asBoolean()||state.path("forecastDeliveryDate").path("manual").asBoolean();
        if(row.arrivalFormula()&&manualDelivery&&incoming.containsKey("forecastFactoryDate")) {
            Map<String,Object> effective=new LinkedHashMap<>(current);effective.putAll(incoming);effective.put("forecastFactoryDate",null);
            if(effective.get("forecastDeliveryDate")==null&&effective.get("plannedMoldDeliveryDate")==null)incoming.put("forecastFactoryDate",null);
            else {MoldScheduleTypes.enrich(effective);incoming.put("forecastFactoryDate",effective.get("suggestedFactoryDate"));}
        }
        incoming.entrySet().removeIf(e->same(current.get(e.getKey()),e.getValue()));
        return new Decision(Collections.unmodifiableMap(incoming),List.copyOf(protectedFields));
    }
}

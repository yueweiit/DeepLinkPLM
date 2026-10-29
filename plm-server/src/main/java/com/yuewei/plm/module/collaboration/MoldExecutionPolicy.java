package com.yuewei.plm.module.collaboration;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.yuewei.plm.module.collaboration.CollaborationFields.*;
import static com.yuewei.plm.module.collaboration.CollaborationTypes.*;

/** A paused application permits recording past facts, never authorising new execution. */
final class MoldExecutionPolicy {
    private static final Set<String> FACT_FIELDS = Set.of("pcStatus", "tpuStatus", "pcPhysicalStatus", "tpuPhysicalStatus",
        "pcCurrentLocation", "tpuCurrentLocation", "pcLocationDetail", "tpuLocationDetail",
        "pcAcceptanceStatus", "tpuAcceptanceStatus", "pcAcceptanceIssues", "tpuAcceptanceIssues");

    static void validate(Map<String,Object> current, Map<String,Object> next, List<CellChange> changes, LocalDate today) {
        for (CellChange change : changes) {
            String key=change.field();
            if (same(current.get(key), next.get(key))) continue;
            Field f=field("mold_set",key);
            if (MoldScheduleTypes.ACTUAL_DATE_KEYS.contains(key)) {
                if (next.get(key)!=null && parseDate(next.get(key).toString()).isAfter(today))
                    throw invalid("申请已暂停，只能补录已经发生的实际日期");
                requireReason(change);
            } else if (FACT_FIELDS.contains(key)) {
                requireReason(change);
            } else if (f.kind().equals("date") || f.kind().equals("count") || key.equals("marketReady")) {
                throw invalid("申请已暂停，不能推进或修改执行计划；请先在申请中处理恢复或终止");
            }
        }
    }
    private static void requireReason(CellChange change) {
        if (change.reason()==null || change.reason().isBlank())
            throw invalid("申请已暂停，补录已发生的事实须填写核对原因");
    }
    private MoldExecutionPolicy() {}
}

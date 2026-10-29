package com.yuewei.plm.module.moldapplication;

import java.util.Set;

/** Read policy shared by applications and schedules; raw OA statuses remain intact. */
public final class MoldApplicationBusinessStatus {
    private MoldApplicationBusinessStatus() {}
    public static final Set<String> VALUES=Set.of("draft","submitting","submission_unknown","awaiting_approval","approved","rejected","withdrawn","terminated","approval_unknown");
    public static final String SQL="""
        case
          when lower(coalesce(a.remote_result,''))='refuse' or a.execution_exception_json->>'state'='rejected' then 'rejected'
          when a.remote_status='TERMINATED' or a.status='terminated' then
            case when a.previous_application_id is not null or exists(select 1 from plm_mold_application revision where revision.previous_application_id=a.application_id) then 'withdrawn' else 'terminated' end
          when a.status in ('draft','submitting','submission_unknown') then a.status
          when a.status in ('withdrawing','withdrawal_unknown') then 'approval_unknown'
          when a.approval_granted then 'approved'
          when a.remote_status='RUNNING' then 'awaiting_approval'
          else 'approval_unknown'
        end
        """;
}

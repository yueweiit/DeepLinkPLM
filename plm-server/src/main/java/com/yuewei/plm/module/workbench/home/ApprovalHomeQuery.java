package com.yuewei.plm.module.workbench.home;

import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.yuewei.plm.module.workbench.home.WorkbenchHomeModels.*;

/** Preserves the original approval-center actor/status/object scope; adds count + pagination. */
@Service @RequiredArgsConstructor
public class ApprovalHomeQuery {
    private final JdbcTemplate jdbc;
    private final PilotAccessService access;
    public HomePage page(Query query) {
        access.requirePermission("approval:view");
        String from = " from plm_approval_task t join plm_approval_instance i on i.approval_instance_id=t.approval_instance_id";
        String where = " where t.deleted_flag=0 and i.deleted_flag=0 and t.status='pending' and i.status='in_progress' and t.approver_user_id=? and ((i.object_type='Product' and "+access.visibleSqlClause("product","i.object_id")+") or (i.object_type='Attachment' and "+access.visibleSqlClause("file","i.object_id")+"))";
        long total = Objects.requireNonNull(jdbc.queryForObject("select count(*)"+from+where,Long.class,access.actorId()));
        var rows = jdbc.queryForList("""
            select 'generic:'||t.approval_task_id as key,'generic' as source,'approval_list' as target,
              t.approval_task_id::text as "businessId",t.node_code as title,i.object_type as "objectType",
              i.object_id as "objectId",t.created_at::text as "sourceUpdatedAt",'查看原审批任务' as "nextAction"
            """+from+where+" order by t.created_at,t.approval_task_id limit ? offset ?",access.actorId(),query.getSize(),(long)(query.getPage()-1)*query.getSize());
        return WorkbenchHomeModels.page("generic",query,rows,total,null);
    }
}

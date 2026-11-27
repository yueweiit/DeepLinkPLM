package com.yuewei.plm.module.workbench.home;

import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.yuewei.plm.module.workbench.home.WorkbenchHomeModels.*;

/** A saved-state projection, never a second stage readiness/advancement engine. */
@Service @RequiredArgsConstructor
public class ResearchHomeQuery {
    private final JdbcTemplate jdbc;
    private final PilotAccessService access;
    private static final List<String> KEYS = List.of("initiation","drawing","prototype","sample-confirm","mold-design","mold","pilot","ship");
    private static final List<String> STAGES = List.of("立项","画3D产品图","3D打样","样品确认","模具设计","开模","试产","发模");
    private static final List<String> ACTIONS = List.of("负责人确认立项","采用开发型号与3D图纸","登记本轮实际收样","负责人确认代表样","确认设计与原申请授权","逐套试模与验证","试产、技术资料及成本定型","确认实际发模批次");
    static final String REVISION = "jsonb_typeof(s.data->'revision')='object'";
    static final String ACTIVE = "((p.status not in ('released','archived') and not coalesce(p.current_step_no=8 and p.timeline_current_confirmed,false)) or (p.status='released' and "+REVISION+"))";
    static final String EXCEPTION = "(p.lock_status='frozen' or (s.data->'profileDraft' is not null and (coalesce(s.data->'profileDraft'->>'ownerUserId','0')='0' or (s.data->'profileDraft'->>'ownerUserId')::bigint is distinct from p.owner_user_id)) or p.expected_delivery_date < (now() at time zone 'Asia/Shanghai')::date)";

    public HomePage page(Query query, boolean exceptions) {
        access.requireDomesticView(); access.requirePermission("project:view");
        String from = " from plm_product p left join plm_product_development_state s on s.product_id=p.product_id left join sys_user u on u.user_id=p.owner_user_id";
        String where = " where p.deleted_flag=0 and p.product_type='product_line' and p.development_mode='launch_v1' and ("+access.visibleSqlClause("product","p.product_id")+") and "+ACTIVE+" and coalesce(p.lock_status,'')<>'abandoned'";
        List<Object> args = new ArrayList<>();
        if (exceptions || "mine".equals(query.getScope())) { where += " and p.owner_user_id=?"; args.add(access.actorId()); }
        if (exceptions) where += " and "+EXCEPTION;
        else where += " and coalesce(p.lock_status,'')<>'frozen'";
        if (query.getKeyword()!=null && !query.getKeyword().isBlank()) {
            where += " and concat_ws(' ',p.product_name,p.product_code) ilike ?";
            args.add("%"+query.getKeyword().trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");
        }
        Long total = jdbc.queryForObject("select count(*)"+from+where,Long.class,args.toArray());
        args.add(query.getSize()); args.add((long)(query.getPage()-1)*query.getSize());
        var rows = jdbc.queryForList("""
            select p.product_id as "projectId",p.product_id::text as "businessId",p.product_code as code,
              p.product_name as title,p.owner_user_id as "ownerUserId",coalesce(u.display_name,u.username) as "ownerName",
              case when jsonb_typeof(s.data->'profileDraft')='object' then coalesce((s.data->'profileDraft'->>'ownerUserId')::bigint,0) else p.owner_user_id end as "confirmationOwnerId",
              coalesce((s.data->'revision'->>'stage')::int,p.current_step_no,1) as stage,
              (jsonb_typeof(s.data->'revision')='object') as revision,p.lock_status as "lockStatus",
              p.expected_delivery_date::text as "dueDate",p.updated_at::text as "sourceUpdatedAt",
              coalesce(p.expected_delivery_date < (now() at time zone 'Asia/Shanghai')::date,false) as overdue
            """+from+where+" order by p.updated_at desc,p.product_id desc limit ? offset ?",args.toArray());
        rows.forEach(row -> {
            int stage = Math.max(1, Math.min(8, ((Number)row.get("stage")).intValue()));
            row.put("stage",stage); row.put("stageKey",KEYS.get(stage-1)); row.put("stageName",STAGES.get(stage-1));
            row.put("key","research:"+row.get("projectId")); row.put("source","research"); row.put("target","research");
            row.put("nextAction",ACTIONS.get(stage-1));
            List<String> reasons = new ArrayList<>();
            if ("frozen".equals(row.get("lockStatus"))) reasons.add("项目已冻结，仅可查看");
            if (!Objects.equals(row.get("ownerUserId"),row.get("confirmationOwnerId"))) reasons.add("草稿确认负责人与项目负责人不同，请进入项目核对");
            if (Objects.equals(row.get("confirmationOwnerId"),0L)) reasons.add("草稿未分配确认负责人");
            if (Boolean.TRUE.equals(row.get("overdue"))) reasons.add("项目总交期已逾期（非阶段截止日）");
            row.put("blocker",String.join("；",reasons));
        });
        return WorkbenchHomeModels.page("research",query,rows,total==null?0:total,null);
    }
}

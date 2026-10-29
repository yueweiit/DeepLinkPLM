package com.yuewei.plm.module.project.development;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.development.DevelopmentFinanceService;
import com.yuewei.plm.module.development.DevelopmentTypes;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.project.development.DevelopmentRules.*;

/** Read-only directory. Latest parallel records stay in project state; finance keeps its original ledger. */
@Service @RequiredArgsConstructor
public class ProductResearchBusinessService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PilotAccessService access;
    private final ProductDevelopmentService development;
    private final DevelopmentFinanceService finance;

    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> page(String kind,String keyword,String status,Long projectId,int page,int size) {
        access.requireDomesticView();access.requirePermission("project:view");
        require(Set.of("cost","promotion","presale").contains(kind),"业务类型无效");
        if(kind.equals("cost"))access.requireFinanceRead();
        if(projectId!=null){access.requireProduct(projectId);require(jdbc.queryForObject("select count(*) from plm_product where product_id=? and development_mode='launch_v1' and product_type='product_line' and deleted_flag=0",Long.class,projectId)>0,"请选择新版新品项目");}
        int current=Math.max(1,page),limit=Math.max(1,Math.min(100,size));
        String from=" from plm_product p left join plm_product_development_state s on s.product_id=p.product_id ";
        boolean records=!kind.equals("cost");
        if(records) from+=" cross join lateral jsonb_array_elements(coalesce(s.data->'"+(kind.equals("promotion")?"promotions":"presales")+"','[]'::jsonb)) r(data) ";
        String where=" where p.product_type='product_line' and p.development_mode='launch_v1' and p.deleted_flag=0 and ("+access.visibleSqlClause("product","p.product_id")+")";
        List<Object> args=new ArrayList<>();
        if(projectId!=null){where+=" and p.product_id=?";args.add(projectId);}
        if(keyword!=null&&!keyword.isBlank()) {
            where+=" and concat_ws(' ',p.product_name,p.product_code,p.model"+(records?",r.data->>'target',r.data->>'channel',r.data->>'action',r.data->'phoneSnapshot'->>'modelName'":"")+") ilike ?";
            args.add("%"+keyword.trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");
        }
        if(status!=null&&!status.isBlank()&&!status.equals("all")) {
            require(records && (kind.equals("promotion")?Set.of("pending","working","done","cancelled"):Set.of("reserved","confirmed","cancelled","fulfilled")).contains(status),"业务状态无效");
            where+=" and r.data->>'status'=?";args.add(status);
        }
        String qty=kind.equals("presale")?",coalesce(sum(case when r.data->>'status'<>'cancelled' then (r.data->>'quantity')::bigint else 0 end),0) as \"quantity\"":"";
        String aggregates="select count(*) as \"recordCount\",count(distinct p.product_id) as \"projectCount\""+qty;
        var summary=jdbc.queryForMap(aggregates+from+where,args.toArray());
        long total=((Number)summary.get("recordCount")).longValue();
        String select="""
            select p.product_id as "productId",p.product_name as "productName",p.product_code as "productCode",p.model,p.status as "projectStatus",
            coalesce((s.data->'revision'->>'stage')::int,p.current_step_no,1) as "currentStepNo",
            (s.data->'plan'->>'planVersion')::int as "currentPlanVersion",s.data->'plan'->>'launchDate' as "launchDate"
            """;
        if(records)select+=",r.data::text as \"recordJson\"";
        else select+=",(s.data->'unitCostDraft' is not null) as \"hasUnitCostDraft\",jsonb_array_length(coalesce(s.data->'unitCostVersions','[]'::jsonb)) as \"unitCostVersionCount\"";
        var pageArgs=new ArrayList<>(args);pageArgs.add(limit);pageArgs.add((current-1L)*limit);
        var rows=jdbc.queryForList(select+from+where+" order by p.product_id desc"+(records?",(r.data->>'id')::bigint desc":"")+" limit ? offset ?",pageArgs.toArray());
        for(var row:rows) {
            if(records) {
                try {row.put("record",development.redact(json.readValue((String)row.remove("recordJson"),new TypeReference<Map<String,Object>>(){})));}
                catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid stored development record",e);}
            } else {
                var q=new DevelopmentTypes.Query();q.setProjectId(((Number)row.get("productId")).longValue());q.setSize(1);
                row.put("budgetCount",finance.page("budget",q).getTotalElements());row.put("expenseCount",finance.page("expense",q).getTotalElements());
                row.put("budgets",finance.summary("budget",q));row.put("expenses",finance.summary("expense",q));
            }
        }
        return Map.of("content",rows,"page",current,"size",limit,"totalElements",total,"totalPages",(total+limit-1)/limit,"summary",summary);
    }
}

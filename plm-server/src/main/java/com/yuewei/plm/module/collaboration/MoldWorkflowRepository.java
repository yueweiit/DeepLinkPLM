package com.yuewei.plm.module.collaboration;

import com.yuewei.plm.module.moldapplication.MoldApplicationBusinessStatus;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared read projection for an application's execution authority over its new physical set. */
public final class MoldWorkflowRepository {
    private final JdbcTemplate jdbc;
    public MoldWorkflowRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    private static final String HISTORICAL_ONLY="a.source_system='dingtalk' and jsonb_typeof(a.execution_resolution_json->'historicalAssociation')='object' and a.execution_resolution_json->'historicalAssociation'->>'associationOnly'='true'";
    private static final String PAUSED="a.deleted_flag=0 and a.execution_intent<>'reuse' and a.execution_status in ('paused','terminated') and not coalesce(("+HISTORICAL_ONLY+"),false)";
    public static String activeSql(String moldAlias) {
        if(!moldAlias.matches("[a-zA-Z][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid SQL alias");
        return "not exists(select 1 from plm_mold_application a where a.mold_set_id="+moldAlias+".mold_set_id and "+PAUSED+")";
    }
    public boolean isPaused(long moldSetId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_mold_application a where a.mold_set_id=? and "+PAUSED+")",Boolean.class,moldSetId));
    }
    /** The conversion transaction is the sole writer of active/ready execution authority. */
    public boolean isAuthorized(long applicationId,long projectId,long moldSetId){
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_mold_application where application_id=? and deleted_flag=0 and (project_id=? or product_line_id=?) and mold_set_id=? and execution_status='active' and provisioning_status='ready' and approval_granted)",Boolean.class,applicationId,projectId,projectId,moldSetId));
    }
    public void lockExecution(long moldSetId) {
        jdbc.queryForList("select a.application_id from plm_mold_application a where a.mold_set_id=? and a.deleted_flag=0 and a.execution_intent<>'reuse' and not coalesce(("+HISTORICAL_ONLY+"),false) order by a.application_id for update", Long.class, moldSetId);
    }
    public void enrich(List<Map<String,Object>> rows) {
        if(rows.isEmpty())return;
        List<Long> ids=rows.stream().map(row->row.get("moldSetId")).filter(Number.class::isInstance).map(id->((Number)id).longValue()).distinct().toList();
        if(ids.isEmpty())return;
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        Map<Long,List<Map<String,Object>>> links=new HashMap<>();
        jdbc.query("select a.application_id,a.approval_no,a.mold_set_id,a.execution_intent,a.provisioning_status,requested.id phone_model_id,requested.brand_id,requested.model_name,requested.code_item_id,requested_brand.name brand_name,requested_code.code_value phone_model_code,("+MoldApplicationBusinessStatus.SQL+") progress_status,("+PAUSED+") suspended,("+HISTORICAL_ONLY+") historical_association from plm_mold_application a left join plm_phone_model_member requested on requested.id=a.phone_model_id left join plm_phone_brand requested_brand on requested_brand.id=requested.brand_id left join plm_code_item requested_code on requested_code.code_item_id=requested.code_item_id and requested_code.deleted_flag=0 where a.deleted_flag=0 and a.mold_set_id in ("+placeholders+") order by a.application_id desc",rs->{
            Map<String,Object> link=new LinkedHashMap<>();link.put("applicationId",rs.getLong("application_id"));link.put("approvalNo",rs.getString("approval_no"));link.put("progressStatus",rs.getString("progress_status"));link.put("provisioningStatus",rs.getString("provisioning_status"));link.put("executionIntent",rs.getString("execution_intent"));link.put("suspended",rs.getBoolean("suspended"));
            link.put("historicalAssociation",rs.getBoolean("historical_association"));
            if(rs.getObject("phone_model_id")!=null) {
                Map<String,Object> phone=new LinkedHashMap<>();
                for(String[] field:List.of(new String[]{"phoneModelId","phone_model_id"},new String[]{"brandId","brand_id"},new String[]{"brandName","brand_name"},new String[]{"modelName","model_name"},new String[]{"phoneModelCode","phone_model_code"},new String[]{"codeItemId","code_item_id"}))phone.put(field[0],rs.getObject(field[1]));
                link.put("standardPhone",phone);
            }
            links.computeIfAbsent(rs.getLong("mold_set_id"),ignored->new ArrayList<>()).add(link);
        },ids.toArray());
        for(Map<String,Object> row:rows) {
            if(!(row.get("moldSetId") instanceof Number id))continue;
            List<Map<String,Object>> related=links.getOrDefault(id.longValue(),List.of());
            row.put("applicationLinks",related);row.put("schedulePaused",related.stream().anyMatch(link->Boolean.TRUE.equals(link.get("suspended"))));
            row.put("applicationPlanned",row.get("openedDate")==null&&related.stream().anyMatch(link->"ready".equals(link.get("provisioningStatus"))&&!"reuse".equals(link.get("executionIntent"))&&!Boolean.TRUE.equals(link.get("historicalAssociation"))));
        }
    }
}

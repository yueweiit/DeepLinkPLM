package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.moldapplication.MoldApplicationForms;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** The live OA parser also reconciles historical authority; it never provisions projects or stock. */
public class V20261014_1740__mold_authorization_history extends BaseJavaMigration {
    @Override public void migrate(Context context)throws Exception {
        migrate(new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(),true)));
    }
    /** Root preflight may call MoldApplicationForms.approvalEvidence(rawSnapshot) without any database. */
    public Map<String,Object> migrate(JdbcTemplate jdbc)throws Exception {
        var json=new ObjectMapper();int granted=0,paused=0;var needsTask=new ArrayList<Long>();
        for(var row:jdbc.queryForList("select application_id,source_system,mold_set_id,execution_status,execution_resolution_json::text,raw_snapshot::text from plm_mold_application where deleted_flag=0 and raw_snapshot is not null order by application_id for update")) {
            long id=((Number)row.get("application_id")).longValue();var evidence=MoldApplicationForms.approvalEvidence(json.readTree(row.get("raw_snapshot").toString()));
            boolean authorized="plm".equals(row.get("source_system"))?evidence.taskAuthorized():evidence.granted();
            if(evidence.authorization()!=null&&!evidence.taskAuthorized()&&evidence.exception()==null)needsTask.add(id);
            String execution=Objects.toString(row.get("execution_status"),"pending");
            if(row.get("mold_set_id")!=null&&!"terminated".equals(execution)) {
                boolean resolved=false;if(row.get("execution_resolution_json")!=null&&evidence.exception()!=null)resolved=Objects.equals(json.readTree(row.get("execution_resolution_json").toString()).path("approvalState").asText(),evidence.exception().get("key"));
                execution=authorized||resolved?"active":"paused";
            }
            String proof=evidence.authorization()==null?null:json.writeValueAsString(evidence.authorization());
            jdbc.update("update plm_mold_application set authorization_json=case when authorization_json is null or (coalesce(authorization_json->>'evidenceSource','')<>'task' and ?='task') then ?::jsonb else authorization_json end,execution_exception_json=?::jsonb,approval_granted=?,execution_status=? where application_id=?",evidence.authorization()==null?null:evidence.authorization().get("evidenceSource"),proof,evidence.exception()==null?null:json.writeValueAsString(evidence.exception()),authorized,execution,id);
            if(authorized)granted++;if("paused".equals(execution))paused++;
        }
        return Map.of("approved",granted,"paused",paused,"operationOnlyApplications",needsTask);
    }
}

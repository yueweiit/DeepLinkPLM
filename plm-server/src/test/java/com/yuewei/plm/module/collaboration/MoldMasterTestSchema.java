package com.yuewei.plm.module.collaboration;

import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared local rollback fixture; never a migration runner for production. */
public final class MoldMasterTestSchema {
    private MoldMasterTestSchema() {}
    public static void apply(JdbcTemplate jdbc) throws Exception {
        if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='plm' and table_name='plm_collaboration_item' and column_name='engraved_code'",Long.class)==0) execute(jdbc,"V20261013_1500__mold_master_overview.sql");
        if(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='plm' and table_name='plm_phone_model_member'",Long.class)==0) execute(jdbc,"V20261013_1510__phone_model_members.sql");
        if(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='plm' and table_name='plm_market_watch_source_projection'",Long.class)==0) execute(jdbc,"V20261013_1700__market_phone_workflow.sql");
        if(jdbc.queryForObject("select count(*) from information_schema.views where table_schema='plm' and table_name='plm_phone_code_brand_effective'",Long.class)==0) execute(jdbc,"V20261014_1000__phone_product_production_metadata.sql");
        if(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='plm' and table_name='plm_collaboration_phone_set_compatibility'",Long.class)==0) execute(jdbc,"V20261014_1730__phone_set_compatibility.sql");
        if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='plm' and table_name='plm_product' and column_name='project_kind'",Long.class)==0) execute(jdbc,"V20261015_1300__project_registration.sql");
    }
    private static void execute(JdbcTemplate jdbc,String name) throws Exception {
        try(var source=MoldMasterTestSchema.class.getResourceAsStream("/db/migration/"+name)) {
            if(source==null) throw new IllegalStateException("Missing rollback schema resource: "+name);
            jdbc.execute(new String(source.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
}

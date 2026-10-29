package com.yuewei.plm.module.masterdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit local migration entrypoint: uses the SAME admin service, never seeds an account or approval. */
@EnabledIfEnvironmentVariable(named="PLM_LOCAL_MASTER_DATA_APPLY",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK,properties={"APP_MASTER_DATA_ENABLED=true","PLM_LOCAL_NO_OUTBOUND=true","PLM_PILOT_DELIVERY_ENABLED=false","DINGTALK_INTEGRATION_ENABLED=false","APP_MOLD_APPLICATION_READ_SYNC_ENABLED=false","APP_MOLD_APPLICATION_WRITE_ENABLED=false","APP_PHONE_WATCH_ENABLED=false","spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"})
@ContextConfiguration(initializers=LocalMasterDataApplyTest.LocalOnly.class)
class LocalMasterDataApplyTest {
    static class LocalOnly implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context){
            String expected="jdbc:postgresql://127.0.0.1:15432/plm_data_management?currentSchema=plm";
            if(!expected.equals(context.getEnvironment().getProperty("spring.datasource.url")))throw new IllegalStateException("Migration entrypoint only accepts the original LOCAL Docker database");
        }
    }
    @Autowired MasterDataImportService imports;@Autowired MasterDataCatalogService catalog;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
    @Test void applyAuthorizedLocalSnapshotWithoutCreatingCredentialsOrEngineeringApprovals() throws Exception {
        var actor=jdbc.queryForMap("select user_id,username,display_name,all_permissions from sys_user where username='mink' and deleted_flag=0 and status='active'");assertEquals(true,actor.get("all_permissions"));
        CurrentUserContext.set(new CurrentUser(((Number)actor.get("user_id")).longValue(),"mink",actor.get("display_name").toString(),true));
        try{
            var protectedFacts=protectedFacts();var untouched=untouchedFacts();
            String snapshot=Objects.requireNonNull(System.getProperty("master.data.apply.snapshot"));
            var preview=imports.preview(new MasterDataImportService.PreviewRequest(snapshot,List.of()));
            assertEquals(3549,((Number)preview.get("skuCount")).intValue());
            var result=imports.commit(new MasterDataImportService.CommitRequest((UUID)preview.get("importId"),preview.get("sourceHash").toString(),preview.get("originalHash").toString(),"按 mink 用户授权，由本地开发助手迁移 CRM／MES 首期快照；已先在隔离库验收，不写服务器，不创建工程通过或外发事件"),null);
            var counts=catalog.options();assertEquals(3549L,counts.get("total"));assertEquals(3380L,counts.get("listed"));assertEquals(164L,counts.get("purchased"));
            var after=protectedFacts();for(var table:protectedFacts.entrySet())table.getValue().fields().forEachRemaining(row->assertEquals(row.getValue(),after.get(table.getKey()).get(row.getKey()),"原对象和人工状态必须保持："+table.getKey()+"/"+row.getKey()));
            assertEquals(untouched,untouchedFacts(),"原审批、模具、附件和研发事实不可改写");
            Path report=Path.of(Objects.requireNonNull(System.getProperty("master.data.apply.report")));assertTrue(report.isAbsolute());assertFalse(Files.exists(report));
            Files.writeString(report,json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("result",result,"counts",counts,"actorId",actor.get("user_id"),"protectedFactsPreserved",true,"untouchedFacts",untouched,"remoteBusinessWrites",0)),StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(report,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }finally{CurrentUserContext.clear();}
    }
    private Map<String,com.fasterxml.jackson.databind.JsonNode> protectedFacts() throws Exception {
        var result=new LinkedHashMap<String,com.fasterxml.jackson.databind.JsonNode>();
        var columns=Map.of("plm_product","product_id,production_status,sales_status,status,source_instance_id,project_kind,project_registered_at,phone_model_member_id","plm_inventory","inventory_id,stock_uom,quantity,opening_stock,unit_cost,currency_code","plm_code_item","code_item_id,production_status,status,code_value","plm_product_bom","product_bom_id,bom_code,version_no,status,released_at,released_by");
        var keys=Map.of("plm_product","product_id","plm_inventory","inventory_id","plm_code_item","code_item_id","plm_product_bom","product_bom_id");
        for(var entry:columns.entrySet()){String key=keys.get(entry.getKey());result.put(entry.getKey(),json.readTree(jdbc.queryForObject("select coalesce(jsonb_object_agg("+key+"::text,to_jsonb(v)-'"+key+"'),'{}'::jsonb)::text from (select "+entry.getValue()+" from "+entry.getKey()+") v",String.class)));}return result;
    }
    private Map<String,String> untouchedFacts(){
        var result=new LinkedHashMap<String,String>();for(String table:List.of("plm_mold_application","plm_collaboration_item","plm_collaboration_item_project","plm_collaboration_mold_set","plm_collaboration_mold_part","plm_collaboration_phone_member","plm_collaboration_phone_set_compatibility","plm_attachment","plm_product_development_state","plm_product_development_round","plm_product_development_release"))result.put(table,jdbc.queryForObject("select md5(coalesce(string_agg(row_value,E'\\n' order by row_value),'')) from (select row_to_json(t)::text row_value from "+table+" t) q",String.class));return result;
    }
}

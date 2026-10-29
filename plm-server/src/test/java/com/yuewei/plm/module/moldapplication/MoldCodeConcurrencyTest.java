package com.yuewei.plm.module.moldapplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Two entry points commit only into a fresh disposable schema, never production/master tables. */
@EnabledIfEnvironmentVariable(named="PLM_MOLD_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/plm_data_management\\?currentSchema=plm")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MoldCodeConcurrencyTest {
    final String schema="mold_code_test_"+UUID.randomUUID().toString().replace("-","");JdbcTemplate admin,jdbc;TransactionTemplate tx;MoldApplicationCodes codes;
    @BeforeAll void isolate(){
        var root=new DriverManagerDataSource(System.getenv("PLM_MOLD_TEST_DB_URL"),"postgres",System.getenv("PLM_MOLD_TEST_DB_PASSWORD"));admin=new JdbcTemplate(root);admin.execute("create schema "+schema);
        var ds=new DriverManagerDataSource(System.getenv("PLM_MOLD_TEST_DB_URL").replace("currentSchema=plm","currentSchema="+schema),"postgres",System.getenv("PLM_MOLD_TEST_DB_PASSWORD"));jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("create table plm_code_item(code_item_id bigserial primary key,code_type text,code_value text,code_name text,status text,sort_order integer,deleted_flag integer default 0,created_by text,updated_by text,unique(code_type,code_value))");
        jdbc.execute("create unique index model_name on plm_code_item(lower(btrim(code_name)))");
        jdbc.execute("create table plm_mold_model_alias(normalized_name text,phone_code text)");
        jdbc.execute("create table plm_mold_application_config(tipo text primary key,product_line_id bigint,product_specific_code text,material_codes jsonb,active boolean default true,version_no bigint default 1)");
        jdbc.execute("create table plm_mold_code_sequence(sequence_key text primary key,last_value integer)");
        jdbc.execute("create table plm_mold_code_reservation(request_id text primary key,request_hash text,application_id bigint,phone_code_item_id bigint,response jsonb,created_by text)");
        var props=new MoldApplicationProperties();props.setCentralCodeEnabled(true);var json=new ObjectMapper();codes=new MoldApplicationCodes(new MoldApplicationRepository(jdbc,json),props,new ProductBusinessCodeGenerator(),json);
    }
    @BeforeEach void seed(){jdbc.execute("truncate plm_code_item,plm_mold_code_reservation,plm_mold_code_sequence,plm_mold_application_config,plm_mold_model_alias");jdbc.update("insert into plm_mold_code_sequence values('phone_model',1294)");jdbc.update("insert into plm_mold_application_config(tipo,product_line_id,product_specific_code,material_codes) values('Fantasia',50,'FA','[{\"code\":\"10\",\"partType\":\"TPU\"},{\"code\":\"20\",\"partType\":\"PC\"}]')");}
    @AfterAll void removeOurDisposableSchema(){if(admin!=null&&schema.matches("mold_code_test_[a-f0-9]{32}"))admin.execute("drop schema "+schema+" cascade");}
    Map<String,Object> reserve(String request,String model){return tx.execute(s->codes.reserve(new MoldApplicationCodes.Request(request,"Fantasia",model,null,null),"test"));}
    @Test void plmAndConnectorConcurrentlyReuseOneReservedModelAndNeverReclaimIt()throws Exception {
        var executor=Executors.newFixedThreadPool(2);try{var a=executor.submit(()->reserve("plm-entry-00000001","Concurrent Phone"));var b=executor.submit(()->reserve("ding-entry-00000001","Concurrent Phone"));assertThat(a.get(10,TimeUnit.SECONDS).get("generatedCodes")).isEqualTo("MFA101295 / MFA201295");assertThat(b.get(10,TimeUnit.SECONDS).get("generatedCodes")).isEqualTo("MFA101295 / MFA201295");}finally{executor.shutdownNow();}
        assertThat(jdbc.queryForObject("select count(*) from plm_code_item",Long.class)).isEqualTo(1L);assertThat(jdbc.queryForObject("select count(*) from plm_mold_code_reservation",Long.class)).isEqualTo(2L);
        assertThat(reserve("plm-next-0000000001","Next Phone").get("phoneModelCode")).isEqualTo("1296");assertThat(reserve("plm-reuse-000000001","Concurrent Phone").get("phoneModelCode")).isEqualTo("1295");
    }
    @Test void reservationExhaustionAndConflictingSourceAliasesStopWithoutChangingTheCounter(){jdbc.update("update plm_mold_code_sequence set last_value=9999");assertThatThrownBy(()->reserve("exhausted-000000001","Phone")).hasMessageContaining("用尽");assertThat(jdbc.queryForObject("select last_value from plm_mold_code_sequence",Integer.class)).isEqualTo(9999);jdbc.update("update plm_mold_code_sequence set last_value=1294");jdbc.update("insert into plm_mold_model_alias values('existing server phone','1294')");assertThatThrownBy(()->reserve("conflict-0000000001","Existing Server Phone")).hasMessageContaining("冲突");assertThat(jdbc.queryForObject("select count(*) from plm_code_item",Long.class)).isZero();}
}

package com.yuewei.plm.module.partner;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Migration rehearsal uses an explicitly named loopback QA database, never application configuration. */
@EnabledIfEnvironmentVariable(named="PLM_SUPPLIER_RANGE_TEST_URL",matches="(?:jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/plm_selection_range_qa|jdbc:postgresql://127\\.0\\.0\\.1:15483/plm_foundation_crud_it\\?currentSchema=plm)")
class SupplierShortCodeMigrationLocalIntegrationTest {
    JdbcTemplate jdbc,admin;TransactionTemplate tx;String schema;
    @BeforeEach void setup() {
        String url=System.getenv("PLM_SUPPLIER_RANGE_TEST_URL"),password=System.getenv("PLM_SUPPLIER_RANGE_TEST_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password));schema="short_codes_qa_"+UUID.randomUUID().toString().replace("-","");admin.execute("create schema "+schema);
        var ds=new DriverManagerDataSource(url.split("\\?",2)[0]+"?currentSchema="+schema,"postgres",password);jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("""
          create table plm_supplier_profile(supplier_code varchar(64) primary key,supplier_name text,short_code varchar(32) not null default '',updated_by text,status text default 'active',deleted_flag int default 0);
          create unique index uk_supplier_short_code on plm_supplier_profile(lower(short_code)) where short_code<>'';
          create table plm_partner_event(supplier_code text,action text,data jsonb);
          create table plm_supplier_range_request(supplier_code text,action text,result jsonb);
          insert into plm_supplier_profile(supplier_code,supplier_name,short_code) values('SUP-1','不可按名称猜身份','XD'),('SUP-2','其他历史供方','旧_号'),('SUP-3','熊大','');
          """);
    }
    @AfterEach void cleanup(){if(admin!=null&&schema!=null)admin.execute("drop schema "+schema+" cascade");}
    void migrate() throws Exception {
        try(var input=getClass().getResourceAsStream("/db/migration/V20261026_1000__supplier_short_code_reservations.sql")){
            String sql=new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8);tx.executeWithoutResult(s->jdbc.execute(sql));
        }
    }
    @Test void migrationKeepsOriginalValuesAndExplicitHistoryWithoutAssigningCodesByName() throws Exception {
        jdbc.update("insert into plm_partner_event values('SUP-2','supplier_metadata','{\"before\":[{\"shortCode\":\"old-b\"}],\"after\":{\"shortCode\":\"旧_号\"}}'::jsonb)");
        jdbc.update("insert into plm_supplier_range_request values('SUP-2','metadata','{\"shortCode\":\"OLD2\"}'::jsonb)");
        migrate();
        assertThat(jdbc.queryForList("select short_code from plm_supplier_profile order by supplier_code",String.class)).containsExactly("XD","旧_号","");
        assertThat(jdbc.queryForList("select short_code from plm_supplier_short_code_reservation where supplier_code='SUP-2' order by short_code",String.class)).containsExactlyInAnyOrder("OLD-B","OLD2","旧_号");
        assertThat(jdbc.queryForObject("select original_short_code from plm_supplier_short_code_reservation where short_code='OLD-B'",String.class)).isEqualTo("old-b");
        assertThat(jdbc.queryForObject("select count(*) from plm_supplier_short_code_reservation where supplier_code='SUP-3'",Long.class)).isZero();
        jdbc.update("update plm_supplier_profile set supplier_name='正常更新名称',short_code=short_code where supplier_code='SUP-2'");
        assertThat(jdbc.queryForObject("select short_code from plm_supplier_profile where supplier_code='SUP-2'",String.class)).isEqualTo("旧_号");
    }
    @Test void legacyOwnershipConflictStopsEntireMigrationInsteadOfChoosingAnOwner() {
        jdbc.update("insert into plm_partner_event values('SUP-2','supplier_metadata','{\"before\":[{\"shortCode\":\"xd\"}]}'::jsonb)");
        assertThatThrownBy(this::migrate).hasMessageContaining("历史归属冲突");
        assertThat(jdbc.queryForObject("select to_regclass('plm_supplier_short_code_reservation') is null",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select short_code from plm_supplier_profile where supplier_code='SUP-1'",String.class)).isEqualTo("XD");
        assertThat(jdbc.queryForObject("select data_type from information_schema.columns where table_schema=? and table_name='plm_supplier_profile' and column_name='short_code'",String.class,schema)).isEqualTo("character varying");
    }
    @Test void explicitInsertAndRenameUseSameReservationButNoHistoricalCodeCanMoveOwners() throws Exception {
        migrate();jdbc.update("insert into plm_supplier_profile(supplier_code,supplier_name,short_code) values('SUP-4','新供方',' own-2 ')");
        assertThat(jdbc.queryForObject("select short_code from plm_supplier_profile where supplier_code='SUP-4'",String.class)).isEqualTo("OWN-2");
        jdbc.update("update plm_supplier_profile set short_code='OWN-3' where supplier_code='SUP-4'");
        assertThatThrownBy(()->jdbc.update("update plm_supplier_profile set short_code='own-2' where supplier_code='SUP-3'")).hasMessageContaining("历史简码保留");
        assertThatThrownBy(()->jdbc.update("update plm_supplier_short_code_reservation set supplier_code='SUP-3' where short_code='OWN-2'")).hasMessageContaining("不可删除或改给");
        assertThatThrownBy(()->jdbc.update("delete from plm_supplier_profile where supplier_code='SUP-4'")).hasMessageContaining("foreign key");
    }
    @Test void onlyExistingXdIdentityIsProtectedRegardlessOfSupplierName() throws Exception {
        migrate();assertThatThrownBy(()->jdbc.update("update plm_supplier_profile set short_code='XD2' where supplier_code='SUP-1'")).hasMessageContaining("不能清空");
        assertThatThrownBy(()->jdbc.update("update plm_supplier_profile set short_code='XD' where supplier_code='SUP-3'")).hasMessageContaining("duplicate key");
        assertThat(jdbc.queryForObject("select short_code from plm_supplier_profile where supplier_code='SUP-3'",String.class)).isEmpty();
    }
}

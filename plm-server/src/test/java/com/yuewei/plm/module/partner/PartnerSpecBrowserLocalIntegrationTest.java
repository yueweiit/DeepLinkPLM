package com.yuewei.plm.module.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIf("localDatabaseConfigured")
class PartnerSpecBrowserLocalIntegrationTest {
    JdbcTemplate jdbc; DataSourceTransactionManager tx; TransactionStatus transaction; PilotAccessService access; SupplierProfileService suppliers; PartnerSpecBrowser browser;
    static String localUrl(){String original=System.getenv("PLM_REFERENCE_BROWSER_TEST_URL");if(original!=null&&original.matches("jdbc:postgresql://127\\.0\\.0\\.1:15443/plm_reference_qa\\?currentSchema=plm"))return original;String qa=System.getenv("PLM_SUPPLIER_RANGE_TEST_URL");return qa!=null&&qa.matches("jdbc:postgresql://127\\.0\\.0\\.1:15446/plm_selection_range_qa")?qa:"";}
    static boolean localDatabaseConfigured(){return !localUrl().isEmpty();}
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(localUrl(),"postgres",System.getenv(localUrl().contains(":15443/")?"PLM_REFERENCE_BROWSER_TEST_PASSWORD":"PLM_SUPPLIER_RANGE_TEST_PASSWORD"));jdbc=new JdbcTemplate(ds);tx=new DataSourceTransactionManager(ds);transaction=tx.getTransaction(new DefaultTransactionDefinition());
        access=mock(PilotAccessService.class);when(access.visibleSqlClause(anyString(),anyString())).thenReturn("true");suppliers=mock(SupplierProfileService.class);
        var store=new PartnerStore(jdbc,new ObjectMapper(),access,mock(OperationLogService.class));browser=new PartnerSpecBrowser(store,suppliers,new PartnerVisibility(access,jdbc));
        jdbc.execute("set local search_path=pg_temp,plm,public");jdbc.execute("""
          create temp table plm_partner_record(record_id bigint,name text,kind text,status text,deleted_flag int default 0);
          create temp table plm_partner_spec(spec_id bigint,record_id bigint,code text,name text,website_sku text,data jsonb,deleted_flag int default 0);
          create temp table plm_phone_brand(id bigint,name text,aliases text[]);
          create temp table plm_phone_model_member(id bigint,brand_id bigint,model_name text,code_item_id bigint,aliases text[]);
          create temp table plm_code_item(code_item_id bigint,code_value text,deleted_flag int default 0);
          insert into plm_phone_brand values(1,'Apple',array['IP']),(2,'Samsung',array['SM']),(3,'Hidden',array['private']);
          insert into plm_code_item values(11,'0185',0),(12,'1211',0),(13,'9999',0);
          insert into plm_phone_model_member values(101,1,'iPhone 17 Pro Max',11,array['17PM']), (102,2,'Galaxy S26 5G',12,array['SM S26']), (103,3,'Hidden model',13,array[]::text[]);
          insert into plm_partner_record values(10,'可见保护壳','product','draft',0),(20,'隐藏保护壳','product','draft',0),(30,'停用保护壳','product','inactive',0);
          insert into plm_partner_spec values
            (501,10,'PS-501','共用透明规格','WEBSITE-501','{"phoneModelIds":[101,102],"sources":[{"supplierCode":"SUP-A","priceCents":1000,"currency":"CNY"}]}'::jsonb,0),
            (502,10,'PS-502','未核对原文 / 4G','WEBSITE-502','{"phoneModelIds":["bad",null,"99999999999999999999999999999999"]}'::jsonb,0),
            (503,20,'PS-503','隐藏规格','WEBSITE-503','{"phoneModelIds":[103]}'::jsonb,0),
            (504,30,'PS-504','历史停用规格','WEBSITE-504','{"phoneModelIds":[101]}'::jsonb,0);
          """);
    }
    @AfterEach void rollback(){if(transaction!=null&&!transaction.isCompleted())tx.rollback(transaction);}
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> result){return (List<Map<String,Object>>)result.get("content");}
    @Test void directoryRestrictsItsPhoneRowsWhileTheCommonSpecRetainsEveryExplicitPhone() {
        var q=Map.of("brandId","1","activeOnly","true","supplierCode","SUP-A","size","50");
        assertThat(rows(browser.phones(q))).extracting(r->r.get("phoneModelId")).containsExactly(101L);
        var result=rows(browser.page(Map.of("brandId","1","phoneModelId","101","activeOnly","true","supplierCode","SUP-A")));
        assertThat(result).hasSize(1);assertThat(result.get(0)).containsEntry("id",501L);assertThat((List<?>)result.get(0).get("phoneModels")).hasSize(2);assertThat(result.toString()).contains("0185");verify(suppliers,times(2)).requireSupplier("SUP-A");
    }
    @Test void aliasesMultipleSearchTermsAndLegacyUnlinkedNamesRemainSearchableWithoutGuessing() {
        assertThat(rows(browser.page(Map.of("keyword","IP 17 Pro Max","activeOnly","true")))).extracting(r->r.get("id")).containsExactly(501L);
        assertThat(rows(browser.page(Map.of("keyword","SM S26","activeOnly","true")))).extracting(r->r.get("id")).containsExactly(501L);
        assertThat(rows(browser.page(Map.of("unlinkedPhone","true")))).extracting(r->r.get("id")).containsExactly(502L);
        assertThat(browser.phones(Map.of("activeOnly","true"))).containsEntry("unlinkedCount",1L);
    }
    @Test void remotePagesAreNotTruncatedAtTheOldPreloadedOptionsLimitAndSelectedIdsCanBeRestored() {
        jdbc.update("insert into plm_partner_spec select 1000+v,10,'PAGE-'||v,'翻页规格'||v,'EXT-'||v,'{\"phoneModelIds\":[101]}'::jsonb,0 from generate_series(1,1002) v");
        var result=browser.page(Map.of("keyword","PAGE-","page","21","size","50"));assertThat(result).containsEntry("totalElements",1002L);assertThat(rows(result)).hasSize(2);
        assertThat(rows(browser.page(Map.of("ids","2002,501","size","100")))).extracting(r->r.get("id")).containsExactly(501L,2002L);
    }
    @Test void hiddenObjectsCannotLeakTheirBrandFacetsDetailsOrCountAndMoneyRemainsRedacted() {
        when(access.visibleSqlClause("partner","p.record_id")).thenReturn("p.record_id=10");
        var result=browser.page(Map.of());assertThat(result).containsEntry("totalElements",2L);assertThat(result.toString()).doesNotContain("Hidden").doesNotContain("priceCents");
        assertThat(rows(browser.page(Map.of("id","503")))).isEmpty();assertThat(browser.phones(Map.of()).toString()).doesNotContain("Hidden");
        when(access.hasPermission("finance:read")).thenReturn(true);assertThat(browser.page(Map.of("id","501")).toString()).contains("priceCents=1000");
    }
    @Test void inactiveRecordsCanBeRestoredButAreNotOfferedForNewActiveSelections() {
        assertThat(rows(browser.page(Map.of("activeOnly","true","keyword","历史停用")))).isEmpty();
        assertThat(rows(browser.page(Map.of("activeOnly","true","id","504")))).extracting(r->r.get("id")).containsExactly(504L);
    }
    @Test void invalidFiltersAndMissingPermissionFailBeforeReadingBusinessData() {
        assertThatThrownBy(()->browser.page(Map.of("size","10001"))).hasMessageContaining("范围");assertThatThrownBy(()->browser.phones(Map.of("brandId","-1"))).hasMessageContaining("ID");
        doThrow(new BusinessException(40103,"未授权合作产品")).when(access).requirePermission("partner:view");assertThatThrownBy(()->browser.page(Map.of())).hasMessageContaining("未授权");
    }
}

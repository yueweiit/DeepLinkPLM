package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.project.development.*;
import com.yuewei.plm.repository.*;
import com.yuewei.plm.repository.entity.Product;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Both real application entry points commit solely into an empty disposable schema. */
@EnabledIfEnvironmentVariable(named="PLM_MOLD_TEST_DB_URL",matches=".+")
class MoldResearchDraftConcurrencyLocalIntegrationTest {
    @Test void manualAndResearchFirstSavesShareOneIncompleteDraftAndKeepTheFirstEditorsFields()throws Exception {
        String schema="mold_research_test_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getenv("PLM_MOLD_TEST_DB_URL"),password=System.getenv("PLM_MOLD_TEST_DB_PASSWORD");
        LocalMoldTestDatabase.database(url);var admin=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password));LocalMoldTestDatabase.verify(url,admin);admin.execute("create schema "+schema);
        var pool=Executors.newFixedThreadPool(2);
        try{
            var source=new DriverManagerDataSource(url.replace("currentSchema=plm","currentSchema="+schema),"postgres",password);
            var jdbc=new JdbcTemplate(source);var json=new ObjectMapper().findAndRegisterModules();
            for(String table:List.of("plm_product","plm_mold_application","plm_mold_application_config","plm_mold_application_schema","plm_code_item","plm_product_development_state","plm_product_development_event","sys_user","plm_attachment","plm_document_reference","plm_product_bom","plm_process"))jdbc.execute("create table "+table+" (like plm."+table+" including defaults)");
            jdbc.execute("create sequence research_application_seq");jdbc.execute("alter table plm_mold_application alter column application_id set default nextval('research_application_seq')");
            jdbc.update("insert into plm_product(product_id,product_code,product_name,product_type,version_no,status,development_mode,current_step_no,sales_status,created_by,updated_by) values(1,'CONCURRENT-RESEARCH','并发新品','product_line','V1','developing','launch_v1',1,'unconfirmed','qa','qa')");
            var actor=new CurrentUser(1L,"qa","并发验证",true);var access=mock(PilotAccessService.class);
            when(access.actor()).thenReturn(actor);when(access.actorId()).thenReturn(1L);when(access.access()).thenReturn(new PilotAccessService.Access(actor,"administrator",List.of("*"),true,true));
            when(access.hasPermission(anyString())).thenReturn(true);when(access.visibleSqlClause(anyString(),anyString())).thenReturn("true");
            var product=new Product();product.setProductId(1L);product.setProductType("product_line");product.setProductCode("CONCURRENT-RESEARCH");product.setProductName("并发新品");product.setDevelopmentMode("launch_v1");product.setStatus("developing");product.setCurrentStepNo(1);product.setDeletedFlag(0);
            var products=mock(ProductRepository.class);when(products.selectById(1L)).thenReturn(product);
            var development=new ProductDevelopmentService(jdbc,json,access,products,mock(com.yuewei.plm.module.attachment.repository.AttachmentRepository.class),mock(OperationLogService.class),new DevelopmentWorkflowStore(jdbc,json),mock(com.yuewei.plm.module.inventory.service.InventorySupplierService.class),mock(LaunchDevelopmentWorkflow.class),mock(LaunchReviewWorkflow.class),mock(DevelopmentUnitCostWorkflow.class));
            var repo=new MoldApplicationRepository(jdbc,json);var props=new MoldApplicationProperties();props.setEnabled(true);
            var media=mock(MoldApplicationMedia.class);when(media.inheritResearchFiles(anyLong(),anyLong(),anyList(),anyString(),any())).thenReturn(List.of());
            var service=new MoldApplicationService(repo,props,mock(MoldDingTalkClient.class),media,new MoldApplicationCodes(repo,props,new ProductBusinessCodeGenerator(),json),access,json,new DataSourceTransactionManager(source),mock(OperationLogService.class),mock(MoldApplicationDingTalkFiles.class),mock(MoldApplicationBackupImport.class));
            ReflectionTestUtils.setField(service,"productDevelopment",development);
            var barrier=new CyclicBarrier(2);
            var manual=pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return service.save(null,new MoldApplicationService.Draft(json.valueToTree(Map.of("remark","手工入口填写")),1L,null,null,null,"new",null,null,null),null);});
            var research=pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);var draft=new MoldApplicationService.Draft(json.valueToTree(Map.of("remark","研发入口填写")),1L,null,null,null,"new",null,null,null);return service.startFromResearch(new MoldApplicationService.ResearchDraft(1L,null,"concurrent-research-request",0L,draft),null);});
            var a=manual.get(20,TimeUnit.SECONDS);var b=research.get(20,TimeUnit.SECONDS);
            assertThat(a.get("applicationId")).isEqualTo(b.get("applicationId"));assertThat(jdbc.queryForObject("select count(*) from plm_mold_application",Integer.class)).isEqualTo(1);
            assertThat(((com.fasterxml.jackson.databind.JsonNode)a.get("form")).path("remark").asText()).isEqualTo(((com.fasterxml.jackson.databind.JsonNode)b.get("form")).path("remark").asText());
            String remark=repo.row(((Number)a.get("applicationId")).longValue(),false).get("form").toString();assertThat(remark).containsAnyOf("手工入口填写","研发入口填写");
            assertThat(jdbc.queryForObject("select count(*) from plm_product_development_state",Integer.class)).isZero();
        }finally{pool.shutdownNow();try{pool.awaitTermination(5,TimeUnit.SECONDS);}finally{admin.execute("drop schema "+schema+" cascade");}}
    }
}

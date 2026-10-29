package com.yuewei.plm.module.attachment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.attachment.repository.AttachmentRepository;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The fixed loopback clone and transaction-local tables prevent acceptance fixtures touching live facts. */
@EnabledIfEnvironmentVariable(named="PLM_REFERENCE_BROWSER_TEST_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15443/plm_reference_qa\\?currentSchema=plm")
class DocumentReferenceBrowserLocalIntegrationTest {
    JdbcTemplate jdbc; DataSourceTransactionManager tx; TransactionStatus scope; DocumentReferenceBrowser service;
    PilotAccessService access;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(System.getenv("PLM_REFERENCE_BROWSER_TEST_URL"),"postgres",System.getenv("PLM_REFERENCE_BROWSER_TEST_PASSWORD"));
        jdbc=new JdbcTemplate(ds);tx=new DataSourceTransactionManager(ds);scope=tx.getTransaction(new DefaultTransactionDefinition());
        access=mock(PilotAccessService.class);when(access.visibleSqlClause(anyString(),anyString())).thenReturn("true");
        when(access.access()).thenReturn(new PilotAccessService.Access(new CurrentUser(1L,"qa","QA",true),"administrator",List.of("*"),true,true));
        var json=new ObjectMapper();var docs=new DocumentService(jdbc,mock(AttachmentRepository.class),mock(AttachmentService.class),access,json);
        service=new DocumentReferenceBrowser(docs,jdbc,access,json);
        jdbc.execute("set local search_path=pg_temp,plm,public");
        jdbc.execute("""
          create temp table plm_product(product_id bigint,parent_product_id bigint,product_code text,product_name text,product_type text,model text,color text,phone_model_code text,phone_model_member_id bigint,finished_product_code text,project_kind text,project_registered_at timestamp,development_mode text,status text default 'released',updated_at timestamptz default now(),deleted_flag int default 0);
          create temp table plm_product_phone_model(product_id bigint,phone_model_id bigint);
          create temp table plm_product_development_state(product_id bigint,data jsonb);
          create temp table plm_collaboration_item_project(project_id bigint,item_id bigint);
          create temp table plm_phone_brand(id bigint,name text,aliases text[],status text default 'enabled');
          create temp table plm_phone_model_member(id bigint,brand_id bigint,model_name text,code_item_id bigint,aliases text[],status text default 'enabled');
          create temp table plm_code_item(code_item_id bigint,code_type text,code_value text,status text default 'enabled',deleted_flag int default 0);
          create temp table plm_phone_code_brand_effective(code_item_id bigint,brand_id bigint);
          create temp table plm_collaboration_item(item_id bigint,mold_name text,style_name text,engraved_code text,phone_model_code text,compatible_models text,product_line_id bigint,brand_id bigint,deleted_flag int default 0);
          create temp table plm_collaboration_mold_set(mold_set_id bigint,item_id bigint,set_name text,set_no int,deleted_flag int default 0);
          create temp table plm_collaboration_mold_part(mold_set_id bigint,part_type text,original_mold_code text,product_mold_code_id bigint);
          create temp table plm_product_mold_code(product_mold_code_id bigint,product_id bigint,mold_code text,mold_name text,key_code text,deleted_flag int default 0);
          create temp table plm_mold_code_source(source_code_id bigint,source_system text,mold_code text,mold_name text,part_type text,specifications text,source_disabled boolean,phone_model_code text);
          create temp table plm_partner_record(record_id bigint,code text,name text,kind text,deleted_flag int default 0);
          create temp table plm_collaboration_phone_member(item_id bigint,phone_model_id bigint);
          create temp table plm_collaboration_phone_set_compatibility(item_id bigint,mold_set_id bigint,phone_model_id bigint);
          create temp table plm_mold_application(item_id bigint,mold_set_id bigint,phone_model_id bigint,deleted_flag int default 0);
          insert into plm_phone_brand(id,name,aliases) values(1,'Apple',array['IP']),(2,'Samsung',array['SM']);
          insert into plm_code_item(code_item_id,code_type,code_value) values(11,'phone_model','0185'),(12,'phone_model','1211'),(13,'phone_model','5000');
          insert into plm_phone_model_member(id,brand_id,model_name,code_item_id,aliases) values(101,1,'iPhone 17 Pro Max',11,array['17PM']),(102,2,'Galaxy S26',12,array['SM S26']),(103,1,'Combination original / preserved',13,array[]::text[]);
          insert into plm_phone_code_brand_effective values(11,1),(12,2),(13,1);
          insert into plm_product(product_id,product_code,product_name,product_type) values(10,'NBA4030','亮甲 2.0','product_line');
          insert into plm_product(product_id,parent_product_id,product_code,product_name,product_type,model,color,phone_model_code,phone_model_member_id,project_kind,project_registered_at)
            values(20,10,'APPLE-185','Apple 黑色','model_variant','iPhone 17 Pro Max','黑色','0185',101,'model_extension',now()),
              (21,10,'SAMSUNG-1211','Samsung 蓝色','model_variant','Galaxy S26','蓝色','1211',102,null,null),
              (22,10,'UNLINKED','组合原文','model_variant','A / B 4G','原色','5000',null,null,null);
          insert into plm_product(product_id,parent_product_id,product_code,product_name,product_type,model,phone_model_code)
            values(30,20,'SKU-GOOD','成品黑色','sku','iPhone 17 Pro Max','0185'),(31,20,'SKU-CONFLICT','冲突成品','sku','Galaxy S26','1211');
          insert into plm_collaboration_item(item_id,mold_name,style_name,engraved_code,phone_model_code,compatible_models,product_line_id) values(40,'共模主档','亮甲 2.0','1144','0185','iPhone / Samsung',10);
          insert into plm_collaboration_phone_member values(40,101),(40,102);
          insert into plm_collaboration_mold_set(mold_set_id,item_id,set_name,set_no) values(50,40,'第一套',1);
          insert into plm_collaboration_phone_set_compatibility values(40,50,101);
          """);
    }
    @AfterEach void rollback(){if(scope!=null&&!scope.isCompleted())tx.rollback(scope);}
    DocumentReferenceBrowser.Query query(String type){var q=new DocumentReferenceBrowser.Query();q.setType(type);return q;}
    @SuppressWarnings("unchecked") List<Map<String,Object>> content(Map<String,Object> result){return (List<Map<String,Object>>)result.get("content");}
    @Test void brandAndExactModelFilteringPreserveRealIdsLeadingZeroAndProductLevels() {
        var q=query("product");q.setProductType("model_variant");q.setBrandId(1L);var result=service.page(q);
        assertThat(content(result)).extracting(r->((Number)r.get("id")).longValue()).containsExactly(22L,20L);
        q.setPhoneModelId(101L);var only=content(service.page(q));assertThat(only).hasSize(1);assertThat(only.get(0)).containsEntry("id",20L).containsEntry("phoneModelCode","0185").containsEntry("color","黑色");
        q=query("product");q.setProductType("product_line");assertThat(content(service.page(q))).extracting(r->r.get("id")).containsExactly(10L);
    }
    @Test void aCodeCanFilterBrandWithoutInventingAStandardPhoneAndConflictingSkuCannotInheritItsParentsPhone() {
        var q=query("product");q.setKeyword("UNLINKED");var row=content(service.page(q)).get(0);
        assertThat(row.get("modelName")).isEqualTo("A / B 4G");assertThat(row.get("phoneModels").toString()).contains("phoneModelId=null");
        q.setPhoneModelId(103L);assertThat(content(service.page(q))).isEmpty();
        q=query("sku");q.setPhoneModelId(101L);assertThat(content(service.page(q))).extracting(r->r.get("id")).containsExactly(30L);
    }
    @Test void physicalSetsOnlyShowTheirExplicitPhonesAndCommonMastersKeepTheirWholeIdentity() {
        var q=query("mold_set");q.setBrandId(2L);assertThat(content(service.page(q))).isEmpty();
        q.setBrandId(1L);assertThat(content(service.page(q))).extracting(r->r.get("id")).containsExactly(50L);
        q=query("item");q.setBrandId(2L);var row=content(service.page(q)).get(0);assertThat(row).containsEntry("id",40L).containsEntry("code","1144");
        assertThat((List<?>)row.get("phoneModels")).hasSize(2);
        assertThat(content(service.phones(q))).extracting(r->r.get("phoneModelId")).containsExactly(102L);
        q.setBrandId(1L);assertThat(content(service.phones(q))).extracting(r->r.get("phoneModelId")).containsExactly(101L);
    }
    @Test void paginationAndCountsUseTheSameServerScope() {
        jdbc.update("insert into plm_product(product_id,parent_product_id,product_code,product_name,product_type,phone_model_code,phone_model_member_id) select 1000+v,10,'PAGE-'||v,'Page '||v,'model_variant','0185',101 from generate_series(1,52) v");
        var q=query("product");q.setKeyword("PAGE");q.setPage(2);var result=service.page(q);
        assertThat(result).containsEntry("totalElements",52L).containsEntry("totalPages",2L);assertThat(content(result)).hasSize(2);
        q.setPage(1);assertThat(content(service.page(q))).hasSize(50);
    }
    @Test void hiddenProductsCannotLeakThroughBrandOrProductFacetsAndProjectSelectionExcludesSkuArchives() {
        when(access.visibleSqlClause("product","p.product_id")).thenReturn("p.product_id in (10,20)");
        var q=query("product");var result=service.page(q);
        assertThat(content(result)).extracting(r->r.get("id")).containsExactly(20L,10L);
        assertThat(result.get("brands").toString()).doesNotContain("Samsung");
        q.setProjectOnly(true);assertThat(content(service.page(q))).extracting(r->r.get("id")).containsExactly(20L);
    }
    @Test void phoneDirectoryCountsPhonesBeforeListingTheirProductAndColorReferences() {
        var q=query("product");q.setProductType("model_variant");var result=service.phones(q);
        assertThat(result).containsEntry("totalElements",2L).containsEntry("unlinkedCount",1L);
        assertThat(content(result)).extracting(r->r.get("phoneModelId")).containsExactly(102L,101L);
        q.setBrandId(1L);var phone=content(service.phones(q)).get(0);assertThat(phone).containsEntry("phoneModelId",101L).containsEntry("referenceCount",1L).containsEntry("phoneModelCode","0185");
        q.setUnlinkedPhone(true);assertThat(content(service.page(q))).extracting(r->r.get("id")).containsExactly(22L);
    }
    @Test void aliasAndMultiTokenSearchReadTheCurrentStandardDictionary() {
        var q=query("product");q.setKeyword("IP 17 Pro Max");assertThat(content(service.page(q))).extracting(r->r.get("id")).containsExactly(20L);
        q.setKeyword("SM S26");assertThat(content(service.page(q))).extracting(r->r.get("id")).containsExactly(21L);
    }
    @Test void collaborationSelectionUsesItsOwnPermissionAndPreservesExistingProjectOwnership() {
        doThrow(new com.yuewei.plm.common.exception.BusinessException(40103,"无文件读取权限")).when(access).requirePermission("file:read");
        jdbc.update("insert into plm_collaboration_item_project values(21,40)");
        var q=query("product");q.setPhoneModelId(102L);var row=content(service.collaborationPage(q)).get(0);
        assertThat(row).containsEntry("id",21L).containsEntry("linkedItemId",40L);verify(access).requirePermission("collaboration:view");
        q=query("item");q.setBrandId(2L);assertThat(content(service.collaborationPhones(q))).extracting(r->r.get("phoneModelId")).containsExactly(102L);
        var fileQuery=q;assertThatThrownBy(()->service.page(fileQuery)).hasMessageContaining("权限");
        q.setType("sku");var invalid=q;assertThatThrownBy(()->service.collaborationPage(invalid)).hasMessageContaining("协作关联");
    }
    @Test void researchSelectionOnlyReadsTheActualNewProductWorkflowAndDoesNotRequireFilePermission() {
        jdbc.update("update plm_product set development_mode='launch_v1' where product_id=10");
        var q=query("product");assertThat(content(service.researchPage(q))).extracting(r->r.get("id")).containsExactly(10L);verify(access).requirePermission("project:view");verify(access,never()).requirePermission("file:read");
        q.setProductType("model_variant");assertThatThrownBy(()->service.researchPage(q)).hasMessageContaining("新品研发");
    }
    @Test void financeAndSampleMetadataPreserveExactIdsAndNeverExpandAParentToUnrelatedPhones() {
        var rows=new ArrayList<Map<String,Object>>();for(long id:List.of(20L,30L,31L,22L))rows.add(new LinkedHashMap<>(Map.of("id",id)));
        com.yuewei.plm.module.moldreference.PhoneSelectionMetadata.enrich(jdbc,rows,"id","product");
        assertThat(rows.get(0).get("phoneModels").toString()).contains("phoneModelId=101").contains("0185");
        assertThat(rows.get(1).get("phoneModels").toString()).contains("phoneModelId=101");
        assertThat(rows.get(2).get("phoneModels").toString()).doesNotContain("phoneModelId=101");
        assertThat(rows.get(3).get("phoneModels").toString()).contains("phoneModelId=null").contains("A / B 4G");
        var items=new ArrayList<Map<String,Object>>();items.add(new LinkedHashMap<>(Map.of("id",40L)));com.yuewei.plm.module.moldreference.PhoneSelectionMetadata.enrich(jdbc,items,"id","item");assertThat((List<?>)items.get(0).get("phoneModels")).hasSize(2);
        var sets=new ArrayList<Map<String,Object>>();sets.add(new LinkedHashMap<>(Map.of("id",50L)));com.yuewei.plm.module.moldreference.PhoneSelectionMetadata.enrich(jdbc,sets,"id","mold_set");assertThat(sets.get(0).get("phoneModels").toString()).contains("phoneModelId=101").doesNotContain("phoneModelId=102");
    }
    @Test void sampleAndTrialCandidatesUseActualProjectsInsteadOfTheHistoricalColorSkuCatalogue() {
        jdbc.update("insert into plm_product(product_id,product_code,product_name,product_type,development_mode) values(100,'RD-100','实际新品草稿','product_line','launch_v1')");
        var store=new com.yuewei.plm.module.partner.PartnerStore(jdbc,new ObjectMapper(),access,mock(com.yuewei.plm.module.operationlog.service.OperationLogService.class));
        var samples=new com.yuewei.plm.module.partner.PartnerSampleService(store,mock(com.yuewei.plm.module.partner.PartnerProductService.class),mock(com.yuewei.plm.module.partner.PartnerMaterialService.class));
        @SuppressWarnings("unchecked") var projects=(List<Map<String,Object>>)samples.sources(null).get("projects");
        assertThat(projects).extracting(r->r.get("id")).containsExactlyInAnyOrder(20L,100L);assertThat(projects).allMatch(r->r.containsKey("phoneModels"));
        var trials=new com.yuewei.plm.module.pilotdelivery.PilotTrialService(jdbc,new ObjectMapper(),access,mock(com.yuewei.plm.module.pilotdelivery.PilotDeliveryQueue.class));
        @SuppressWarnings("unchecked") var trialProjects=(List<Map<String,Object>>)trials.options().get("projects");assertThat(trialProjects).extracting(r->r.get("product_id")).containsExactly(20L);
    }
}

package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.*;
import com.yuewei.plm.common.config.AppProperties;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.*;
import com.yuewei.plm.common.util.*;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.integration.dingtalk.config.DingTalkIntegrationProperties;
import com.yuewei.plm.repository.*;
import com.yuewei.plm.repository.entity.Product;
import com.yuewei.plm.module.workflow.service.WorkflowTemplateService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No production URI accepted. Every fixture, reservation, conversion and identity rolls back. */
@EnabledIfEnvironmentVariable(named="PLM_MOLD_TEST_DB_URL",matches=".+")
class MoldApplicationLocalIntegrationTest {
    @Test void configurationCannotSwapTheConfirmedMaterialCodesOrReserveFromAnOldSwappedConfig() {
        assertThatThrownBy(()->codes.saveConfig(tipo,50,"FA",List.of(Map.of("code","10","partType","PC"),Map.of("code","20","partType","TPU")),1L,"it"))
            .hasMessageContaining("TPU=10、PC=20");
        jdbc.update("update plm_mold_application_config set material_codes='[{\"code\":\"10\",\"partType\":\"PC\"}]'::jsonb where tipo=?",tipo);
        assertThatThrownBy(()->codes.config(tipo)).hasMessageContaining("TPU=10、PC=20");
    }
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    JdbcTemplate jdbc;DataSourceTransactionManager tx;TransactionStatus scope;
    MoldApplicationRepository repo;MoldApplicationCodes codes;MoldApplicationService service;
    MoldApplicationMedia media;MoldApplicationProperties props;MoldDingTalkClient client;MoldApplicationDingTalkFiles files;
    PilotAccessService access;long actor,phone,phoneModel;String tipo="IT Mold Tipo";
    @BeforeEach void begin()throws Exception {
        LocalMoldTestDatabase.database(System.getenv("PLM_MOLD_TEST_DB_URL"));
        var source=new DriverManagerDataSource(System.getenv("PLM_MOLD_TEST_DB_URL"),"postgres",System.getenv("PLM_MOLD_TEST_DB_PASSWORD"));
        jdbc=new JdbcTemplate(source);LocalMoldTestDatabase.verify(System.getenv("PLM_MOLD_TEST_DB_URL"),jdbc);tx=new DataSourceTransactionManager(source);scope=tx.getTransaction(new DefaultTransactionDefinition());
        com.yuewei.plm.module.collaboration.MoldMasterTestSchema.apply(jdbc);
        if(jdbc.queryForObject("select to_regclass('plm_product_code_seq') is null",Boolean.class))try(var migrationSql=getClass().getResourceAsStream("/db/migration/V20261023_1800__durable_product_code_numbers.sql")){jdbc.execute(new String(migrationSql.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        if(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='plm' and table_name='plm_market_watch_source_projection'",Long.class)==0)try(var migrationSql=getClass().getResourceAsStream("/db/migration/V20261013_1700__market_phone_workflow.sql")){jdbc.execute(new String(migrationSql.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='plm' and table_name='plm_mold_application' and column_name='execution_intent'",Long.class)==0)try(var migrationSql=getClass().getResourceAsStream("/db/migration/V20261013_1710__mold_application_workflow.sql")){jdbc.execute(new String(migrationSql.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        new db.migration.V20261014_1740__mold_authorization_history().migrate(jdbc);
        var who=jdbc.queryForMap("select user_id,username,display_name from sys_user where all_permissions=true and deleted_flag=0 order by user_id limit 1");actor=((Number)who.get("user_id")).longValue();CurrentUserContext.set(new CurrentUser(actor,who.get("username").toString(),who.get("display_name").toString(),true));
        repo=new MoldApplicationRepository(jdbc,json);props=new MoldApplicationProperties();props.setEnabled(true);props.setCentralCodeEnabled(false);props.setReadSyncEnabled(true);props.setWriteEnabled(false);props.setAllowedSubmitters(List.of(actor));
        codes=new MoldApplicationCodes(repo,props,new ProductBusinessCodeGenerator(),json);access=new PilotAccessService(jdbc,json);
        var app=new AppProperties();app.getStorage().setLocalRoot(Files.createTempDirectory("plm-mold-verification-").toString());media=new MoldApplicationMedia(jdbc,json,app);client=mock(MoldDingTalkClient.class);files=mock(MoldApplicationDingTalkFiles.class);
        var importer=new MoldApplicationBackupImport(repo,media,json,tx);
        service=new MoldApplicationService(repo,props,client,media,codes,access,json,tx,mock(OperationLogService.class),files,importer);
        var phoneRow=jdbc.queryForMap("select code_item_id,code_value from plm_code_item where code_type='phone_model' and status='enabled' and deleted_flag=0 order by code_value desc limit 1");phone=((Number)phoneRow.get("code_item_id")).longValue();
        long brand=jdbc.queryForObject("insert into plm_phone_brand(name,created_by,updated_by) values(?,'it','it') returning id",Long.class,"IT-Brand-"+UUID.randomUUID());phoneModel=jdbc.queryForObject("insert into plm_phone_model_member(brand_id,model_name,code_item_id,created_by,updated_by) values(?,'IT New Phone',?,'it','it') returning id",Long.class,brand,phone);
        codes.saveConfig(tipo,50,"FA",List.of(Map.of("code","10","partType","TPU"),Map.of("code","20","partType","PC")),null,"it");
        repo.saveSchema(props.getProcessCode(),schema());
        when(client.schema(anyString())).thenReturn(schema());when(client.user(anyString())).thenReturn(json.readTree("{\"name\":\"本地测试\",\"dept_id_list\":[1]}"));
    }
    @AfterEach void rollback(){if(scope!=null&&!scope.isCompleted())tx.rollback(scope);CurrentUserContext.clear();}
    JsonNode schema(){var root=json.createObjectNode();var items=root.putObject("schemaContent").putArray("items");for(String key:List.of("model","tipo","expectedDeliveryDate","generatedCodes","remark")){var item=items.addObject();item.put("componentName",key.equals("tipo")?"DDSelectField":"TextField");var p=item.putObject("props");p.put("id",MoldApplicationForms.COMPONENTS.get(key));p.put("label",key);p.put("required",!key.equals("remark"));if(key.equals("tipo"))p.putArray("options").add("{\"value\":\"IT Mold Tipo\"}");}return root;}
    Map<String,Object> draft() {return service.save(null,new MoldApplicationService.Draft(json.valueToTree(Map.of("model","IT New Phone","tipo",tipo,"expectedDeliveryDate","2026-11-01","remark","PLM离线测试")),50L,phone,null,phoneModel,"new",null,null,null),null);}
    long id(Map<String,Object> row){return ((Number)row.get("applicationId")).longValue();}long version(Map<String,Object> row){return ((Number)row.get("version")).longValue();}
    Map<String,Object> prepared() {
        var r=draft();long id=id(r);byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6BuMAAAAASUVORK5CYII=");
        r=service.upload(id,"key_photo",new MockMultipartFile("file","sample.png","image/png",png),null);return service.reserve(id,version(r),null);
    }
    void allowWrite(){props.setWriteEnabled(true);jdbc.update("insert into plm_pilot_account(user_id,role_code,permissions,all_objects,dingtalk_user_id,created_by,updated_by) values(?,'administrator','[\"*\"]',true,'mold-it-user','it','it') on conflict(user_id) do update set dingtalk_user_id='mold-it-user'",actor);}
    @Test void applicationOptionsExposeCurrentSalesStateForHistoricalAndNewSelections() {
        var lines=(List<Map<String,Object>>)service.options().get("productLines");
        var onSale=lines.stream().filter(p->Objects.equals(p.get("productId"),50L)).findFirst().orElseThrow();
        var retired=lines.stream().filter(p->Objects.equals(p.get("productId"),47L)).findFirst().orElseThrow();
        assertThat(onSale).containsEntry("salesStatus","on_sale");assertThat(retired).containsEntry("salesStatus","off_sale");
        assertThat(((com.yuewei.plm.module.productionmetadata.ProductSalesPolicy.MoldApplicationEligibility)onSale.get("moldApplicationEligibility")).eligible()).isTrue();
        assertThat(((com.yuewei.plm.module.productionmetadata.ProductSalesPolicy.MoldApplicationEligibility)retired.get("moldApplicationEligibility")).eligible()).isFalse();
        assertThat(onSale).doesNotContainKeys("development_mode","lock_status","current_step_no");
    }
    @Test void researchDraftEligibilityDoesNotRequireOrChangeTheOnSaleCatalog() {
        jdbc.update("update plm_product set development_mode='launch_v1',status='developing',current_step_no=2,sales_status='unconfirmed' where product_id=50");
        var lines=(List<Map<String,Object>>)service.options().get("productLines");
        var research=lines.stream().filter(p->Objects.equals(p.get("productId"),50L)).findFirst().orElseThrow();
        var eligibility=(com.yuewei.plm.module.productionmetadata.ProductSalesPolicy.MoldApplicationEligibility)research.get("moldApplicationEligibility");
        assertThat(eligibility.eligible()).isTrue();assertThat(eligibility.kind()).isEqualTo("research");assertThat(draft()).containsEntry("productLineId",50L);
        assertThat(jdbc.queryForObject("select sales_status from plm_product where product_id=50",String.class)).isEqualTo("unconfirmed");
        assertThatThrownBy(()->new com.yuewei.plm.module.productionmetadata.ProductSalesPolicy(jdbc).requireOnSale(List.of(50L))).hasMessageContaining("在售状态尚未确认");verifyNoInteractions(client,files);
    }
    @Test void nonSaleProductsCannotCreateDraftsFromEitherSelectionOrTipoOrReuseCachedReservations() {
        var request=new MoldApplicationCodes.Request("it-sales-cache-12345678",tipo,"IT New Phone",phone,null);codes.reserve(request,"it");
        long applications=jdbc.queryForObject("select count(*) from plm_mold_application",Long.class),reservations=jdbc.queryForObject("select count(*) from plm_mold_code_reservation",Long.class);
        for(String state:List.of("off_sale","unconfirmed")) {
            jdbc.update("update plm_product set sales_status=? where product_id=50",state);
            String message=state.equals("off_sale")?"已停售":"在售状态尚未确认";
            assertThatThrownBy(()->draft()).hasMessageContaining(message);
            assertThatThrownBy(()->service.save(null,new MoldApplicationService.Draft(json.valueToTree(Map.of("model","IT New Phone","tipo",tipo)),null,phone,null),null)).hasMessageContaining(message);
            assertThatThrownBy(()->service.startFromPhone(new MoldApplicationService.SourceDraft(phoneModel,null,null,null,50L,"new",null,null,null),null)).hasMessageContaining(message);
            assertThatThrownBy(()->codes.reserve(request,"it")).hasMessageContaining(message);
        }
        assertThat(jdbc.queryForObject("select count(*) from plm_mold_application",Long.class)).isEqualTo(applications);
        assertThat(jdbc.queryForObject("select count(*) from plm_mold_code_reservation",Long.class)).isEqualTo(reservations);
        verifyNoInteractions(client,files);
    }
    @Test void retiredDraftKeepsItsHistoryButCannotReserveOrSubmitUntilTheProductIsOnSaleAgain() {
        var original=prepared();allowWrite();jdbc.update("update plm_product set sales_status='off_sale' where product_id=50");
        var form=((JsonNode)original.get("form")).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)form).put("remark","保留停售前的历史草稿说明");
        var saved=service.save(id(original),new MoldApplicationService.Draft(form,50L,phone,version(original),phoneModel,"new",null,null,null),null);
        assertThat(saved).containsEntry("productLineId",50L).containsEntry("tipo",tipo).containsEntry("status","draft");
        assertThatThrownBy(()->service.reserve(id(saved),version(saved),null)).hasMessageContaining("已停售");
        assertThatThrownBy(()->service.submit(id(saved),new MoldApplicationService.Submit(version(saved),null,true),null)).hasMessageContaining("已停售");
        assertThat(repo.row(id(saved),false)).containsEntry("status","draft").containsEntry("version",version(saved));verifyNoInteractions(client,files);
        jdbc.update("update plm_product set sales_status='on_sale' where product_id=50");
        assertThat(service.reserve(id(saved),version(saved),null).get("generatedCodes")).isNotNull();
        jdbc.update("update plm_mold_application set status='submitted',external_instance_id='it-sales-existing',remote_status='RUNNING' where application_id=?",id(saved));
        jdbc.update("update plm_product set sales_status='off_sale' where product_id=50");
        assertThat(service.submit(id(saved),new MoldApplicationService.Submit(version(saved),null,true),null)).containsEntry("instanceId","it-sales-existing").containsEntry("productLineId",50L);verify(client,never()).submit(anyMap());
    }
    @Test void unconfirmedProductWithoutAPrefixDoesNotBreakHistoricalReview() {
        jdbc.update("insert into plm_product(product_code,product_name,product_type,version_no,sales_status,created_by,updated_by) values(?,'IT 未定款式','product_line','V1','unconfirmed','it','it')","IT-UNMAPPED-"+UUID.randomUUID());
        assertThat(reconciler().preview()).containsKey("items");verifyNoInteractions(client,files);
    }
    JsonNode snapshot(String status,String remark)throws Exception {
        var d=json.createObjectNode();d.put("status",status);d.put("title","本地测试提交的开模申请");d.put("originator_userid","mold-it-user");d.put("originator_dept_id",1);d.put("create_time","2026-10-13 12:00:00");d.put("business_id","IT-"+UUID.randomUUID());
        var f=d.putArray("form_component_values");for(var e:Map.of("model","IT New Phone","tipo",tipo,"remark",remark,"generatedCodes","MFA10"+jdbc.queryForObject("select code_value from plm_code_item where code_item_id=?",String.class,phone)).entrySet())f.addObject().put("id",MoldApplicationForms.COMPONENTS.get(e.getKey())).put("value",e.getValue());return d;
    }
    @Test void baselineHas106FormalInstancesAndAllFilesAreVerified() {
        assertThat(jdbc.queryForObject("select count(*) from plm_mold_application where source_system='dingtalk' and process_code=?",Long.class,props.getProcessCode())).isEqualTo(106L);
        assertThat(jdbc.queryForObject("select count(*) from plm_mold_application where process_code='PROC-BD65F530-F66F-46B9-8F72-0567DD68F60C'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from plm_attachment f join plm_mold_application a on a.application_id=f.owner_object_id where f.owner_object_type='MoldApplication' and f.download_status='downloaded' and a.source_system='dingtalk' and a.process_code=?",Long.class,props.getProcessCode())).isEqualTo(475L);
        assertThat(jdbc.queryForObject("select count(distinct f.source_url) from plm_attachment f join plm_mold_application a on a.application_id=f.owner_object_id where f.owner_object_type='MoldApplication' and a.source_system='dingtalk' and a.process_code=?",Long.class,props.getProcessCode())).isEqualTo(124L);
        assertThat(jdbc.queryForObject("select count(*) from plm_mold_application a left join plm_product p on p.product_id=a.project_id and p.deleted_flag=0 where a.source_system='dingtalk' and a.project_id is not null and (p.product_id is null or p.project_kind is null)",Long.class)).isZero();
    }
    @Test void draftVersionPaginationAndSubmittedFreeze() {
        var r=draft();long id=id(r);var saved=service.save(id,new MoldApplicationService.Draft((JsonNode)r.get("form"),50L,phone,version(r),phoneModel,"new",null,null,null),null);assertThat(version(saved)).isGreaterThan(version(r));
        assertThatThrownBy(()->service.save(id,new MoldApplicationService.Draft((JsonNode)r.get("form"),50L,phone,version(r),phoneModel,"new",null,null,null),null)).isInstanceOf(BusinessException.class).hasMessageContaining("更新");
        assertThat(service.page(1,10,"IT New Phone",tipo,null,null,null,null).getContent()).hasSize(1);
        assertThatThrownBy(()->service.page(0,10,null,null,null,null,null,null)).isInstanceOf(BusinessException.class);
        jdbc.update("update plm_mold_application set status='submitted' where application_id=?",id);
        assertThatThrownBy(()->service.save(id,new MoldApplicationService.Draft((JsonNode)saved.get("form"),50L,phone,version(saved),phoneModel,"new",null,null,null),null)).isInstanceOf(BusinessException.class).hasMessageContaining("只读");
    }
    @Test void explicitBusinessStatesPartitionHistoryAndDraftIsNotApplied() {
        var all=service.summary();var stages=(List<Map<String,Object>>)all.get("businessCounts");assertThat(stages.stream().mapToLong(r->((Number)r.get("count")).longValue()).sum()).isEqualTo(jdbc.queryForObject("select count(*) from plm_mold_application where deleted_flag=0",Long.class));
        assertThat(service.page(1,100,null,null,"approved",null,null,null).getTotalElements()).isEqualTo(84L);
        assertThat(service.page(1,100,null,null,"awaiting_approval",null,null,null).getTotalElements()).isEqualTo(3L);
        assertThat(service.page(1,100,null,null,"rejected",null,null,null).getTotalElements()).isEqualTo(6L);
        assertThat(service.page(1,100,null,null,"terminated",null,null,null).getTotalElements()).isEqualTo(11L);
        var row=draft();assertThat(repo.row(id(row),false)).containsEntry("businessStatus","draft");assertThat(service.progressPage(1,50,"IT New Phone",null,phoneModel,null).getTotalElements()).isZero();
        jdbc.update("update plm_mold_application set status='submission_unknown' where application_id=?",id(row));assertThat(repo.row(id(row),false)).containsEntry("businessStatus","submission_unknown");
    }
    @Test void requestIdDeduplicatesAndExistingModelNeverAllocatesANewCode() {
        var request=new MoldApplicationCodes.Request("it-request-12345678",tipo,"IT New Phone",phone,null);var first=codes.reserve(request,"it");var second=codes.reserve(request,"it");
        assertThat(second.get("generatedCodes")).isEqualTo(first.get("generatedCodes"));assertThat(second.get("moldCodes")).isEqualTo(first.get("moldCodes"));assertThat(((Number)second.get("phoneCodeItemId")).longValue()).isEqualTo(((Number)first.get("phoneCodeItemId")).longValue());assertThat(jdbc.queryForObject("select count(*) from plm_mold_code_reservation where request_id=?",Long.class,request.requestId())).isEqualTo(1L);
        assertThatThrownBy(()->codes.reserve(new MoldApplicationCodes.Request(request.requestId(),tipo,"Another",phone,null),"it")).isInstanceOf(BusinessException.class).hasMessageContaining("另一份");
        assertThatThrownBy(()->codes.reserve(new MoldApplicationCodes.Request("it-no-new-12345678",tipo,"Definitely absent "+UUID.randomUUID(),null,null),"it")).isInstanceOf(BusinessException.class).hasMessageContaining("中央编码");
    }
    @Test void missingConfigCannotGenerateAndWriteDisabledNeverCallsDingTalk() {
        assertThatThrownBy(()->codes.reserve(new MoldApplicationCodes.Request("it-missing-12345678","Missing", "Phone",phone,null),"it")).isInstanceOf(BusinessException.class).hasMessageContaining("配置");
        var r=prepared();assertThatThrownBy(()->service.submit(id(r),new MoldApplicationService.Submit(version(r),null,true),null)).isInstanceOf(BusinessException.class).hasMessageContaining("启用");verifyNoInteractions(client,files);
    }
    @Test void aFailedFileUploadDoesNotCreateAnApproval() {
        allowWrite();var r=prepared();doThrow(MoldApplicationForms.invalid("文件上传失败")).when(files).fillForm(anyLong(),anyString(),anyMap());
        assertThatThrownBy(()->service.submit(id(r),new MoldApplicationService.Submit(version(r),null,true),null)).isInstanceOf(BusinessException.class);verify(client,never()).submit(anyMap());assertThat(repo.row(id(r),false)).containsEntry("status","draft");
    }
    @Test void persistedFileSelectionBlocksSubmissionAcrossPageReloadUntilExplicitlyUploadedOrDiscarded() {
        allowWrite();var r=prepared();long id=id(r);var selected=service.selectFiles(id,List.of(new MoldApplicationMedia.Selection("selection-reload-123","attachment","chosen.pdf",10,"application/pdf")),null);
        assertThat((List<?>)selected.get("files")).hasSize(2);assertThat(media.internal(id)).anySatisfy(f->assertThat(f).containsEntry("download_status","pending_upload"));
        assertThatThrownBy(()->service.submit(id,new MoldApplicationService.Submit(version(selected),null,true),null)).hasMessageContaining("归档");verify(client,never()).submit(anyMap());verifyNoInteractions(files);
        long pending=((Number)media.internal(id).stream().filter(f->"pending_upload".equals(f.get("download_status"))).findFirst().orElseThrow().get("attachment_id")).longValue();
        service.discardSelection(id,pending,null);assertThat(media.internal(id)).allSatisfy(f->assertThat(f).containsEntry("download_status","downloaded"));
    }
    @Test void dateFilteringUsesShanghaiBoundariesRatherThanDatabaseSessionTimezone() {
        var today=draft();var yesterday=service.save(null,new MoldApplicationService.Draft((JsonNode)today.get("form"),50L,phone,null,phoneModel,"new",null,null,null,"独立审批日期边界测试",UUID.randomUUID().toString(),true),null);jdbc.update("update plm_mold_application set source_created_at='2026-10-12 16:01:00+00' where application_id=?",id(today));jdbc.update("update plm_mold_application set source_created_at='2026-10-12 15:59:00+00' where application_id=?",id(yesterday));
        assertThat(service.page(1,10,"IT New Phone",tipo,null,null,"2026-10-13","2026-10-13").getContent()).extracting(r->r.get("applicationId")).containsExactly(id(today));
    }
    @Test void successfulWithdrawalCopiesOnceAndRepeatedClickReusesTheRevision()throws Exception {
        allowWrite();var r=prepared();long original=id(r);String instance="it-withdraw-"+UUID.randomUUID();jdbc.update("update plm_mold_application set status='submitted',remote_status='RUNNING',originator_user_id='mold-it-user',external_instance_id=? where application_id=?",instance,original);
        when(client.instance(instance)).thenReturn(snapshot("TERMINATED","PLM离线撤回测试"));var revision=service.withdraw(original,version(r),null);
        assertThat(revision).containsEntry("status","draft").containsEntry("previousApplicationId",original);assertThat(repo.row(original,false)).containsEntry("remoteStatus","TERMINATED");assertThat((List<?>)revision.get("files")).hasSize(1);
        assertThat(service.withdraw(original,version(r),null).get("applicationId")).isEqualTo(revision.get("applicationId"));verify(client,times(1)).withdraw(instance,"mold-it-user");
    }
    @Test void ambiguousSubmissionFreezesAndReconciliationClaimsOneInstance()throws Exception {
        allowWrite();var r=prepared();when(client.submit(anyMap())).thenThrow(new MoldDingTalkClient.RemoteFailure(true,"未知结果"));
        assertThatThrownBy(()->service.submit(id(r),new MoldApplicationService.Submit(version(r),null,true),null)).isInstanceOf(BusinessException.class);var frozen=repo.row(id(r),false);assertThat(frozen).containsEntry("status","submission_unknown");
        assertThatThrownBy(()->service.submit(id(r),new MoldApplicationService.Submit(version(frozen),null,true),null)).isInstanceOf(BusinessException.class);verify(client,times(1)).submit(anyMap());
        String external="it-reconciled-"+UUID.randomUUID();when(client.instance(external)).thenReturn(snapshot("RUNNING","[PLM-ID:"+frozen.get("submissionKey")+"]"));when(client.ids(anyString(),any(),any())).thenReturn(List.of(external));
        assertThat(service.syncInternal(external)).isEqualTo(id(r));assertThat(repo.row(id(r),false)).containsEntry("status","submitted").containsEntry("instanceId",external);
    }
    @Test void confirmedInstanceStaysFrozenEvenWhenDetailPullFails() {
        allowWrite();var r=prepared();when(client.submit(anyMap())).thenReturn("it-confirmed");when(client.instance("it-confirmed")).thenThrow(new MoldDingTalkClient.RemoteFailure(false,"详情故障"));
        var result=service.submit(id(r),new MoldApplicationService.Submit(version(r),null,true),null);assertThat(result).containsEntry("status","submitted").containsEntry("instanceId","it-confirmed");
        service.submit(id(r),new MoldApplicationService.Submit(version(result),null,true),null);verify(client,times(1)).submit(anyMap());
    }
    @Test void connectorsRequireNonEmptyMatchingTokensAndCentralAllocationMustBeExplicit() {
        var controller=new MoldApplicationConnectorController(props,codes,service);assertThatThrownBy(()->controller.authenticate("Bearer ")).isInstanceOf(BusinessException.class);props.setConnectorToken("a-safe-test-token");
        assertThatThrownBy(()->controller.authenticate("Bearer wrong")).isInstanceOf(BusinessException.class);controller.authenticate("Bearer a-safe-test-token");
        assertThatThrownBy(()->controller.reserve("Bearer a-safe-test-token",new MoldApplicationCodes.Request("it-connector-123",tipo,"Phone",phone,null),new org.springframework.mock.web.MockHttpServletRequest())).isInstanceOf(BusinessException.class).hasMessageContaining("启用");
    }
    MoldApplicationConversion converter() {
        ProductRepository products=mock(ProductRepository.class);Product parent=new Product();parent.setProductId(50L);parent.setSeriesName("Test");parent.setProductName("骑士");parent.setProductSpecificCode("FA");when(products.selectById(anyLong())).thenAnswer(call->{long target=((Number)call.getArgument(0)).longValue();var r=jdbc.queryForMap("select product_id,product_name,product_specific_code,series_name from plm_product where product_id=?",target);var value=new Product();value.setProductId(target);value.setProductName(Objects.toString(r.get("product_name")));value.setProductSpecificCode(Objects.toString(r.get("product_specific_code")));value.setSeriesName(Objects.toString(r.get("series_name")));return value;});
        doAnswer(call->{Product p=call.getArgument(0);Long id=jdbc.queryForObject("insert into plm_product(parent_product_id,product_code,product_name,product_type,model,product_specific_code,phone_model_code,phone_model_member_id,status,version_no,source_system,source_instance_id,created_by,updated_by) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?) returning product_id",Long.class,p.getParentProductId(),p.getProductCode(),p.getProductName(),p.getProductType(),p.getModel(),p.getProductSpecificCode(),p.getPhoneModelCode(),p.getPhoneModelMemberId(),p.getStatus(),p.getVersionNo(),p.getSourceSystem(),p.getSourceInstanceId(),p.getCreatedBy(),p.getUpdatedBy());p.setProductId(id);return 1;}).when(products).insert(any(Product.class));
        var value=new MoldApplicationConversion(repo,service,codes,products,new ProductCodeGenerator(jdbc),mock(WorkflowTemplateService.class),access,tx);service.setConversion(value);return value;
    }
    Map<String,Object> approved() {var r=prepared();jdbc.update("update plm_mold_application set status='submitted',remote_status='RUNNING',approval_granted=true,approval_no='IT-APPROVAL',external_instance_id=? where application_id=?","it-approval-"+UUID.randomUUID(),id(r));return repo.row(id(r),false);}
    @Test void conversionCreatesPlannedSetWithoutInventingStockAndIsIdempotent() {
        var r=approved();var convert=converter();var preview=convert.preview(id(r));assertThat((List<?>)preview.get("projects")).isEmpty();
        var input=new MoldApplicationConversion.Convert(version(r),"create",null,null,null,true);var result=convert.convert(id(r),input,null);
        assertThat(result).containsEntry("status","converted");assertThat(result.get("projectId")).isNotNull();assertThat(result.get("itemId")).isNotNull();assertThat(result.get("moldSetId")).isNotNull();
        assertThat(jdbc.queryForObject("select project_kind from plm_product where product_id=?",String.class,result.get("projectId"))).isEqualTo("model_extension");
        assertThat(jdbc.queryForObject("select count(*) from plm_inventory where product_id=? and inventory_type='tooling'",Long.class,result.get("projectId"))).isZero();
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_part where mold_set_id=?",Long.class,result.get("moldSetId"))).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select status from plm_project_requirement_form where project_id=?",String.class,result.get("projectId"))).isEqualTo("draft");
        var again=convert.convert(id(r),input,null);assertThat(again.get("projectId")).isEqualTo(result.get("projectId"));
    }
    @Test void refusedOrTerminatedApplicationsCannotConvertAndFailedConversionRollsBack() {
        var r=approved();var convert=converter();jdbc.update("update plm_mold_application set remote_status='TERMINATED' where application_id=?",id(r));assertThatThrownBy(()->convert.preview(id(r))).isInstanceOf(BusinessException.class).hasMessageContaining("终止");
        jdbc.update("update plm_mold_application set remote_status='RUNNING' where application_id=?",id(r));
        var nested=new TransactionTemplate(tx);nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(()->nested.execute(s->convert.convert(id(r),new MoldApplicationConversion.Convert(version(r),"create",null,-1L,null,true),null))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("select count(*) from plm_product where source_instance_id=?",Long.class,r.get("instanceId"))).isZero();assertThat(repo.row(id(r),false).get("projectId")).isNull();
    }
    @Test void anExistingApprovalInstanceCannotSilentlyCreateAnotherProjectEvenWhenItsModelWasEdited() {
        var r=approved();long duplicate=jdbc.queryForObject("insert into plm_product(parent_product_id,product_code,product_name,product_type,model,version_no,status,source_system,source_instance_id,created_by,updated_by) values(50,?,'Edited historical project','model_variant','Edited Phone','A','developing','dingtalk',?,'it','it') returning product_id",Long.class,"IT-EXIST-"+UUID.randomUUID(),r.get("instanceId"));
        var convert=converter();assertThat((List<?>)convert.preview(id(r)).get("projects")).hasSize(1);assertThatThrownBy(()->convert.convert(id(r),new MoldApplicationConversion.Convert(version(r),"create",null,null,null,true),null)).hasMessageContaining("已存在");
        Long anotherLine=jdbc.queryForObject("select min(product_id) from plm_product where product_type='product_line' and product_id<>50 and deleted_flag=0",Long.class);jdbc.update("update plm_product set parent_product_id=? where product_id=?",anotherLine,duplicate);assertThatThrownBy(()->convert.preview(id(r))).hasMessageContaining("同一审批实例");
    }
    @Test void permissionAndDisabledFeatureBlockReadsAndMutations() {
        props.setEnabled(false);assertThatThrownBy(()->service.summary()).isInstanceOf(BusinessException.class).hasMessageContaining("启用");props.setEnabled(true);
        long uid=jdbc.queryForObject("insert into sys_user(user_code,username,login_name,user_name,display_name,all_permissions,status) values(?,?,?,'测试只读','测试只读',false,'active') returning user_id",Long.class,"mold-read-"+UUID.randomUUID(),"mold-read-"+UUID.randomUUID(),"mold-read-"+UUID.randomUUID());
        jdbc.update("insert into plm_pilot_account(user_id,role_code,permissions,all_objects,created_by,updated_by) values(?,'domestic','[\"collaboration:view\"]',true,'it','it')",uid);CurrentUserContext.set(new CurrentUser(uid,"test","test",true));
        assertThat(service.page(1,10,null,null,null,null,null,null).getContent()).hasSize(10);assertThatThrownBy(this::draft).isInstanceOf(BusinessException.class).hasMessageContaining("权限");assertThatThrownBy(()->converter().preview(2)).isInstanceOf(BusinessException.class).hasMessageContaining("权限");
    }
    private record SharedFixture(long item,long set,long member,long code,String model,String phoneCode,String digest) {}
    private SharedFixture sharedFixture() {
        String codeValue=jdbc.queryForObject("select lpad(v::text,4,'0') from generate_series(0,9999) v where not exists(select 1 from plm_code_item where code_type='phone_model' and code_value=lpad(v::text,4,'0')) order by v limit 1",String.class);
        String model="Shared phone-"+UUID.randomUUID();long code=jdbc.queryForObject("insert into plm_code_item(code_type,code_value,code_name,status,sort_order) values('phone_model',?,?,'enabled',0) returning code_item_id",Long.class,codeValue,model);
        long brand=jdbc.queryForObject("insert into plm_phone_brand(name,created_by,updated_by) values(?,'it','it') returning id",Long.class,"Brand-"+UUID.randomUUID());long member=jdbc.queryForObject("insert into plm_phone_model_member(brand_id,model_name,code_item_id,created_by,updated_by) values(?,?,?,'it','it') returning id",Long.class,brand,model,code);
        long item=jdbc.queryForObject("insert into plm_collaboration_item(product_line_id,style_name,compatible_models,mold_name,engraved_code,created_by,updated_by) values(50,'IT shared',?,?,'1144','it','it') returning item_id",Long.class,model,"Mold-"+UUID.randomUUID());long set=jdbc.queryForObject("insert into plm_collaboration_mold_set(item_id,set_no,set_name,created_by,updated_by) values(?,1,'IT confirmed shared set','it','it') returning mold_set_id",Long.class,item);
        for(var part:List.of(Map.entry("PC","MFA201144"),Map.entry("TPU","MFA101144")))jdbc.update("insert into plm_collaboration_mold_part(mold_set_id,part_type,original_mold_code,physical_status) values(?,?,?,'confirmed')",set,part.getKey(),part.getValue());
        jdbc.update("insert into plm_collaboration_phone_member(item_id,phone_model_id,mold_set_ids,pc_confirmed,tpu_confirmed,evidence,created_by,updated_by) values(?,?,?::bigint[],true,true,'人工明确PC/TPU均适配','it','it')",item,member,"{"+set+"}");
        jdbc.update("insert into plm_collaboration_phone_set_compatibility(item_id,phone_model_id,mold_set_id,pc_confirmed,tpu_confirmed,pc_evidence,tpu_evidence,created_by,updated_by) values(?,?,?,true,true,'人工明确PC适配','人工明确TPU适配','it','it')",item,member,set);
        var row=codes.references().compatibleMolds(member,50).get(0);return new SharedFixture(item,set,member,code,model,codeValue,row.get("compatibilityDigest").toString());
    }
    private SharedFixture historyFixture(){
        var f=sharedFixture();long brand=jdbc.queryForObject("select id from plm_phone_brand where status='enabled' and deleted_flag=0 and lower(name)='samsung' order by id limit 1",Long.class);String name="Samsung Lemos "+UUID.randomUUID();
        jdbc.update("update plm_phone_model_member set brand_id=?,model_name=? where id=?",brand,name,f.member());jdbc.update("update plm_collaboration_item set compatible_models=? where item_id=?",name,f.item());
        String digest=codes.references().compatibleMolds(f.member(),50).get(0).get("compatibilityDigest").toString();return new SharedFixture(f.item(),f.set(),f.member(),f.code(),name,f.phoneCode(),digest);
    }
    private Map<String,Object> sharedApproved(SharedFixture f) {
        var row=service.save(null,new MoldApplicationService.Draft(json.valueToTree(Map.of("model",f.model(),"tipo",tipo,"expectedDeliveryDate","2026-11-01")),50L,f.code(),null),null);
        row=service.reserve(id(row),version(row),f.member(),f.item(),f.set(),f.digest(),null);jdbc.update("update plm_mold_application set approval_granted=true,approval_no='IT-SHARED',remote_status='RUNNING',status='submitted',external_instance_id=? where application_id=?","it-shared-"+UUID.randomUUID(),id(row));return repo.row(id(row),false);
    }
    @Test void confirmedSharedReservationUsesOriginalPartCodesWithoutAllocatingOrCreatingPhysicalRecords() {
        var f=sharedFixture();long codesBefore=jdbc.queryForObject("select count(*) from plm_code_item",Long.class),setsBefore=jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class),inventoryBefore=jdbc.queryForObject("select count(*) from plm_inventory",Long.class);
        var row=sharedApproved(f);assertThat(row).containsEntry("phoneCodeItemId",f.code());assertThat(row.get("generatedCodes")).isEqualTo("MFA201144 / MFA101144");
        assertThat(jdbc.queryForObject("select count(*) from plm_code_item",Long.class)).isEqualTo(codesBefore);assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class)).isEqualTo(setsBefore);assertThat(jdbc.queryForObject("select count(*) from plm_inventory",Long.class)).isEqualTo(inventoryBefore);
    }
    @Test void confirmedDifferentCodePhoneConvertsByLinkingSameSetAndKeepsItsOwnProjectPhoneIdentity() {
        var f=sharedFixture();var row=sharedApproved(f);var convert=converter();var preview=convert.preview(id(row));assertThat(preview.get("phoneCode")).isEqualTo(f.phoneCode());
        var result=convert.convert(id(row),new MoldApplicationConversion.Convert(version(row),"create",null,f.item(),f.set(),true,f.member(),true,f.digest()),null);
        assertThat(result).containsEntry("itemId",f.item()).containsEntry("moldSetId",f.set());assertThat(jdbc.queryForObject("select phone_model_member_id from plm_product where product_id=?",Long.class,result.get("projectId"))).isEqualTo(f.member());assertThat(jdbc.queryForObject("select phone_model_code from plm_product where product_id=?",String.class,result.get("projectId"))).isEqualTo(f.phoneCode());assertThat(jdbc.queryForObject("select count(*) from plm_inventory where product_id=?",Long.class,result.get("projectId"))).isZero();
        assertThat(jdbc.queryForObject("select original_mold_code from plm_collaboration_mold_part where mold_set_id=? and part_type='PC'",String.class,f.set())).isEqualTo("MFA201144");assertThat(convert.convert(id(row),new MoldApplicationConversion.Convert(version(row),"create",null,null,null,true),null).get("projectId")).isEqualTo(result.get("projectId"));
        long otherMember=jdbc.queryForObject("insert into plm_phone_model_member(brand_id,model_name,code_item_id,created_by,updated_by) select brand_id,'Other same-code phone',code_item_id,'it','it' from plm_phone_model_member where id=? returning id",Long.class,f.member());
        long otherProject=jdbc.queryForObject("insert into plm_product(parent_product_id,product_code,product_name,product_type,model,phone_model_code,phone_model_member_id,status,version_no,created_by,updated_by) values(50,?,'Other phone project','model_variant','Other same-code phone',?,?,'developing','A','it','it') returning product_id",Long.class,"IT-SAME-CODE-"+UUID.randomUUID(),f.phoneCode(),otherMember);
        var next=sharedApproved(f);
        var wrong=new MoldApplicationConversion.Convert(version(next),"link",otherProject,f.item(),f.set(),true,f.member(),true,f.digest());
        assertThatThrownBy(()->convert.convert(id(next),wrong,null)).isInstanceOf(BusinessException.class).hasMessageContaining("请选择预览");
        assertThat(jdbc.queryForObject("select phone_model_member_id from plm_product where product_id=?",Long.class,otherProject)).isEqualTo(otherMember);assertThat(repo.row(id(next),false).get("projectId")).isNull();
        jdbc.update("update plm_product set phone_model_member_id=null where product_id=?",otherProject);
        assertThatThrownBy(()->convert.convert(id(next),wrong,null)).isInstanceOf(BusinessException.class).hasMessageContaining("请选择预览");
        jdbc.update("update plm_product set model=? where product_id=?",f.model(),otherProject);
        String retiredCode=jdbc.queryForObject("select lpad(v::text,4,'0') from generate_series(0,9999) v where not exists(select 1 from plm_code_item where code_type='phone_model' and code_value=lpad(v::text,4,'0')) order by v limit 1",String.class);
        jdbc.update("insert into plm_code_item(code_type,code_value,code_name,status,deleted_flag,sort_order) values('phone_model',?,?,'disabled',1,0)",retiredCode,f.model());
        assertThatThrownBy(()->convert.convert(id(next),wrong,null)).isInstanceOf(BusinessException.class).hasMessageContaining("请选择预览");
        jdbc.update("update plm_product set model='Old phone name snapshot' where product_id=?",result.get("projectId"));
        var linked=convert.convert(id(next),new MoldApplicationConversion.Convert(version(next),"link",((Number)result.get("projectId")).longValue(),f.item(),f.set(),true,f.member(),true,f.digest()),null);
        assertThat(linked.get("projectId")).isEqualTo(result.get("projectId"));assertThat(jdbc.queryForObject("select model from plm_product where product_id=?",String.class,result.get("projectId"))).isEqualTo("Old phone name snapshot");
    }
    @Test void changedCompatibilityDigestIncompletePartsAndRetiredCodeCannotBeSilentlyReused() {
        var f=sharedFixture();var request=new MoldApplicationCodes.Request("it-shared-stale-123",tipo,f.model(),f.code(),null,f.member(),f.item(),f.set(),f.digest());
        jdbc.update("update plm_collaboration_phone_set_compatibility set pc_evidence='已重新核对' where item_id=?",f.item());assertThatThrownBy(()->codes.reserve(request,"it")).isInstanceOf(BusinessException.class).hasMessageContaining("变化");
        jdbc.update("update plm_collaboration_mold_part set original_mold_code=null where mold_set_id=? and part_type='TPU'",f.set());assertThat(codes.references().compatibleMolds(f.member(),50)).isEmpty();assertThatThrownBy(()->codes.reserve(new MoldApplicationCodes.Request("it-shared-missing-123",tipo,f.model(),f.code(),null,f.member(),f.item(),f.set(),f.digest()),"it")).hasMessageContaining("完整确认");
        jdbc.update("update plm_code_item set deleted_flag=1 where code_item_id=?",f.code());assertThatThrownBy(()->codes.reserve(new MoldApplicationCodes.Request("it-shared-retired-123",tipo,f.model(),f.code(),null,f.member(),f.item(),f.set(),f.digest()),"it")).hasMessageContaining("核对");
    }
    @Test void phoneDraftIsStableDeduplicatedAndDoesNotReserveOrSubmit() {
        long before=jdbc.queryForObject("select count(*) from plm_mold_code_reservation",Long.class);
        var input=new MoldApplicationService.SourceDraft(phoneModel,null,null,null,null,"new",null,null,null);
        var first=service.startFromPhone(input,null);var again=service.startFromPhone(input,null);
        assertThat(first).containsEntry("phoneModelId",phoneModel).containsEntry("alreadyExists",false);
        assertThat(again).containsEntry("applicationId",first.get("applicationId")).containsEntry("alreadyExists",true);
        var reopened=service.detail(id(first));assertThat(reopened).containsEntry("phoneModelId",phoneModel).containsEntry("executionIntent","new");
        assertThat(jdbc.queryForObject("select count(*) from plm_mold_code_reservation",Long.class)).isEqualTo(before);verifyNoInteractions(client,files);
    }
    @Test void specifiedApprovalAutomaticallyBuildsOnceAndLaterTerminationPausesWithoutDeletingFacts() throws Exception {
        var row=prepared();converter();String instance="it-workflow-"+UUID.randomUUID();jdbc.update("update plm_mold_application set status='submitted',remote_status='RUNNING',external_instance_id=?,provisioning_status='pending' where application_id=?",instance,id(row));
        var source=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot("RUNNING","");source.withArray("form_component_values").addObject().put("id",MoldApplicationForms.COMPONENTS.get("expectedDeliveryDate")).put("value","2026-11-01");for(var component:source.withArray("form_component_values"))if(MoldApplicationForms.COMPONENTS.get("generatedCodes").equals(component.path("id").asText()))((com.fasterxml.jackson.databind.node.ObjectNode)component).put("value",row.get("generatedCodes").toString());source.putArray("tasks").addObject().put("userid",MoldApplicationForms.EXECUTION_APPROVER_USER_ID).put("taskid","approved-task").put("activity_id","changed-node-version").put("task_status","COMPLETED").put("task_result","AGREE").put("finish_time","2026-10-13 12:10:00");
        long stockBefore=jdbc.queryForObject("select count(*) from plm_inventory",Long.class),setsBefore=jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class);
        when(client.instance(instance)).thenReturn(source);assertThat(service.syncInternal(instance)).isEqualTo(id(row));var built=service.detail(id(row));assertThat(built).containsEntry("approvalGranted",true).containsEntry("provisioningStatus","ready").containsEntry("executionStatus","active");assertThat(built.get("moldSetId")).isNotNull();assertThat(workbench(access).overview(((Number)built.get("projectId")).longValue())).containsEntry("moldSetCount",1).containsEntry("physicalSetCount",0L);assertThat(service.progressPage(1,50,"IT New Phone",null,phoneModel,50L).getContent().get(0)).containsEntry("scheduleStatus","pending");assertThat(jdbc.queryForObject("select count(*) from plm_inventory",Long.class)).isEqualTo(stockBefore);assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class)).isEqualTo(setsBefore+1);
        service.callback(instance,props.getProcessCode(),true);assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class)).isEqualTo(setsBefore+1);
        jdbc.update("update plm_collaboration_mold_set set opened_date='2026-10-14' where mold_set_id=?",built.get("moldSetId"));source.put("status","TERMINATED").put("finish_time","2026-10-14 10:00:00");service.syncInternal(instance);var paused=service.detail(id(row));assertThat(paused).containsEntry("executionStatus","paused").containsEntry("approvalGranted",false);assertThat(paused.get("authorization")).isNotNull();assertThat(jdbc.queryForObject("select opened_date::text from plm_collaboration_mold_set where mold_set_id=?",String.class,built.get("moldSetId"))).isEqualTo("2026-10-14");
        var resumed=service.resolve(id(row),new MoldApplicationService.Resolution(version(paused),"resume","工程确认已发生投入，继续完成"),null);assertThat(resumed).containsEntry("executionStatus","active");service.syncInternal(instance);assertThat(service.detail(id(row))).containsEntry("executionStatus","active");
    }
    @Test void sharedDifferentPhoneCodeCanSubmitTheOriginalCodesWithoutCreatingAnotherSet() throws Exception {
        allowWrite();var f=sharedFixture();var row=service.save(null,new MoldApplicationService.Draft(json.valueToTree(Map.of("model",f.model(),"tipo",tipo,"expectedDeliveryDate","2026-11-01")),50L,f.code(),null,f.member(),"reuse",f.item(),f.set(),null),null);
        row=service.upload(id(row),"key_photo",new MockMultipartFile("file","sample.png","image/png",Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6BuMAAAAASUVORK5CYII=")),null);row=service.reserve(id(row),version(row),f.member(),f.item(),f.set(),f.digest(),null);
        long sets=jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class);when(client.submit(anyMap())).thenReturn("it-shared-submit");when(client.instance("it-shared-submit")).thenThrow(new MoldDingTalkClient.RemoteFailure(false,"offline"));var result=service.submit(id(row),new MoldApplicationService.Submit(version(row),null,true),null);assertThat(result).containsEntry("status","submitted").containsEntry("phoneModelId",f.member());verify(client,times(1)).submit(anyMap());assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class)).isEqualTo(sets);
    }
    @Test void newSetUsesOriginalMasterCodeAndCreatesOnlyAnotherPlannedSet() throws Exception {
        var f=sharedFixture();var row=service.save(null,new MoldApplicationService.Draft(json.valueToTree(Map.of("model",f.model(),"tipo",tipo,"expectedDeliveryDate","2026-11-01")),50L,f.code(),null,f.member(),"new_set",f.item(),null,null,"新增一套用于第二条生产线",UUID.randomUUID().toString(),true),null);row=service.reserve(id(row),version(row),null);assertThat(row.get("generatedCodes")).isEqualTo("MFA101144 / MFA201144");allowWrite();row=service.upload(id(row),"key_photo",new MockMultipartFile("file","new-set.png","image/png",Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6BuMAAAAASUVORK5CYII=")),null);when(client.submit(anyMap())).thenReturn("it-new-set-submit");when(client.instance("it-new-set-submit")).thenThrow(new MoldDingTalkClient.RemoteFailure(false,"offline"));row=service.submit(id(row),new MoldApplicationService.Submit(version(row),null,true),null);assertThat(row).containsEntry("status","submitted");
        converter();var approved=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot("RUNNING","");for(var component:approved.withArray("form_component_values")){String key=component.path("id").asText();if(MoldApplicationForms.COMPONENTS.get("model").equals(key))((com.fasterxml.jackson.databind.node.ObjectNode)component).put("value",f.model());if(MoldApplicationForms.COMPONENTS.get("generatedCodes").equals(key))((com.fasterxml.jackson.databind.node.ObjectNode)component).put("value",row.get("generatedCodes").toString());}approved.withArray("form_component_values").addObject().put("id",MoldApplicationForms.COMPONENTS.get("expectedDeliveryDate")).put("value","2026-11-01");approved.putArray("tasks").addObject().put("userid",MoldApplicationForms.EXECUTION_APPROVER_USER_ID).put("taskid","new-set-approved-task").put("task_status","COMPLETED").put("task_result","AGREE").put("finish_time","2026-10-14 12:00:00");doReturn(approved).when(client).instance("it-new-set-submit");
        long sets=jdbc.queryForObject("select count(*) from plm_collaboration_mold_set where item_id=?",Long.class,f.item());service.syncInternal("it-new-set-submit");var result=service.detail(id(row));assertThat(result).containsEntry("itemId",f.item()).containsEntry("provisioningStatus","ready");assertThat(result.get("moldSetId")).isNotEqualTo(f.set());assertThat(jdbc.queryForObject("select pc_confirmed and tpu_confirmed from plm_collaboration_phone_member where item_id=? and phone_model_id=?",Boolean.class,f.item(),f.member())).isTrue();assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_set where item_id=?",Long.class,f.item())).isEqualTo(sets+1);assertThat(jdbc.queryForObject("select count(*) from plm_inventory where product_id=?",Long.class,result.get("projectId"))).isZero();assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_phone_set_compatibility where mold_set_id=?",Long.class,result.get("moldSetId"))).isZero();
        long project=((Number)result.get("projectId")).longValue();
        var second=service.save(null,new MoldApplicationService.Draft((JsonNode)result.get("form"),50L,f.code(),null,f.member(),"new_set",f.item(),null,project,"原项目新增第三条生产线的实物套",UUID.randomUUID().toString(),true),null);second=service.reserve(id(second),version(second),null);
        jdbc.update("update plm_mold_application set status='submitted',remote_status='RUNNING',approval_granted=true,approval_no='IT-SECOND-NEW-SET',external_instance_id='it-second-new-set',authorization_json=(select authorization_json from plm_mold_application where application_id=?) where application_id=?",id(row),id(second));second=repo.row(id(second),false);
        var linked=converter().convert(id(second),new MoldApplicationConversion.Convert(version(second),"link",project,f.item(),null,true),null);assertThat(linked).containsEntry("projectId",project).containsEntry("itemId",f.item());assertThat(linked.get("moldSetId")).isNotEqualTo(result.get("moldSetId"));assertThat(jdbc.queryForObject("select count(*) from plm_product where project_kind='model_extension' and phone_model_member_id=?",Long.class,f.member())).isEqualTo(1L);

    }

    @Test void anUnrelatedApproverOrUntrustedCallbackCannotBuildOrAuthorize() throws Exception {
        var row=prepared();converter();String instance="it-unrelated-"+UUID.randomUUID();jdbc.update("update plm_mold_application set status='submitted',remote_status='RUNNING',external_instance_id=?,provisioning_status='pending' where application_id=?",instance,id(row));var source=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot("RUNNING","");source.put("result","agree");source.putArray("tasks").addObject().put("userid","not-tiffany").put("taskid","another-task").put("task_status","COMPLETED").put("task_result","AGREE");when(client.instance(instance)).thenReturn(source);long sets=jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class);service.callback(instance,props.getProcessCode(),true);assertThat(service.detail(id(row))).containsEntry("approvalGranted",false).containsEntry("businessStatus","awaiting_approval").containsEntry("moldSetId",null);assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class)).isEqualTo(sets);
    }

    @Test void stableDraftRequestAcrossSourcesPreservesOriginalEvidenceAndSurvivesProductSelection() {
        var market=mock(com.yuewei.plm.module.marketwatch.MarketWatchWorkflowService.class);org.springframework.test.util.ReflectionTestUtils.setField(service,"marketWorkflow",market);
        when(market.draftContext("source-one")).thenReturn(Map.of("phoneModelId",phoneModel,"capturedAt","first","sources",List.of(Map.of("kind","candidate","id",101L,"model","IP raw original"))));
        when(market.draftContext("source-two")).thenReturn(Map.of("phoneModelId",phoneModel,"capturedAt","second","sources",List.of(Map.of("kind","phone","id",202L,"model","Second country raw model"))));
        String key="it-source-request-"+UUID.randomUUID();var first=service.startFromPhone(new MoldApplicationService.SourceDraft(phoneModel,"source-one",null,null,null,"new",null,null,null,null,key,false),null);
        var again=service.startFromPhone(new MoldApplicationService.SourceDraft(phoneModel,"source-two",null,null,50L,"new",null,null,null,null,key,false),null);
        assertThat(again).containsEntry("applicationId",first.get("applicationId")).containsEntry("alreadyExists",true);var snapshot=(JsonNode)again.get("sourceSnapshot");assertThat(snapshot.path("capturedAt").asText()).isEqualTo("first");assertThat(snapshot.path("sources").size()).isEqualTo(2);
        var saved=service.save(id(again),new MoldApplicationService.Draft((JsonNode)again.get("form"),50L,phone,version(again),phoneModel,"new",null,null,null,null,key,false),null);
        assertThat(saved).containsEntry("productLineId",50L).containsEntry("draftRequestId",key).containsEntry("sourceSnapshot",snapshot);
        var crossSource=service.startFromPhone(new MoldApplicationService.SourceDraft(phoneModel,"source-one",null,null,50L,"new",null,null,null,null,"another-request-"+UUID.randomUUID(),false),null);
        assertThat(crossSource).containsEntry("applicationId",saved.get("applicationId"));assertThat(jdbc.queryForObject("select count(*) from plm_mold_application where phone_model_id=?",Long.class,phoneModel)).isEqualTo(1);
        var logs=(OperationLogService)org.springframework.test.util.ReflectionTestUtils.getField(service,"logs");verify(logs,atLeastOnce()).logRequired(argThat(command->"MOLD_APPLICATION_SOURCE_APPEND".equals(command.getAction())),any(CurrentUser.class));verifyNoInteractions(client,files);
    }

    @Test void explicitNewSetRequiresReasonAndItsStableRequestCannotCreateTwice() {
        var f=sharedFixture();String key="it-new-set-request-"+UUID.randomUUID();var input=new MoldApplicationService.SourceDraft(f.member(),null,null,null,50L,"new_set",f.item(),null,null,"新增第二条生产线实物套",key,true);
        var first=service.startFromPhone(input,null);var second=service.startFromPhone(input,null);assertThat(second).containsEntry("applicationId",first.get("applicationId")).containsEntry("executionReason","新增第二条生产线实物套").containsEntry("newApplication",true);
        assertThat(jdbc.queryForObject("select count(*) from plm_mold_application where phone_model_id=?",Long.class,f.member())).isEqualTo(1);
        assertThatThrownBy(()->service.startFromPhone(new MoldApplicationService.SourceDraft(f.member(),null,null,null,50L,"new_set",f.item(),null,null,null,"other-request-"+UUID.randomUUID(),true),null)).hasMessageContaining("原因");
        assertThatThrownBy(()->service.startFromPhone(new MoldApplicationService.SourceDraft(phoneModel,null,null,null,50L,"new",null,null,null,null,key,false),null)).hasMessageContaining("请求ID");verifyNoInteractions(client,files);
    }

    @Test void productSelectionAndSubmissionRecheckAnotherApplicationWithoutChangingTheOriginalDraft() {
        var incomplete=service.save(null,new MoldApplicationService.Draft(json.valueToTree(Map.of("model","IT New Phone","tipo",tipo)),null,phone,null,phoneModel,"new",null,null,null),null);
        var other=service.save(null,new MoldApplicationService.Draft(json.valueToTree(Map.of("model","IT New Phone","tipo",tipo,"expectedDeliveryDate","2026-11-01")),50L,phone,null,phoneModel,"new",null,null,null,"另一个明确独立申请",UUID.randomUUID().toString(),true),null);
        assertThatThrownBy(()->service.save(id(incomplete),new MoldApplicationService.Draft((JsonNode)incomplete.get("form"),50L,phone,version(incomplete),phoneModel,"new",null,null,null),null)).hasMessageContaining("已有同手机");assertThat(repo.row(id(incomplete),false).get("productLineId")).isNull();
        allowWrite();assertThatThrownBy(()->service.submit(id(incomplete),new MoldApplicationService.Submit(version(incomplete),null,true),null)).hasMessageContaining("已有同手机");verify(client,never()).submit(anyMap());assertThat(repo.row(id(other),false)).containsEntry("status","draft");
    }

    @Test void hiddenApplicationBlocksBothSourceAndDirectCreationWithoutExposingItsIdentity() {
        var existing=draft();long before=jdbc.queryForObject("select count(*) from plm_mold_application",Long.class);String user="restricted-"+UUID.randomUUID();long uid=jdbc.queryForObject("insert into sys_user(user_code,username,login_name,user_name,display_name,all_permissions,status) values(?,?,?,'受限申请人','受限申请人',false,'active') returning user_id",Long.class,user,user,user);
        jdbc.update("insert into plm_pilot_account(user_id,role_code,permissions,all_objects,created_by,updated_by) values(?,'domestic','[\"collaboration:view\",\"mold-application:create\"]',false,'it','it')",uid);CurrentUserContext.set(new CurrentUser(uid,user,"受限申请人",false));
        assertThatThrownBy(()->service.startFromPhone(new MoldApplicationService.SourceDraft(phoneModel,null,null,null,null,"new",null,null,null),null)).hasMessageContaining("未获授权").satisfies(error->assertThat(error.getMessage()).doesNotContain(existing.get("approvalNo")==null?"IT New Phone":existing.get("approvalNo").toString()));
        assertThatThrownBy(()->service.save(null,new MoldApplicationService.Draft(json.valueToTree(Map.of("model","IT New Phone")),null,phone,null,phoneModel,"new",null,null,null),null)).hasMessageContaining("未获授权");assertThat(jdbc.queryForObject("select count(*) from plm_mold_application",Long.class)).isEqualTo(before);verifyNoInteractions(client,files);
    }

    @Test void runningTaskNamesUseVerifiedMappingsAndNeverDisplayAnUnknownNumericIdAsAName() {
        var row=draft();String known="it-known-handler-"+UUID.randomUUID(),unknown="235631134438-1463120700",ambiguous="it-ambiguous-handler-"+UUID.randomUUID();
        for(String name:List.of("李真实","另一姓名"))jdbc.update("insert into plm_mold_application(process_code,source_system,status,originator_user_id,originator_name,created_by,updated_by) values(?,'dingtalk','completed',?,?,'it','it')",props.getProcessCode(),ambiguous,name);
        jdbc.update("insert into plm_mold_application(process_code,source_system,status,originator_user_id,originator_name,created_by,updated_by) values(?,'dingtalk','completed',?,'李真实','it','it')",props.getProcessCode(),known);
        for(String uid:List.of(known,unknown,ambiguous))jdbc.update("insert into plm_mold_application_activity(application_id,source_key,activity_type,actor_id,actor_name,task_id,payload) values(?,?,'task',?,?,?,?::jsonb)",id(row),uid,uid,uid,"task-"+uid,repo.json(Map.of("task_status","RUNNING")));
        var handlers=(List<Map<String,Object>>)service.detail(id(row)).get("currentHandlers");assertThat(handlers).hasSize(3);assertThat(handlers.get(0)).containsEntry("userId",known).containsEntry("name","李真实");assertThat(handlers.get(1)).containsEntry("userId",unknown).containsEntry("name","处理人待核对");assertThat(handlers.get(2)).containsEntry("userId",ambiguous).containsEntry("name","处理人待核对");
    }

    @Test void operationOnlyApprovalWaitsForTaskEvidenceBeforeAutomaticProvisioning()throws Exception {
        var row=prepared();converter();String instance="it-operation-only-"+UUID.randomUUID();jdbc.update("update plm_mold_application set status='submitted',remote_status='RUNNING',external_instance_id=?,provisioning_status='pending' where application_id=?",instance,id(row));var source=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot("RUNNING","");for(var component:source.withArray("form_component_values"))if(MoldApplicationForms.COMPONENTS.get("generatedCodes").equals(component.path("id").asText()))((com.fasterxml.jackson.databind.node.ObjectNode)component).put("value",row.get("generatedCodes").toString());
        source.putArray("operation_records").addObject().put("userid",MoldApplicationForms.EXECUTION_APPROVER_USER_ID).put("operation_type","EXECUTE_TASK_NORMAL").put("operation_result","AGREE").put("date","2026-10-13 12:00:00");when(client.instance(instance)).thenReturn(source);long sets=jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class);service.syncInternal(instance);
        var pending=service.detail(id(row));assertThat(pending).containsEntry("approvalGranted",false).containsEntry("moldSetId",null);assertThat(((JsonNode)pending.get("authorization")).path("evidenceSource").asText()).isEqualTo("operation");assertThat(pending.get("nextStep").toString()).contains("补拉完整任务证据");assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class)).isEqualTo(sets);
    }

    @Test void transferredTaskReplacesItsHandlerAndCompletionTimeWithoutKeepingTheOldName()throws Exception {
        var row=draft();String instance="it-task-transfer-"+UUID.randomUUID(),taskId="transferred-task",oldActor="old-handler-"+UUID.randomUUID(),newActor="new-handler-"+UUID.randomUUID();jdbc.update("update plm_mold_application set external_instance_id=?,status='submitted',remote_status='RUNNING' where application_id=?",instance,id(row));
        var source=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot("RUNNING","");var task=source.putArray("tasks").addObject().put("taskid",taskId).put("userid",oldActor).put("task_status","RUNNING");when(client.instance(instance)).thenReturn(source);service.syncInternal(instance);
        jdbc.update("update plm_mold_application_activity set actor_name='原负责人' where application_id=? and task_id=?",id(row),taskId);
        task.put("userid",newActor);service.syncInternal(instance);var transferred=jdbc.queryForMap("select actor_id,actor_name,occurred_at from plm_mold_application_activity where application_id=? and task_id=?",id(row),taskId);assertThat(transferred).containsEntry("actor_id",newActor).containsEntry("actor_name",null).containsEntry("occurred_at",null);
        var handlers=(List<Map<String,Object>>)service.detail(id(row)).get("currentHandlers");assertThat(handlers).hasSize(1);assertThat(handlers.get(0)).containsEntry("userId",newActor).containsEntry("name","处理人待核对");
        task.put("task_status","COMPLETED").put("task_result","AGREE").put("finish_time","2026-10-14 12:30:00");service.syncInternal(instance);assertThat(jdbc.queryForObject("select count(*) from plm_mold_application_activity where application_id=? and task_id=?",Long.class,id(row),taskId)).isEqualTo(1);assertThat(jdbc.queryForObject("select occurred_at::text from plm_mold_application_activity where application_id=? and task_id=?",String.class,id(row),taskId)).isNotBlank();assertThat((List<?>)service.detail(id(row)).get("currentHandlers")).isEmpty();assertThat(service.detail(id(row))).containsEntry("approvalGranted",false);
    }

    @Test void renamedStandardPhoneBrandAndCodeAreSearchableInBothListsWithoutRewritingTheOriginalModel() {
        var row=draft();String renamed="IT Renamed Standard Phone",brand="IT Renamed Brand",alias="IT Current Phone Alias";jdbc.update("update plm_mold_application set status='submitted',remote_status='RUNNING',external_instance_id=? where application_id=?","it-search-"+UUID.randomUUID(),id(row));
        jdbc.update("update plm_phone_model_member set model_name=?,aliases=?::text[] where id=?",renamed,"{"+alias+"}",phoneModel);jdbc.update("update plm_phone_brand set name=? where id=(select brand_id from plm_phone_model_member where id=?)",brand,phoneModel);String code=jdbc.queryForObject("select code_value from plm_code_item where code_item_id=?",String.class,phone);
        for(String keyword:List.of(renamed,brand,alias,code,"IT New Phone")) {
            var page=service.page(1,50,keyword,tipo,null,null,null,null);assertThat(page.getTotalElements()).as("application keyword %s",keyword).isEqualTo(1);assertThat(page.getContent().get(0)).containsEntry("applicationId",id(row));
            var progress=service.progressPage(1,50,keyword,null,phoneModel,50L);assertThat(progress.getTotalElements()).as("progress keyword %s",keyword).isEqualTo(1);assertThat(progress.getContent().get(0)).containsEntry("applicationId",id(row));assertThat((Map<String,Object>)progress.getContent().get(0).get("standardPhone")).containsEntry("modelName",renamed).containsEntry("brandName",brand);
        }
        assertThat(repo.row(id(row),false)).containsEntry("model","IT New Phone");verifyNoInteractions(client,files);
    }

    private Map<String,Object> uncodedHistory(Long initialPhone,Long originalCode){
        var form=Map.of("model","历史组合原文 17 / 18 Pro","tipo",tipo,"remark","原审批文字保留，不改刻码");long application=jdbc.queryForObject("insert into plm_mold_application(source_system,process_code,external_instance_id,model,tipo,form_data,status,remote_status,approval_granted,phone_model_id,phone_code_item_id,execution_resolution_json,created_by,updated_by) values('dingtalk',?,?,?,?,?::jsonb,'submitted','RUNNING',false,?,?,?::jsonb,'it','it') returning application_id",Long.class,props.getProcessCode(),"it-history-"+UUID.randomUUID(),form.get("model"),tipo,repo.json(form),initialPhone,originalCode,repo.json(Map.of("action","resume","reason","原工程处理记录","approvalState","original-proof")));return repo.row(application,false);
    }
    private long existingHistoricalProject(long member){return jdbc.queryForObject("insert into plm_product(parent_product_id,product_code,product_name,product_type,model,phone_model_code,phone_model_member_id,status,version_no,created_by,updated_by) select 50,?,'已有历史项目','model_variant',m.model_name,c.code_value,m.id,'developing','A','it','it' from plm_phone_model_member m left join plm_code_item c on c.code_item_id=m.code_item_id where m.id=? returning product_id",Long.class,"IT-HISTORY-"+UUID.randomUUID(),member);}
    private Map<String,Long> physicalAndEntityCounts(){var counts=new LinkedHashMap<String,Long>();for(String table:List.of("plm_product","plm_collaboration_item","plm_collaboration_mold_set","plm_collaboration_mold_part","plm_inventory","plm_code_item","plm_product_mold_code","plm_project_cost_item","plm_collaboration_phone_set_compatibility","plm_collaboration_phone_member","plm_mold_code_reservation"))counts.put(table,jdbc.queryForObject("select count(*) from "+table,Long.class));return counts;}

    @Test void uncodedHistoricalAssociationLinksOnlyExistingFactsAndPreservesApprovalAndResolution()throws Exception {
        var f=sharedFixture();long project=existingHistoricalProject(phoneModel);var row=uncodedHistory(f.member(),f.code());var convert=converter();var before=physicalAndEntityCounts();var original=repo.row(id(row),false);
        var preview=convert.associationPreview(id(row),new MoldApplicationConversion.AssociationPreview(phoneModel,50L));assertThat(((List<Map<String,Object>>)preview.get("projects")).stream().filter(p->Objects.equals(p.get("projectId"),project)).findFirst().orElseThrow().get("blockedReason")).isNull();assertThat(preview.get("application")).isNotNull();assertThat(row.get("generatedCodes")).isNull();
        var linked=convert.associateHistory(id(row),new MoldApplicationConversion.Association(version(row),phoneModel,50,project,f.item(),f.set(),"按原记录明确更正手机并补现有关系，适配另行核对",true),null);
        assertThat(linked).containsEntry("phoneModelId",phoneModel).containsEntry("projectId",project).containsEntry("itemId",f.item()).containsEntry("moldSetId",f.set()).containsEntry("approvalGranted",false).containsEntry("executionStatus","pending").containsEntry("nextStep","历史关系已关联，适配待确认");assertThat(physicalAndEntityCounts()).isEqualTo(before);assertThat(linked.get("model")).isEqualTo(original.get("model"));assertThat(linked.get("form")).isEqualTo(original.get("form"));assertThat(linked.get("generatedCodes")).isEqualTo(original.get("generatedCodes"));assertThat(linked.get("phoneCodeItemId")).isEqualTo(phone);
        var resolution=(JsonNode)linked.get("executionResolution");assertThat(resolution.path("action").asText()).isEqualTo("resume");assertThat(resolution.path("approvalState").asText()).isEqualTo("original-proof");assertThat(resolution.path("historicalAssociation").path("before").path("phoneModelId").asLong()).isEqualTo(f.member());assertThat(resolution.path("historicalAssociation").path("associationOnly").asBoolean()).isTrue();assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_phone_set_compatibility where phone_model_id=? and mold_set_id=?",Long.class,phoneModel,f.set())).isZero();assertThat(jdbc.queryForObject("select item_id from plm_collaboration_item_project where project_id=?",Long.class,project)).isEqualTo(f.item());
        var workflow=new com.yuewei.plm.module.collaboration.MoldWorkflowRepository(jdbc);var projected=new LinkedHashMap<String,Object>();projected.put("moldSetId",f.set());projected.put("openedDate",null);workflow.enrich(List.of(projected));assertThat(projected).containsEntry("schedulePaused",false).containsEntry("applicationPlanned",false);
        var logs=(OperationLogService)org.springframework.test.util.ReflectionTestUtils.getField(service,"logs");verify(logs).logRequired(argThat(command->"MOLD_APPLICATION_HISTORY_ASSOCIATE".equals(command.getAction())),any(CurrentUser.class));
        var terminated=json.createObjectNode().put("status","TERMINATED").put("title","历史发起人提交的开模申请").put("finish_time","2026-10-14 12:00:00");var fields=terminated.putArray("form_component_values");((JsonNode)original.get("form")).fields().forEachRemaining(field->fields.addObject().put("id",MoldApplicationForms.COMPONENTS.get(field.getKey())).put("value",field.getValue().asText()));when(client.instance(row.get("instanceId").toString())).thenReturn(terminated);service.syncInternal(row.get("instanceId").toString());var later=service.detail(id(row));assertThat(((JsonNode)later.get("executionResolution")).path("historicalAssociation")).isEqualTo(resolution.path("historicalAssociation"));assertThat(later).containsEntry("approvalGranted",false).containsEntry("nextStep","历史关系已关联，适配待确认");assertThat(new com.yuewei.plm.module.collaboration.MoldWorkflowRepository(jdbc).isPaused(f.set())).isFalse();verify(client,never()).submit(anyMap());
    }

    @Test void historicalAssociationRejectsSilentPhoneChangesStaleVersionsWrongProjectsAndPlannedSets(){
        var f=sharedFixture();var other=sharedFixture();long project=existingHistoricalProject(phoneModel),wrongProject=existingHistoricalProject(f.member());var row=uncodedHistory(f.member(),f.code());var convert=converter();
        assertThatThrownBy(()->convert.associateHistory(id(row),new MoldApplicationConversion.Association(version(row),phoneModel,50,project,f.item(),f.set(),"",true),null)).hasMessageContaining("原因");
        assertThatThrownBy(()->convert.associateHistory(id(row),new MoldApplicationConversion.Association(version(row),phoneModel,50,project,f.item(),f.set(),"明确核对",false),null)).hasMessageContaining("明确确认");
        assertThatThrownBy(()->convert.associateHistory(id(row),new MoldApplicationConversion.Association(version(row)+1,phoneModel,50,project,f.item(),f.set(),"明确核对",true),null)).hasMessageContaining("已更新");
        assertThatThrownBy(()->convert.associateHistory(id(row),new MoldApplicationConversion.Association(version(row),phoneModel,50,wrongProject,f.item(),f.set(),"明确核对",true),null)).hasMessageContaining("另一标准手机");
        assertThatThrownBy(()->convert.associateHistory(id(row),new MoldApplicationConversion.Association(version(row),phoneModel,50,project,f.item(),other.set(),"明确核对",true),null)).hasMessageContaining("不属于");
        long planned=jdbc.queryForObject("insert into plm_collaboration_mold_set(item_id,set_no,set_name,created_by,updated_by) values(?,2,'只有拟制资料','it','it') returning mold_set_id",Long.class,f.item());assertThatThrownBy(()->convert.associateHistory(id(row),new MoldApplicationConversion.Association(version(row),phoneModel,50,project,f.item(),planned,"明确核对",true),null)).hasMessageContaining("拟制");
        var options=convert.associationPreview(id(row),new MoldApplicationConversion.AssociationPreview(phoneModel,50L));assertThat(((List<Map<String,Object>>)options.get("sets")).stream().filter(s->Objects.equals(s.get("moldSetId"),planned)).findFirst().orElseThrow().get("blockedReason").toString()).contains("拟制");assertThat(repo.row(id(row),false)).containsEntry("phoneModelId",f.member()).containsEntry("projectId",null);
        assertThatThrownBy(()->convert.convert(id(row),new MoldApplicationConversion.Convert(version(row),"create",null,null,null,true),null)).hasMessageContaining("不创建");verifyNoInteractions(client,files);
    }

    @Test void historicalAssociationAuditFailureRollsBackAllRelationChanges(){
        var f=sharedFixture();long project=existingHistoricalProject(phoneModel);var row=uncodedHistory(phoneModel,phone);var convert=converter();var before=physicalAndEntityCounts();var logs=(OperationLogService)org.springframework.test.util.ReflectionTestUtils.getField(service,"logs");doThrow(new IllegalStateException("required audit failed")).when(logs).logRequired(any(),any());var nested=new TransactionTemplate(tx);nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(()->nested.execute(scope->convert.associateHistory(id(row),new MoldApplicationConversion.Association(version(row),phoneModel,50,project,f.item(),f.set(),"按真实资料补关系",false),null))).hasMessageContaining("audit failed");assertThat(repo.row(id(row),false)).containsEntry("projectId",null).containsEntry("moldSetId",null).containsEntry("executionResolution",row.get("executionResolution"));assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_item_project where project_id=?",Long.class,project)).isZero();assertThat(physicalAndEntityCounts()).isEqualTo(before);
    }

    @Test void historicalAssociationRequiresRealEngineeringPermissionAndVisibleApplication(){
        var row=uncodedHistory(phoneModel,phone);var convert=converter();String user="history-view-"+UUID.randomUUID();long uid=jdbc.queryForObject("insert into sys_user(user_code,username,login_name,user_name,display_name,all_permissions,status) values(?,?,?,'普通人员','普通人员',false,'active') returning user_id",Long.class,user,user,user);jdbc.update("insert into plm_pilot_account(user_id,role_code,permissions,all_objects,created_by,updated_by) values(?,'domestic','[\"collaboration:view\",\"mold-application:convert\"]',true,'it','it')",uid);CurrentUserContext.set(new CurrentUser(uid,user,"普通人员",true));
        assertThatThrownBy(()->convert.associationPreview(id(row),new MoldApplicationConversion.AssociationPreview(null,null))).hasMessageContaining("工程职责");jdbc.update("insert into plm_collaboration_assignment(field_group,user_id) values('engineering',?)",uid);jdbc.update("update plm_pilot_account set all_objects=false where user_id=?",uid);assertThatThrownBy(()->convert.associationPreview(id(row),new MoldApplicationConversion.AssociationPreview(null,null))).hasMessageContaining("未获授权");assertThat(repo.row(id(row),false)).containsEntry("projectId",null);verifyNoInteractions(client,files);
    }


    com.yuewei.plm.module.project.ProjectWorkbenchService workbench(PilotAccessService permissions) {
        var ledger=new com.yuewei.plm.module.collaboration.CollaborationRepository(jdbc,json);
        var collab=new com.yuewei.plm.module.collaboration.CollaborationService(jdbc,json,ledger,tx,mock(OperationLogService.class),permissions);
        var schedules=new com.yuewei.plm.module.collaboration.MoldScheduleService(jdbc,json,collab,ledger,permissions,tx);
        var docs=new com.yuewei.plm.module.attachment.service.DocumentService(jdbc,mock(com.yuewei.plm.module.attachment.repository.AttachmentRepository.class),mock(com.yuewei.plm.module.attachment.service.AttachmentService.class),permissions,json);
        var finance=new com.yuewei.plm.module.development.DevelopmentFinanceService(jdbc,json,permissions,tx,mock(OperationLogService.class));
        var timeline=spy(new com.yuewei.plm.module.project.service.TimelineDefinitionProvider());
        doAnswer(call->{Product product=call.getArgument(0);assertThat(product.getStatus()).isNotBlank();assertThat(product.getProductId()).isNotNull();return call.callRealMethod();}).when(timeline).getDefinitions(any(Product.class));
        return new com.yuewei.plm.module.project.ProjectWorkbenchService(jdbc,permissions,service,schedules,timeline,docs,finance);
    }
    long formalProject(String type,String state,String suffix) {
        return jdbc.queryForObject("insert into plm_product(parent_product_id,product_code,product_name,product_type,status,version_no,project_kind,project_registered_at,created_by,updated_by) values(?,?,?, ?,?,'A',?,now(),'it','it') returning product_id",Long.class,type.equals("model_variant")?50L:null,"IT-WORK-"+UUID.randomUUID(),"IT Workbench "+suffix,type,state,type.equals("product_line")?"product_research":"model_extension");
    }
    @Test void workbenchExcludesCatalogAndUsesServerPagingKindsAndStates() {
        var dashboard=workbench(access);var q=new com.yuewei.plm.module.project.ProjectWorkbenchService.Query();q.setKeyword("IT Workbench");
        assertThat(dashboard.page(q)).containsEntry("totalElements",0L);
        long model=formalProject("model_variant","developing","phone");formalProject("product_line","released","released");long stopped=formalProject("model_variant","developing","stopped");jdbc.update("update plm_product set abandoned_at=now(),abandon_reason='工程停止' where product_id=?",stopped);
        var result=dashboard.page(q);assertThat((Map<String,Object>)result.get("stats")).containsEntry("active",1L).containsEntry("all",3L);assertThat(result).containsEntry("totalElements",1L);assertThat(((List<Map<String,Object>>)result.get("content")).get(0)).containsEntry("projectId",model);
        q.setView("all");q.setSize(1);q.setPage(2);assertThat(dashboard.page(q)).containsEntry("totalElements",3L);q.setKind("product_research");assertThat(dashboard.page(q)).containsEntry("totalElements",1L);
        assertThat(dashboard.overview(50)).containsEntry("historicalRecord",true);assertThat(jdbc.queryForObject("select project_kind from plm_product where product_id=50",String.class)).isNull();
    }
    @Test void colorCatalogSkuNeverBecomesAProjectOrBlocksApplicationConversion() {
        long sku=existingHistoricalProject(phoneModel);
        jdbc.update("update plm_product set color='黑色',finished_product_code=? where product_id=?","IT-FINISHED-"+sku,sku);
        assertThat((List<?>)service.targetOptions(phoneModel,50).get("projects")).isEmpty();
        var row=approved();var conversion=converter();assertThat((List<?>)conversion.preview(id(row)).get("projects")).isEmpty();
        var built=conversion.convert(id(row),new MoldApplicationConversion.Convert(version(row),"create",null,null,null,true),null);
        assertThat(built.get("projectId")).isNotEqualTo(sku);
        var coverage=new com.yuewei.plm.module.moldreference.PhoneModelCoverageService(jdbc,access).batch(List.of(phoneModel)).get(phoneModel);
        assertThat(coverage.stream().flatMap(g->((List<Map<String,Object>>)g.get("applications")).stream()).filter(a->Objects.equals(a.get("applicationId"),id(row))).findFirst().orElseThrow()).containsEntry("businessStatus","approved").containsEntry("coverageState","planned");
        com.yuewei.plm.module.project.ProjectRegistration.registerConverted(jdbc,sku);
        assertThat(jdbc.queryForObject("select project_kind from plm_product where product_id=?",String.class,sku)).isNull();
        long project=((Number)built.get("projectId")).longValue();jdbc.update("update plm_product set color='黑色,白色' where product_id=?",project);
        assertThat((List<Map<String,Object>>)service.targetOptions(phoneModel,50).get("projects")).extracting(p->p.get("projectId")).containsExactly(project);
    }
    @Test void exactChildSkuCompletesTheProjectAndRetainsPhoneIdentityWithoutManufacturingStock() {
        var row=approved();var built=converter().convert(id(row),new MoldApplicationConversion.Convert(version(row),"create",null,null,null,true),null);
        long project=((Number)built.get("projectId")).longValue(),sets=jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class),inventory=jdbc.queryForObject("select count(*) from plm_inventory",Long.class);
        var dashboard=workbench(access);assertThat(dashboard.overview(project)).containsEntry("projectState","active");
        long sku=jdbc.queryForObject("insert into plm_product(parent_product_id,product_code,product_name,product_type,phone_model_code,color,finished_product_code,status,version_no,created_by,updated_by) select product_id,?,'IT child SKU','sku',phone_model_code,'黑色',?,'released','A','it','it' from plm_product where product_id=? returning product_id",Long.class,"IT-CHILD-"+UUID.randomUUID(),"IT-SKU-"+UUID.randomUUID(),project);
        assertThat(dashboard.overview(project)).containsEntry("projectState","completed");
        assertThat(com.yuewei.plm.module.moldreference.PhoneProductEvidence.rows(jdbc,List.of(phoneModel),"p.product_id="+sku)).singleElement().satisfies(r->assertThat(r).containsEntry("phoneModelId",phoneModel).containsEntry("productLineId",50L).containsEntry("identitySource","parent").containsEntry("historicalSkuEvidence",true));
        jdbc.update("update plm_product set phone_model_code='DIFFERENT' where product_id=?",sku);assertThat(dashboard.overview(project)).containsEntry("projectState","active");
        long legacy=existingHistoricalProject(phoneModel);jdbc.update("update plm_product set color='白色',finished_product_code=? where product_id=?","IT-LEGACY-"+legacy,legacy);
        assertThat(dashboard.overview(project)).containsEntry("projectState","completed");
        jdbc.update("update plm_product set mold_development_variant='磨砂款' where product_id=?",project);
        assertThat(dashboard.overview(project)).containsEntry("projectState","active").containsEntry("historicalSkuCount",0);
        jdbc.update("update plm_product set phone_model_code=(select phone_model_code from plm_product where product_id=?) where product_id=?",project,sku);
        assertThat(dashboard.overview(project)).containsEntry("projectState","completed").containsEntry("historicalSkuCount",1);
        assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class)).isEqualTo(sets);assertThat(jdbc.queryForObject("select count(*) from plm_inventory",Long.class)).isEqualTo(inventory);
    }
    @Test void convertedProjectOverviewTracksExactSetsPausesAndDoesNotInferAllArchiveStock() {
        var row=approved();var convert=converter();var built=convert.convert(id(row),new MoldApplicationConversion.Convert(version(row),"create",null,null,null,true),null);long project=((Number)built.get("projectId")).longValue(),item=((Number)built.get("itemId")).longValue(),set=((Number)built.get("moldSetId")).longValue();
        assertThat(jdbc.queryForObject("select project_kind from plm_product where product_id=?",String.class,project)).isEqualTo("model_extension");
        jdbc.update("insert into plm_collaboration_mold_set(item_id,set_no,set_name,created_by,updated_by) values(?,2,'另一个项目的实物套','it','it')",item);
        var dashboard=workbench(access);var overview=dashboard.overview(project);assertThat(overview).containsEntry("applicationCount",1).containsEntry("archiveCount",1).containsEntry("moldSetCount",1).containsEntry("physicalSetCount",0L).containsEntry("flowStarted",false);assertThat(((List<Map<String,Object>>)overview.get("moldSets")).get(0)).containsEntry("moldSetId",set);

        long file=jdbc.queryForObject("insert into plm_attachment(owner_object_type,owner_object_id,file_category,file_name,original_file_name,file_ext,content_type,file_size,storage_type,storage_key,version_no,status,created_by,updated_by) values('Document',0,'drawing','it.pdf','it.pdf','pdf','application/pdf',10,'local','it/workbench.pdf','V1','draft','it','it') returning attachment_id",Long.class);
        jdbc.update("insert into plm_document_reference(family_id,object_type,object_id,part_type,created_by) values(?,'product',?,'ALL','it'),(?,'item',?,'ALL','it'),(?,'mold_set',?,'ALL','it')",file,project,file,item,file,set);
        jdbc.update("insert into plm_project_cost_item(product_id,cost_name,cost_category,item_id,mold_set_id,currency_code,requested_amount,approved_amount,amount,status,source_system,created_by,updated_by) values(?,'直接及套双重关联','mold',?,?,'CNY',100,90,80,'confirmed','manual','it','it'),(null,'共用主档参考费用','mold',?,null,'USD',30,null,null,'draft','manual','it','it')",project,item,set,item);
        var withCost=dashboard.overview(project);assertThat(withCost).containsEntry("documentCount",1L).containsEntry("relatedExpenseCount",2L);
        var currencies=(List<Map<String,Object>>)((Map<String,Object>)withCost.get("directFinance")).get("currencyGroups");assertThat(currencies).hasSize(1);assertThat(currencies.get(0)).containsEntry("currencyCode","CNY");assertThat(new java.math.BigDecimal(currencies.get(0).get("declaredTotal").toString())).isEqualByComparingTo("80");
        var readonly=new com.yuewei.plm.module.project.ProjectWorkbenchService.Query();readonly.setKeyword("IT Mold Tipo");dashboard.page(readonly);assertThat(jdbc.queryForObject("select current_step_no from plm_product where product_id=?",Integer.class,project)).isEqualTo(1);
        var filter=new com.yuewei.plm.module.collaboration.CollaborationTypes.Query();filter.setView("delivery");filter.setMoldLedger(true);filter.setProjectId(project);assertThat(new com.yuewei.plm.module.collaboration.CollaborationRepository(jdbc,json).page(filter,Set.of("engineering"),"true").getContent()).hasSize(1);
        jdbc.update("update plm_collaboration_mold_set set opened_date=current_date where mold_set_id=?",set);jdbc.update("update plm_mold_application set execution_status='paused' where application_id=?",id(row));assertThat(dashboard.overview(project)).containsEntry("phase","执行暂停");assertThat(jdbc.queryForObject("select opened_date is not null from plm_collaboration_mold_set where mold_set_id=?",Boolean.class,set)).isTrue();
        assertThat(service.page(1,50,null,null,null,null,null,null,project,null).getContent()).hasSize(1);assertThat(service.page(1,50,null,null,null,null,null,null,project,true).getContent()).isEmpty();
    }
    @Test void projectReadWithoutDownstreamPermissionsReturnsNoBusinessCountsOrDetails() {
        long project=formalProject("model_variant","developing","scoped");var restricted=mock(PilotAccessService.class);when(restricted.visibleSqlClause(anyString(),anyString())).thenReturn("true");when(restricted.hasPermission(anyString())).thenReturn(false);
        var result=workbench(restricted).overview(project);assertThat(result).containsEntry("applicationCount",null).containsEntry("archiveCount",null).containsEntry("moldSetCount",null).containsEntry("physicalSetCount",null);assertThat((List<?>)result.get("applications")).isEmpty();assertThat(result).doesNotContainKeys("directFinance","relatedExpenseCount","documentCount","bomCount");verify(restricted).requireProduct(project);
        String name="workbench-reader-"+UUID.randomUUID();long uid=jdbc.queryForObject("insert into sys_user(user_code,username,login_name,user_name,display_name,all_permissions,status) values(?,?,?,'项目读者','项目读者',false,'active') returning user_id",Long.class,name,name,name);
        jdbc.update("insert into plm_pilot_account(user_id,role_code,permissions,all_objects,created_by,updated_by) values(?,'domestic','[\"project:view\"]',false,'it','it')",uid);
        jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) values(?,'product',?)",uid,project);
        CurrentUserContext.set(new CurrentUser(uid,name,"项目读者",false));var realScope=workbench(access);var query=new com.yuewei.plm.module.project.ProjectWorkbenchService.Query();var visible=realScope.page(query);assertThat(visible).containsEntry("totalElements",1L).doesNotContainKey("unlinkedApplications");assertThat(realScope.overview(project)).containsEntry("applicationCount",null).containsEntry("moldSetCount",null);assertThatThrownBy(()->realScope.overview(50)).hasMessageContaining("授权");
        doThrow(new BusinessException(40103,"未获授权")).when(restricted).requireProduct(50L);assertThatThrownBy(()->workbench(restricted).overview(50)).hasMessageContaining("未获授权");
    }
    @Test void newPlmExecutionCannotUseHistoricalAssociationToBypassItsStrictWorkflow(){var row=draft();var convert=converter();assertThatThrownBy(()->convert.associationPreview(id(row),new MoldApplicationConversion.AssociationPreview(phoneModel,50L))).hasMessageContaining("PLM新执行");assertThatThrownBy(()->convert.associateHistory(id(row),new MoldApplicationConversion.Association(version(row),phoneModel,50,1,1,1,"不能绕过流程",false),null)).hasMessageContaining("PLM新执行");assertThat(repo.row(id(row),false)).containsEntry("status","draft").containsEntry("projectId",null);verifyNoInteractions(client,files);}

    MoldHistoryReconciliationService reconciler(){var convert=converter();return new MoldHistoryReconciliationService(repo,service,convert,codes.references(),access,(ProductRepository)org.springframework.test.util.ReflectionTestUtils.getField(convert,"products"),new ProductCodeGenerator(jdbc),mock(WorkflowTemplateService.class),tx);}
    @Test void historicalAssociationCanSaveIdentityAndProjectBeforeArchiveOrSet(){
        var row=uncodedHistory(phoneModel,phone);long project=existingHistoricalProject(phoneModel);var before=physicalAndEntityCounts();
        var linked=converter().associateHistory(id(row),new MoldApplicationConversion.Association(version(row),phoneModel,50L,Long.valueOf(project),(Long)null,(Long)null,"分层核对：先保存明确身份和项目",false),null);
        assertThat(linked).containsEntry("projectId",project).containsEntry("itemId",null).containsEntry("moldSetId",null).containsEntry("status",row.get("status")).containsEntry("remoteStatus",row.get("remoteStatus"));assertThat(physicalAndEntityCounts()).isEqualTo(before);verifyNoInteractions(client,files);
    }
    @SuppressWarnings("unchecked") @Test void historicalBatchRegistersOnlyTrustedProjectsAndLinksSampleSetsWithoutCreatingPhysicalFacts()throws Exception {
        // The production clone evolves. Build explicit approval/import fixtures instead of assuming old IDs 90-93 still name the same phones.
        var realBefore=jdbc.queryForList("select application_id,phone_model_id,project_id,item_id,mold_set_id,status,remote_status from plm_mold_application where application_id in(90,91,92,93) order by application_id");
        long batch=jdbc.queryForObject("insert into plm_collaboration_import_batch(file_hash,import_kind,file_name,imported_by) values(?,'plan','controlled-historical-evidence.xlsx','it') returning batch_id",Long.class,MoldApplicationMedia.hash(UUID.randomUUID().toString().getBytes()));
        var expected=new LinkedHashMap<Long,SharedFixture>();
        for(int n=1;n<=4;n++){
            var f=historyFixture();jdbc.update("update plm_collaboration_mold_set set opened_date=current_date where mold_set_id=?",f.set());
            jdbc.update("insert into plm_collaboration_import_row(batch_id,sheet_name,row_no,item_id,mold_set_id) values(?,'明确来源',?,?,?)",batch,n,f.item(),f.set());
            var row=sharedApproved(f);var raw=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot("RUNNING","有明确的审批任务与原始导入行");
            for(var component:raw.withArray("form_component_values")){if(MoldApplicationForms.COMPONENTS.get("model").equals(component.path("id").asText()))((com.fasterxml.jackson.databind.node.ObjectNode)component).put("value",f.model());if(MoldApplicationForms.COMPONENTS.get("generatedCodes").equals(component.path("id").asText()))((com.fasterxml.jackson.databind.node.ObjectNode)component).put("value",row.get("generatedCodes").toString());}
            raw.putArray("tasks").addObject().put("userid",MoldApplicationForms.EXECUTION_APPROVER_USER_ID).put("task_status","COMPLETED").put("task_result","AGREE").put("taskid","it-historical-approved-"+n).put("finish_time","2026-10-14 12:00:00");
            jdbc.update("update plm_mold_application set source_system='dingtalk',raw_snapshot=?::jsonb,project_id=null,item_id=null,mold_set_id=null where application_id=?",repo.json(raw),id(row));expected.put(id(row),f);
        }
        var before=physicalAndEntityCounts();var history=reconciler();var decisions=(List<Map<String,Object>>)history.preview().get("items");
        var sample=decisions.stream().filter(r->expected.containsKey(((Number)r.get("applicationId")).longValue())).toList();assertThat(sample).hasSize(4);assertThat(sample).allMatch(r->Boolean.TRUE.equals(r.get("authorized"))&&r.get("itemId")!=null&&r.get("moldSetId")!=null);
        var result=history.apply(new MoldHistoryReconciliationService.Apply(sample.stream().map(r->new MoldHistoryReconciliationService.Selection(((Number)r.get("applicationId")).longValue(),((Number)r.get("version")).longValue(),r.get("fingerprint").toString())).toList(),"本地回滚核验明确审批及原始导入依据"),null);
        assertThat(result).containsEntry("saved",4L);
        for(var fixture:expected.entrySet()){var row=repo.row(fixture.getKey(),false);assertThat(row).containsEntry("phoneModelId",fixture.getValue().member()).containsEntry("itemId",fixture.getValue().item()).containsEntry("moldSetId",fixture.getValue().set());assertThat(row.get("projectId")).isNotNull();}
        var after=physicalAndEntityCounts();for(var entry:before.entrySet())if(!entry.getKey().equals("plm_product"))assertThat(after.get(entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());assertThat(after.get("plm_product")).isEqualTo(before.get("plm_product")+4);
        assertThat(jdbc.queryForList("select application_id,phone_model_id,project_id,item_id,mold_set_id,status,remote_status from plm_mold_application where application_id in(90,91,92,93) order by application_id")).isEqualTo(realBefore);
        var current=(Map<String,Object>)result.get("remaining");var unchanged=((List<Map<String,Object>>)current.get("items")).stream().filter(r->expected.containsKey(((Number)r.get("applicationId")).longValue())).toList();assertThat(unchanged).allMatch(r->!Boolean.TRUE.equals(r.get("actionable")));verifyNoInteractions(client,files);
    }
    @SuppressWarnings("unchecked") @Test void staleHistoricalPreflightDoesNotCreateAProject(){
        var history=reconciler();var row=((List<Map<String,Object>>)history.preview().get("items")).stream().filter(r->((Number)r.get("applicationId")).longValue()==90L).findFirst().orElseThrow();var before=physicalAndEntityCounts();
        jdbc.update("update plm_mold_application set version_no=version_no+1 where application_id=90");var result=history.apply(new MoldHistoryReconciliationService.Apply(List.of(new MoldHistoryReconciliationService.Selection(90,((Number)row.get("version")).longValue(),row.get("fingerprint").toString())),"冲突不得写入"),null);assertThat(((Number)result.get("saved")).intValue()).isZero();assertThat(physicalAndEntityCounts()).isEqualTo(before);verifyNoInteractions(client,files);
    }
    @SuppressWarnings("unchecked") @Test void historicalRetryReusesRegisteredProjectAfterItsProductionColorsAreConfirmed() {
        long project=jdbc.queryForObject("select project_id from plm_mold_application where application_id=90",Long.class);
        jdbc.update("update plm_product set color='黑色,透明色' where product_id=?",project);
        jdbc.update("update plm_mold_application set project_id=null where application_id=90");
        var history=reconciler();var decision=((List<Map<String,Object>>)history.preview().get("items")).stream().filter(r->Objects.equals(r.get("applicationId"),90L)).findFirst().orElseThrow();
        assertThat(decision).containsEntry("createProject",false).containsEntry("projectId",project);
        long count=jdbc.queryForObject("select count(*) from plm_product",Long.class);
        var result=history.apply(new MoldHistoryReconciliationService.Apply(List.of(new MoldHistoryReconciliationService.Selection(90,((Number)decision.get("version")).longValue(),decision.get("fingerprint").toString())),"正式项目确认颜色后继续关联原项目"),null);
        assertThat(result).containsEntry("saved",1L);assertThat(jdbc.queryForObject("select project_id from plm_mold_application where application_id=90",Long.class)).isEqualTo(project);assertThat(jdbc.queryForObject("select count(*) from plm_product",Long.class)).isEqualTo(count);
    }

    @SuppressWarnings("unchecked") @Test void newTrustedHistoricalProjectDoesNotNeedAnInventedPhysicalSet()throws Exception {
        var row=approved();var raw=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot("RUNNING","本地回滚核验");raw.putArray("tasks").addObject().put("userid",MoldApplicationForms.EXECUTION_APPROVER_USER_ID).put("task_status","COMPLETED").put("task_result","AGREE").put("taskid","history-task-"+UUID.randomUUID()).put("finish_time","2026-10-14 12:00:00");
        jdbc.update("update plm_mold_application set source_system='dingtalk',raw_snapshot=?::jsonb where application_id=?",repo.json(raw),id(row));
        var history=reconciler();var preview=history.preview();var decision=((List<Map<String,Object>>)preview.get("items")).stream().filter(d->Objects.equals(d.get("applicationId"),id(row))).findFirst().orElseThrow();assertThat(decision.get("createProject")).isEqualTo(true);var before=physicalAndEntityCounts();
        var result=history.apply(new MoldHistoryReconciliationService.Apply(List.of(new MoldHistoryReconciliationService.Selection(id(row),((Number)decision.get("version")).longValue(),decision.get("fingerprint").toString())),"仅登记真实同意项目，不造实物"),null);
        assertThat(result.get("saved")).isEqualTo(1L);var linked=repo.row(id(row),false);assertThat(linked.get("projectId")).isNotNull();assertThat(linked).containsEntry("moldSetId",null).containsEntry("status","submitted");var after=physicalAndEntityCounts();for(var entry:before.entrySet())if(!entry.getKey().equals("plm_product"))assertThat(after.get(entry.getKey())).isEqualTo(entry.getValue());assertThat(after.get("plm_product")).isEqualTo(before.get("plm_product")+1);verifyNoInteractions(client,files);
    }

    @SuppressWarnings("unchecked") @Test void originalMoldCodeDifferenceDoesNotEraseAnExactHistoricalPhoneIdentity(){
        var f=historyFixture();var history=uncodedHistory(f.member(),f.code());var form=json.valueToTree(Map.of("model",f.model(),"tipo",tipo));
        jdbc.update("update plm_mold_application set model=?,form_data=?::jsonb,generated_codes='MFA101144 / MFA201144' where application_id=?",f.model(),repo.json(form),id(history));
        var row=((List<Map<String,Object>>)reconciler().preview().get("items")).stream().filter(r->Objects.equals(r.get("applicationId"),id(history))).findFirst().orElseThrow();
        assertThat(row).containsEntry("phoneModelId",f.member()).containsEntry("phoneCode",f.phoneCode());assertThat(row.get("rawCodes").toString()).contains("1144");assertThat((List<Map<String,Object>>)row.get("gaps")).anyMatch(g->"original_mold_code_differs".equals(g.get("code")));assertThat(row.get("moldSetId")).isNull();verifyNoInteractions(client,files);
    }

    com.yuewei.plm.module.project.development.ProductDevelopmentService researchDevelopment;
    com.yuewei.plm.module.project.development.DevelopmentWorkflowStore researchStore;
    long researchProject,researchFile;
    long researchFixture(boolean withFile)throws Exception {
        researchProject=jdbc.queryForObject("insert into plm_product(product_code,product_name,product_type,version_no,status,development_mode,current_step_no,sales_status,owner_user_id,development_requirements,expected_delivery_date,created_by,updated_by) values(?,'IT LEMOS 新品','product_line','V1','developing','launch_v1',2,'unconfirmed',?,'透明PC背板，TPU包边；保留原始开发要求','2026-12-01','it','it') returning product_id",Long.class,"IT-LEMOS-"+UUID.randomUUID(),actor);
        researchStore=new com.yuewei.plm.module.project.development.DevelopmentWorkflowStore(jdbc,json);
        var products=mock(ProductRepository.class);
        when(products.selectById(anyLong())).thenAnswer(invocation->{long selected=invocation.getArgument(0);var rows=jdbc.queryForList("select * from plm_product where product_id=?",selected);if(rows.isEmpty())return null;var row=rows.get(0);var p=new Product();p.setProductId(selected);p.setProductName((String)row.get("product_name"));p.setProductCode((String)row.get("product_code"));p.setModel((String)row.get("model"));p.setProductType((String)row.get("product_type"));p.setDevelopmentMode((String)row.get("development_mode"));p.setStatus((String)row.get("status"));p.setCurrentStepNo(((Number)row.get("current_step_no")).intValue());p.setLockStatus((String)row.get("lock_status"));p.setDeletedFlag(((Number)row.get("deleted_flag")).intValue());p.setOwnerUserId(((Number)row.get("owner_user_id")).longValue());p.setDevelopmentRequirements((String)row.get("development_requirements"));p.setExpectedDeliveryDate(((java.sql.Date)row.get("expected_delivery_date")).toLocalDate());return p;});
        var attachments=mock(com.yuewei.plm.module.attachment.repository.AttachmentRepository.class);
        when(attachments.selectBatchIds(anyCollection())).thenAnswer(invocation->{Collection<Long> ids=invocation.getArgument(0);return ids.stream().map(this::researchAttachment).toList();});
        when(attachments.selectById(anyLong())).thenAnswer(invocation->researchAttachment(invocation.getArgument(0)));
        var reviews=mock(com.yuewei.plm.module.project.development.LaunchReviewWorkflow.class);
        var launch=new com.yuewei.plm.module.project.development.LaunchDevelopmentWorkflow(jdbc,json,access,researchStore,mock(com.yuewei.plm.module.collaboration.MoldScheduleService.class),mock(com.yuewei.plm.module.development.DevelopmentFinanceService.class),reviews,codes.references());
        researchDevelopment=new com.yuewei.plm.module.project.development.ProductDevelopmentService(jdbc,json,access,products,attachments,mock(OperationLogService.class),researchStore,mock(com.yuewei.plm.module.inventory.service.InventorySupplierService.class),launch,reviews,mock(com.yuewei.plm.module.project.development.DevelopmentUnitCostWorkflow.class));
        org.springframework.test.util.ReflectionTestUtils.setField(researchDevelopment,"engineering",new com.yuewei.plm.module.project.development.EngineeringBasisService(jdbc,access,mock(com.yuewei.plm.module.partner.SupplierProfileService.class)));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"productDevelopment",researchDevelopment);
        var references=new ArrayList<Map<String,Object>>();
        if(withFile){byte[] bytes="%PDF IT exact V3".getBytes();researchFile=media.store(0,MoldApplicationForms.COMPONENTS.get("attachments"),"attachment","工程方案.pdf","plm:"+MoldApplicationMedia.hash(bytes),null,bytes,"application/pdf","it","downloaded");jdbc.update("update plm_attachment set owner_object_type='Product',owner_object_id=?,version_no='V3' where attachment_id=?",researchProject,researchFile);references.add(Map.of("type","file","id",researchFile));}
        var plan=Map.of("needsMold",true,"method","own_mold","phoneModelIds",List.of(phoneModel),"supplierAssignments",List.of(Map.of("mode","internal","role","component","internalName","工程部","scope","PC样板")),"references",references);
        researchStore.save(researchProject,new LinkedHashMap<>(Map.of("version",0L,"requirement",Map.of("requirements","已确认立项要求","references",references),"plan",plan,"planDraft",plan,"stage1Basis",Map.of("confirmed",true))));
        return researchProject;
    }
    com.yuewei.plm.module.attachment.entity.Attachment researchAttachment(long selected){
        var row=jdbc.queryForMap("select * from plm_attachment where attachment_id=?",selected);var a=new com.yuewei.plm.module.attachment.entity.Attachment();a.setAttachmentId(selected);a.setOwnerObjectType((String)row.get("owner_object_type"));a.setOwnerObjectId(((Number)row.get("owner_object_id")).longValue());a.setOriginalFileName((String)row.get("original_file_name"));a.setFileCategory((String)row.get("file_category"));a.setFileExt((String)row.get("file_ext"));a.setChecksum((String)row.get("checksum"));a.setVersionNo((String)row.get("version_no"));a.setStatus((String)row.get("status"));a.setDeletedFlag(((Number)row.get("deleted_flag")).intValue());return a;
    }
    MoldApplicationService.ResearchDraft researchInput(String request){return new MoldApplicationService.ResearchDraft(researchProject,phoneModel,request,((Number)researchStore.load(researchProject).get("version")).longValue());}
    @Test void confirmedResearchPreparesTheExistingDraftAndAdoptsExactFileWithoutGeneratingExecution()throws Exception {
        researchFixture(true);long count=jdbc.queryForObject("select count(*) from plm_mold_application",Long.class),sets=jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class);
        var created=service.startFromResearch(researchInput("it-research-once-12345678"),null);long application=id(created);
        assertThat(created).containsEntry("status","draft").containsEntry("productLineId",researchProject).containsEntry("phoneModelId",phoneModel).containsEntry("canEditDraft",true).containsEntry("alreadyExists",false);
        assertThat(((JsonNode)created.get("form")).path("remark").asText()).contains("IT LEMOS 新品","透明PC背板，TPU包边");
        assertThat(((JsonNode)created.get("sourceSnapshot")).path("research").path("suppliers").get(0).path("internalName").asText()).isEqualTo("工程部");
        var inherited=media.list(application);assertThat(inherited).hasSize(1);assertThat(inherited.get(0)).containsEntry("sourceAttachmentId",researchFile).containsEntry("versionNo","V3").containsEntry("category","attachment");
        var source=jdbc.queryForMap("select storage_key,checksum from plm_attachment where attachment_id=?",researchFile);var adopted=jdbc.queryForMap("select storage_key,checksum from plm_attachment where attachment_id=?",inherited.get(0).get("attachmentId"));assertThat(adopted).isEqualTo(source);
        assertThat(service.download(application,((Number)inherited.get(0).get("attachmentId")).longValue())).isEqualTo("%PDF IT exact V3".getBytes());assertThat(researchDevelopment.fileReferenced(researchFile)).isTrue();
        var form=((JsonNode)created.get("form")).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)form).put("remark","工程师已补齐，不可覆盖");var saved=service.save(application,new MoldApplicationService.Draft(form,researchProject,phone,version(created),phoneModel,"new",null,null,null),null);
        var preview=service.researchContext(researchProject,null,((Number)researchStore.load(researchProject).get("version")).longValue());assertThat(id((Map<String,Object>)preview.get("existingApplication"))).isEqualTo(application);assertThat(version((Map<String,Object>)preview.get("existingApplication"))).isEqualTo(version(saved));
        var again=service.startFromResearch(researchInput("it-research-another-12345678"),null);assertThat(id(again)).isEqualTo(application);assertThat(version(again)).isEqualTo(version(saved));assertThat(((JsonNode)again.get("form")).path("remark").asText()).isEqualTo("工程师已补齐，不可覆盖");assertThat(media.list(application)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from plm_mold_application",Long.class)).isEqualTo(count+1);assertThat(jdbc.queryForObject("select count(*) from plm_collaboration_mold_set",Long.class)).isEqualTo(sets);
        assertThat(again.get("generatedCodes")).isNull();assertThat(again.get("projectId")).isNull();verifyNoInteractions(client,files);
    }
    @Test void everyActiveResearchStageCanPreviewWithoutCreatingApplicationsOrConfirmingPlans()throws Exception {
        researchFixture(true);long applications=jdbc.queryForObject("select count(*) from plm_mold_application",Long.class),attachments=jdbc.queryForObject("select count(*) from plm_attachment",Long.class);
        var state=researchStore.load(researchProject);state.remove("requirement");state.remove("plan");state.put("planDraft",Map.of("phoneModelIds",List.of(),"needsMold",false,"references",List.of(Map.of("type","file","id",researchFile))));researchStore.save(researchProject,state);
        state=researchStore.load(researchProject);long sourceVersion=((Number)state.get("version")).longValue();
        for(int stage=1;stage<=8;stage++){
            jdbc.update("update plm_product set current_step_no=? where product_id=?",stage,researchProject);
            var preview=service.researchContext(researchProject,null,sourceVersion);
            assertThat(preview).containsEntry("applicationId",null).containsEntry("phoneModelId",null).containsEntry("productLineId",researchProject);
            var source=(Map<String,Object>)((Map<?,?>)preview.get("sourceSnapshot")).get("research");assertThat((List<?>)source.get("references")).hasSize(1);assertThat((List<String>)source.get("missingFields")).contains("标准手机型号");
            assertThat(preview.get("form").toString()).contains("透明PC背板");assertThat((List<?>)preview.get("files")).isEmpty();
        }
        assertThat(jdbc.queryForObject("select count(*) from plm_mold_application",Long.class)).isEqualTo(applications);assertThat(jdbc.queryForObject("select count(*) from plm_attachment",Long.class)).isEqualTo(attachments);assertThat(researchStore.load(researchProject)).isEqualTo(state);verifyNoInteractions(client,files);
    }
    @Test void incompleteResearchDraftUsesOneWriterAndCanFillItsPhoneWithoutChangingTheResearchPlan()throws Exception {
        researchFixture(false);jdbc.update("update plm_product set current_step_no=1 where product_id=?",researchProject);
        var state=researchStore.load(researchProject);state.remove("requirement");state.remove("plan");state.put("planDraft",Map.of("phoneModelIds",List.of(),"needsMold",false));researchStore.save(researchProject,state);long sourceVersion=((Number)state.get("version")).longValue();
        jdbc.update("update plm_product set model='--' where product_id=?",researchProject);
        var placeholder=service.researchContext(researchProject,null,sourceVersion);assertThat((Map<String,Object>)placeholder.get("form")).doesNotContainKey("model");assertThat(((Map<?,?>)((Map<?,?>)placeholder.get("sourceSnapshot")).get("research")).get("model")).isEqualTo("--");
        state.put("profileDraft",Map.of("model","真实产品款式描述"));researchStore.save(researchProject,state);sourceVersion=((Number)state.get("version")).longValue();
        var productDescription=service.researchContext(researchProject,null,sourceVersion);assertThat((Map<String,Object>)productDescription.get("form")).doesNotContainKey("model");assertThat(((Map<?,?>)((Map<?,?>)productDescription.get("sourceSnapshot")).get("research")).get("model")).isEqualTo("真实产品款式描述");
        var input=new MoldApplicationService.Draft(json.valueToTree(Map.of("remark","弹窗填写且原子保存","model","用户手填待核对型号")),researchProject,null,null,null,"new",null,null,null);
        var request=new MoldApplicationService.ResearchDraft(researchProject,null,"it-research-incomplete-12345678",sourceVersion,input);
        var created=service.startFromResearch(request,null);assertThat(created).containsEntry("phoneModelId",null).containsEntry("status","draft");assertThat(((JsonNode)created.get("form")).path("remark").asText()).isEqualTo("弹窗填写且原子保存");assertThat(((JsonNode)created.get("form")).path("model").asText()).isEqualTo("用户手填待核对型号");
        var again=service.startFromResearch(request,null);assertThat(id(again)).isEqualTo(id(created));assertThat(version(again)).isEqualTo(version(created));
        var manual=service.save(null,new MoldApplicationService.Draft(json.valueToTree(Map.of("remark","另一个入口不能覆盖原草稿")),researchProject,null,null,null,"new",null,null,null),null);assertThat(id(manual)).isEqualTo(id(created));assertThat(version(manual)).isEqualTo(version(created));assertThat(((JsonNode)manual.get("form")).path("remark").asText()).isEqualTo("弹窗填写且原子保存");
        assertThatThrownBy(()->service.reserve(id(created),version(created),null)).hasMessageContaining("请选择标准手机型号");
        var saved=service.save(id(created),new MoldApplicationService.Draft((JsonNode)created.get("form"),researchProject,phone,version(created),phoneModel,"new",null,null,null),null);assertThat(saved).containsEntry("phoneModelId",phoneModel);
        assertThatThrownBy(()->service.save(id(saved),new MoldApplicationService.Draft((JsonNode)saved.get("form"),50L,phone,version(saved),phoneModel,"new",null,null,null),null)).hasMessageContaining("产品已固定");
        assertThatThrownBy(()->service.startFromResearch(new MoldApplicationService.ResearchDraft(researchProject,phoneModel,"it-research-version-12345678",0L),null)).hasMessageContaining("项目已被其他操作更新");
        assertThat(researchStore.load(researchProject)).isEqualTo(state);assertThat(jdbc.queryForObject("select current_step_no from plm_product where product_id=?",Integer.class,researchProject)).isEqualTo(1);verifyNoInteractions(client,files);
    }
    @Test void researchContinuesTheCrossSourceDraftAndViewsFinishedRequestsWithoutDuplicating()throws Exception {
        researchFixture(true);var original=service.startFromPhone(new MoldApplicationService.SourceDraft(phoneModel,null,null,null,researchProject,"new",null,null,null,null,"it-cross-source-12345678",false),null);
        var originalForm=((JsonNode)original.get("form")).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)originalForm).put("remark","原入口工程师备注");original=service.save(id(original),new MoldApplicationService.Draft(originalForm,researchProject,phone,version(original),phoneModel,"new",null,null,null),null);
        var linked=service.startFromResearch(researchInput("it-research-cross-12345678"),null);assertThat(id(linked)).isEqualTo(id(original));assertThat(((JsonNode)linked.get("form")).path("remark").asText()).isEqualTo("原入口工程师备注");assertThat(media.list(id(linked))).hasSize(1);
        jdbc.update("update plm_mold_application set status='completed',remote_status='COMPLETED',remote_result='refuse',external_instance_id='IT-LEMOS-REJECTED' where application_id=?",id(linked));
        long count=jdbc.queryForObject("select count(*) from plm_mold_application",Long.class);var finished=service.startFromResearch(researchInput("it-research-finished-12345678"),null);assertThat(id(finished)).isEqualTo(id(linked));assertThat(finished).containsEntry("canEditDraft",false);assertThat(service.researchApplications(researchProject).get(0)).containsEntry("businessStatus","rejected");assertThat(jdbc.queryForObject("select count(*) from plm_mold_application",Long.class)).isEqualTo(count);verifyNoInteractions(client,files);
    }
    @Test void inheritedFilesAndSourceCannotBypassRevokedProjectOrFilePermissions()throws Exception {
        researchFixture(true);var created=service.startFromResearch(researchInput("it-research-permissions-12345678"),null);long application=id(created),file=((Number)media.list(application).get(0).get("attachmentId")).longValue();
        var scoped=spy(access);doReturn(false).when(scoped).hasPermission("file:read");doThrow(new BusinessException(40103,"原文件未获授权")).when(scoped).requireFile(researchFile);org.springframework.test.util.ReflectionTestUtils.setField(service,"access",scoped);org.springframework.test.util.ReflectionTestUtils.setField(researchDevelopment,"access",scoped);
        assertThat((List<?>)service.detail(application).get("files")).isEmpty();assertThat(((JsonNode)service.detail(application).get("sourceSnapshot")).path("research").path("referencesRestricted").asBoolean()).isTrue();
        assertThatThrownBy(()->service.download(application,file)).hasMessageContaining("未获授权");
        doReturn(false).when(scoped).hasPermission("project:view");assertThatThrownBy(()->service.detail(application)).hasMessageContaining("申请不存在或未获授权");assertThat(service.page(1,50,null,null,null,null,null,null).getContent()).noneMatch(row->Objects.equals(row.get("applicationId"),application));
        assertThatThrownBy(()->service.download(application,file)).hasMessageContaining("未获授权");verifyNoInteractions(client,files);
    }
    @Test void researchSupplierTextUsesBusinessNamesAndNeverInternalJsonIds() {
        String text=MoldApplicationService.researchSuppliers(Map.of("supplierPlanConfirmed",false,"suppliers",List.of(Map.of("mode","supplier","role","mold","supplierName","制模厂","supplierId",99,"supplierCode","SUP99","scope","PC／TPU"),Map.of("mode","internal","role","processing","internalName","工程部"))));
        assertThat(text).contains("已保存，待工程确认","模具：制模厂（PC／TPU）","加工：内部制作 工程部").doesNotContain("supplierId","SUP99","99","{");
    }

    @Test void frozenResearchDraftIsReadOnlyAcrossSaveUploadAndSelection()throws Exception {
        researchFixture(false);var created=service.startFromResearch(researchInput("it-research-freeze-12345678"),null);jdbc.update("update plm_product set lock_status='frozen' where product_id=?",researchProject);
        assertThat(service.detail(id(created))).containsEntry("canEditDraft",false);assertThat(service.researchApplications(researchProject).get(0)).containsEntry("canEditDraft",false);
        assertThatThrownBy(()->service.save(id(created),new MoldApplicationService.Draft((JsonNode)created.get("form"),researchProject,phone,version(created),phoneModel,"new",null,null,null),null)).hasMessageContaining("只读");
        assertThatThrownBy(()->service.upload(id(created),"attachment",new MockMultipartFile("file","blocked.pdf","application/pdf","blocked".getBytes()),null)).hasMessageContaining("只读");
        assertThatThrownBy(()->service.selectFiles(id(created),List.of(new MoldApplicationMedia.Selection("selection-12345678","attachment","blocked.pdf",7,"application/pdf")),null)).hasMessageContaining("只读");
        assertThat(media.list(id(created))).isEmpty();verifyNoInteractions(client,files);
    }
    @Test void hiddenSupplierFactsAreNotReturnedOrClearedByDraftSaveOrRequestRetry()throws Exception {
        researchFixture(false);var created=service.startFromResearch(researchInput("it-research-supplier-scope-12345678"),null);long application=id(created);
        var snapshot=((JsonNode)created.get("sourceSnapshot")).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)snapshot.path("research")).set("suppliers",json.valueToTree(List.of(Map.of("mode","supplier","role","mold","supplierCode","IT-SECRET-SUP","supplierName","受限制模厂"))));
        jdbc.update("update plm_mold_application set source_snapshot_json=?::jsonb where application_id=?",repo.json(snapshot),application);
        var restricted=spy(access);doReturn(false).when(restricted).hasPermission("supplier:view");org.springframework.test.util.ReflectionTestUtils.setField(service,"access",restricted);org.springframework.test.util.ReflectionTestUtils.setField(researchDevelopment,"access",restricted);org.springframework.test.util.ReflectionTestUtils.setField(org.springframework.test.util.ReflectionTestUtils.getField(researchDevelopment,"engineering"),"access",restricted);
        var detail=service.detail(application);assertThat(detail).containsEntry("canEditDraft",false);assertThat(((JsonNode)detail.get("sourceSnapshot")).path("research").path("suppliersRestricted").asBoolean()).isTrue();assertThat(repo.json(detail)).doesNotContain("IT-SECRET-SUP","受限制模厂");assertThat(((JsonNode)detail.get("form")).has("remark")).isFalse();
        assertThatThrownBy(()->service.save(application,new MoldApplicationService.Draft((JsonNode)detail.get("form"),researchProject,phone,version(created),phoneModel,"new",null,null,null),null)).hasMessageContaining("不能覆盖受限草稿");
        var retried=service.save(null,new MoldApplicationService.Draft(json.valueToTree(Map.of("model","IT New Phone")),researchProject,phone,null,phoneModel,"new",null,null,null,null,"it-research-supplier-scope-12345678",false),null);assertThat(repo.json(retried)).doesNotContain("IT-SECRET-SUP","受限制模厂");
        assertThat(((JsonNode)repo.row(application,false).get("form")).path("remark").asText()).contains("透明PC背板");verifyNoInteractions(client,files);
    }
    @Test void requiredAuditFailureRollsBackDraftSourceAndFileAdoptionTogether()throws Exception {
        researchFixture(true);long applications=jdbc.queryForObject("select count(*) from plm_mold_application",Long.class),attachments=jdbc.queryForObject("select count(*) from plm_attachment",Long.class);
        var audit=mock(OperationLogService.class);when(audit.logRequired(any(),any())).thenAnswer(invocation->{var command=(com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand)invocation.getArgument(0);if("MOLD_APPLICATION_RESEARCH_DRAFT".equals(command.getAction()))throw new IllegalStateException("IT mandatory audit failed");return 1L;});org.springframework.test.util.ReflectionTestUtils.setField(service,"logs",audit);
        var nested=new TransactionTemplate(tx);nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(()->nested.execute(status->service.startFromResearch(researchInput("it-research-audit-12345678"),null))).hasMessageContaining("mandatory audit failed");
        assertThat(jdbc.queryForObject("select count(*) from plm_mold_application",Long.class)).isEqualTo(applications);assertThat(jdbc.queryForObject("select count(*) from plm_attachment",Long.class)).isEqualTo(attachments);verifyNoInteractions(client,files);
    }
    @Test void genericAttachmentAuthorizationAlsoChecksTheOriginalResearchFileAndProject()throws Exception {
        researchFixture(true);var created=service.startFromResearch(researchInput("it-research-generic-file-12345678"),null);long adopted=((Number)media.list(id(created)).get(0).get("attachmentId")).longValue();
        assertThatCode(()->access.requireFile(adopted)).doesNotThrowAnyException();
        var fileRestricted=spy(access);doThrow(new BusinessException(40103,"原文件已撤销授权")).when(fileRestricted).requireFile(researchFile);assertThatThrownBy(()->fileRestricted.requireFile(adopted)).hasMessageContaining("原文件已撤销授权");
        var projectRestricted=spy(access);doThrow(new BusinessException(40103,"原项目未获授权")).when(projectRestricted).requirePermission("project:view");assertThatThrownBy(()->projectRestricted.requireFile(adopted)).hasMessageContaining("原项目未获授权");verifyNoInteractions(client,files);
    }

    @Test void researchDraftCompletesTheOriginalLemosSubmitWithAdoptedFilesAndHumanSupplierText()throws Exception {
        researchFixture(true);var created=service.startFromResearch(researchInput("it-research-formal-submit-12345678"),null);long application=id(created);
        assertThat(created.get("generatedCodes")).isNull();assertThat(((JsonNode)created.get("form")).path("tipo").asText()).isEmpty();
        repo.saveSchema(props.getProcessCode(),currentResearchSchema());when(client.schema(anyString())).thenReturn(currentResearchSchema());
        tipo="亮甲Rainbow 3.0";jdbc.update("delete from plm_mold_application_config where tipo=?",tipo);
        var form=((JsonNode)created.get("form")).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)form).put("tipo",tipo).put("expectedDeliveryDate","2026-12-01");
        var saved=service.save(application,new MoldApplicationService.Draft(form,researchProject,phone,version(created),phoneModel,"new",null,null,null),null);
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6BuMAAAAASUVORK5CYII=");saved=service.upload(application,"key_photo",new MockMultipartFile("file","设计图.png","image/png",png),null);
        jdbc.update("update plm_product set current_step_no=1 where product_id=?",researchProject);
        var sourceState=researchStore.load(researchProject);sourceState.remove("requirement");sourceState.remove("plan");sourceState.put("planDraft",Map.of("phoneModelIds",List.of(),"needsMold",false));researchStore.save(researchProject,sourceState);
        assertThat(saved.get("generatedCodes")).isNull();allowWrite();var complete=currentResearchSchema();
        when(client.schema(anyString())).thenReturn(complete);when(client.user(anyString())).thenReturn(json.readTree("{\"name\":\"本地测试\",\"unionid\":\"mock-union\",\"dept_id_list\":[1]}"));
        when(client.uploadImage(any(),anyString(),anyString())).thenReturn("https://static.dingtalk.com/qa-research-photo.png");
        when(client.modern(eq("/v1.0/workflow/processInstances/spaces/infos/query"),any())).thenReturn(json.readTree("{\"result\":{\"spaceId\":\"123\"}}"));
        when(client.modern(contains("uploadInfos/query"),any())).thenReturn(json.readTree("{\"uploadKey\":\"mock-upload-key\",\"headerSignatureInfo\":{\"resourceUrls\":[\"https://mock-upload.aliyuncs.com/file\"],\"headers\":{}}}"));
        when(client.modernWrite(contains("/files/commit"),any())).thenReturn(json.readTree("{\"dentry\":{\"id\":\"mock-adopted-file\"}}"));
        var realFiles=new MoldApplicationDingTalkFiles(media,client,repo,json,new DingTalkIntegrationProperties());var http=mock(java.net.http.HttpClient.class);var uploaded=mock(java.net.http.HttpResponse.class);when(uploaded.statusCode()).thenReturn(200);when(http.send(any(java.net.http.HttpRequest.class),any(java.net.http.HttpResponse.BodyHandler.class))).thenReturn(uploaded);org.springframework.test.util.ReflectionTestUtils.setField(realFiles,"http",http);org.springframework.test.util.ReflectionTestUtils.setField(service,"dingFiles",realFiles);
        when(client.submit(anyMap())).thenReturn("it-research-submitted-instance");when(client.instance("it-research-submitted-instance")).thenThrow(new MoldDingTalkClient.RemoteFailure(false,"离线补拉"));
        var submitted=service.submit(application,new MoldApplicationService.Submit(version(saved),null,true),null);assertThat(submitted).containsEntry("status","submitted").containsEntry("instanceId","it-research-submitted-instance");assertThat(service.researchApplications(researchProject).get(0)).containsEntry("businessStatus","awaiting_approval");
        var payload=org.mockito.ArgumentCaptor.forClass(Map.class);verify(client,times(1)).submit(payload.capture());var values=(List<Map<String,String>>)payload.getValue().get("formComponentValues");
        var fieldsById=new HashMap<String,String>();for(var field:values)fieldsById.put(field.get("id"),field.get("value"));
        assertThat(fieldsById.get(MoldApplicationForms.COMPONENTS.get("model"))).isEqualTo("IT New Phone");assertThat(fieldsById.get(MoldApplicationForms.COMPONENTS.get("remark"))).contains("IT LEMOS 新品","透明PC背板，TPU包边","部件：内部制作 工程部（PC样板）").doesNotContain("supplierId","supplierCode");
        assertThat(fieldsById).doesNotContainKeys(MoldApplicationForms.COMPONENTS.get("generatedCodes"),MoldApplicationForms.COMPONENTS.get("informationComplete"));
        assertThat(fieldsById.get(MoldApplicationForms.COMPONENTS.get("attachments"))).contains("mock-adopted-file","工程方案.pdf");assertThat(fieldsById.get(MoldApplicationForms.COMPONENTS.get("keyPhotos"))).contains("qa-research-photo.png");
        assertThat(((JsonNode)repo.row(application,false).get("form")).path("remark").asText()).doesNotContain("供应商分工");long count=jdbc.queryForObject("select count(*) from plm_mold_application",Long.class);
        var reopened=service.startFromResearch(researchInput("it-research-after-submit-12345678"),null);assertThat(id(reopened)).isEqualTo(application);assertThat(reopened).containsEntry("canEditDraft",false).containsEntry("status","submitted");assertThat(jdbc.queryForObject("select count(*) from plm_mold_application",Long.class)).isEqualTo(count);verify(client,times(1)).submit(anyMap());verify(http,times(1)).send(any(java.net.http.HttpRequest.class),any(java.net.http.HttpResponse.BodyHandler.class));
    }

    @Test void researchWrongTipoCannotChangeThePinnedSourceProductDuringReservation()throws Exception {
        researchFixture(false);var created=service.startFromResearch(researchInput("it-research-wrong-tipo-12345678"),null);var form=((JsonNode)created.get("form")).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)form).put("tipo",tipo);
        var saved=service.save(id(created),new MoldApplicationService.Draft(form,researchProject,phone,version(created),phoneModel,"new",null,null,null),null);long reservations=jdbc.queryForObject("select count(*) from plm_mold_code_reservation",Long.class);
        assertThatThrownBy(()->service.reserve(id(saved),version(saved),null)).hasMessageContaining("Tipo与新品产品不一致");assertThat(repo.row(id(saved),false)).containsEntry("productLineId",researchProject).containsEntry("generatedCodes",null);assertThat(jdbc.queryForObject("select count(*) from plm_mold_code_reservation",Long.class)).isEqualTo(reservations);verifyNoInteractions(client,files);
    }
    @Test void visibleOtherApplicantsResearchDraftIsContinuedReadOnlyWithoutChangingEvidence()throws Exception {
        researchFixture(false);var created=service.startFromResearch(researchInput("it-research-original-owner-12345678"),null);
        String name="research-reader-"+UUID.randomUUID();long reader=jdbc.queryForObject("insert into sys_user(user_code,username,login_name,user_name,display_name,all_permissions,status) values(?,?,?,'研发工程员','研发工程员',false,'active') returning user_id",Long.class,name,name,name);
        jdbc.update("insert into plm_pilot_account(user_id,role_code,permissions,all_objects,created_by,updated_by) values(?,'domestic','[\"collaboration:view\",\"mold-application:create\",\"documents:write\",\"project:view\",\"file:read\"]',true,'it','it')",reader);CurrentUserContext.set(new CurrentUser(reader,name,"研发工程员",false));
        var continued=service.startFromResearch(researchInput("it-research-different-owner-12345678"),null);assertThat(id(continued)).isEqualTo(id(created));assertThat(continued).containsEntry("canEditDraft",false).containsEntry("alreadyExists",true);assertThat(version(continued)).isEqualTo(version(created));assertThat(service.researchApplications(researchProject).get(0)).containsEntry("canEditDraft",false);
        assertThatThrownBy(()->service.save(id(created),new MoldApplicationService.Draft((JsonNode)created.get("form"),researchProject,phone,version(created),phoneModel,"new",null,null,null),null)).hasMessageContaining("只能编辑本人申请");
        jdbc.update("update plm_pilot_account set all_objects=false where user_id=?",reader);jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) values(?,'product',?) on conflict do nothing",reader,researchProject);
        var preview=service.researchContext(researchProject,null,((Number)researchStore.load(researchProject).get("version")).longValue());assertThat((List<?>)preview.get("existingApplications")).isEmpty();assertThat(preview).doesNotContainKey("existingApplication");assertThat(preview).containsKey("existingApplicationNotice");assertThat(preview.get("existingApplicationNotice").toString()).doesNotContain(Long.toString(id(created)));
        assertThatThrownBy(()->service.startFromResearch(researchInput("it-research-hidden-existing-12345678"),null)).hasMessageContaining("申请不存在或未获授权");verifyNoInteractions(client,files);
    }
    @Test void researchSupplierSnapshotResolvesTheVerifiedCodeAndMarksTheSavedUnconfirmedPlan()throws Exception {
        researchFixture(false);var supplier=jdbc.queryForMap("select supplier_id,supplier_code,supplier_name,version from plm_supplier_profile where deleted_flag=0 order by supplier_id limit 1");
        var engineering=(com.yuewei.plm.module.project.development.EngineeringBasisService)org.springframework.test.util.ReflectionTestUtils.getField(researchDevelopment,"engineering");var profiles=(com.yuewei.plm.module.partner.SupplierProfileService)org.springframework.test.util.ReflectionTestUtils.getField(engineering,"suppliers");
        when(profiles.requireSupplier(supplier.get("supplier_code").toString())).thenAnswer(invocation->{access.requireSupplierProfile(((Number)supplier.get("supplier_id")).longValue());return Map.of("supplierId",supplier.get("supplier_id"),"supplierCode",supplier.get("supplier_code"),"supplierName",supplier.get("supplier_name"),"version",supplier.get("version"));});
        var state=researchStore.load(researchProject);var pending=new LinkedHashMap<>((Map<String,Object>)state.get("planDraft"));pending.put("supplierAssignments",List.of(Map.of("mode","supplier","role","mold","supplierCode",supplier.get("supplier_code"),"supplierName","不能信任的旧名称","scope","PC／TPU")));state.put("planDraft",pending);researchStore.save(researchProject,state);
        var created=service.startFromResearch(researchInput("it-research-real-supplier-12345678"),null);var source=((JsonNode)created.get("sourceSnapshot")).path("research");assertThat(source.path("supplierPlanConfirmed").asBoolean()).isFalse();assertThat(source.path("suppliers").get(0).path("supplierName").asText()).isEqualTo(supplier.get("supplier_name"));assertThat(source.path("suppliers").get(0).path("supplierId").asLong()).isEqualTo(((Number)supplier.get("supplier_id")).longValue());assertThat(source.toString()).doesNotContain("不能信任的旧名称");verify(profiles,atLeastOnce()).requireSupplier(supplier.get("supplier_code").toString());verifyNoInteractions(client,files);
    }

    @Test void sourceSupplierConfirmationIgnoresPlanVersionAndTimestampMetadata()throws Exception {
        researchFixture(false);var state=researchStore.load(researchProject);var confirmed=new LinkedHashMap<>((Map<String,Object>)state.get("plan"));confirmed.put("planVersion",3);confirmed.put("confirmedBy","已确认的工程师");confirmed.put("confirmedAt","2026-10-27T12:00:00");state.put("plan",confirmed);researchStore.save(researchProject,state);
        var created=service.startFromResearch(researchInput("it-research-confirmed-plan-12345678"),null);assertThat(((JsonNode)created.get("sourceSnapshot")).path("research").path("supplierPlanConfirmed").asBoolean()).isTrue();verifyNoInteractions(client,files);
    }

    JsonNode currentResearchSchema()throws Exception {
        try(var in=getClass().getResourceAsStream("/moldapplication/lemos-current-20261028-schema.json")){return json.readTree(in);}
    }
    Map<String,Object> uncodedResearchPrepared()throws Exception {
        researchFixture(false);tipo="亮甲Rainbow 3.0";jdbc.update("delete from plm_mold_application_config where tipo=?",tipo);
        var actual=currentResearchSchema();repo.saveSchema(props.getProcessCode(),actual);when(client.schema(anyString())).thenReturn(actual);
        var state=researchStore.load(researchProject);state.put("planDraft",Map.of("phoneModelIds",List.of(),"needsMold",true));state.put("plan",Map.of("phoneModelIds",List.of(),"needsMold",true));researchStore.save(researchProject,state);
        var created=service.startFromResearch(new MoldApplicationService.ResearchDraft(researchProject,null,"it-uncoded-"+UUID.randomUUID(),((Number)researchStore.load(researchProject).get("version")).longValue()),null);
        var form=((JsonNode)created.get("form")).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)form).put("model","Honor 600 Lite 待最终适配确认").put("tipo",tipo).put("expectedDeliveryDate","2026-12-01");
        var saved=service.save(id(created),new MoldApplicationService.Draft(form,researchProject,null,version(created),null,"new",null,null,null),null);
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6BuMAAAAASUVORK5CYII=");
        return service.upload(id(saved),"key_photo",new MockMultipartFile("file","设计图.png","image/png",png),null);
    }
    @Test void actualTwentyControlTemplateAllowsResearchCodesToStayEmptyAndApprovalRemainsOnOriginalProject()throws Exception {
        var row=uncodedResearchPrepared();allowWrite();var actual=currentResearchSchema();
        assertThat(actual.path("schemaContent").path("items")).hasSize(20);
        assertThat(MoldApplicationForms.controls(actual,json).stream().filter(c->Boolean.TRUE.equals(c.get("required"))).map(c->c.get("key")).toList()).containsExactly("model","tipo");
        var before=new LinkedHashMap<String,Long>();for(String table:List.of("plm_product","plm_code_item","plm_collaboration_item","plm_collaboration_mold_set"))before.put(table,jdbc.queryForObject("select count(*) from "+table,Long.class));
        when(client.submit(anyMap())).thenReturn("it-uncoded-approved");when(client.instance("it-uncoded-approved")).thenThrow(new MoldDingTalkClient.RemoteFailure(false,"离线补拉"));
        var submitted=service.submit(id(row),new MoldApplicationService.Submit(version(row),null,true),null);
        assertThat(submitted).containsEntry("status","submitted").containsEntry("generatedCodes",null).containsEntry("phoneCodeItemId",null).containsEntry("phoneModelId",null).containsEntry("projectId",null);
        var values=org.mockito.ArgumentCaptor.forClass(Map.class);verify(client).submit(values.capture());var fields=(List<Map<String,String>>)values.getValue().get("formComponentValues");
        assertThat(fields).noneMatch(f->Set.of(MoldApplicationForms.COMPONENTS.get("generatedCodes"),MoldApplicationForms.COMPONENTS.get("informationComplete")).contains(f.get("id")));
        assertThat(fields).anySatisfy(f->assertThat(f).containsEntry("id",MoldApplicationForms.COMPONENTS.get("tipo")).containsEntry("value","亮甲Rainbow 3.0"));
        assertThat(((JsonNode)submitted.get("sourceSnapshot")).path("researchSubmission").path("projectId").asLong()).isEqualTo(researchProject);
        var convert=converter();var approved=json.createObjectNode();approved.put("status","RUNNING").put("business_id","IT-RESEARCH").put("title","本地新品审批").put("originator_userid","mold-it-user");
        var remoteForm=approved.putArray("form_component_values");for(var field:fields)remoteForm.addObject().put("id",field.get("id")).put("value",field.get("value"));
        approved.putArray("tasks").addObject().put("userid",MoldApplicationForms.EXECUTION_APPROVER_USER_ID).put("taskid","it-research-proof").put("task_status","COMPLETED").put("task_result","AGREE").put("finish_time","2026-10-29 12:00:00");
        doReturn(approved).when(client).instance("it-uncoded-approved");
        service.callback("it-uncoded-approved",props.getProcessCode(),true);service.callback("it-uncoded-approved",props.getProcessCode(),true);
        var synced=repo.row(id(row),false);assertThat(synced).containsEntry("approvalGranted",true).containsEntry("productLineId",researchProject).containsEntry("projectId",null).containsEntry("provisioningStatus","blocked");
        assertThat(((JsonNode)synced.get("sourceSnapshot")).path("researchSubmission")).isEqualTo(((JsonNode)submitted.get("sourceSnapshot")).path("researchSubmission"));
        assertThatThrownBy(()->convert.preview(id(row))).hasMessageContaining("不自动建立型号开发项目或实物套");
        assertThatThrownBy(()->convert.convertAutomatically(id(row))).hasMessageContaining("不自动建立型号开发项目或实物套");
        assertThatThrownBy(()->service.correctTargets(id(row),new MoldApplicationService.TargetCorrection(version(submitted),"new",null,null,"工程核对"),null)).hasMessageContaining("不自动建立型号开发项目或实物套");
        assertThatThrownBy(()->service.retryProvision(id(row),version(submitted),null)).hasMessageContaining("不自动建立型号开发项目或实物套");
        assertThat(service.researchApplications(researchProject)).singleElement().satisfies(r->assertThat(r).containsEntry("model","Honor 600 Lite 待最终适配确认"));
        assertThat(service.detail(id(row)).get("nextStep").toString()).contains("新品审批已同意","新品项目");
        approved.put("status","TERMINATED");service.callback("it-uncoded-approved",props.getProcessCode(),false);
        var ended=repo.row(id(row),false);assertThat(ended).containsEntry("approvalGranted",false).containsEntry("productLineId",researchProject);assertThat(service.detail(id(row)).get("nextStep").toString()).contains("新品审批已结束");
        assertThat(((JsonNode)ended.get("sourceSnapshot")).path("researchSubmission")).isEqualTo(((JsonNode)submitted.get("sourceSnapshot")).path("researchSubmission"));
        for(var entry:before.entrySet())assertThat(jdbc.queryForObject("select count(*) from "+entry.getKey(),Long.class)).isEqualTo(entry.getValue());
    }
    @Test void researchUncertainCreateFreezesTheSameDraftAndNeverCallsCreateTwice()throws Exception {
        var row=uncodedResearchPrepared();allowWrite();when(client.submit(anyMap())).thenThrow(new MoldDingTalkClient.RemoteFailure(true,"远端结果未知"));
        assertThatThrownBy(()->service.submit(id(row),new MoldApplicationService.Submit(version(row),null,true),null)).hasMessageContaining("远端结果未知");
        var frozen=repo.row(id(row),false);assertThat(frozen).containsEntry("status","submission_unknown");
        assertThatThrownBy(()->service.submit(id(row),new MoldApplicationService.Submit(version(frozen),null,true),null)).isInstanceOf(BusinessException.class);
        verify(client,times(1)).submit(anyMap());assertThat(service.detail(id(row))).containsEntry("canEditDraft",false);
    }
    @Test void researchStillChecksOfficialRequiredFieldsAndNeverSendsAFakeCode()throws Exception {
        var row=uncodedResearchPrepared();allowWrite();var strict=(com.fasterxml.jackson.databind.node.ObjectNode)currentResearchSchema();
        strict.path("schemaContent").path("items").forEach(c->{if(MoldApplicationForms.COMPONENTS.get("generatedCodes").equals(c.path("props").path("id").asText()))((com.fasterxml.jackson.databind.node.ObjectNode)c.path("props")).put("required",true);});
        when(client.schema(anyString())).thenReturn(strict);
        assertThatThrownBy(()->service.submit(id(row),new MoldApplicationService.Submit(version(row),null,true),null)).hasMessageContaining("钉钉模板必填字段未填写");
        assertThat(repo.row(id(row),false)).containsEntry("status","draft").containsEntry("generatedCodes",null);verify(client,never()).submit(anyMap());
    }
    @Test void manualResearchProductCannotClaimResearchModeAndOtherProductsTipoCannotBypassCodes()throws Exception {
        researchFixture(false);var form=json.valueToTree(Map.of("model","Phone","tipo",tipo,"expectedDeliveryDate","2026-12-01"));
        var manual=service.save(null,new MoldApplicationService.Draft(form,researchProject,null,null,phoneModel,"new",null,null,null),null);allowWrite();
        assertThat(((MoldApplicationSubmissionPolicy)manual.get("submissionPolicy")).mode()).isEqualTo("model");
        assertThatThrownBy(()->service.submit(id(manual),new MoldApplicationService.Submit(version(manual),null,true),null)).hasMessageContaining("生成编码");
        var forged=((com.fasterxml.jackson.databind.node.ObjectNode)form).deepCopy();forged.put("submissionPolicy","research");
        assertThatThrownBy(()->service.save(id(manual),new MoldApplicationService.Draft(forged,researchProject,null,version(manual),phoneModel,"new",null,null,null),null)).hasMessageContaining("未知申请字段");
        var started=service.startFromResearch(researchInput("it-tipo-mapping-"+UUID.randomUUID()),null);
        assertThatThrownBy(()->service.submit(id(started),new MoldApplicationService.Submit(version(started),null,true),null)).hasMessageContaining("不能用其他款式代替新品");verify(client,never()).submit(anyMap());
    }
    @Test void alreadyLinkedExternalResearchApprovalReturnsTheSameRecordBeforeAnyCreateOrCodeCheck()throws Exception {
        var row=uncodedResearchPrepared();allowWrite();jdbc.update("update plm_mold_application set source_system='dingtalk',status='submitted',external_instance_id='it-existing-202610281759000301110',approval_no='202610281759000301110',remote_status='RUNNING' where application_id=?",id(row));
        var result=service.submit(id(row),new MoldApplicationService.Submit(version(row),null,true),null);assertThat(result).containsEntry("applicationId",id(row)).containsEntry("instanceId","it-existing-202610281759000301110");verifyNoInteractions(client,files);
        var original=service.startFromResearch(researchInput("it-resume-existing-"+UUID.randomUUID()),null);assertThat(id(original)).isEqualTo(id(row));
    }

    @Test void researchApprovalCannotCarryHiddenNewSetOrModelExecutionTargets()throws Exception {
        var row=uncodedResearchPrepared();allowWrite();var form=(JsonNode)row.get("form");
        var saved=service.save(id(row),new MoldApplicationService.Draft(form,researchProject,null,version(row),null,"new_set",null,null,null,"错误旧隐藏选择",null,true),null);
        assertThatThrownBy(()->service.submit(id(saved),new MoldApplicationService.Submit(version(saved),null,true),null)).hasMessageContaining("新增套、共模和型号项目目标请从型号开发办理");verify(client,never()).submit(anyMap());
    }

    @Test void researchSubmissionMarkerAndStateRollBackWhenMandatorySubmitAuditFails()throws Exception {
        var row=uncodedResearchPrepared();allowWrite();var before=repo.row(id(row),false);var logs=(OperationLogService)org.springframework.test.util.ReflectionTestUtils.getField(service,"logs");
        doThrow(new IllegalStateException("research submit audit failed")).when(logs).logRequired(argThat(command->"MOLD_APPLICATION_SUBMIT_START".equals(command.getAction())),any());
        var nested=new TransactionTemplate(tx);nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(()->nested.execute(scope->service.submit(id(row),new MoldApplicationService.Submit(version(row),null,true),null))).hasMessageContaining("research submit audit failed");
        assertThat(repo.row(id(row),false)).containsEntry("status","draft").containsEntry("sourceSnapshot",before.get("sourceSnapshot")).containsEntry("version",before.get("version"));verify(client,never()).submit(anyMap());
    }

}

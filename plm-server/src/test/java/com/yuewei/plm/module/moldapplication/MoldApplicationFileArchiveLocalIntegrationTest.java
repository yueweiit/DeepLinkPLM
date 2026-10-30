package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.*;
import com.yuewei.plm.common.config.AppProperties;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A new empty local PostgreSQL database, real existing attachment DDL/store, and mocked external transport only. */
@EnabledIfEnvironmentVariable(named="PLM_MOLD_TEST_DB_URL",matches=".+")
class MoldApplicationFileArchiveLocalIntegrationTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final long application=92381L,project=19557L;
    final byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6BuMAAAAASUVORK5CYII=");
    JdbcTemplate jdbc;DataSourceTransactionManager tx;TransactionStatus scope;Path root;
    MoldApplicationMedia media;HttpClient http;MoldDingTalkClient client;PilotAccessService access;MoldApplicationService service;MoldApplicationRepository repo;OperationLogService logs;
    Map<String,Object> original;JsonNode remote;
    @BeforeEach void prepare()throws Exception {
        String url=System.getenv("PLM_MOLD_TEST_DB_URL");LocalMoldTestDatabase.database(url);
        var source=new DriverManagerDataSource(url,"postgres",System.getenv("PLM_MOLD_TEST_DB_PASSWORD"));jdbc=new JdbcTemplate(source);LocalMoldTestDatabase.verify(url,jdbc);
        jdbc.execute("create schema if not exists plm");tx=new DataSourceTransactionManager(source);scope=tx.getTransaction(new DefaultTransactionDefinition());
        createAttachmentSchema(jdbc);
        root=Files.createTempDirectory("plm-file-archive-test-");var app=new AppProperties();app.getStorage().setLocalRoot(root.toString());media=new MoldApplicationMedia(jdbc,json,app);
        http=mock(HttpClient.class);ReflectionTestUtils.setField(media,"http",http);var response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(png);when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type",List.of("application/octet-stream")),(a,b)->true));when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        try(var in=getClass().getResourceAsStream("/moldapplication/bright3-original-file-metadata.json")){remote=json.readTree(in);}
        access=mock(PilotAccessService.class);var actor=new CurrentUser(1L,"qa","归档验证",true);when(access.actor()).thenReturn(actor);when(access.actorId()).thenReturn(1L);when(access.hasPermission(anyString())).thenReturn(true);when(access.access()).thenReturn(new PilotAccessService.Access(actor,"administrator",List.of("*"),true,true));when(access.visibleSqlClause(anyString(),anyString())).thenReturn("true");
        client=mock(MoldDingTalkClient.class);when(client.instance("original-instance")).thenReturn(remote);when(client.approvalFileDownloadUrl(eq("original-instance"),anyString())).thenReturn("http://lippi-space-zjk.oss-cn-zhangjiakou.aliyuncs.com/original?Signature=private");
        repo=mock(MoldApplicationRepository.class);var queries=mock(JdbcTemplate.class);when(repo.jdbc()).thenReturn(queries);when(repo.view(anyMap())).thenAnswer(call->call.getArgument(0));
        original=new LinkedHashMap<>();original.put("applicationId",application);original.put("instanceId","original-instance");original.put("approvalNo","202610281759000301110");original.put("productLineId",project);original.put("projectId",null);original.put("status","submitted");original.put("businessStatus","awaiting_approval");original.put("version",7L);original.put("form",json.createObjectNode());original.put("generatedCodes",null);original.put("sourceSnapshot",null);
        when(queries.queryForList(anyString(),eq(project),eq(project))).thenAnswer(call->List.of(new LinkedHashMap<>(original)));
        var props=new MoldApplicationProperties();props.setEnabled(true);props.setReadSyncEnabled(true);logs=mock(OperationLogService.class);
        service=spy(new MoldApplicationService(repo,props,client,media,new MoldApplicationCodes(repo,props,new ProductBusinessCodeGenerator(),json),access,json,tx,logs,mock(MoldApplicationDingTalkFiles.class),mock(MoldApplicationBackupImport.class)));
        doAnswer(call->new LinkedHashMap<>(original)).when(service).require(application,false);
        doAnswer(call->{var value=new LinkedHashMap<>(original);value.put("files",media.list(application));return value;}).when(service).detail(application);
    }
    static String migration(String name)throws Exception {try(var in=MoldApplicationFileArchiveLocalIntegrationTest.class.getResourceAsStream("/db/migration/"+name)){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    static void createAttachmentSchema(JdbcTemplate jdbc)throws Exception {
        String base=migration("V20260710_1000__m4_bom_process_attachment.sql");jdbc.execute(base.substring(base.indexOf("create table if not exists plm_attachment ("),base.indexOf("create table if not exists plm_attachment_download_log")));
        for(String name:List.of("V20260723_1100__attachment_preview_and_mold_transfer_express.sql","V20260724_1000__dingtalk_approval_product_line_and_outbound.sql")){String sql=migration(name);int at=sql.indexOf("alter table if exists plm_attachment");jdbc.execute(sql.substring(at,sql.indexOf(';',at)+1));}
        String archive=migration("V20261013_1100__mold_applications.sql");jdbc.execute(archive.substring(archive.indexOf("alter table plm_attachment")));
    }
    @AfterEach void clean()throws Exception {if(scope!=null&&!scope.isCompleted())tx.rollback(scope);if(root!=null)try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    @Test void sixOriginalIdsBecomeRealPngFilesOnceAndOrdinarySyncNeverGrantsOrDowngrades()throws Exception {
        media.archiveRemote(application,remote);var before=media.list(application);assertThat(before).hasSize(6).allSatisfy(f->assertThat(f).containsEntry("downloadStatus","download_failed"));verifyNoInteractions(client,http);
        var ids=before.stream().map(f->f.get("attachmentId")).toList();var result=service.archiveFiles(application,null);
        assertThat((List<?>)result.get("files")).hasSize(6);assertThat(original).containsEntry("status","submitted").containsEntry("version",7L).containsEntry("productLineId",project).containsEntry("generatedCodes",null);
        var after=media.list(application);assertThat(after.stream().map(f->f.get("attachmentId")).toList()).isEqualTo(ids);assertThat(after).allSatisfy(f->assertThat(f).containsEntry("downloadStatus","downloaded").containsEntry("contentType","image/png").containsEntry("archiveError",null).containsEntry("sha256",MoldApplicationMedia.hash(png)));
        for(var f:after)assertThat(media.bytes(application,((Number)f.get("attachmentId")).longValue())).isEqualTo(png);
        assertThat(jdbc.queryForObject("select count(*) from plm_attachment where owner_object_type='Product'",Integer.class)).isZero();assertThat(jdbc.queryForObject("select count(distinct source_file_id) from plm_attachment",Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("select count(*) from plm_attachment where source_url like '%Signature=%' or storage_key like '%Signature=%' or remark like '%Signature=%'",Integer.class)).isZero();
        service.archiveFiles(application,null);media.archiveRemote(application,remote);assertThat(media.list(application).stream().map(f->f.get("attachmentId")).toList()).isEqualTo(ids);
        verify(client,times(6)).approvalFileDownloadUrl(eq("original-instance"),anyString());var request=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(http,times(6)).send(request.capture(),any(HttpResponse.BodyHandler.class));assertThat(request.getAllValues()).allSatisfy(r->assertThat(r.uri().getScheme()).isEqualTo("https"));
        verify(repo,never()).ingest(anyString(),anyString(),any(),any(),any(),any());verify(client,never()).submit(anyMap());verify(service,never()).syncInternal(anyString());
        verify(logs,times(2)).logRequired(argThat(c->"MOLD_APPLICATION_FILES_ARCHIVE".equals(c.getAction())),any());
    }
    @Test void remoteDenialIsSpecificRetryableAndDoesNotBecomeAFalseDownloadedFile() {
        when(client.approvalFileDownloadUrl(anyString(),anyString())).thenThrow(new MoldDingTalkClient.RemoteFailure(false,"钉钉拒绝请求（noPermission），请检查应用权限和表单配置"));
        service.archiveFiles(application,null);assertThat(media.list(application)).hasSize(6).allSatisfy(f->assertThat(f).containsEntry("downloadStatus","download_failed").satisfies(x->assertThat(x.get("archiveError").toString()).contains("noPermission").doesNotContain("Signature")));verifyNoInteractions(http);
    }
    @Test void deniedAdministratorApplicationOrOneFileNeverReachesOfficialDownloadOrDisk() {
        doThrow(new BusinessException(40103,"非管理员")).when(access).requireAdministrator();assertThatThrownBy(()->service.archiveFiles(application,null)).hasMessageContaining("非管理员");verifyNoInteractions(client,http);
        doNothing().when(access).requireAdministrator();doThrow(new BusinessException(40103,"无原申请权限")).when(service).require(application,false);assertThatThrownBy(()->service.archiveFiles(application,null)).hasMessageContaining("无原申请权限");verifyNoInteractions(client,http);
        doReturn(new LinkedHashMap<>(original)).when(service).require(application,false);media.archiveRemote(application,remote);long denied=((Number)media.list(application).get(1).get("attachmentId")).longValue();doThrow(new BusinessException(40103,"无原文件权限")).when(access).requireFile(denied);
        assertThatThrownBy(()->service.archiveFiles(application,null)).hasMessageContaining("无原文件权限");verifyNoInteractions(client,http);assertThat(jdbc.queryForObject("select count(*) from plm_attachment where storage_type='local'",Integer.class)).isZero();
    }
    @Test void researchProjectionAndDownloadBothRespectFilePermissionAndKeepOriginalIds() {
        media.archiveRemote(application,remote);var files=media.list(application);long denied=((Number)files.get(1).get("attachmentId")).longValue();doThrow(new BusinessException(40103,"无原文件权限")).when(access).requireFile(denied);
        var row=service.researchApplications(project).get(0);assertThat(row).containsEntry("canArchiveFiles",false);assertThat((List<Map<String,Object>>)row.get("files")).hasSize(5).noneMatch(f->Objects.equals(f.get("attachmentId"),denied));
        assertThatThrownBy(()->service.download(application,denied)).hasMessageContaining("无原文件权限");verifyNoInteractions(client,http);
        when(access.hasPermission("file:read")).thenReturn(false);row=service.researchApplications(project).get(0);assertThat((List<?>)row.get("files")).isEmpty();assertThat(row).containsEntry("canArchiveFiles",false);
    }
    @Test void mandatoryStartAuditFailurePreventsAnyRemoteReadOrFileWrite() {
        doThrow(new IllegalStateException("required archive audit failed")).when(logs).logRequired(any(),any());assertThatThrownBy(()->service.archiveFiles(application,null)).hasMessageContaining("required archive audit failed");verifyNoInteractions(client,http);assertThat(media.list(application)).isEmpty();
    }
    @Test void aLateFailedStoreCannotDowngradeAnAlreadyDownloadedOriginal() {
        service.archiveFiles(application,null);var before=media.internal(application).get(0);long attachment=((Number)before.get("attachment_id")).longValue();
        Long result=ReflectionTestUtils.invokeMethod(media,"store",application,before.get("source_component_id").toString(),"attachment",before.get("original_file_name").toString(),before.get("source_file_id").toString(),null,null,"application/octet-stream","dingtalk-sync","download_failed",((Number)before.get("file_size")).longValue());
        assertThat(result).isEqualTo(attachment);var after=media.internal(application).get(0);for(String key:List.of("attachment_id","download_status","storage_type","storage_key","content_sha256","file_size","content_type"))assertThat(after.get(key)).isEqualTo(before.get(key));
    }
    @Test void missingFileReadOrProjectScopeCannotCallOfficialApiOrWriteFiles() {
        doThrow(new BusinessException(40103,"无文件读取权限")).when(access).requirePermission("file:read");assertThatThrownBy(()->service.archiveFiles(application,null)).hasMessageContaining("无文件读取权限");verifyNoInteractions(client,http);
        doNothing().when(access).requirePermission("file:read");doThrow(new BusinessException(40103,"无新品项目权限")).when(access).requireProduct(project);assertThatThrownBy(()->service.archiveFiles(application,null)).hasMessageContaining("无新品项目权限");assertThatThrownBy(()->service.download(application,1)).hasMessageContaining("无新品项目权限");assertThatThrownBy(()->service.researchApplications(project)).hasMessageContaining("无新品项目权限");verifyNoInteractions(client,http);assertThat(media.list(application)).isEmpty();
    }

    @Test void restrictedResearchSourceHidesOriginalFilesAndArchiveAction() {
        media.archiveRemote(application,remote);original.put("sourceSnapshot",json.valueToTree(Map.of("research",Map.of("projectId",project))));
        var development=mock(com.yuewei.plm.module.project.development.ProductDevelopmentService.class);when(development.redactMoldApplicationSource(anyMap())).thenReturn(Map.of("projectId",project,"restricted",true));ReflectionTestUtils.setField(service,"productDevelopment",development);
        var result=service.researchApplications(project).get(0);assertThat((List<?>)result.get("files")).isEmpty();assertThat(result).containsEntry("canArchiveFiles",false);verifyNoInteractions(client,http);
    }
    @Test void mandatoryCompletionAuditRollsBackFileMetadataAndKeepsApprovalIdentity() {
        media.archiveRemote(application,remote);var ids=media.list(application).stream().map(f->f.get("attachmentId")).toList();
        doThrow(new IllegalStateException("completion audit failed")).when(logs).logRequired(argThat(c->"MOLD_APPLICATION_FILES_ARCHIVE".equals(c.getAction())),any());
        var nested=new org.springframework.transaction.support.TransactionTemplate(tx);nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(()->nested.execute(status->service.archiveFiles(application,null))).hasMessageContaining("completion audit failed");
        assertThat(media.list(application).stream().map(f->f.get("attachmentId")).toList()).isEqualTo(ids);assertThat(media.list(application)).allSatisfy(f->assertThat(f).containsEntry("downloadStatus","download_failed").containsEntry("sha256",null));
        assertThat(original).containsEntry("status","submitted").containsEntry("version",7L).containsEntry("productLineId",project).containsEntry("generatedCodes",null);
    }

    @Test void scheduledMalformedFieldsStayBestEffortWhileExplicitArchiveReportsTheirMissingData() {
        for(String bad:List.of("{","{}","[{}]")) {
            var detail=json.createObjectNode();var fields=detail.putArray("form_component_values");fields.addObject().put("id","bad-field").put("component_type","DDAttachment").put("value",bad);remote.path("form_component_values").forEach(fields::add);
            assertThatCode(()->media.archiveRemote(application,detail)).doesNotThrowAnyException();assertThat(media.list(application)).hasSize(6);
            assertThatThrownBy(()->media.archiveRemote(application,detail,file->{throw new AssertionError("Malformed input must not authorize any file");})).isInstanceOf(BusinessException.class).hasMessageContaining("原");
        }
        var literalNull=json.createObjectNode();literalNull.putArray("form_component_values").addObject().put("id","empty-field").put("component_type","DDAttachment").put("value","null");
        assertThatCode(()->media.archiveRemote(application,literalNull)).doesNotThrowAnyException();assertThatCode(()->media.archiveRemote(application,literalNull,file->{throw new AssertionError("Null media must not authorize a file");})).doesNotThrowAnyException();verifyNoInteractions(client,http);
    }

}

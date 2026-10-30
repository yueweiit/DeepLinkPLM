package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.*;
import com.yuewei.plm.common.config.AppProperties;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import com.yuewei.plm.module.operationlog.repository.OperationLogRepository;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.operationlog.service.impl.OperationLogServiceImpl;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No ambient test transaction: real Spring MANDATORY audit, real MyBatis repository and committed PostgreSQL DDL. */
@EnabledIfEnvironmentVariable(named="PLM_MOLD_TEST_DB_URL",matches=".+")
class MoldApplicationArchiveAuditLocalIntegrationTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final long application=12381L,project=1951L;
    JdbcTemplate admin,jdbc;String schema;Path root;MoldApplicationMedia media;MoldDingTalkClient client;MoldApplicationService service;SqlSessionTemplate session;MockHttpServletRequest request;
    final byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6BuMAAAAASUVORK5CYII=");
    @BeforeEach void committedFixture()throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        String url=System.getenv("PLM_MOLD_TEST_DB_URL"),password=System.getenv("PLM_MOLD_TEST_DB_PASSWORD");LocalMoldTestDatabase.database(url);admin=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",password));LocalMoldTestDatabase.verify(url,admin);
        schema="mold_archive_audit_"+UUID.randomUUID().toString().replace("-","");admin.execute("create schema "+schema);
        var source=new DriverManagerDataSource(url.replace("currentSchema=plm,public","currentSchema="+schema+",public").replace("currentSchema=plm","currentSchema="+schema),"postgres",password);jdbc=new JdbcTemplate(source);var transactions=new DataSourceTransactionManager(source);
        MoldApplicationFileArchiveLocalIntegrationTest.createAttachmentSchema(jdbc);
        String logSql=MoldApplicationFileArchiveLocalIntegrationTest.migration("V20260703_1600__m1_auth_and_operation_log.sql");int at=logSql.indexOf("create table if not exists plm_operation_log (");jdbc.execute(logSql.substring(at,logSql.indexOf(");",at)+2));
        var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);configuration.addMapper(OperationLogRepository.class);
        var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(configuration);factory.afterPropertiesSet();session=new SqlSessionTemplate(factory.getObject());
        var logProxy=new ProxyFactory(new OperationLogServiceImpl(session.getMapper(OperationLogRepository.class)));logProxy.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));var logs=(OperationLogService)logProxy.getProxy();
        root=Files.createTempDirectory("plm-archive-audit-test-");var app=new AppProperties();app.getStorage().setLocalRoot(root.toString());media=new MoldApplicationMedia(jdbc,json,app);
        var http=mock(HttpClient.class);ReflectionTestUtils.setField(media,"http",http);var response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(png);when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type",List.of("application/octet-stream")),(a,b)->true));when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        JsonNode remote;try(var in=getClass().getResourceAsStream("/moldapplication/bright3-original-file-metadata.json")){remote=json.readTree(in);}media.archiveRemote(application,remote);
        client=mock(MoldDingTalkClient.class);when(client.instance("qa-original-instance")).thenAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();assertThat(jdbc.queryForObject("select count(*) from plm_operation_log where action='MOLD_APPLICATION_FILES_ARCHIVE_START'",Integer.class)).isGreaterThan(0);return remote;});when(client.approvalFileDownloadUrl(anyString(),anyString())).thenReturn("http://lippi-space-zjk.oss-cn-zhangjiakou.aliyuncs.com/original?Signature=private");
        var actor=new CurrentUser(1L,"qa-admin","QA管理员",true);var access=mock(PilotAccessService.class);when(access.actor()).thenReturn(actor);when(access.actorId()).thenReturn(1L);when(access.hasPermission(anyString())).thenReturn(true);
        var props=new MoldApplicationProperties();props.setEnabled(true);props.setReadSyncEnabled(true);var repo=mock(MoldApplicationRepository.class);when(repo.json(any())).thenAnswer(call->json.writeValueAsString(call.getArgument(0)));
        var original=new LinkedHashMap<String,Object>();original.put("applicationId",application);original.put("instanceId","qa-original-instance");original.put("productLineId",project);original.put("status","submitted");original.put("version",7L);
        service=spy(new MoldApplicationService(repo,props,client,media,new MoldApplicationCodes(repo,props,new ProductBusinessCodeGenerator(),json),access,json,transactions,logs,mock(MoldApplicationDingTalkFiles.class),mock(MoldApplicationBackupImport.class)));
        doAnswer(call->new LinkedHashMap<>(original)).when(service).require(application,false);doAnswer(call->{var r=new LinkedHashMap<>(original);r.put("files",media.list(application));return r;}).when(service).detail(application);
        request=new MockHttpServletRequest("POST","/api/v1/mold-applications/"+application+"/files/archive");request.addHeader("X-Request-Id","qa-archive-audit");request.addHeader("User-Agent","JUnit");
    }
    @AfterEach void cleanup()throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();if(admin!=null&&schema!=null)admin.execute("drop schema "+schema+" cascade");
        if(root!=null)try(var paths=Files.walk(root)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
    }
    @Test void noAmbientTransactionArchivesSixOriginalFilesAndWritesBothAuditsInTheSameRealTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();var before=media.list(application).stream().map(f->f.get("attachmentId")).toList();
        var result=service.archiveFiles(application,request);assertThat(result).containsEntry("status","submitted").containsEntry("version",7L).containsEntry("productLineId",project);assertThat(media.list(application)).hasSize(6).allSatisfy(f->assertThat(f).containsEntry("downloadStatus","downloaded").containsEntry("contentType","image/png"));
        var audits=jdbc.queryForList("select action,business_type,business_id,operator_user_name,xmin::text as transaction_id,detail_json from plm_operation_log order by log_id");assertThat(audits).hasSize(2);assertThat(audits.stream().map(a->a.get("transaction_id")).distinct().count()).isEqualTo(1);assertThat(audits).allSatisfy(a->assertThat(a).containsEntry("business_type","MOLD_APPLICATION").containsEntry("business_id",Long.toString(application)).containsEntry("operator_user_name","QA管理员"));
        assertThat(audits.get(0).get("action")).isEqualTo("MOLD_APPLICATION_FILES_ARCHIVE_START");assertThat(audits.get(1).get("action")).isEqualTo("MOLD_APPLICATION_FILES_ARCHIVE");
        service.archiveFiles(application,request);assertThat(media.list(application).stream().map(f->f.get("attachmentId")).toList()).isEqualTo(before);verify(client,times(6)).approvalFileDownloadUrl(anyString(),anyString());verify(client,never()).submit(anyMap());
    }
    @Test void realMandatoryStartAuditConstraintFailureStopsBeforeRemoteRead() {
        jdbc.execute("alter table plm_operation_log add constraint qa_reject_start check(action<>'MOLD_APPLICATION_FILES_ARCHIVE_START')");
        assertThatThrownBy(()->service.archiveFiles(application,request)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);verifyNoInteractions(client);assertThat(jdbc.queryForObject("select count(*) from plm_operation_log",Integer.class)).isZero();assertThat(media.list(application)).allSatisfy(f->assertThat(f).containsEntry("downloadStatus","download_failed"));
    }
    @Test void realMandatoryCompletionAuditConstraintFailureRollsBackStartAndFileMetadata() {
        jdbc.execute("alter table plm_operation_log add constraint qa_reject_complete check(action<>'MOLD_APPLICATION_FILES_ARCHIVE')");
        var before=media.list(application).stream().map(f->f.get("attachmentId")).toList();assertThatThrownBy(()->service.archiveFiles(application,request)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from plm_operation_log",Integer.class)).isZero();assertThat(media.list(application).stream().map(f->f.get("attachmentId")).toList()).isEqualTo(before);assertThat(media.list(application)).allSatisfy(f->assertThat(f).containsEntry("downloadStatus","download_failed").containsEntry("sha256",null));
    }
}

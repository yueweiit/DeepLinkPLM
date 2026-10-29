package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.*;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.interceptor.*;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual REQUIRED/MANDATORY proxies reproduce the HTTP boundary, without a pre-existing test transaction. */
@EnabledIfEnvironmentVariable(named="PLM_MOLD_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/plm_data_management\\?currentSchema=plm")
class MoldApplicationConfigAtomicityLocalIntegrationTest {
    final ObjectMapper json=new ObjectMapper();final String tipo="Config-atomicity-"+UUID.randomUUID();
    final List<Map<String,String>> materials=List.of(Map.of("code","10","partType","TPU"),Map.of("code","20","partType","PC"));
    JdbcTemplate jdbc;DataSourceTransactionManager transactions;MoldApplicationService service;
    MoldApplicationRepository repo;OperationLogService logDelegate;MoldApplicationCodes rawCodes;
    @BeforeEach void configure()throws Exception{
        var source=new DriverManagerDataSource(System.getenv("PLM_MOLD_TEST_DB_URL"),"postgres",System.getenv("PLM_MOLD_TEST_DB_PASSWORD"));
        jdbc=new JdbcTemplate(source);transactions=new DataSourceTransactionManager(source);
        repo=mock(MoldApplicationRepository.class);when(repo.jdbc()).thenReturn(jdbc);when(repo.json(any())).thenAnswer(c->json.writeValueAsString(c.getArgument(0)));when(repo.node(any())).thenAnswer(c->json.readTree(Objects.toString(c.getArgument(0))));
        var schema=json.createObjectNode();var field=schema.putObject("schemaContent").putArray("items").addObject();field.put("componentName","DDSelectField");var fieldProps=field.putObject("props");fieldProps.put("id",MoldApplicationForms.COMPONENTS.get("tipo"));fieldProps.put("label","Tipo");fieldProps.putArray("options").addObject().put("value",tipo);when(repo.schema(anyString())).thenReturn(schema);
        var props=new MoldApplicationProperties();props.setEnabled(true);props.setCentralCodeEnabled(false);
        rawCodes=new MoldApplicationCodes(repo,props,new ProductBusinessCodeGenerator(),json);
        var codeFactory=new ProxyFactory(rawCodes);codeFactory.setProxyTargetClass(true);codeFactory.addAdvice(advice("saveConfig",TransactionDefinition.PROPAGATION_REQUIRED));var codes=(MoldApplicationCodes)codeFactory.getProxy();
        logDelegate=mock(OperationLogService.class);var logFactory=new ProxyFactory(logDelegate);logFactory.addAdvice(advice("logRequired",TransactionDefinition.PROPAGATION_MANDATORY));var logs=(OperationLogService)logFactory.getProxy();
        var access=mock(PilotAccessService.class);when(access.actor()).thenReturn(new CurrentUser(3L,"admin","Administrator",true));
        service=new MoldApplicationService(repo,props,mock(MoldDingTalkClient.class),mock(MoldApplicationMedia.class),codes,access,json,transactions,logs,mock(MoldApplicationDingTalkFiles.class),mock(MoldApplicationBackupImport.class));
    }
    TransactionInterceptor advice(String method,int propagation){var attrs=new NameMatchTransactionAttributeSource();attrs.addTransactionalMethod(method,new DefaultTransactionAttribute(propagation));return new TransactionInterceptor(transactions,attrs);}
    @AfterEach void removeOnlyOurFixture(){if(jdbc!=null)jdbc.update("delete from plm_mold_application_config where tipo=?",tipo);}
    @Test void configAndRequiredAuditCommitInOneTransaction(){
        doAnswer(c->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();return null;}).when(logDelegate).logRequired(any(),any());
        service.saveConfig(tipo,50L,"FA",materials,null,null);
        assertThat(jdbc.queryForObject("select count(*) from plm_mold_application_config where tipo=?",Long.class,tipo)).isEqualTo(1L);verify(logDelegate).logRequired(any(),any());
    }
    @Test void auditFailureRollsBackTheConfigRatherThanReturningAPartialSuccess(){
        doThrow(new IllegalStateException("audit unavailable")).when(logDelegate).logRequired(any(),any());
        assertThatThrownBy(()->service.saveConfig(tipo,50L,"FA",materials,null,null)).hasMessage("audit unavailable");
        assertThat(jdbc.queryForObject("select count(*) from plm_mold_application_config where tipo=?",Long.class,tipo)).isZero();
    }
    @Test void confirmedFantasiaUses1294WithoutAllocatingOrPersistingAValidationDraft(){
        var confirmed=jdbc.queryForList("select tipo from plm_mold_application_config where tipo='幻影Fantasía Case' and product_line_id=50 and product_specific_code='FA'");
        Assumptions.assumeFalse(confirmed.isEmpty(),"Requires the user's confirmed local Fantasia mapping");
        Long phone=jdbc.queryForObject("select code_item_id from plm_code_item where code_type='phone_model' and code_value='1294' and deleted_flag=0 and status='enabled'",Long.class);
        Long before=jdbc.queryForObject("select count(*) from plm_code_item where code_type='phone_model'",Long.class);
        Integer highWater=jdbc.queryForObject("select last_value from plm_mold_code_sequence where sequence_key='phone_model'",Integer.class);
        new TransactionTemplate(transactions).executeWithoutResult(tx->{var r=rawCodes.reserve(new MoldApplicationCodes.Request("confirmed-fa-check-"+UUID.randomUUID(),"幻影Fantasía Case","OPPO RENO 16F 5G",phone,null),"config-atomicity-test");assertThat(r.get("generatedCodes")).isEqualTo("MFA101294 / MFA201294");assertThat(r.get("phoneModelCode")).isEqualTo("1294");tx.setRollbackOnly();});
        assertThat(jdbc.queryForObject("select count(*) from plm_code_item where code_type='phone_model'",Long.class)).isEqualTo(before);assertThat(jdbc.queryForObject("select last_value from plm_mold_code_sequence where sequence_key='phone_model'",Integer.class)).isEqualTo(highWater);
    }
}

package com.yuewei.plm.module.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Empty task-owned local database only. No application or production fixtures are copied. */
@EnabledIfEnvironmentVariable(named="PLM_I18N_QA_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:15641/plm_i18n_qa")
class UserLanguagePostgresTest {
    JdbcTemplate jdbc;
    PilotAccessService access;
    UserLanguageService service;
    OperationLogService logs;

    @BeforeEach @SuppressWarnings("deprecation") void prepare() {
        var data = new DriverManagerDataSource(System.getenv("PLM_I18N_QA_URL"), "postgres", System.getenv("PLM_I18N_QA_PASSWORD"));
        jdbc = new JdbcTemplate(data);
        jdbc.execute("drop table if exists plm_collaboration_assignment; drop table if exists plm_pilot_account; drop table if exists sys_user");
        jdbc.execute("create table sys_user(user_id bigint primary key,username text,display_name text,department_name text,all_permissions boolean,status text,deleted_flag int default 0,updated_at timestamp,updated_by text)");
        jdbc.execute("create table plm_pilot_account(user_id bigint primary key,role_code text,permissions jsonb,all_objects boolean)");
        jdbc.execute("create table plm_collaboration_assignment(user_id bigint,field_group text)");
        jdbc.execute("insert into sys_user(user_id,username,display_name,department_name,all_permissions,status) values(1,'admin','Administrator','Engineering',true,'active'),(2,'engineer','Engineer','Engineering',false,'active'),(3,'sales','Sales','Sales',false,'active')");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261029_1600__user_preferred_language.sql")).execute(data);
        access = new PilotAccessService(jdbc, new ObjectMapper());
        logs = mock(OperationLogService.class);
        var proxy = new ProxyFactory(new UserLanguageService(jdbc, access, logs));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(data), new AnnotationTransactionAttributeSource()));
        service = (UserLanguageService) proxy.getProxy();
        as(2);
    }
    @AfterEach void clear() { CurrentUserContext.clear(); }
    void as(long id) { CurrentUserContext.set(new CurrentUser(id,"untrusted-name","Untrusted",true)); }
    String language(long id) { return jdbc.queryForObject("select preferred_language from sys_user where user_id=?",String.class,id); }

    @Test void historicalAccountsStartInChineseAndOwnPreferenceDoesNotChangeOtherUsersOrPermissions() {
        assertThat(jdbc.queryForList("select preferred_language from sys_user",String.class)).containsOnly("zh-CN");
        var before=jdbc.queryForMap("select username,all_permissions,status,department_name from sys_user where user_id=2");
        assertThat(service.save(null,new UserLanguageService.Input("es","zh-CN"),null).preferredLanguage()).isEqualTo("es");
        assertThat(language(1)).isEqualTo("zh-CN"); assertThat(language(3)).isEqualTo("zh-CN");
        assertThat(jdbc.queryForMap("select username,all_permissions,status,department_name from sys_user where user_id=2")).isEqualTo(before);
        assertThat(access.userView(new CurrentUser(2L,"forged","Forged",true)).getPreferredLanguage()).isEqualTo("es");
        verify(logs).logRequired(argThat(c->c.getAction().equals("USER_LANGUAGE_UPDATE") && c.getDetailJson().contains("\"after\":\"es\"")),argThat(a->a.username().equals("engineer")&&!a.allPermissions()));
    }
    @Test void ordinaryAccountCannotUseAdministratorLanguageEntryEvenWithForgedAdminClaim() {
        assertThatThrownBy(()->service.save(3L,new UserLanguageService.Input("es","zh-CN"),null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.save(2L,new UserLanguageService.Input("es","zh-CN"),null)).isInstanceOf(BusinessException.class);
        assertThat(language(3)).isEqualTo("zh-CN"); verifyNoInteractions(logs);
    }
    @Test void administratorCanSetAnotherAccountsDefaultWithoutChangingItsGrants() {
        as(1);var before=jdbc.queryForMap("select username,all_permissions,status from sys_user where user_id=3");
        service.save(3L,new UserLanguageService.Input("es","zh-CN"),null);
        assertThat(language(3)).isEqualTo("es");assertThat(jdbc.queryForMap("select username,all_permissions,status from sys_user where user_id=3")).isEqualTo(before);
    }
    @Test void staleOriginalIsRejectedAndSameLanguageDoesNotWriteAudit() {
        service.save(null,new UserLanguageService.Input("zh-CN","zh-CN"),null);verifyNoInteractions(logs);
        service.save(null,new UserLanguageService.Input("es","zh-CN"),null);
        assertThatThrownBy(()->service.save(null,new UserLanguageService.Input("zh-CN","zh-CN"),null)).isInstanceOf(BusinessException.class).hasMessageContaining("已变更");
        assertThat(language(2)).isEqualTo("es");
    }
    @Test void auditFailureRollsBackTheActualPreferenceUpdate() {
        var before=jdbc.queryForMap("select * from sys_user where user_id=2");
        when(logs.logRequired(any(),any())).thenThrow(new IllegalStateException("audit-unavailable"));
        assertThatThrownBy(()->service.save(null,new UserLanguageService.Input("es","zh-CN"),null)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForMap("select * from sys_user where user_id=2")).isEqualTo(before);
    }
    @Test void concurrentOriginalsAllowOneSaveAndRejectTheStaleWriter() throws Exception {
        Callable<String> change=()->{as(2);try{service.save(null,new UserLanguageService.Input("es","zh-CN"),null);return "saved";}catch(BusinessException e){return "conflict";}finally{CurrentUserContext.clear();}};
        var pool=Executors.newFixedThreadPool(2);
        try { var results=pool.invokeAll(List.of(change,change));assertThat(List.of(results.get(0).get(),results.get(1).get())).containsExactlyInAnyOrder("saved","conflict"); }
        finally { pool.shutdownNow(); }
        assertThat(language(2)).isEqualTo("es");
    }
    @Test void unsupportedPreferenceIsRejectedByRequestValidationAndDatabaseConstraint() {
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()){
            assertThat(factory.getValidator().validate(new UserLanguageService.Input("en","zh-CN"))).isNotEmpty();
        }
        assertThatThrownBy(()->jdbc.update("update sys_user set preferred_language='en' where user_id=2")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(language(2)).isEqualTo("zh-CN");
    }
}

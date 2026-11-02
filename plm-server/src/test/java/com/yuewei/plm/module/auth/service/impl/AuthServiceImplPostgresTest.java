package com.yuewei.plm.module.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.yuewei.plm.common.constant.ErrorCodeConstants;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUserContext;
import com.yuewei.plm.module.auth.dto.LoginDTO;
import com.yuewei.plm.module.auth.service.TokenSessionService;
import com.yuewei.plm.module.auth.vo.LoginVO;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.user.entity.SysUser;
import com.yuewei.plm.module.user.repository.SysUserRepository;
import org.apache.ibatis.exceptions.TooManyResultsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/** Task-owned empty PostgreSQL only; all account fixtures are temporary and rolled back. */
@EnabledIfEnvironmentVariable(named = "PLM_AUTH_QA_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:15652/plm_auth_qa")
class AuthServiceImplPostgresTest {
    private static final String PASSWORD = "IsolatedPassword-123";
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private TransactionStatus transaction;
    private SysUserRepository users;
    private TokenSessionService sessions;
    private OperationLogService logs;
    private AuthServiceImpl service;
    private long angelId;

    @BeforeEach
    void prepare() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("PLM_AUTH_QA_URL"), "postgres", System.getenv("PLM_AUTH_QA_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        transaction = transactions.getTransaction(new DefaultTransactionDefinition());
        jdbc.execute("""
            create temp table sys_user(
                user_id bigserial primary key, username text, password_hash text, display_name text,
                department_name text, formal_flag int, all_permissions boolean, status text,
                created_at timestamp, created_by text, updated_at timestamp, updated_by text,
                deleted_flag int default 0
            ) on commit drop
            """);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(SysUserRepository.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        users = new SqlSessionTemplate(factory.getObject()).getMapper(SysUserRepository.class);
        sessions = mock(TokenSessionService.class);
        when(sessions.createSession(any())).thenReturn("isolated-test-session");
        when(sessions.getExpiresInSeconds()).thenReturn(28800L);
        logs = mock(OperationLogService.class);
        service = new AuthServiceImpl(users, sessions, logs, encoder);
        angelId = save("angel");
    }

    @AfterEach
    void rollback() {
        CurrentUserContext.clear();
        if (transaction != null && !transaction.isCompleted()) transactions.rollback(transaction);
    }

    @ParameterizedTest
    @ValueSource(strings = {"angel", "Angel", "ANGEL"})
    void usernameCaseVariantsReturnTheSameSavedAccount(String username) {
        LoginVO result = login(username, PASSWORD);
        assertThat(result.getUser().getUserId()).isEqualTo(angelId);
        assertThat(result.getUser().getUsername()).isEqualTo("angel");
        assertThat(users.selectById(angelId).getUsername()).isEqualTo("angel");
        assertThat(result.getExpiresInSeconds()).isEqualTo(28800L);
        assertThat(CurrentUserContext.get()).isEmpty();
    }

    @Test
    void existingAccountStillLogsInWithItsOriginalUsernameCase() {
        long existingId = save("Engineer01");
        assertThat(login("Engineer01", PASSWORD).getUser().getUserId()).isEqualTo(existingId);
        assertThat(users.selectById(existingId).getUsername()).isEqualTo("Engineer01");
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-password", "isolatedpassword-123"})
    void wrongOrDifferentlyCasedPasswordRemainsUnauthorized(String password) {
        assertUnauthorized("Angel", password);
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "deleted"})
    void usernameCaseMatchingDoesNotEnableInactiveOrSoftDeletedAccounts(String state) {
        if (state.equals("inactive")) jdbc.update("update sys_user set status='inactive' where user_id=?", angelId);
        else jdbc.update("update sys_user set deleted_flag=1 where user_id=?", angelId);
        assertUnauthorized("ANGEL", PASSWORD);
    }

    @Test
    void usernameIsBoundAsDataRatherThanSql() {
        assertUnauthorized("angel') OR true --", PASSWORD);
    }

    @Test
    void multipleCaseInsensitiveMatchesAreRejectedByTheExistingRepositoryContract() {
        save("Angel");
        assertThatThrownBy(() -> login("ANGEL", PASSWORD)).isInstanceOf(TooManyResultsException.class);
        verifyNoInteractions(sessions, logs);
    }

    private long save(String username) {
        var user = new SysUser();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(PASSWORD));
        user.setDisplayName("隔离登录测试");
        user.setAllPermissions(false);
        user.setStatus("active");
        user.setDeletedFlag(0);
        assertThat(users.insert(user)).isEqualTo(1);
        return user.getUserId();
    }

    private LoginVO login(String username, String password) {
        return service.login(new LoginDTO(username, password), new MockHttpServletRequest("POST", "/api/v1/auth/login"));
    }

    private void assertUnauthorized(String username, String password) {
        assertThatThrownBy(() -> login(username, password))
            .isInstanceOfSatisfying(BusinessException.class, error -> {
                assertThat(error.getCode()).isEqualTo(ErrorCodeConstants.UNAUTHORIZED);
                assertThat(error.getMessage()).isEqualTo("账号或密码错误");
            });
        verifyNoInteractions(sessions, logs);
    }
}

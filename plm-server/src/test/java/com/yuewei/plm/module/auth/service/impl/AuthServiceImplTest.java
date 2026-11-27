package com.yuewei.plm.module.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuewei.plm.common.constant.ErrorCodeConstants;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import com.yuewei.plm.module.auth.dto.ChangePasswordDTO;
import com.yuewei.plm.module.auth.service.TokenSessionService;
import com.yuewei.plm.module.auth.dto.LoginDTO;
import com.yuewei.plm.module.auth.vo.LoginVO;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.user.entity.SysUser;
import com.yuewei.plm.module.user.repository.SysUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.mockito.ArgumentCaptor;

class AuthServiceImplTest {

    @AfterEach
    void clearCurrentUser() {
        CurrentUserContext.clear();
    }

    @Test
    void loginWithCorrectPasswordReturnsTokenAndCurrentUser() {
        SysUserRepository userRepository = mock(SysUserRepository.class);
        TokenSessionService tokenSessionService = mock(TokenSessionService.class);
        when(tokenSessionService.createSession(any())).thenReturn("unit-test-session");
        when(tokenSessionService.getExpiresInSeconds()).thenReturn(86400L);
        OperationLogService operationLogService = mock(OperationLogService.class);
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        AuthServiceImpl authService = new AuthServiceImpl(userRepository, tokenSessionService, operationLogService, passwordEncoder);
        SysUser user = activeUser(passwordEncoder.encode("initial-password"));
        when(userRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);
        when(operationLogService.logSuccess(any())).thenReturn(10L);

        LoginVO loginVO = authService.login(new LoginDTO("engineer01", "initial-password"), mock(HttpServletRequest.class));

        assertThat(loginVO.getToken()).isNotBlank();
        assertThat(loginVO.getTokenType()).isEqualTo("Bearer");
        assertThat(loginVO.getExpiresInSeconds()).isEqualTo(86400L);
        assertThat(loginVO.getUser().getUserId()).isEqualTo(1L);
        assertThat(loginVO.getUser().getUsername()).isEqualTo("engineer01");
        assertThat(loginVO.getUser().isAllPermissions()).isTrue();
        verify(operationLogService).logSuccess(any());
    }

    @Test
    void loginWithWrongPasswordThrowsUnifiedAuthError() {
        SysUserRepository userRepository = mock(SysUserRepository.class);
        TokenSessionService tokenSessionService = mock(TokenSessionService.class);
        OperationLogService operationLogService = mock(OperationLogService.class);
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        AuthServiceImpl authService = new AuthServiceImpl(userRepository, tokenSessionService, operationLogService, passwordEncoder);
        when(userRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(activeUser(passwordEncoder.encode("right-password")));

        assertThatThrownBy(() -> authService.login(new LoginDTO("engineer01", "wrong-password"), mock(HttpServletRequest.class)))
            .isInstanceOf(BusinessException.class)
            .extracting("code")
            .isEqualTo(ErrorCodeConstants.UNAUTHORIZED);
    }

    @Test
    void passwordChangeVerifiesCurrentPasswordAndAuditsOnlyThatThePasswordChanged() {
        SysUserRepository userRepository = mock(SysUserRepository.class);
        OperationLogService logs = mock(OperationLogService.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        AuthServiceImpl authService = new AuthServiceImpl(userRepository, mock(TokenSessionService.class), logs, encoder);
        SysUser user = activeUser(encoder.encode("initial-password"));
        CurrentUser actor = new CurrentUser(1L, "engineer01", "工程部用户一", true);
        when(userRepository.selectById(1L)).thenReturn(user);
        when(userRepository.updateById(any(SysUser.class))).thenReturn(1);
        CurrentUserContext.set(actor);

        authService.changePassword(new ChangePasswordDTO("initial-password", "new-password-123"), mock(HttpServletRequest.class));

        ArgumentCaptor<SysUser> savedUser = ArgumentCaptor.forClass(SysUser.class);
        verify(userRepository).updateById(savedUser.capture());
        assertThat(encoder.matches("new-password-123", savedUser.getValue().getPasswordHash())).isTrue();
        assertThat(savedUser.getValue().getPasswordHash()).isNotEqualTo("new-password-123");
        ArgumentCaptor<OperationLogCreateCommand> audit = ArgumentCaptor.forClass(OperationLogCreateCommand.class);
        verify(logs).logRequired(audit.capture(), eq(actor));
        assertThat(audit.getValue().getDetailJson()).isEqualTo("{\"passwordChanged\":true}")
            .doesNotContain("initial-password", "new-password-123");
    }

    @Test
    void passwordChangeRejectsWrongCurrentPasswordWithoutUpdatingTheAccount() {
        SysUserRepository userRepository = mock(SysUserRepository.class);
        OperationLogService logs = mock(OperationLogService.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        AuthServiceImpl authService = new AuthServiceImpl(userRepository, mock(TokenSessionService.class), logs, encoder);
        when(userRepository.selectById(1L)).thenReturn(activeUser(encoder.encode("initial-password")));
        CurrentUserContext.set(new CurrentUser(1L, "engineer01", "工程部用户一", true));

        assertThatThrownBy(() -> authService.changePassword(
            new ChangePasswordDTO("wrong-password", "new-password-123"), mock(HttpServletRequest.class)))
            .isInstanceOfSatisfying(BusinessException.class, error -> {
                assertThat(error.getCode()).isEqualTo(ErrorCodeConstants.VALIDATION_ERROR);
                assertThat(error.getMessage()).isEqualTo("当前密码不正确");
            });
        verify(userRepository, never()).updateById(any(SysUser.class));
        verify(logs, never()).logRequired(any(), any());
    }

    private SysUser activeUser(String passwordHash) {
        SysUser user = new SysUser();
        user.setUserId(1L);
        user.setUsername("engineer01");
        user.setPasswordHash(passwordHash);
        user.setDisplayName("工程部用户一");
        user.setDepartmentName("工程部");
        user.setFormalFlag(1);
        user.setAllPermissions(true);
        user.setStatus("active");
        user.setDeletedFlag(0);
        return user;
    }
}

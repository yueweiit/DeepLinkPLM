package com.yuewei.plm.module.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuewei.plm.common.constant.ErrorCodeConstants;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import com.yuewei.plm.module.auth.dto.ChangePasswordDTO;
import com.yuewei.plm.module.auth.dto.LoginDTO;
import com.yuewei.plm.module.auth.service.AuthService;
import com.yuewei.plm.module.auth.service.TokenSessionService;
import com.yuewei.plm.module.auth.vo.CurrentUserVO;
import com.yuewei.plm.module.auth.vo.LoginVO;
import com.yuewei.plm.module.operationlog.constant.OperationActionConstants;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.user.entity.SysUser;
import com.yuewei.plm.module.user.repository.SysUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String LOGIN_ERROR_MESSAGE = "账号或密码错误";
    private static final String BEARER_PREFIX = "Bearer ";
    private final SysUserRepository sysUserRepository;
    private final TokenSessionService tokenSessionService;
    private final OperationLogService operationLogService;
    private final PasswordEncoder passwordEncoder;
    @org.springframework.beans.factory.annotation.Autowired
    private com.yuewei.plm.module.pilot.PilotAccessService pilotAccess;

    @Override
    public LoginVO login(LoginDTO loginDTO, HttpServletRequest request) {
        SysUser user = sysUserRepository.selectOne(new LambdaQueryWrapper<SysUser>()
            .apply("lower(username) = lower({0})", loginDTO.getUsername()));
        if (!canLogin(user) || !passwordEncoder.matches(loginDTO.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCodeConstants.UNAUTHORIZED, LOGIN_ERROR_MESSAGE);
        }
        CurrentUser currentUser = new CurrentUser(
            user.getUserId(),
            user.getUsername(),
            user.getDisplayName(),
            Boolean.TRUE.equals(user.getAllPermissions())
        );
        String token = tokenSessionService.createSession(currentUser);
        CurrentUserContext.set(currentUser);
        try {
            operationLogService.logSuccess(OperationLogCreateCommand.builder()
                .action(OperationActionConstants.AUTH_LOGIN)
                .businessType("AUTH")
                .businessId(String.valueOf(user.getUserId()))
                .businessCode(user.getUsername())
                .businessName(user.getDisplayName())
                .request(request)
                .build());
        } finally {
            CurrentUserContext.clear();
        }
        return LoginVO.builder()
            .token(token)
            .tokenType("Bearer")
            .expiresInSeconds(tokenSessionService.getExpiresInSeconds())
            .user(pilotAccess==null?CurrentUserVO.from(currentUser):pilotAccess.userView(currentUser))
            .build();
    }

    @Override
    public CurrentUserVO profile() {
        CurrentUser currentUser = CurrentUserContext.get()
            .orElseThrow(() -> new BusinessException(ErrorCodeConstants.UNAUTHORIZED, "未登录或登录已失效"));
        return pilotAccess==null?CurrentUserVO.from(currentUser):pilotAccess.userView(currentUser);
    }

    @Override
    @Transactional
    public void changePassword(ChangePasswordDTO input, HttpServletRequest request) {
        CurrentUser actor = CurrentUserContext.get()
            .orElseThrow(() -> new BusinessException(ErrorCodeConstants.UNAUTHORIZED, "未登录或登录已失效"));
        SysUser user = sysUserRepository.selectById(actor.userId());
        if (!canLogin(user) || !StringUtils.hasText(user.getPasswordHash()))
            throw new BusinessException(ErrorCodeConstants.UNAUTHORIZED, "未登录或登录已失效");
        if (!passwordEncoder.matches(input.currentPassword(), user.getPasswordHash()))
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "当前密码不正确");
        if (input.newPassword().length() < 12
            || input.newPassword().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "新密码须至少12位且不超过72个UTF-8字节");
        if (passwordEncoder.matches(input.newPassword(), user.getPasswordHash()))
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "新密码不能与当前密码相同");

        user.setPasswordHash(passwordEncoder.encode(input.newPassword()));
        user.setUpdatedAt(LocalDateTime.now());
        user.setUpdatedBy(actor.username());
        if (sysUserRepository.updateById(user) != 1)
            throw new BusinessException(ErrorCodeConstants.RESOURCE_NOT_FOUND, "账号不存在");

        operationLogService.logRequired(OperationLogCreateCommand.builder()
            .action(OperationActionConstants.AUTH_PASSWORD_CHANGE)
            .businessType("AUTH")
            .businessId(String.valueOf(actor.userId()))
            .businessCode(actor.username())
            .businessName(actor.displayName())
            .detailJson("{\"passwordChanged\":true}")
            .request(request)
            .build(), actor);
    }

    @Override
    public void logout(String authorization, HttpServletRequest request) {
        String token = parseBearerToken(authorization);
        operationLogService.logSuccess(OperationLogCreateCommand.builder()
            .action(OperationActionConstants.AUTH_LOGOUT)
            .businessType("AUTH")
            .request(request)
            .build());
        tokenSessionService.invalidate(token);
    }

    private boolean canLogin(SysUser user) {
        return user != null
            && !Integer.valueOf(1).equals(user.getDeletedFlag())
            && "active".equals(user.getStatus());
    }

    private String parseBearerToken(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(ErrorCodeConstants.UNAUTHORIZED, "未登录或登录已失效");
        }
        return authorization.substring(BEARER_PREFIX.length());
    }

}

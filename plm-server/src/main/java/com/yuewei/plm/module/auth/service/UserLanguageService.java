package com.yuewei.plm.module.auth.service;

import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserLanguageService {
    private final JdbcTemplate jdbc;
    private final PilotAccessService access;
    private final OperationLogService logs;

    public record Input(
        @NotNull @Pattern(regexp = "zh-CN|es") String language,
        @NotNull @Pattern(regexp = "zh-CN|es") String originalLanguage) {}
    public record Result(long userId, String preferredLanguage) {}

    @Transactional
    public Result save(Long requestedUserId, Input input, HttpServletRequest request) {
        var actor = access.actor();
        long id = requestedUserId == null ? actor.userId() : requestedUserId;
        if (requestedUserId != null) access.requireAdministrator();
        var rows = jdbc.queryForList(
            "select preferred_language from sys_user where user_id=? and deleted_flag=0 for update", id);
        if (rows.isEmpty()) throw new BusinessException(40401, "账号不存在");
        String before = rows.get(0).get("preferred_language").toString();
        if (!before.equals(input.originalLanguage()))
            throw new BusinessException(40901, "语言设置已变更，请刷新后重试");
        if (before.equals(input.language())) return new Result(id, before);
        jdbc.update("update sys_user set preferred_language=?,updated_at=now(),updated_by=? where user_id=?",
            input.language(), actor.username(), id);
        logs.logRequired(OperationLogCreateCommand.builder()
            .action("USER_LANGUAGE_UPDATE").businessType("USER").businessId(String.valueOf(id))
            .detailJson("{\"before\":\"" + before + "\",\"after\":\"" + input.language() + "\"}")
            .request(request).build(), actor);
        return new Result(id, input.language());
    }
}

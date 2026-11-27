package com.yuewei.plm.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordDTO(
    @NotBlank(message = "当前密码不能为空") @Size(max = 128) String currentPassword,
    @NotBlank(message = "新密码不能为空") @Size(min = 12, max = 72, message = "新密码须为12至72个字符") String newPassword
) {
}

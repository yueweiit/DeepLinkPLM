package com.yuewei.plm.module.auth.service;

import com.yuewei.plm.module.auth.dto.ChangePasswordDTO;
import com.yuewei.plm.module.auth.dto.LoginDTO;
import com.yuewei.plm.module.auth.vo.CurrentUserVO;
import com.yuewei.plm.module.auth.vo.LoginVO;
import jakarta.servlet.http.HttpServletRequest;

public interface AuthService {

    LoginVO login(LoginDTO loginDTO, HttpServletRequest request);

    CurrentUserVO profile();

    void changePassword(ChangePasswordDTO input, HttpServletRequest request);

    void logout(String authorization, HttpServletRequest request);
}

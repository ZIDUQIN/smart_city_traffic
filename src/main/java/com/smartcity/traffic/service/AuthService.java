package com.smartcity.traffic.service;

import com.smartcity.traffic.dto.LoginRequest;
import com.smartcity.traffic.dto.LoginResponse;
import com.smartcity.traffic.dto.RefreshRequest;
import com.smartcity.traffic.dto.RegisterRequest;
import com.smartcity.traffic.entity.SysUser;

/**
 * 认证相关业务接口。
 */
public interface AuthService {

    /** 账号密码登录，返回令牌 */
    LoginResponse login(LoginRequest request);

    /** 用户注册 */
    void register(RegisterRequest request);

    /** 凭刷新令牌换新令牌 */
    LoginResponse refresh(RefreshRequest request);

    /** 注销令牌 */
    void logout(String accessToken, String refreshToken);

    /** 获取当前登录用户 */
    SysUser getCurrentUser();
}

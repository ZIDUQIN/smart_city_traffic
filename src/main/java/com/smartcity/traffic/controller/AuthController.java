package com.smartcity.traffic.controller;

import com.smartcity.traffic.common.Result;
import com.smartcity.traffic.dto.LoginRequest;
import com.smartcity.traffic.dto.LoginResponse;
import com.smartcity.traffic.dto.RefreshRequest;
import com.smartcity.traffic.dto.RegisterRequest;
import com.smartcity.traffic.entity.SysUser;
import com.smartcity.traffic.service.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证相关 REST 接口（RESTful 风格，统一前缀 /api/auth）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 登录：账号密码换令牌 */
    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    /** 注册：新用户创建账号 */
    @PostMapping("/register")
    public Result<Void> register(@RequestBody RegisterRequest request) {
        authService.register(request);
        return Result.success();
    }

    /** 刷新：refreshToken 换新令牌 */
    @PostMapping("/refresh")
    public Result<LoginResponse> refresh(@RequestBody RefreshRequest request) {
        return Result.success(authService.refresh(request));
    }

    /** 退出：注销当前访问令牌及刷新令牌 */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @RequestBody(required = false) RefreshRequest request) {
        String accessToken = extractToken(authorization);
        String refreshToken = request == null ? null : request.getRefreshToken();
        authService.logout(accessToken, refreshToken);
        return Result.success();
    }

    /** 获取当前登录用户信息 */
    @GetMapping("/me")
    public Result<SysUser> me() {
        return Result.success(authService.getCurrentUser());
    }

    /** 从 Authorization 头提取 Bearer 令牌 */
    private String extractToken(String authorization) {
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}

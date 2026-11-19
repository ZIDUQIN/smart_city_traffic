package com.smartcity.traffic.service.impl;

import com.smartcity.traffic.common.SnowflakeIdGenerator;
import com.smartcity.traffic.dto.LoginRequest;
import com.smartcity.traffic.dto.LoginResponse;
import com.smartcity.traffic.dto.RefreshRequest;
import com.smartcity.traffic.dto.RegisterRequest;
import com.smartcity.traffic.entity.SysUser;
import com.smartcity.traffic.mapper.SysUserMapper;
import com.smartcity.traffic.security.RedisTokenStore;
import com.smartcity.traffic.service.AuthService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证业务实现。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final RedisTokenStore tokenStore;
    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public AuthServiceImpl(AuthenticationManager authenticationManager,
                           RedisTokenStore tokenStore,
                           SysUserMapper sysUserMapper,
                           PasswordEncoder passwordEncoder,
                           SnowflakeIdGenerator snowflakeIdGenerator) {
        this.authenticationManager = authenticationManager;
        this.tokenStore = tokenStore;
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        if (request.getUsername() == null || request.getPassword() == null
                || request.getUsername().isBlank() || request.getPassword().isBlank()) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }

        // 交给 Spring Security 完成密码校验（含 BCrypt 比对），失败会抛 BadCredentialsException
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

        // 校验通过，签发不透明令牌
        return tokenStore.storeTokens(authentication.getName());
    }

    @Override
    public void register(RegisterRequest request) {
        if (request.getUsername() == null || request.getPassword() == null
                || request.getUsername().isBlank() || request.getPassword().isBlank()) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }
        // 字段长度先于入库校验：数据库列有限长，超长直接截断会抛 500 并泄露 SQL 细节
        if (request.getUsername().length() < 3 || request.getUsername().length() > 32) {
            throw new IllegalArgumentException("用户名长度需在 3-32 个字符之间");
        }
        if (request.getPassword().length() < 6 || request.getPassword().length() > 64) {
            throw new IllegalArgumentException("密码长度需在 6-64 个字符之间");
        }
        if (request.getNickname() != null && request.getNickname().length() > 32) {
            throw new IllegalArgumentException("昵称最多 32 个字符");
        }
        if (sysUserMapper.countByUsername(request.getUsername()) > 0) {
            throw new IllegalArgumentException("用户名已存在");
        }

        SysUser user = new SysUser();
        user.setId(snowflakeIdGenerator.nextId());
        user.setUsername(request.getUsername());
        // 密码 BCrypt 加密后入库，绝不存明文
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setStatus(1);
        // 注册一律普通用户，管理角色只能由 DBA/初始化脚本授予
        user.setRole("USER");

        sysUserMapper.insert(user);
    }

    @Override
    public LoginResponse refresh(RefreshRequest request) {
        if (request.getRefreshToken() == null || request.getRefreshToken().isBlank()) {
            throw new IllegalArgumentException("refreshToken 不能为空");
        }
        LoginResponse response = tokenStore.refresh(request.getRefreshToken());
        if (response == null) {
            throw new IllegalArgumentException("refreshToken 无效或已过期，请重新登录");
        }
        return response;
    }

    @Override
    public void logout(String accessToken, String refreshToken) {
        if (accessToken != null && !accessToken.isBlank()) {
            tokenStore.removeAccessToken(accessToken);
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            tokenStore.removeRefreshToken(refreshToken);
        }
    }

    @Override
    public SysUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("当前无登录用户");
        }
        SysUser user = sysUserMapper.selectByUsername(authentication.getName());
        if (user != null) {
            // 密码不返回给前端
            user.setPassword(null);
        }
        return user;
    }
}

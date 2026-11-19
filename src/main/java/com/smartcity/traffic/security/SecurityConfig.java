package com.smartcity.traffic.security;

import com.smartcity.traffic.common.Result;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

/**
 * Spring Security 配置：无状态、Redis 不透明令牌、BCrypt。
 */
@Configuration
public class SecurityConfig {

    private final BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter,
                          ObjectMapper objectMapper) {
        this.bearerTokenAuthenticationFilter = bearerTokenAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 前后端分离、无状态，关闭 CSRF
                .csrf(AbstractHttpConfigurer::disable)
                // 不创建/使用 HttpSession
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 登录、注册、刷新接口匿名放行
                        .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/refresh").permitAll()
                        // 支付 / 退款渠道异步回调匿名放行，靠 HMAC 签名保护
                        .requestMatchers("/api/pay/notify", "/api/pay/refund/notify").permitAll()
                        // 前端静态资源放行
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/css/**", "/js/**").permitAll()
                        // 监控端点放行：本地排障要能不登录直接看。
                        // 真实生产不能这么开——指标会暴露调用量、错误率、连接数，
                        // 要么走独立管理端口，要么加 IP 白名单/独立口令
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/metrics/**").permitAll()
                        // 运维/调试端点只允许管理员：普通用户不能建实例、清实例、预热、注入渠道结果
                        .requestMatchers("/api/instances/**").hasRole("ADMIN")
                        .requestMatchers("/api/orders/preheat").hasRole("ADMIN")
                        .requestMatchers("/api/pay/mock/refund-result").hasRole("ADMIN")
                        // 其余接口一律需要认证
                        .anyRequest().authenticated())
                // 未认证、无权限均返回 JSON
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "未认证或登录已过期"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN, "没有访问权限")))
                // 注册令牌过滤器
                .addFilterBefore(bearerTokenAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** BCrypt 密码编码器 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 暴露 AuthenticationManager 供登录接口做账号密码校验 */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    /** 向响应写入统一 JSON 错误 */
    private void writeError(HttpServletResponse response, int httpStatus, String message) throws java.io.IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(httpStatus, message)));
    }
}

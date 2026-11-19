package com.smartcity.traffic.security;

import com.smartcity.traffic.dto.LoginResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的不透明令牌存储。
 * 访问令牌与刷新令牌均为随机 UUID，value 存用户名，各自带过期时间。
 */
@Component
public class RedisTokenStore {

    /** 访问令牌 key 前缀 */
    private static final String ACCESS_PREFIX = "auth:access:";

    /** 刷新令牌 key 前缀 */
    private static final String REFRESH_PREFIX = "auth:refresh:";

    /** 访问令牌有效期：2 小时 */
    private static final long ACCESS_TTL_SECONDS = 7200L;

    /** 刷新令牌有效期：7 天 */
    private static final long REFRESH_TTL_SECONDS = 604800L;

    private final StringRedisTemplate redisTemplate;

    public RedisTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 为指定用户签发一对新令牌并写入 Redis。
     */
    public LoginResponse storeTokens(String username) {
        String accessToken = randomToken();
        String refreshToken = randomToken();

        redisTemplate.opsForValue().set(ACCESS_PREFIX + accessToken, username,
                ACCESS_TTL_SECONDS, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(REFRESH_PREFIX + refreshToken, username,
                REFRESH_TTL_SECONDS, TimeUnit.SECONDS);

        return new LoginResponse(accessToken, refreshToken, ACCESS_TTL_SECONDS);
    }

    /**
     * 凭访问令牌解析用户名，无效或已过期返回 null。
     */
    public String getUsernameByAccessToken(String accessToken) {
        return redisTemplate.opsForValue().get(ACCESS_PREFIX + accessToken);
    }

    /**
     * 凭刷新令牌换取新的一对令牌（刷新令牌旋转：旧 refresh 作废）。
     *
     * @return 新令牌；刷新令牌无效返回 null
     */
    public LoginResponse refresh(String refreshToken) {
        String username = redisTemplate.opsForValue().get(REFRESH_PREFIX + refreshToken);
        if (username == null) {
            return null;
        }
        // 删除旧刷新令牌，实现一次性旋转
        redisTemplate.delete(REFRESH_PREFIX + refreshToken);
        return storeTokens(username);
    }

    /**
     * 注销访问令牌。
     */
    public void removeAccessToken(String accessToken) {
        redisTemplate.delete(ACCESS_PREFIX + accessToken);
    }

    /**
     * 注销刷新令牌。
     */
    public void removeRefreshToken(String refreshToken) {
        redisTemplate.delete(REFRESH_PREFIX + refreshToken);
    }

    /** 生成无横线的随机令牌 */
    private String randomToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

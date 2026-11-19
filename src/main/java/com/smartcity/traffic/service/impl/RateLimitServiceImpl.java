package com.smartcity.traffic.service.impl;

import com.smartcity.traffic.service.RateLimitService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 令牌桶限流实现：Redis Hash 存桶状态，Lua 原子完成 补令牌 → 判桶 → 扣令牌。
 *
 * 两层共用同一份 Lua 脚本，靠传入不同 key 与不同阈值区分：
 *   全局：rate:global:{trainNo}     容量 500、每秒补 500，保护系统容量
 *   单用户：rate:{userId}:{trainNo}  容量 3、每秒补 1，防单人刷单
 *
 * 用令牌桶而不是"数次数"的原因：秒杀是"瞬时突发 + 之后静默"，
 * 令牌桶允许桶容量内的突发，之后按固定速率匀速放行；状态只有 2 个字段，内存恒定。
 */
@Service
public class RateLimitServiceImpl implements RateLimitService {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> rateLimitScript;

    /** 单用户桶容量 */
    private final int capacity;

    /** 单用户每秒补充令牌数 */
    private final int refillPerSecond;

    /** 全局桶容量（按车次） */
    private final int globalCapacity;

    /** 全局每秒补充令牌数 */
    private final int globalRefillPerSecond;

    public RateLimitServiceImpl(StringRedisTemplate redisTemplate,
                                @Value("${rate-limit.capacity:3}") int capacity,
                                @Value("${rate-limit.refill-per-second:1}") int refillPerSecond,
                                @Value("${rate-limit.global.capacity:500}") int globalCapacity,
                                @Value("${rate-limit.global.refill-per-second:500}") int globalRefillPerSecond) {
        this.redisTemplate = redisTemplate;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.globalCapacity = globalCapacity;
        this.globalRefillPerSecond = globalRefillPerSecond;

        rateLimitScript = new DefaultRedisScript<>();
        rateLimitScript.setLocation(new ClassPathResource("lua/rate_limit.lua"));
        rateLimitScript.setResultType(Long.class);
    }

    @Override
    public boolean tryAcquireGlobal(String trainNo) {
        return acquire(globalKey(trainNo), globalCapacity, globalRefillPerSecond);
    }

    @Override
    public boolean tryAcquire(Long userId, String trainNo) {
        return acquire(rateKey(userId, trainNo), capacity, refillPerSecond);
    }

    /**
     * 令牌桶统一入口：两层共用，只是 key 与阈值不同。
     * ttl = 桶从空回满所需时间 + 60 秒冗余，保证 key 最终被回收，不在 Redis 里无限堆积。
     */
    private boolean acquire(String key, int bucketCapacity, int refillRatePerSecond) {
        long fullRefillMs = bucketCapacity * 1000L / Math.max(1, refillRatePerSecond);
        long ttlMs = fullRefillMs + 60_000L;

        Long result = redisTemplate.execute(rateLimitScript,
                List.of(key),
                String.valueOf(bucketCapacity),
                String.valueOf(refillRatePerSecond),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(ttlMs));

        // Lua 返回 1 放行，0 拒绝；异常直接冒泡（fail-close，见文档第七节）
        return result != null && result == 1L;
    }

    /** 全局 key：rate:global:{trainNo} */
    private String globalKey(String trainNo) {
        return "rate:global:{" + trainNo + "}";
    }

    /** 单用户 key：rate:{userId}:{trainNo} */
    private String rateKey(Long userId, String trainNo) {
        return "rate:{" + userId + "}:" + trainNo;
    }
}

package com.smartcity.traffic.voice.service;

import com.smartcity.traffic.voice.config.VoiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语音接口限流：分钟额度 + 日额度，计数放 Redis。
 *
 * <p>为什么不用 JVM 内存：内存计数是"每台机器各自算"，双实例部署下真实额度会变成两倍，
 * 而外部 ASR 是按调用次数真金白银计费的。Redis 计数让所有实例看到同一份账。</p>
 *
 * <p>为什么要日额度：分钟限流只防"一个人猛点"，挡不住"一百万个人各点一次"。
 * 日额度是全局的总闸，到量就停，直接降级到打字/句式按钮，不再往外付钱。</p>
 *
 * <p>为什么失败要退还：用户说一句被系统判定"没听清"不是他的错，
 * 不该扣他的分钟额度；只有真正走完一次识别/解析才计数。</p>
 *
 * <p>Redis 不可用时降级到本机内存计数：语音是辅助功能，
 * 不能因为限流器连不上 Redis 就让整个对话报错。降级后多实例额度会变宽，但功能仍在。</p>
 */
@Component
public class VoiceRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(VoiceRateLimiter.class);

    private static final long WINDOW_MS = 60_000L;

    /** 分钟 key 的过期时间：多给一个窗口的冗余，避免边界上刚建就被回收 */
    private static final long MINUTE_KEY_TTL_SECONDS = 120L;

    private static final DateTimeFormatter MINUTE_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final VoiceProperties properties;

    private final StringRedisTemplate redisTemplate;

    private final DefaultRedisScript<Long> limitScript;

    /** Redis 不可用时的兜底；只在单实例内准确 */
    private final ConcurrentHashMap<Long, Deque<Long>> fallbackBuckets = new ConcurrentHashMap<>();

    private final AtomicLong fallbackDaily = new AtomicLong();

    private volatile LocalDate fallbackDay = LocalDate.now();

    public VoiceRateLimiter(VoiceProperties properties, StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.limitScript = new DefaultRedisScript<>();
        this.limitScript.setLocation(new ClassPathResource("lua/voice_limit.lua"));
        this.limitScript.setResultType(Long.class);
    }

    /**
     * 申请一次语音额度。
     *
     * @return 凭据；返回 null 表示被限流（分钟额度用完或日额度用尽）
     */
    public Ticket tryAcquire(Long userId) {
        if (userId == null) {
            return null;
        }
        int limit = Math.max(1, properties.getRateLimit().getPerUserPerMinute());
        long dailyQuota = properties.getRateLimit().getDailyQuota();
        String minuteKey = minuteKey(userId);
        String dayKey = dayKey();

        try {
            // 先占日额度：一旦放行就真的会调外部 ASR，必须先确认今天还有余量
            Long day = execute(dayKey, "acquire", dailyQuota, dayTtlSeconds());
            if (day == null || day < 0) {
                log.warn("语音日额度已用尽：quota={}", dailyQuota);
                return null;
            }
            warnIfNeeded(day, dailyQuota);

            Long minute = execute(minuteKey, "acquire", limit, MINUTE_KEY_TTL_SECONDS);
            if (minute == null || minute < 0) {
                // 分钟额度超了：把刚占的日额度退回去，不然一次被拒也扣了一天的钱
                execute(dayKey, "release", 0, 0);
                return null;
            }
            return new Ticket(userId, minuteKey, dayKey, true);
        } catch (Exception e) {
            log.warn("语音限流 Redis 不可用，降级到本机计数：{}", e.getMessage());
            return fallbackAcquire(userId, limit, dailyQuota);
        }
    }

    /**
     * 退还额度：识别失败、没听清、格式不对时调用，不占用户额度。
     */
    public void release(Ticket ticket) {
        if (ticket == null) {
            return;
        }
        if (!ticket.isRedis()) {
            fallbackRelease(ticket.getUserId());
            return;
        }
        try {
            execute(ticket.getMinuteKey(), "release", 0, 0);
            execute(ticket.getDayKey(), "release", 0, 0);
        } catch (Exception e) {
            // 退还是"尽量"，失败就少退一次；不能因为退不了而把用户请求变错
            log.debug("语音额度退还失败：{}", e.getMessage());
        }
    }

    // ---------------- Redis ----------------

    private Long execute(String key, String mode, long limit, long ttlSeconds) {
        return redisTemplate.execute(limitScript, List.of(key),
                mode, String.valueOf(limit), String.valueOf(ttlSeconds));
    }

    /** 分钟窗口 key：voice:rate:{userId}:{yyyyMMddHHmm} */
    private String minuteKey(Long userId) {
        return "voice:rate:{" + userId + "}:" + LocalDateTime.now().format(MINUTE_FMT);
    }

    /** 日额度 key：voice:quota:day:{yyyyMMdd}，全局共用一个 */
    private String dayKey() {
        return "voice:quota:day:" + LocalDate.now().format(DAY_FMT);
    }

    /** 日 key 活到后天，跨零点自然换新 key，不用定时清理 */
    private long dayTtlSeconds() {
        return 2 * 24 * 3600L;
    }

    private void warnIfNeeded(long used, long quota) {
        double ratio = properties.getRateLimit().getDailyAlertRatio();
        if (quota <= 0 || ratio <= 0) {
            return;
        }
        long threshold = (long) Math.floor(quota * ratio);
        if (threshold > 0 && used == threshold) {
            log.warn("语音日额度已用 {} / {}（达到告警线 {}%），注意补充额度或降级",
                    used, quota, Math.round(ratio * 100));
        }
    }

    // ---------------- 兜底 ----------------

    private Ticket fallbackAcquire(Long userId, int limit, long dailyQuota) {
        // 日额度兜底：内存里跨天自动清零
        LocalDate today = LocalDate.now();
        if (!today.equals(fallbackDay)) {
            fallbackDay = today;
            fallbackDaily.set(0);
        }
        if (dailyQuota > 0 && fallbackDaily.incrementAndGet() > dailyQuota) {
            fallbackDaily.decrementAndGet();
            log.warn("语音日额度已用尽（本机兜底计数）");
            return null;
        }

        long now = System.currentTimeMillis();
        Deque<Long> window = fallbackBuckets.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MS) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                fallbackDaily.decrementAndGet();
                return null;
            }
            window.addLast(now);
        }
        return new Ticket(userId, null, null, false);
    }

    private void fallbackRelease(Long userId) {
        Deque<Long> window = fallbackBuckets.get(userId);
        if (window != null) {
            synchronized (window) {
                window.pollLast();
            }
        }
        if (fallbackDaily.get() > 0) {
            fallbackDaily.decrementAndGet();
        }
    }

    /** 一次放行的凭据：失败时凭它把额度退回去 */
    public static final class Ticket {

        private final Long userId;

        private final String minuteKey;

        private final String dayKey;

        private final boolean redis;

        Ticket(Long userId, String minuteKey, String dayKey, boolean redis) {
            this.userId = userId;
            this.minuteKey = minuteKey;
            this.dayKey = dayKey;
            this.redis = redis;
        }

        public Long getUserId() {
            return userId;
        }

        public String getMinuteKey() {
            return minuteKey;
        }

        public String getDayKey() {
            return dayKey;
        }

        public boolean isRedis() {
            return redis;
        }
    }
}

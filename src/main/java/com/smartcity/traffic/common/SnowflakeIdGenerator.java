package com.smartcity.traffic.common;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 纯手写雪花算法 ID 生成器（Spring 单例，线程安全）。
 *
 * ID 结构（共 64 bit）：
 *  1 bit 符号位（固定 0）| 41 bit 毫秒级时间戳（相对自定义纪元）| 10 bit workerId | 12 bit 序列号
 *
 * workerId：服务启动时通过 Redis + Lua 原子抢占，范围 0~1023，集群内唯一；
 *           每个 worker key 带 TTL，实例宕机后由 Redis 自动回收，存活期间由心跳线程定期续期。
 * 时间回拨：检测到系统时间小于上次生成时间戳，直接抛异常，禁止生成 ID。
 * 序列号：  范围 0~4095，同一毫秒耗尽后自旋阻塞等待到下一毫秒，序列号重置后继续，不抛异常。
 */
@Component
public class SnowflakeIdGenerator {

    // ============================== 雪花位分配常量 ==============================

    /** 自定义纪元：2024-01-01 00:00:00（毫秒），41 bit 时间戳相对它计算 */
    private static final long EPOCH = 1704038400000L;

    /** workerId 位数：10 bit */
    private static final long WORKER_ID_BITS = 10L;

    /** 序列号位数：12 bit */
    private static final long SEQUENCE_BITS = 12L;

    /** workerId 最大值：1023 */
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);

    /** 序列号掩码：4095，用于序列号循环归零判断 */
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    /** workerId 左移位数 = 序列号位数 */
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;

    /** 时间戳左移位数 = 序列号位数 + workerId 位数 = 22 */
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    // ============================== Redis 相关常量 ==============================

    /**
     * worker key 前缀，完整 key 为 prefix + workerId。
     *
     * <p>必须带 hash tag {@code {worker}}：抢占脚本会在一次调用里探测
     * snowflake:{worker}:0 到 snowflake:{worker}:1023 全部 key，
     * 只有这些 key 落在同一个 slot，Redis Cluster 才允许脚本操作它们。
     * 去掉 hash tag 会直接报 CROSSSLOT / 跨槽访问。</p>
     */
    private static final String WORKER_KEY_PREFIX = "snowflake:{worker}:";

    /** worker key 过期时间（秒），宕机后 30 秒自动回收 */
    private static final long TTL_SECONDS = 30L;

    /** 心跳续期间隔（秒），小于 TTL，保证存活实例不会被回收 */
    private static final long RENEW_INTERVAL_SECONDS = 10L;

    /** workerId 抢占脚本（classpath 下独立 lua 文件） */
    private static final DefaultRedisScript<Long> REGISTER_SCRIPT;

    /** 续期脚本：仅当 key 的 value 仍是本实例标识时才续期，避免误续别人的 key */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('GET', KEYS[1]) " +
            "if current == ARGV[1] then return redis.call('EXPIRE', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    /** 释放脚本：仅当 key 的 value 仍是本实例标识时才删除，避免误删别人的 key */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('GET', KEYS[1]) " +
            "if current == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    static {
        REGISTER_SCRIPT = new DefaultRedisScript<>();
        REGISTER_SCRIPT.setLocation(new ClassPathResource("scripts/snowflake_worker_register.lua"));
        REGISTER_SCRIPT.setResultType(Long.class);
    }

    // ============================== 运行时状态 ==============================

    private final StringRedisTemplate redisTemplate;

    /** 本实例唯一标识：主机名 + UUID，写入 worker key 的 value，用于安全续期/释放 */
    private final String instanceId = buildInstanceId();

    /** 本实例分配到的 workerId */
    private long workerId;

    /** 当前毫秒内已使用的序列号 */
    private long sequence = 0L;

    /** 上一次生成 ID 的时间戳 */
    private long lastTimestamp = -1L;

    /** 心跳续期守护线程池 */
    private ScheduledExecutorService heartbeatExecutor;

    public SnowflakeIdGenerator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 启动时原子抢占 workerId，并启动心跳续期。
     */
    @PostConstruct
    public void init() {
        Long id = redisTemplate.execute(
                REGISTER_SCRIPT,
                Collections.singletonList(WORKER_KEY_PREFIX),
                instanceId,
                String.valueOf(TTL_SECONDS));

        if (id == null || id < 0) {
            throw new IllegalStateException("雪花 workerId 已全部被占用（0~1023），无法为当前实例分配");
        }
        this.workerId = id;

        // 守护线程定期给自己的 worker key 续期；设为守护线程，不阻止 JVM 退出
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snowflake-worker-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(this::renewWorkerId,
                RENEW_INTERVAL_SECONDS, RENEW_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 容器关闭时：停止心跳并主动释放 workerId，便于立刻被其他实例复用。
     */
    @PreDestroy
    public void destroy() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        try {
            redisTemplate.execute(RELEASE_SCRIPT,
                    Collections.singletonList(WORKER_KEY_PREFIX + workerId),
                    instanceId, String.valueOf(TTL_SECONDS));
        } catch (Exception ignored) {
            // 关闭流程中 Redis 异常不影响退出；即使释放失败，TTL 也会兜底回收
        }
    }

    /**
     * 生成全局唯一 ID。方法加 synchronized 保证多线程下序列号与时间戳状态安全。
     *
     * @return 64 bit 雪花 ID
     * @throws IllegalStateException 检测到系统时间回拨时抛出
     */
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        // 时间回拨检测：当前时间小于上次生成时间，直接拒绝生成
        if (timestamp < lastTimestamp) {
            throw new IllegalStateException(
                    "检测到系统时间回拨，拒绝生成 ID。当前时间戳=" + timestamp + "，上次时间戳=" + lastTimestamp
                            + "，回拨毫秒数=" + (lastTimestamp - timestamp));
        }

        if (timestamp == lastTimestamp) {
            // 同一毫秒内序列号自增，通过掩码实现 0~4095 循环
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 本毫秒 4096 个序列号已耗尽，自旋阻塞等待进入下一毫秒，不抛异常
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            // 进入新的毫秒，序列号重置
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        // 拼接：时间戳 | workerId | 序列号
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /**
     * 自旋等待，直到当前时间严格大于上次时间戳。等待期间若发生时间回拨同样抛异常。
     */
    private long waitNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            if (timestamp < lastTimestamp) {
                throw new IllegalStateException(
                        "等待下一毫秒期间检测到系统时间回拨，拒绝生成 ID。当前=" + timestamp + "，上次=" + lastTimestamp);
            }
            // 自旋提示：降低 CPU 功耗，减少对超线程兄弟核的干扰，不引入线程切换
            Thread.onSpinWait();
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    /**
     * 心跳续期：仅当 worker key 仍属于本实例时刷新 TTL。
     */
    private void renewWorkerId() {
        try {
            redisTemplate.execute(RENEW_SCRIPT,
                    Collections.singletonList(WORKER_KEY_PREFIX + workerId),
                    instanceId, String.valueOf(TTL_SECONDS));
        } catch (Exception ignored) {
            // 单次续期失败不抛出，下个周期继续尝试；连续失败超过 TTL 才会被回收
        }
    }

    public long getWorkerId() {
        return workerId;
    }

    /** 生成实例标识：主机名 + 随机 UUID，保证不同实例不冲突 */
    private static String buildInstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        return host + ":" + UUID.randomUUID().toString().replace("-", "");
    }
}

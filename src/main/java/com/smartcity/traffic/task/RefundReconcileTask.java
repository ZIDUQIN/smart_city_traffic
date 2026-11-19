package com.smartcity.traffic.task;

import com.smartcity.traffic.common.AlarmService;
import com.smartcity.traffic.common.BusinessMetrics;
import com.smartcity.traffic.common.InstanceId;
import com.smartcity.traffic.service.RefundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 退款对账任务（每分钟一次）。
 *
 * 与 DeductReconcileTask 的差异：
 * - 扫的是 refund_record 而不是 order：退款主体是退款流水，order 只是状态镜像，
 *   扫 order 拿不到 channel_refund_no，会把已受理的单子当成没受理而重复重发。
 * - 锁 key 独立，两个任务互不阻塞。
 */
@Component
public class RefundReconcileTask {

    private static final Logger log = LoggerFactory.getLogger(RefundReconcileTask.class);

    /** 分布式锁 key，TTL 55 秒（每分钟一次，锁自然过期） */
    private static final String LOCK_KEY = "refund:reconcile:lock";

    private final StringRedisTemplate redisTemplate;
    private final RefundService refundService;
    private final AlarmService alarmService;
    private final BusinessMetrics metrics;
    private final InstanceId instanceId;

    public RefundReconcileTask(StringRedisTemplate redisTemplate,
                               RefundService refundService,
                               AlarmService alarmService,
                               BusinessMetrics metrics,
                               InstanceId instanceId) {
        this.redisTemplate = redisTemplate;
        this.refundService = refundService;
        this.alarmService = alarmService;
        this.metrics = metrics;
        this.instanceId = instanceId;
    }

    @Scheduled(cron = "0 * * * * ?")
    public void reconcile() {
        // 分布式锁：多实例只有一个执行。value 用实例标识，Redis 里能直接看出是谁抢到的
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, instanceId.get(), Duration.ofSeconds(55));
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        try {
            int handled = refundService.reconcileOnce();
            metrics.reconcileHandled("refund", handled);
            if (handled > 0) {
                log.info("退款对账完成，本轮推进 {} 条，持有实例={}", handled, instanceId.get());
            }
        } catch (Exception e) {
            // 整轮异常不能沉默：否则对账形同虚设
            alarmService.send("退款对账任务整体异常", e);
        }
    }
}

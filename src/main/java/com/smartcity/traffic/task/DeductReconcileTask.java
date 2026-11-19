package com.smartcity.traffic.task;

import com.smartcity.traffic.common.AlarmService;
import com.smartcity.traffic.common.BusinessMetrics;
import com.smartcity.traffic.common.InstanceId;
import com.smartcity.traffic.entity.DeductRecord;
import com.smartcity.traffic.entity.OrderRecord;
import com.smartcity.traffic.mapper.OrderMapper;
import com.smartcity.traffic.service.DeductRecordService;
import com.smartcity.traffic.service.OrderService;
import com.smartcity.traffic.service.RedisLuaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 定时对账任务（每分钟一次）。
 *
 * 1. 分布式锁（SETNX + 过期），多实例只有一个执行
 * 2. 只扫中间态（扣减中/已扣减）且超过 5 分钟的记录
 * 3. 先 CAS 抢占改"补偿中"，失败立即跳过
 * 4. 按判定矩阵处理，终态一律 CAS 写
 * 5. 单条异常不中断，记录 + 告警继续
 */
@Component
public class DeductReconcileTask {

    private static final Logger log = LoggerFactory.getLogger(DeductReconcileTask.class);

    /** 分布式锁 key，TTL 55 秒（任务每分钟一次，锁会自然过期） */
    private static final String LOCK_KEY = "reconcile:lock";

    /** 中间态判定阈值：5 分钟 */
    private static final int STALE_MINUTES = 5;

    /** 每轮最多处理 200 条 */
    private static final int BATCH_LIMIT = 200;

    private final StringRedisTemplate redisTemplate;
    private final DeductRecordService deductRecordService;
    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final RedisLuaService redisLuaService;
    private final AlarmService alarmService;
    private final BusinessMetrics metrics;
    private final InstanceId instanceId;

    public DeductReconcileTask(StringRedisTemplate redisTemplate,
                               DeductRecordService deductRecordService,
                               OrderMapper orderMapper,
                               OrderService orderService,
                               RedisLuaService redisLuaService,
                               AlarmService alarmService,
                               BusinessMetrics metrics,
                               InstanceId instanceId) {
        this.redisTemplate = redisTemplate;
        this.deductRecordService = deductRecordService;
        this.orderMapper = orderMapper;
        this.orderService = orderService;
        this.redisLuaService = redisLuaService;
        this.alarmService = alarmService;
        this.metrics = metrics;
        this.instanceId = instanceId;
    }

    @Scheduled(cron = "0 * * * * ?")
    public void reconcile() {
        // ---------- 分布式锁：抢不到直接结束。value 用实例标识，便于看出谁在执行 ----------
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                LOCK_KEY, instanceId.get(), Duration.ofSeconds(55));
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }

        try {
            List<DeductRecord> records =
                    deductRecordService.listIntermediate(STALE_MINUTES, BATCH_LIMIT);
            if (records.isEmpty()) {
                return;
            }
            log.info("扣减对账开始，待处理 {} 条，持有实例={}", records.size(), instanceId.get());

            int handled = 0;
            for (DeductRecord r : records) {
                try {
                    handleOne(r);
                    handled++;
                } catch (Exception e) {
                    // 单条异常不中断整个任务
                    log.error("对账单条处理失败 orderNo={}", r.getOrderNo(), e);
                    alarmService.send("对账处理失败 orderNo=" + r.getOrderNo(), e);
                }
            }
            metrics.reconcileHandled("deduct", handled);
        } catch (Exception e) {
            alarmService.send("扣减对账任务整体异常", e);
        }
        // 锁只在 TTL 到期时释放，不主动 delete。
        // 主动删除的隐患：若本轮执行超过 55 秒，锁已过期并被另一实例持有，
        // 此时 delete 删掉的是别人的锁，会导致下一轮出现两个实例并发执行。
        // 本任务每分钟才触发一次，靠 TTL 多占几十秒没有任何实际代价。
    }

    /** 处理单条记录：抢占 → 矩阵判定 → 终态 CAS */
    private void handleOne(DeductRecord r) {
        String orderNo = r.getOrderNo();
        String oldStatus = r.getStatus();

        // 第一步：CAS 抢占改补偿中，失败说明正常流程已抢先改走，立即跳过
        int claimed = deductRecordService.casUpdate(orderNo,
                List.of(DeductRecord.STATUS_DEDUCTING, DeductRecord.STATUS_DEDUCTED),
                DeductRecord.STATUS_COMPENSATING, null);
        if (claimed == 0) {
            return;
        }

        OrderRecord order = orderMapper.selectByOrderNo(orderNo);

        if (order == null) {
            handleNoOrder(r, oldStatus);
            return;
        }

        switch (order.getStatus()) {
            case OrderRecord.STATUS_CONFIRMED -> {
                // 已确认订单：只补流水，禁止动库存
                deductRecordService.casUpdate(orderNo,
                        List.of(DeductRecord.STATUS_COMPENSATING),
                        DeductRecord.STATUS_CREATED, null);
                log.info("对账：订单已确认，只补流水状态 orderNo={}", orderNo);
            }
            case OrderRecord.STATUS_PENDING -> {
                // 待确认订单：取消 + 回补（按流水起讫区段）→ 已取消
                orderService.cancelOrder(orderNo);
                redisLuaService.rollback(r.resolveInstanceNo(), r.getSeatNo(),
                        r.getPassengerId(), orderNo, segmentsOf(r));
                deductRecordService.casUpdate(orderNo,
                        List.of(DeductRecord.STATUS_COMPENSATING),
                        DeductRecord.STATUS_CANCELED, "对账：待确认订单超时取消");
                log.warn("对账：待确认订单取消并回补 orderNo={}", orderNo);
            }
            case OrderRecord.STATUS_CANCELED -> {
                // 订单已取消：确保回补（幂等），流水置已取消
                redisLuaService.rollback(r.resolveInstanceNo(), r.getSeatNo(),
                        r.getPassengerId(), orderNo, segmentsOf(r));
                deductRecordService.casUpdate(orderNo,
                        List.of(DeductRecord.STATUS_COMPENSATING),
                        DeductRecord.STATUS_CANCELED, "对账：订单已取消");
            }
            default -> throw new IllegalStateException("未知订单状态：" + order.getStatus());
        }
    }

    /** 无订单分支（原状态 扣减中/已扣减） */
    private void handleNoOrder(DeductRecord r, String oldStatus) {
        String orderNo = r.getOrderNo();

        if (DeductRecord.STATUS_DEDUCTING.equals(oldStatus)) {
            // 扣减中：先看 Redis 里到底扣没扣
            boolean member = redisLuaService.isDeducted(
                    r.resolveInstanceNo(), r.getSeatNo(), orderNo);
            if (!member) {
                // 没扣过 → 不回补，直接已取消
                deductRecordService.casUpdate(orderNo,
                        List.of(DeductRecord.STATUS_COMPENSATING),
                        DeductRecord.STATUS_CANCELED, "对账：Redis未扣，无订单");
                return;
            }
        }

        // 已扣减（或扣减中但确认已扣）：回补 → 已补偿
        redisLuaService.rollback(r.resolveInstanceNo(), r.getSeatNo(),
                r.getPassengerId(), orderNo, segmentsOf(r));
        deductRecordService.casUpdate(orderNo,
                List.of(DeductRecord.STATUS_COMPENSATING),
                DeductRecord.STATUS_COMPENSATED, "对账：无订单，已回补");
        log.warn("对账：无订单，已回补 Redis orderNo={}", orderNo);
    }

    /** 从流水起讫站序算区段数组：fromSeq..toSeq-1，与扣减时完全一致 */
    private List<Integer> segmentsOf(DeductRecord r) {
        List<Integer> segments = new ArrayList<>();
        for (int i = r.getFromSeq(); i < r.getToSeq(); i++) {
            segments.add(i);
        }
        return segments;
    }
}

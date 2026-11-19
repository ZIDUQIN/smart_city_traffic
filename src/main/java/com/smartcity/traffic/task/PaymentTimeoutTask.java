package com.smartcity.traffic.task;

import com.smartcity.traffic.common.AlarmService;
import com.smartcity.traffic.common.BusinessMetrics;
import com.smartcity.traffic.common.InstanceId;
import com.smartcity.traffic.entity.OrderRecord;
import com.smartcity.traffic.entity.PaymentRecord;
import com.smartcity.traffic.mapper.OrderMapper;
import com.smartcity.traffic.mapper.PaymentRecordMapper;
import com.smartcity.traffic.service.RedisLuaService;
import com.smartcity.traffic.service.StockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 支付超时关单定时任务（每分钟一次）。
 *
 * 1. 分布式锁，多实例只有一个执行
 * 2. 扫描已确认且超支付时限的订单
 * 3. 关单前先查是否已支付成功（关单前查单），已成功跳过
 * 4. 取消订单 → 回补 Redis/DB → 关闭待支付流水
 * 5. 单条异常不中断，告警继续
 */
@Component
public class PaymentTimeoutTask {

    private static final Logger log = LoggerFactory.getLogger(PaymentTimeoutTask.class);

    /** 分布式锁 key，TTL 55 秒 */
    private static final String LOCK_KEY = "payment:timeout:lock";

    /** 每轮最多处理 200 单 */
    private static final int BATCH_LIMIT = 200;

    private final StringRedisTemplate redisTemplate;
    private final OrderMapper orderMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final RedisLuaService redisLuaService;
    private final StockService stockService;
    private final AlarmService alarmService;
    private final BusinessMetrics metrics;
    private final InstanceId instanceId;
    private final int timeoutSeconds;

    public PaymentTimeoutTask(StringRedisTemplate redisTemplate,
                              OrderMapper orderMapper,
                              PaymentRecordMapper paymentRecordMapper,
                              RedisLuaService redisLuaService,
                              StockService stockService,
                              AlarmService alarmService,
                              BusinessMetrics metrics,
                              InstanceId instanceId,
                              @Value("${pay.mock.timeout-seconds}") int timeoutSeconds) {
        this.redisTemplate = redisTemplate;
        this.orderMapper = orderMapper;
        this.paymentRecordMapper = paymentRecordMapper;
        this.redisLuaService = redisLuaService;
        this.stockService = stockService;
        this.alarmService = alarmService;
        this.metrics = metrics;
        this.instanceId = instanceId;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Scheduled(cron = "0 * * * * ?")
    public void closeTimeoutOrders() {
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                LOCK_KEY, instanceId.get(), Duration.ofSeconds(55));
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }

        try {
            List<OrderRecord> orders =
                    orderMapper.listTimeoutConfirmed(timeoutSeconds, BATCH_LIMIT);
            if (!orders.isEmpty()) {
                log.info("超时关单开始，待处理 {} 单，持有实例={}", orders.size(), instanceId.get());
            }
            int handled = 0;
            for (OrderRecord order : orders) {
                try {
                    handleOne(order);
                    handled++;
                } catch (Exception e) {
                    log.error("超时关单处理失败 orderNo={}", order.getOrderNo(), e);
                    alarmService.send("超时关单失败 orderNo=" + order.getOrderNo(), e);
                }
            }
            metrics.reconcileHandled("payment-timeout", handled);
        } catch (Exception e) {
            alarmService.send("超时关单任务整体异常", e);
        }
        // 锁只在 TTL 到期时释放，不主动 delete。
        // 主动删除的隐患：若本轮处理 200 单超过 55 秒，锁已过期并被另一实例持有，
        // 此时 delete 删掉的是别人的锁，会让下一轮出现两个实例同时关单、重复回补库存。
        // 本任务每分钟才触发一次，靠 TTL 多占几十秒没有实际代价。
    }

    /** 处理单订单：关单前查单 → 取消 → 回补 → 关流水 */
    private void handleOne(OrderRecord order) {
        String orderNo = order.getOrderNo();

        // 关单前查单：已支付成功则跳过，不能误关
        if (paymentRecordMapper.selectSuccessByOrderNo(orderNo) != null) {
            return;
        }

        // 条件更新 已确认 → 已取消
        int rows = orderMapper.casUpdateStatus(orderNo,
                OrderRecord.STATUS_CONFIRMED, OrderRecord.STATUS_CANCELED);
        if (rows != 1) {
            // 状态已被其他流程改变，跳过
            return;
        }

        List<Integer> segments = segmentsOf(order);
        // 回补 Redis（rollback.lua 幂等，含释放乘车人占位）
        redisLuaService.rollback(order.resolveInstanceNo(), order.getSeatNo(),
                order.getPassengerId(), orderNo, segments);
        // 回补 DB 真实库存：带幂等 + 容量两道闸。
        // 被容量闸拦下会抛异常，由 closeTimeoutOrders 外层捕获并告警；
        // 此时订单已取消、占位已释放，只是 DB 少补一张，交给巡检和对账兜。
        stockService.restoreRealStock(orderNo, order.resolveInstanceNo(),
                order.getSeatNo(), segments);

        // 关闭该订单全部待支付流水
        for (PaymentRecord waiting : paymentRecordMapper.selectWaitingByOrderNo(orderNo)) {
            paymentRecordMapper.casClose(waiting.getPaymentNo());
        }
        log.warn("支付超时关单并回补 orderNo={}", orderNo);
    }

    /** 区段数组：fromSeq..toSeq-1，与下单扣减一致 */
    private List<Integer> segmentsOf(OrderRecord order) {
        List<Integer> segments = new ArrayList<>();
        for (int i = order.getFromSeq(); i < order.getToSeq(); i++) {
            segments.add(i);
        }
        return segments;
    }
}

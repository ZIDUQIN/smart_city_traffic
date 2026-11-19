package com.smartcity.traffic.task;

import com.smartcity.traffic.common.AlarmService;
import com.smartcity.traffic.common.BusinessMetrics;
import com.smartcity.traffic.common.InstanceId;
import com.smartcity.traffic.mapper.StockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 库存巡检（每小时整点一次，多实例靠分布式锁互斥）。
 *
 * <p>查两种毛病：</p>
 * <ol>
 *   <li>越界：remain 超过该段总容量——说明回补多补过；</li>
 *   <li>漂移：remain 不等于「总容量 − 占用订单数」——这是独立基准，
 *       防的是 Redis 和 DB 一起错、单看两边对账看不出来的情况。</li>
 * </ol>
 *
 * <p>只扫近两天动过的行：全表扫太重，而长期没动过的旧实例本来就不会有新误差。</p>
 */
@Component
public class StockDriftCheckTask {

    private static final Logger log = LoggerFactory.getLogger(StockDriftCheckTask.class);

    /** 分布式锁 key，TTL 55 秒 */
    private static final String LOCK_KEY = "stock:drift:lock";

    /** 告警里最多列几条明细，剩下的只报数量，免得一条日志刷屏 */
    private static final int DETAIL_LIMIT = 5;

    private final StringRedisTemplate redisTemplate;
    private final StockMapper stockMapper;
    private final AlarmService alarmService;
    private final BusinessMetrics metrics;
    private final InstanceId instanceId;

    public StockDriftCheckTask(StringRedisTemplate redisTemplate,
                               StockMapper stockMapper,
                               AlarmService alarmService,
                               BusinessMetrics metrics,
                               InstanceId instanceId) {
        this.redisTemplate = redisTemplate;
        this.stockMapper = stockMapper;
        this.alarmService = alarmService;
        this.metrics = metrics;
        this.instanceId = instanceId;
    }

    @Scheduled(cron = "0 0 * * * ?")
    public void check() {
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                LOCK_KEY, instanceId.get(), Duration.ofSeconds(55));
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }

        try {
            List<Map<String, Object>> over = stockMapper.selectOverCapacity();
            List<Map<String, Object>> drift = stockMapper.selectRemainDrift();

            if (!over.isEmpty()) {
                alarmService.send("库存越界 " + over.size() + " 行（remain 超过总容量）：" + brief(over));
            }
            if (!drift.isEmpty()) {
                alarmService.send("库存漂移 " + drift.size() + " 行（remain 与「总容量−占用订单数」不符）："
                        + brief(drift));
            }
            metrics.reconcileHandled("stock-drift", over.size() + drift.size());

            if (over.isEmpty() && drift.isEmpty()) {
                log.info("库存巡检通过：近两天变动的库存行没有越界也没有漂移");
            }
        } catch (Exception e) {
            alarmService.send("库存巡检任务异常", e);
        }
    }

    /** 拼一条可读的明细，最多 DETAIL_LIMIT 条 */
    private String brief(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size() && i < DETAIL_LIMIT; i++) {
            Map<String, Object> r = rows.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append(r.get("instanceNo")).append('/').append(r.get("seatNo"))
                    .append("/段").append(r.get("segmentNo"))
                    .append(" 实际=").append(r.get("remain"));
            if (r.get("capacity") != null) {
                sb.append(" 容量=").append(r.get("capacity"));
            }
            if (r.get("expectRemain") != null) {
                sb.append(" 应为=").append(r.get("expectRemain"));
            }
        }
        if (rows.size() > DETAIL_LIMIT) {
            sb.append("；等共 ").append(rows.size()).append(" 行");
        }
        return sb.toString();
    }
}

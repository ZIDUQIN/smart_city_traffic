package com.smartcity.traffic.task;

import com.smartcity.traffic.common.AlarmService;
import com.smartcity.traffic.service.InstanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 班次实例滚动窗口任务。
 *
 * <p>补齐每分钟跑一次：只补缺的日期，重复执行无副作用。
 * 过期清理每天凌晨跑一次，运营日期早于 today - delayDays 的实例才删。</p>
 */
@Component
public class InstanceRollingTask {

    private static final Logger log = LoggerFactory.getLogger(InstanceRollingTask.class);

    private final InstanceService instanceService;
    private final AlarmService alarmService;

    /** 窗口天数，默认 7（含今天） */
    @Value("${traffic.instance.window-days:7}")
    private int windowDays;

    /** 过期实例延迟清理天数，默认 2 */
    @Value("${traffic.instance.cleanup-delay-days:2}")
    private int cleanupDelayDays;

    public InstanceRollingTask(InstanceService instanceService, AlarmService alarmService) {
        this.instanceService = instanceService;
        this.alarmService = alarmService;
    }

    /** 每分钟补齐：幂等，缺哪天补哪天 */
    @Scheduled(cron = "0 * * * * ?")
    public void rollForward() {
        try {
            int created = instanceService.ensureWindow(windowDays);
            if (created > 0) {
                log.info("滚动窗口补齐完成，新建实例 {} 个（窗口 {} 天）", created, windowDays);
            }
        } catch (Exception e) {
            // 定时任务不能把异常抛出去，否则调度会被静默终止
            log.error("滚动窗口补齐失败", e);
            alarmService.send("滚动窗口补齐失败: " + e.getMessage());
        }
    }

    /** 每天 04:30 清理过期实例 */
    @Scheduled(cron = "0 30 4 * * ?")
    public void cleanup() {
        try {
            int removed = instanceService.cleanupExpired(cleanupDelayDays);
            if (removed > 0) {
                log.info("过期实例清理完成，删除 {} 个", removed);
            }
        } catch (Exception e) {
            log.error("过期实例清理失败", e);
            alarmService.send("过期实例清理失败: " + e.getMessage());
        }
    }
}

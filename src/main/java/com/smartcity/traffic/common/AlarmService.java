package com.smartcity.traffic.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 告警服务：补偿失败时统一入口。
 * 本地环境用 ERROR 日志承载告警（生产可替换为钉钉/短信/监控 webhook）。
 */
@Service
public class AlarmService {

    private static final Logger log = LoggerFactory.getLogger(AlarmService.class);

    private final BusinessMetrics metrics;

    public AlarmService(BusinessMetrics metrics) {
        this.metrics = metrics;
    }

    public void send(String message) {
        log.error("【告警】{}", message);
        // 同时打一个计数：日志要一条条翻，计数能直接看出"今天报了多少次"
        metrics.alarm("app");
    }

    public void send(String message, Throwable cause) {
        log.error("【告警】{}", message, cause);
        metrics.alarm("app");
    }
}

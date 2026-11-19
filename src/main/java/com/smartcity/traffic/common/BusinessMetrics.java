package com.smartcity.traffic.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 业务指标埋点入口。
 *
 * 为什么需要它：Actuator 自带的 HTTP 指标只能看出"哪个接口被调了多少次、花多久"，
 * 但本项目所有业务失败都以 HTTP 200 返回（错误码在响应体里），
 * 所以光看 HTTP 指标分不清"抢票成功"和"余票不足"。这里补上业务维度的计数。
 *
 * 指标命名统一用 traffic. 前缀，方便在 /actuator/metrics/traffic.xxx 直接查。
 */
@Component
public class BusinessMetrics {

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
        // 预注册：Micrometer 的 counter 是首次 increment 时才创建的，不预注册的话
        // 系统刚启动时指标清单里没有这几项，面板上会出现一段"什么都没有"的空白，
        // 分不清是"今天一单没成"还是"指标还没注册"。值为 0 也是一种有效信息。
        // 注意 order.fail 带 reason 标签，没发生失败前无法预知取值，只能等它自然出现。
        registry.counter("traffic.order.success");
        registry.counter("traffic.alarm.total");
        registry.counter("traffic.reconcile.handled");
    }

    /** 下单成功：真正抢到票、库存已扣减 */
    public void orderSuccess() {
        Counter counter = registry.counter("traffic.order.success");
        counter.increment();
    }

    /**
     * 下单失败，按原因分开计数。
     * 分开的原因是"余票不足"和"系统异常"是完全不同的信号：前者说明流量确实超过库存，
     * 后者说明有故障。混在一起看就没意义了。
     */
    public void orderFail(String reason) {
        registry.counter("traffic.order.fail", "reason", reason).increment();
    }

    /** 告警次数：按来源分标签，能看出是哪条链路在报 */
    public void alarm(String source) {
        registry.counter("traffic.alarm.total", "source", source).increment();
    }

    /** 定时对账任务本轮处理条数 */
    public void reconcileHandled(String task, int count) {
        if (count > 0) {
            registry.counter("traffic.reconcile.handled", "task", task).increment(count);
        }
    }
}

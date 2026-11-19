package com.smartcity.traffic.service;

/**
 * 下单限流服务（令牌桶，两层）。
 *
 * 外层：全局按车次限流，保护系统入口容量（Tomcat 线程、DB 连接）。
 * 内层：按 用户 + 车次 限流，保护业务公平（防单用户反复刷单）。
 */
public interface RateLimitService {

    /**
     * 全局（按车次）限流：保护系统入口容量。
     *
     * @param trainNo 车次号
     * @return true 放行；false 触发全局限流
     */
    boolean tryAcquireGlobal(String trainNo);

    /**
     * 单用户限流：同一用户对同一车次的请求频率控制。
     *
     * @param userId  当前登录用户
     * @param trainNo 车次号
     * @return true 放行（已消耗 1 个令牌）；false 触发限流
     */
    boolean tryAcquire(Long userId, String trainNo);
}

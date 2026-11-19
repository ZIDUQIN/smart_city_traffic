package com.smartcity.traffic.service;

import com.smartcity.traffic.entity.TrainInstanceRecord;

import java.util.List;

/**
 * 班次实例的滚动窗口管理。
 *
 * <p>窗口规则：从今天起往后 {@code days} 天，每天一个实例。
 * 补齐是幂等的——重复执行只会补缺失的部分，不会动已有数据。</p>
 */
public interface InstanceService {

    /**
     * 补齐未来 days 天（含今天）的实例与库存行。
     *
     * @return 本次新建的实例数
     */
    int ensureWindow(int days);

    /**
     * 把某实例的 DB 余票用 HSETNX 补进 Redis，再标记 stock_preheated=1。
     * 已存在的 field 不动，已售座位不会被写回满票。
     */
    boolean preheatInstance(String instanceNo);

    /**
     * 清理过期实例：运营日期早于 today - delayDays 的实例连同库存行、Redis key 一起删。
     *
     * @return 清理的实例数
     */
    int cleanupExpired(int delayDays);

    /**
     * 查某班次未来 days 天的实例状态，缺的日期对应 null。
     */
    List<TrainInstanceRecord> windowState(String trainNo, int days);
}

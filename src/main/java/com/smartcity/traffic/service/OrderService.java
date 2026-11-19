package com.smartcity.traffic.service;

import com.smartcity.traffic.dto.OrderRequest;
import com.smartcity.traffic.dto.OrderResponse;
import com.smartcity.traffic.mq.OrderTxArg;
import org.apache.rocketmq.client.producer.LocalTransactionState;

import java.time.LocalDate;

/**
 * 下单服务：事务消息发送 + 本地事务四步。
 */
public interface OrderService {

    /** 入口：发事务消息，返回受理结果 */
    OrderResponse createOrder(OrderRequest request);

    /**
     * 本地事务（executeLocalTransaction），严格按顺序：
     * 写流水 → 扣 Redis → 建订单 → 扣 DB 真实库存。
     */
    LocalTransactionState executeLocalSteps(OrderTxArg arg);

    /** 幂等取消订单（已取消的再取消直接成功） */
    void cancelOrder(String orderNo);

    /**
     * 统一预热：按班次实例把 stock 表的余票写进 Redis stock Hash。
     * 只用 HSETNX 补缺失字段，绝不覆盖已有余票（覆盖式 putAll 会把已售座位写回满票）。
     *
     * @param travelDate 运营日期，为空按当天
     * @param seatNo     可选，为空预热该实例全部席别
     * @return 新写入的区段条数
     */
    int preheat(String trainNo, LocalDate travelDate, String seatNo);
}

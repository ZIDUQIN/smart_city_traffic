package com.smartcity.traffic.mapper;

import com.smartcity.traffic.entity.OrderRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单 Mapper（表 `order`）。
 */
public interface OrderMapper {

    /** 新增订单 */
    int insert(OrderRecord order);

    /** 按订单号查订单 */
    OrderRecord selectByOrderNo(@Param("orderNo") String orderNo);

    /** 按当前用户查全部订单（我的订单列表） */
    List<OrderRecord> selectByUserId(@Param("userId") Long userId);

    /**
     * 查"已确认 + 距创建时间超过指定秒数"的订单，供支付超时关单任务处理。
     *
     * @param timeoutSeconds 支付超时秒数
     * @param limit          每轮处理上限
     */
    List<OrderRecord> listTimeoutConfirmed(@Param("timeoutSeconds") int timeoutSeconds,
                                           @Param("limit") int limit);

    /**
     * CAS 改订单状态。
     *
     * @return 1 成功，0 状态已不是期望值（幂等判定）
     */
    int casUpdateStatus(@Param("orderNo") String orderNo,
                        @Param("expectStatus") String expectStatus,
                        @Param("newStatus") String newStatus);
}

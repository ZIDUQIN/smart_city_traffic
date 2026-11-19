package com.smartcity.traffic.service;

import com.smartcity.traffic.dto.RefundResponse;

/**
 * 退票退款服务：已支付订单退票 → 回补库存 → 调渠道退款 → 回调确认。
 */
public interface RefundService {

    /**
     * 申请退票。
     *
     * 顺序固定：事务内（插退款流水 + 订单转退款中）→ 事务后（回补 Redis / DB 库存）→ 调渠道退款。
     * 状态先切、库存后补，保证不会出现"两笔已支付占同一座位"。
     *
     * @param orderNo 订单号
     * @param reason  退票原因，可为空
     */
    RefundResponse applyRefund(String orderNo, String reason);

    /**
     * 处理渠道退款回调：验签 → 校验金额 → 幂等更新。
     *
     * @return true 处理成功（应答 SUCCESS），false 拒绝/挂起（应答 FAILURE）
     */
    boolean handleNotify(String rawBody, String signature);

    /** 查询退款进度；未退过票返回 status="未退票" */
    RefundResponse status(String orderNo);

    /**
     * 执行一轮对账：扫卡在「退款中」的记录，按判定矩阵补偿。
     *
     * 动作只有四种：补库存、重发退款、本地修状态、原样等待。
     * 绝不能对「处理中/已成功」的退款重发。
     *
     * @return 本轮实际处理并推进的记录数
     */
    int reconcileOnce();
}

package com.smartcity.traffic.service;

import com.smartcity.traffic.dto.PaymentStatusVO;
import com.smartcity.traffic.dto.PrepayResponse;

/**
 * 支付服务。
 */
public interface PaymentService {

    /** 统一下单：校验订单 → 写待支付流水 → 返回支付凭证 */
    PrepayResponse prepay(String orderNo);

    /** 用户确认付款：按支付凭证触发模拟渠道异步回调 */
    void confirmByToken(String payToken);

    /**
     * 处理渠道回调：验签 → 校验 → 幂等更新。
     *
     * @return true 处理成功（应答 SUCCESS），false 拒绝/挂起（应答 FAILURE）
     */
    boolean handleNotify(String rawBody, String signature);

    /** 查询订单与支付状态（前端轮询兜底） */
    PaymentStatusVO status(String orderNo);

    /** 用户主动取消待支付订单 */
    void userCancel(String orderNo);
}

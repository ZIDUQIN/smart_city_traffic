package com.smartcity.traffic.service;

import com.smartcity.traffic.entity.PaymentRecord;
import com.smartcity.traffic.entity.RefundRecord;
import com.smartcity.traffic.enums.RefundQueryResult;

/**
 * 模拟支付渠道：替代真实微信/支付宝，保持统一下单 + 异步回调结构。
 */
public interface MockPayChannelService {

    /**
     * 生成模拟支付凭证（替代 prepay_id），内部维护凭证→支付单号映射。
     */
    String createPayToken(String paymentNo);

    /**
     * 按支付凭证解析支付单号。
     *
     * @return 支付单号；凭证无效返回 null
     */
    String resolvePaymentNo(String payToken);

    /**
     * 用户确认付款：生成渠道交易号，签名后异步回调商户 notify 接口。
     */
    void confirm(PaymentRecord record);

    /**
     * 发起退款：生成渠道退款单号，签名后异步回调商户退款 notify 接口。
     *
     * 与真实渠道一致：退款是异步的，商户必须靠回调或主动查询确认结果。
     *
     * @return 渠道退款单号，必须落库——对账靠它区分「渠道没受理」与「渠道已受理」
     */
    String refund(RefundRecord record);

    /**
     * 查询退款结果（对账用）。真实渠道这里是 HTTP 查询接口，Mock 用内存结果表。
     *
     * @return SUCCESS 已退款成功 / PROCESSING 受理中 / NOT_EXIST 无此单 / FAILED 明确失败
     */
    RefundQueryResult queryRefund(String channelRefundNo);

    /**
     * 仅 Mock 渠道具备：注入一笔退款的查询结果。
     *
     * 存在的唯一目的是让对账的 PROCESSING / FAILED 分支可以被测试——
     * 真实渠道会自然返回这些状态，Mock 渠道如果不注入就永远只有 SUCCESS。
     */
    void setRefundResult(String channelRefundNo, RefundQueryResult result);
}

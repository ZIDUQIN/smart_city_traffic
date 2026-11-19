package com.smartcity.traffic.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款流水实体，对应表 refund_record。
 *
 * 一次退票一条记录，与 payment_record 一一对应关系相反：
 * payment_record 是一次支付尝试一条（可能多条），refund_record 是一个订单一条（uk_order_no 保证）。
 */
public class RefundRecord {

    /** 已提交退票，等待渠道退款回调 */
    public static final String STATUS_REFUNDING = "退款中";
    /** 渠道退款成功（终态） */
    public static final String STATUS_SUCCESS = "退款成功";
    /** 渠道退款失败（预留，Mock 渠道不会产生） */
    public static final String STATUS_FAILED = "退款失败";

    private Long id;

    /** 退款单号（雪花），一次退票一个 */
    private String refundNo;

    /** 原商户订单号 */
    private String orderNo;

    /** 原支付流水号，用于追溯这笔钱从哪来 */
    private String paymentNo;

    /** 下单用户 */
    private Long userId;

    /** 原票款金额 */
    private BigDecimal amount;

    /** 手续费 */
    private BigDecimal fee;

    /** 实退金额 = amount - fee */
    private BigDecimal refundAmount;

    /** 退款中/退款成功/退款失败 */
    private String status;

    /** 渠道退款单号，申请退票时即落库（渠道返回就写），区分「渠道没受理」与「已受理」 */
    private String channelRefundNo;

    /** 退票原因 */
    private String reason;

    /** 回调到达次数，用于观测渠道重试 */
    private Integer callbackCount;

    /** 库存是否已回补：0 未补 / 1 已补。restoreRealStock 不幂等，靠它防对账重复补 */
    private Integer stockRestored;

    /** 对账重试次数：只统计「需要动作却失败」的次数，PROCESSING 这类正常等待不计 */
    private Integer retryCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRefundNo() {
        return refundNo;
    }

    public void setRefundNo(String refundNo) {
        this.refundNo = refundNo;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getPaymentNo() {
        return paymentNo;
    }

    public void setPaymentNo(String paymentNo) {
        this.paymentNo = paymentNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public void setFee(BigDecimal fee) {
        this.fee = fee;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(BigDecimal refundAmount) {
        this.refundAmount = refundAmount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getChannelRefundNo() {
        return channelRefundNo;
    }

    public void setChannelRefundNo(String channelRefundNo) {
        this.channelRefundNo = channelRefundNo;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Integer getCallbackCount() {
        return callbackCount;
    }

    public void setCallbackCount(Integer callbackCount) {
        this.callbackCount = callbackCount;
    }

    public Integer getStockRestored() {
        return stockRestored;
    }

    public void setStockRestored(Integer stockRestored) {
        this.stockRestored = stockRestored;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}

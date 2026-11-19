package com.smartcity.traffic.dto;

import java.math.BigDecimal;

/**
 * 退票结果返回：申请退票与查询退款进度共用。
 */
public class RefundResponse {

    /** 退款单号；未退票时为 null */
    private String refundNo;

    /** 原订单号 */
    private String orderNo;

    /** 原票款金额 */
    private BigDecimal amount;

    /** 手续费 */
    private BigDecimal fee;

    /** 实退金额 = amount - fee */
    private BigDecimal refundAmount;

    /** 退款中 / 退款成功 / 退款失败 / 未退票 */
    private String status;

    /** 退票原因 */
    private String reason;

    public RefundResponse() {
    }

    public RefundResponse(String refundNo, String orderNo, BigDecimal amount, BigDecimal fee,
                          BigDecimal refundAmount, String status, String reason) {
        this.refundNo = refundNo;
        this.orderNo = orderNo;
        this.amount = amount;
        this.fee = fee;
        this.refundAmount = refundAmount;
        this.status = status;
        this.reason = reason;
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

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}

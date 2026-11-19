package com.smartcity.traffic.dto;

import java.math.BigDecimal;

/**
 * 统一下单返回：前端拉起模拟收银台所需的支付凭证。
 */
public class PrepayResponse {

    /** 支付单号 */
    private String paymentNo;

    /** 商户订单号 */
    private String orderNo;

    /** 支付金额 */
    private BigDecimal amount;

    /** 模拟支付凭证（替代真实 prepay_id） */
    private String payToken;

    public PrepayResponse() {
    }

    public PrepayResponse(String paymentNo, String orderNo, BigDecimal amount, String payToken) {
        this.paymentNo = paymentNo;
        this.orderNo = orderNo;
        this.amount = amount;
        this.payToken = payToken;
    }

    public String getPaymentNo() {
        return paymentNo;
    }

    public void setPaymentNo(String paymentNo) {
        this.paymentNo = paymentNo;
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

    public String getPayToken() {
        return payToken;
    }

    public void setPayToken(String payToken) {
        this.payToken = payToken;
    }
}

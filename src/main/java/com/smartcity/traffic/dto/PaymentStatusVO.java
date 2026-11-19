package com.smartcity.traffic.dto;

/**
 * 支付状态查询结果：订单状态 + 支付流水状态，供前端轮询兜底。
 */
public class PaymentStatusVO {

    /** 商户订单号 */
    private String orderNo;

    /** 订单状态（待确认/已确认/已支付/已取消） */
    private String orderStatus;

    /** 支付流水状态（无支付单时为 null） */
    private String paymentStatus;

    public PaymentStatusVO() {
    }

    public PaymentStatusVO(String orderNo, String orderStatus, String paymentStatus) {
        this.orderNo = orderNo;
        this.orderStatus = orderStatus;
        this.paymentStatus = paymentStatus;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(String orderStatus) {
        this.orderStatus = orderStatus;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(String paymentStatus) {
        this.paymentStatus = paymentStatus;
    }
}

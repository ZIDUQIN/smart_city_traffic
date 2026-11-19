package com.smartcity.traffic.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付流水实体，对应表 payment_record。
 * 一次支付尝试一条记录。
 */
public class PaymentRecord {

    /** 待支付 */
    public static final String STATUS_WAITING = "待支付";
    /** 支付成功 */
    public static final String STATUS_SUCCESS = "支付成功";
    /** 支付失败 */
    public static final String STATUS_FAILED = "支付失败";
    /** 已关闭 */
    public static final String STATUS_CLOSED = "已关闭";

    /** 支付渠道：本地模拟 */
    public static final String CHANNEL_MOCK = "MOCK";

    private Long id;

    private String paymentNo;

    private String orderNo;

    private String channel;

    private BigDecimal amount;

    private String status;

    private String channelTradeNo;

    private Integer callbackCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getChannelTradeNo() {
        return channelTradeNo;
    }

    public void setChannelTradeNo(String channelTradeNo) {
        this.channelTradeNo = channelTradeNo;
    }

    public Integer getCallbackCount() {
        return callbackCount;
    }

    public void setCallbackCount(Integer callbackCount) {
        this.callbackCount = callbackCount;
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

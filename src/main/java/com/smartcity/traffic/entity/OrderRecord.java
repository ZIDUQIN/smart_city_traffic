package com.smartcity.traffic.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 订单实体，对应表 `order`。
 */
public class OrderRecord {

    /** 订单已建，DB 真实库存尚未确认 */
    public static final String STATUS_PENDING = "待确认";
    /** DB 真实库存已扣，订单成功、待支付 */
    public static final String STATUS_CONFIRMED = "已确认";
    /** 支付完成（终态） */
    public static final String STATUS_PAID = "已支付";
    /** 已提交退票，库存已回补、退款待渠道回调（过渡态） */
    public static final String STATUS_REFUNDING = "退款中";
    /** 退款完成（终态） */
    public static final String STATUS_REFUNDED = "已退款";
    /** 订单已取消 */
    public static final String STATUS_CANCELED = "已取消";

    private Long id;

    private String orderNo;

    private Long userId;

    private Long passengerId;

    private String trainNo;

    /** 班次实例编号，格式 班次号-yyyyMMdd */
    private String instanceNo;

    /** 运营日期 */
    private LocalDate travelDate;

    private String seatNo;

    private Integer fromSeq;

    private Integer toSeq;

    /** 上车站名，非数据库字段，仅用于前端展示订单行程 */
    private String fromStation;

    /** 下车站名，非数据库字段，仅用于前端展示订单行程 */
    private String toStation;

    private BigDecimal amount;

    private String status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getPassengerId() {
        return passengerId;
    }

    public void setPassengerId(Long passengerId) {
        this.passengerId = passengerId;
    }

    public String getTrainNo() {
        return trainNo;
    }

    public void setTrainNo(String trainNo) {
        this.trainNo = trainNo;
    }

    public String getInstanceNo() {
        return instanceNo;
    }

    public void setInstanceNo(String instanceNo) {
        this.instanceNo = instanceNo;
    }

    /**
     * 取实例编号用于库存 key；历史数据可能缺 instance_no，
     * 此时用 班次号 + 运营日期 补算，避免回补落到错误的 key 上。
     */
    public String resolveInstanceNo() {
        if (instanceNo != null) {
            return instanceNo;
        }
        if (trainNo != null && travelDate != null) {
            return com.smartcity.traffic.common.InstanceNoUtil.of(trainNo, travelDate);
        }
        return null;
    }

    public LocalDate getTravelDate() {
        return travelDate;
    }

    public void setTravelDate(LocalDate travelDate) {
        this.travelDate = travelDate;
    }

    public String getSeatNo() {
        return seatNo;
    }

    public void setSeatNo(String seatNo) {
        this.seatNo = seatNo;
    }

    public Integer getFromSeq() {
        return fromSeq;
    }

    public void setFromSeq(Integer fromSeq) {
        this.fromSeq = fromSeq;
    }

    public Integer getToSeq() {
        return toSeq;
    }

    public void setToSeq(Integer toSeq) {
        this.toSeq = toSeq;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public String getFromStation() {
        return fromStation;
    }

    public void setFromStation(String fromStation) {
        this.fromStation = fromStation;
    }

    public String getToStation() {
        return toStation;
    }

    public void setToStation(String toStation) {
        this.toStation = toStation;
    }
}

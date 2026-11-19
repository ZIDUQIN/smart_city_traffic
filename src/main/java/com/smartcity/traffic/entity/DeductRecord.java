package com.smartcity.traffic.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 扣减流水实体，对应 ticket_deduct_record。
 * 状态只能用这 6 个字符串常量，禁止新增。
 */
public class DeductRecord {

    /** 流水已写，Redis 扣减结果未知 */
    public static final String STATUS_DEDUCTING = "扣减中";
    /** Redis 确认扣减成功，订单尚未确认建成 */
    public static final String STATUS_DEDUCTED = "已扣减";
    /** 订单已建 + DB 真实库存已扣（成功终态） */
    public static final String STATUS_CREATED = "已建单";
    /** 定时任务已抢占，正在执行补偿 */
    public static final String STATUS_COMPENSATING = "补偿中";
    /** 仅回补了 Redis（该单无订单产生） */
    public static final String STATUS_COMPENSATED = "已补偿";
    /** 订单存在且已被取消 + 回补了 Redis */
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

    private String status;

    private String failReason;

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
     * 此时用 班次号 + 运营日期 补算。
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

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
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

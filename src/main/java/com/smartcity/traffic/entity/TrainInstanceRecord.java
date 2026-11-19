package com.smartcity.traffic.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 班次实例实体，对应表 train_instance。
 *
 * <p>模板（train）描述"每天固定跑哪条线路"，实例描述"某一天的那一趟"，
 * 库存和是否停运都挂在实例上。</p>
 */
public class TrainInstanceRecord {

    /** 正常运营 */
    public static final int STATUS_NORMAL = 1;

    /** 停运 */
    public static final int STATUS_STOPPED = 2;

    /** 库存未预热 */
    public static final int PREHEATED_NO = 0;

    /** 库存已预热 */
    public static final int PREHEATED_YES = 1;

    private Long id;

    /** 实例编号，格式 班次号-yyyyMMdd，如 G1001-20261118 */
    private String instanceNo;

    private String trainNo;

    /** 运营日期 */
    private LocalDate travelDate;

    /** 1=正常 2=停运 */
    private Integer status;

    /** 0=未预热 1=已预热 */
    private Integer stockPreheated;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getInstanceNo() {
        return instanceNo;
    }

    public void setInstanceNo(String instanceNo) {
        this.instanceNo = instanceNo;
    }

    public String getTrainNo() {
        return trainNo;
    }

    public void setTrainNo(String trainNo) {
        this.trainNo = trainNo;
    }

    public LocalDate getTravelDate() {
        return travelDate;
    }

    public void setTravelDate(LocalDate travelDate) {
        this.travelDate = travelDate;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getStockPreheated() {
        return stockPreheated;
    }

    public void setStockPreheated(Integer stockPreheated) {
        this.stockPreheated = stockPreheated;
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

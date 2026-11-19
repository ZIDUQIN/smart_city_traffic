package com.smartcity.traffic.entity;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 车次实体，对应表 train。
 */
public class Train {

    private Long id;

    /** 车次号，如 G1001 */
    private String trainNo;

    private Long startStationId;

    private Long endStationId;

    /** 始发时刻 */
    private LocalTime departTime;

    /** 终到时刻 */
    private LocalTime arriveTime;

    /** 起售时间（放票时刻） */
    private LocalDateTime saleStartTime;

    /** 停售时间 */
    private LocalDateTime saleEndTime;

    /** 1 正常，0 停运 */
    private Integer status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTrainNo() {
        return trainNo;
    }

    public void setTrainNo(String trainNo) {
        this.trainNo = trainNo;
    }

    public Long getStartStationId() {
        return startStationId;
    }

    public void setStartStationId(Long startStationId) {
        this.startStationId = startStationId;
    }

    public Long getEndStationId() {
        return endStationId;
    }

    public void setEndStationId(Long endStationId) {
        this.endStationId = endStationId;
    }

    public LocalTime getDepartTime() {
        return departTime;
    }

    public void setDepartTime(LocalTime departTime) {
        this.departTime = departTime;
    }

    public LocalTime getArriveTime() {
        return arriveTime;
    }

    public void setArriveTime(LocalTime arriveTime) {
        this.arriveTime = arriveTime;
    }

    public LocalDateTime getSaleStartTime() {
        return saleStartTime;
    }

    public void setSaleStartTime(LocalDateTime saleStartTime) {
        this.saleStartTime = saleStartTime;
    }

    public LocalDateTime getSaleEndTime() {
        return saleEndTime;
    }

    public void setSaleEndTime(LocalDateTime saleEndTime) {
        this.saleEndTime = saleEndTime;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}

package com.smartcity.traffic.dto;

import java.time.LocalTime;

/**
 * 按站对查询车次的原始行：只承载 SQL 查出来的字段，由 Service 组装成 TrainSearchVO。
 *
 * <p>departTime 取的是上车站的发车时刻（train_station.depart_time），
 * 不是全程始发时刻（train.depart_time）。</p>
 */
public class TrainRouteRow {

    private String trainNo;

    private Integer fromSeq;

    private Integer toSeq;

    private LocalTime departTime;

    private LocalTime arriveTime;

    public String getTrainNo() {
        return trainNo;
    }

    public void setTrainNo(String trainNo) {
        this.trainNo = trainNo;
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
}

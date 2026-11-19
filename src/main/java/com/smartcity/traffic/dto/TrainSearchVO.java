package com.smartcity.traffic.dto;

import java.util.List;

/**
 * 查票结果中的一条车次，供前端渲染车次列表。
 */
public class TrainSearchVO {

    private String trainNo;

    private String instanceNo;

    private String fromStation;

    private String toStation;

    private Integer fromSeq;

    private Integer toSeq;

    /** 上车站发车时刻，HH:mm */
    private String departTime;

    /** 下车站到达时刻，HH:mm */
    private String arriveTime;

    /** 历时（分钟） */
    private Integer durationMinutes;

    private List<SeatOffer> seats;

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

    public String getDepartTime() {
        return departTime;
    }

    public void setDepartTime(String departTime) {
        this.departTime = departTime;
    }

    public String getArriveTime() {
        return arriveTime;
    }

    public void setArriveTime(String arriveTime) {
        this.arriveTime = arriveTime;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public List<SeatOffer> getSeats() {
        return seats;
    }

    public void setSeats(List<SeatOffer> seats) {
        this.seats = seats;
    }
}

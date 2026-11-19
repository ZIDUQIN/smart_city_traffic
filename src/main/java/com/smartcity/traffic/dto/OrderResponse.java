package com.smartcity.traffic.dto;

/**
 * 下单结果。
 */
public class OrderResponse {

    private Long passengerId;

    private String orderNo;

    private String trainNo;

    private String seatNo;

    /** 流水状态：已建单（成功）；处理中（UNKNOWN，稍后查询） */
    private String status;

    private String message;

    public OrderResponse() {
    }

    public OrderResponse(String orderNo, String status, String message) {
        this.orderNo = orderNo;
        this.status = status;
        this.message = message;
    }

    public Long getPassengerId() {
        return passengerId;
    }

    public void setPassengerId(Long passengerId) {
        this.passengerId = passengerId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getTrainNo() {
        return trainNo;
    }

    public void setTrainNo(String trainNo) {
        this.trainNo = trainNo;
    }

    public String getSeatNo() {
        return seatNo;
    }

    public void setSeatNo(String seatNo) {
        this.seatNo = seatNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}

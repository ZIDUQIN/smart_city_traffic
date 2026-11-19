package com.smartcity.traffic.mq;

/**
 * 本地事务入参（sendMessageInTransaction 的 arg），只在进程内传递，不序列化。
 * 同时承载本地事务的执行结果，供 OrderServiceImpl 读取。
 */
public class OrderTxArg {

    private final String orderNo;

    private final Long userId;

    private final Long passengerId;

    private final String trainNo;

    /** 班次实例编号（班次号-yyyyMMdd），库存与防重购都按它隔离 */
    private final String instanceNo;

    /** 运营日期 */
    private final java.time.LocalDate travelDate;

    private final String seatNo;

    private final Integer fromSeq;

    private final Integer toSeq;

    /** 本地事务最终是否成功 */
    private boolean success;

    /** 失败原因（成功时为 null） */
    private String failReason;

    public OrderTxArg(String orderNo, Long userId, Long passengerId,
                      String trainNo, String instanceNo, java.time.LocalDate travelDate,
                      String seatNo, Integer fromSeq, Integer toSeq) {
        this.orderNo = orderNo;
        this.userId = userId;
        this.passengerId = passengerId;
        this.trainNo = trainNo;
        this.instanceNo = instanceNo;
        this.travelDate = travelDate;
        this.seatNo = seatNo;
        this.fromSeq = fromSeq;
        this.toSeq = toSeq;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPassengerId() {
        return passengerId;
    }

    public String getTrainNo() {
        return trainNo;
    }

    public String getInstanceNo() {
        return instanceNo;
    }

    public java.time.LocalDate getTravelDate() {
        return travelDate;
    }

    public String getSeatNo() {
        return seatNo;
    }

    public Integer getFromSeq() {
        return fromSeq;
    }

    public Integer getToSeq() {
        return toSeq;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }
}

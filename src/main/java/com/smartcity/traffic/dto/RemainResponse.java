package com.smartcity.traffic.dto;

/**
 * 余票查询结果。
 */
public class RemainResponse {

    private String trainNo;

    private String seatNo;

    /** 上车站序 */
    private Integer fromSeq;

    /** 下车站序 */
    private Integer toSeq;

    /** 该区间的可售余票（涉及区段取最小值） */
    private Integer remain;

    /** 数据来源：LOCAL 本地缓存 / REDIS 分布式缓存 / DB 数据库，便于演示与排查 */
    private String source;

    /** 该行程票价；票价表没配则为 null（不影响余票查询） */
    private java.math.BigDecimal price;

    public RemainResponse() {
    }

    public RemainResponse(String trainNo, String seatNo, Integer fromSeq, Integer toSeq,
                          Integer remain, String source) {
        this.trainNo = trainNo;
        this.seatNo = seatNo;
        this.fromSeq = fromSeq;
        this.toSeq = toSeq;
        this.remain = remain;
        this.source = source;
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

    public Integer getRemain() {
        return remain;
    }

    public void setRemain(Integer remain) {
        this.remain = remain;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public java.math.BigDecimal getPrice() {
        return price;
    }

    public void setPrice(java.math.BigDecimal price) {
        this.price = price;
    }
}

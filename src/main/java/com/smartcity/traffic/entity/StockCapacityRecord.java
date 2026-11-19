package com.smartcity.traffic.entity;

/**
 * 班次容量实体，对应表 stock_capacity。
 *
 * <p>挂在模板层（班次 + 座位 + 区段），不随日期变化。
 * 滚动窗口生成新一天的库存时，从这里抄数。</p>
 */
public class StockCapacityRecord {

    private Long id;

    private String trainNo;

    private String seatNo;

    private Integer segmentNo;

    /** 该段总座位数 */
    private Integer totalCapacity;

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

    public String getSeatNo() {
        return seatNo;
    }

    public void setSeatNo(String seatNo) {
        this.seatNo = seatNo;
    }

    public Integer getSegmentNo() {
        return segmentNo;
    }

    public void setSegmentNo(Integer segmentNo) {
        this.segmentNo = segmentNo;
    }

    public Integer getTotalCapacity() {
        return totalCapacity;
    }

    public void setTotalCapacity(Integer totalCapacity) {
        this.totalCapacity = totalCapacity;
    }
}

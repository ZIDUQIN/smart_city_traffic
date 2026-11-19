package com.smartcity.traffic.entity;

import java.math.BigDecimal;

/**
 * 票价实体，对应表 ticket_price。
 *
 * <p>挂在模板层，按"起点站序 + 终点站序 + 座位等级"定价，
 * 因为真实客运不同站段价格不同，不能简单按区段数线性叠加。</p>
 */
public class TicketPriceRecord {

    private Long id;

    private String trainNo;

    /** 起点站序，从 1 开始 */
    private Integer fromSeq;

    /** 终点站序 */
    private Integer toSeq;

    private String seatNo;

    private BigDecimal price;

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

    public String getSeatNo() {
        return seatNo;
    }

    public void setSeatNo(String seatNo) {
        this.seatNo = seatNo;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }
}

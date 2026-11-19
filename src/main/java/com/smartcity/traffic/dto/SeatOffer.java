package com.smartcity.traffic.dto;

import java.math.BigDecimal;

/**
 * 车次下某个席别的可售情况。
 *
 * <p>remain = 0 表示售罄；price = null 表示该行程未配置票价，
 * 前端应把这两种情况都置为不可点击（缺价下单会被 fail-close 拒绝）。</p>
 */
public class SeatOffer {

    private String seatNo;

    private String seatName;

    private Integer remain;

    private BigDecimal price;

    public SeatOffer() {
    }

    public SeatOffer(String seatNo, String seatName, Integer remain, BigDecimal price) {
        this.seatNo = seatNo;
        this.seatName = seatName;
        this.remain = remain;
        this.price = price;
    }

    public String getSeatNo() {
        return seatNo;
    }

    public void setSeatNo(String seatNo) {
        this.seatNo = seatNo;
    }

    public String getSeatName() {
        return seatName;
    }

    public void setSeatName(String seatName) {
        this.seatName = seatName;
    }

    public Integer getRemain() {
        return remain;
    }

    public void setRemain(Integer remain) {
        this.remain = remain;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }
}

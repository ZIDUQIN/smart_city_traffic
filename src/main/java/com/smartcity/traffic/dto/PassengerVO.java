package com.smartcity.traffic.dto;

/**
 * 返回给前端的乘车人信息（身份证脱敏）。
 */
public class PassengerVO {

    private Long id;

    private String passengerName;

    /** 脱敏后的身份证号，如 1101**********1234 */
    private String idCard;

    private Integer passengerType;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPassengerName() {
        return passengerName;
    }

    public void setPassengerName(String passengerName) {
        this.passengerName = passengerName;
    }

    public String getIdCard() {
        return idCard;
    }

    public void setIdCard(String idCard) {
        this.idCard = idCard;
    }

    public Integer getPassengerType() {
        return passengerType;
    }

    public void setPassengerType(Integer passengerType) {
        this.passengerType = passengerType;
    }
}

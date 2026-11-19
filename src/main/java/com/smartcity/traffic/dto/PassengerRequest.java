package com.smartcity.traffic.dto;

/**
 * 新增/修改乘车人的请求体。
 */
public class PassengerRequest {

    /** 姓名 */
    private String passengerName;

    /** 身份证号 */
    private String idCard;

    /** 乘车人类型：1 成人，2 学生，3 儿童 */
    private Integer passengerType;

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

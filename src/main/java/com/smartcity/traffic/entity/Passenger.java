package com.smartcity.traffic.entity;

import java.time.LocalDateTime;

/**
 * 乘车人实体，对应表 passenger。
 */
public class Passenger {

    /** 乘车人ID（雪花算法生成） */
    private Long id;

    /** 所属用户ID */
    private Long userId;

    /** 姓名 */
    private String passengerName;

    /** 身份证号 */
    private String idCard;

    /** 乘车人类型：1 成人，2 学生，3 儿童 */
    private Integer passengerType;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}

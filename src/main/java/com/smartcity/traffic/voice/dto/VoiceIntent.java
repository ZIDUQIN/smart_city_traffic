package com.smartcity.traffic.voice.dto;

/**
 * 解析出的购票意图。语音链路里始终不出现 passengerId——选了"几张"不等于选了"谁"。
 */
public class VoiceIntent {

    private String fromStation;

    private String toStation;

    /** yyyy-MM-dd */
    private String travelDate;

    /** SECOND_CLASS / FIRST_CLASS / BUSINESS */
    private String seatPreference;

    /**
     * "什么时候走"：null（没说）/ EARLIEST / ASAP / MORNING / AFTERNOON / EVENING / HH:mm。
     * ASAP 的含义是"当前时刻之后最近的一趟"，与 EARLIEST（当天第一趟）不是一回事。
     */
    private String departTimePreference;

    private Integer passengerCount;

    /** rule：全部由规则得到；model：模型补齐过缺失字段 */
    private String parserSource;

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

    public String getTravelDate() {
        return travelDate;
    }

    public void setTravelDate(String travelDate) {
        this.travelDate = travelDate;
    }

    public String getSeatPreference() {
        return seatPreference;
    }

    public void setSeatPreference(String seatPreference) {
        this.seatPreference = seatPreference;
    }

    public String getDepartTimePreference() {
        return departTimePreference;
    }

    public void setDepartTimePreference(String departTimePreference) {
        this.departTimePreference = departTimePreference;
    }

    public Integer getPassengerCount() {
        return passengerCount;
    }

    public void setPassengerCount(Integer passengerCount) {
        this.passengerCount = passengerCount;
    }

    public String getParserSource() {
        return parserSource;
    }

    public void setParserSource(String parserSource) {
        this.parserSource = parserSource;
    }
}

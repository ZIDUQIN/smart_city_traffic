package com.smartcity.traffic.voice.dto;

/**
 * 解析请求。fromStation/toStation 由前端在用户点选候选后回传，用于二次确认。
 */
public class VoiceInterpretRequest {

    private String text;

    private String fromStation;

    private String toStation;

    /** 上次用过的出发站（localStorage 传来），只用于候选排序，绝不静默采用 */
    private String lastUsedFrom;

    /** 客户端本地日期 yyyy-MM-dd，缺省用服务器日期 */
    private String todayHint;

    /**
     * 前端指定的乘车日期，优先级高于从原话里解析出的日期。
     *
     * <p>点"看看明天的"时前端直接传明天，服务端不必再从"明天"这种词去猜。</p>
     */
    private String travelDate;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

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

    public String getLastUsedFrom() {
        return lastUsedFrom;
    }

    public void setLastUsedFrom(String lastUsedFrom) {
        this.lastUsedFrom = lastUsedFrom;
    }

    public String getTodayHint() {
        return todayHint;
    }

    public void setTodayHint(String todayHint) {
        this.todayHint = todayHint;
    }

    public String getTravelDate() {
        return travelDate;
    }

    public void setTravelDate(String travelDate) {
        this.travelDate = travelDate;
    }
}

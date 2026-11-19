package com.smartcity.traffic.voice.dto;

/**
 * 一个站名候选，带分数和它对应原文的哪一段。
 */
public class StationCandidate {

    private String stationName;

    private String city;

    /** 0~1，越大越可信 */
    private Double score;

    /** 命中原文的片段，例如"西岸" */
    private String matchedText;

    /**
     * 按对面已知的站反查，这条路有没有车。
     * null = 没探测（对面还没定），true = 有车，false = 没车。
     */
    private Boolean hasTrain;

    public StationCandidate() {
    }

    public StationCandidate(String stationName, String city, Double score, String matchedText) {
        this.stationName = stationName;
        this.city = city;
        this.score = score;
        this.matchedText = matchedText;
    }

    public String getStationName() {
        return stationName;
    }

    public void setStationName(String stationName) {
        this.stationName = stationName;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public String getMatchedText() {
        return matchedText;
    }

    public void setMatchedText(String matchedText) {
        this.matchedText = matchedText;
    }

    public Boolean getHasTrain() {
        return hasTrain;
    }

    public void setHasTrain(Boolean hasTrain) {
        this.hasTrain = hasTrain;
    }
}

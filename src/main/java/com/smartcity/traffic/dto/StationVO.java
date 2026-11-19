package com.smartcity.traffic.dto;

/**
 * 车站下拉项，供前端选择出发/到达站。
 */
public class StationVO {

    private Long id;

    private String stationName;

    private String city;

    public StationVO() {
    }

    public StationVO(Long id, String stationName, String city) {
        this.id = id;
        this.stationName = stationName;
        this.city = city;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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
}

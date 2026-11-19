package com.smartcity.traffic.service;

import com.smartcity.traffic.dto.RemainResponse;
import com.smartcity.traffic.dto.StationVO;
import com.smartcity.traffic.dto.TrainSearchVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 车票查询业务接口（B2：读统一库存）。
 */
public interface TicketService {

    /**
     * 查询某车次指定区间、指定席别的余票。
     * 走三级缓存：本地缓存（1 秒）→ Redis stock Hash → DB stock 表。
     *
     * @return 余票结果（含数据来源）
     */
    /**
     * 查某一天的余票。
     *
     * @param travelDate 运营日期，为空按当天
     */
    RemainResponse queryRemain(String trainNo, LocalDate travelDate,
                               Integer fromSeq, Integer toSeq, String seatNo);

    /**
     * 按出发站 + 到达站 + 日期查车次列表，每条带三个席别的余票与票价。
     * 实例不存在时不抛异常，该车次三个席别统一 remain = 0。
     */
    List<TrainSearchVO> search(String fromStation, String toStation, LocalDate travelDate);

    /** 全部车站，供前端下拉 */
    List<StationVO> stations();

    /**
     * 这条线到底通不通车：只要"存在"，不取时刻、不算票价与余票。
     *
     * <p>站名纠错要用它做业务校验，一次语音最多试 9 组站名组合。
     * 若走 search()，每组都会把每趟车 × 三种席别的余票票价算一遍，
     * 等于算 9 份没人看的结果、扔掉 8 份。判断"通不通车"只需要一行数据。</p>
     */
    boolean routeExists(String fromStation, String toStation);
}

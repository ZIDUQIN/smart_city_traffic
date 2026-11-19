package com.smartcity.traffic.mapper;

import com.smartcity.traffic.dto.StationVO;
import com.smartcity.traffic.dto.TrainRouteRow;
import com.smartcity.traffic.entity.Train;
import org.apache.ibatis.annotations.Param;

/**
 * 车次 Mapper。
 */
public interface TrainMapper {

    /** 按主键查询车次 */
    Train selectById(@Param("id") Long id);

    /** 查全部正常运营的班次模板，滚动窗口按它生成实例 */
    java.util.List<Train> selectActiveAll();

    /** 全部车站，供前端出发/到达站下拉 */
    java.util.List<StationVO> selectAllStations();

    /**
     * 按出发站名 + 到达站名查可售车次。
     * 只返回上车站序 < 下车站序的组合，时刻取经停站表而非全程始发时刻。
     */
    java.util.List<TrainRouteRow> selectByStations(@Param("fromStation") String fromStation,
                                                   @Param("toStation") String toStation);

    /**
     * 这条线有没有车：命中返回 1，没有返回 null。
     * 只取一行，不联表聚合、不碰时刻与票价余票，用于站名纠错的业务校验。
     */
    Integer selectRouteExists(@Param("fromStation") String fromStation,
                              @Param("toStation") String toStation);

    /**
     * 全量「车次 + 站序 + 站名」映射，供订单列表补齐上下车站名（订单表本身不存站名）。
     * 数据量固定为 车次数 × 经停站数，一次性查完比逐单查询省往返。
     */
    java.util.List<java.util.Map<String, Object>> selectAllStationSeqNames();
}

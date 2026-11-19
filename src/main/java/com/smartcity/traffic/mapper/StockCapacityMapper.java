package com.smartcity.traffic.mapper;

import com.smartcity.traffic.entity.StockCapacityRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 班次容量 Mapper（表 stock_capacity），模板层，不随日期变化。
 */
public interface StockCapacityMapper {

    /** 查某班次全部"座位 × 区段"的容量 */
    List<StockCapacityRecord> selectByTrainNo(@Param("trainNo") String trainNo);
}

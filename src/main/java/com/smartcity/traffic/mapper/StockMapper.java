package com.smartcity.traffic.mapper;

import com.smartcity.traffic.entity.StockRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 统一库存 Mapper（表 stock），读链路与写链路共用。
 */
public interface StockMapper {

    /**
     * 按车次 + 席别查询全部区段（按区段号升序），用于读链路 DB 兜底。
     */
    List<StockRecord> selectByInstanceNoAndSeatNo(@Param("instanceNo") String instanceNo,
                                                  @Param("seatNo") String seatNo);

    /**
     * 按车次查询全部"席别 × 区段"，用于预热。
     */
    List<StockRecord> selectByInstanceNo(@Param("instanceNo") String instanceNo);

    /**
     * 扣单个区段真实库存：remain>0 才允许扣，DB 层再防一次超卖。
     * 由 StockService 在一个事务内按区段循环调用。
     *
     * @return 影响行数，1 成功，0 库存不足（视为失败，整体回滚）
     */
    int deductOneSegment(@Param("instanceNo") String instanceNo,
                         @Param("seatNo") String seatNo,
                         @Param("segmentNo") Integer segmentNo);

    /**
     * 回补单个区段真实库存：remain + 1，但被总容量卡住（remain 必须小于该段 total_capacity）。
     * 由 StockService 在一个事务内按区段循环调用（取消/超时关单/退款）。
     *
     * @return 影响行数，1 回补成功，0 行不存在或已顶到总容量（调用方必须当失败处理并告警）
     */
    int restoreOneSegment(@Param("instanceNo") String instanceNo,
                          @Param("seatNo") String seatNo,
                          @Param("segmentNo") Integer segmentNo);

    /**
     * 登记"这一单的这个区段已经回补过"，唯一键冲突时 INSERT IGNORE 返回 0。
     *
     * @return 1 首次登记（该补），0 已登记过（跳过）
     */
    int insertRestoreRecord(@Param("orderNo") String orderNo,
                            @Param("seatNo") String seatNo,
                            @Param("segmentNo") Integer segmentNo);

    /** 巡检：近两天动过的库存行里，remain 超过总容量的 */
    List<java.util.Map<String, Object>> selectOverCapacity();

    /** 巡检：近两天动过的库存行里，remain 与「总容量 − 占用订单数」不符的 */
    List<java.util.Map<String, Object>> selectRemainDrift();

    /**
     * 新实例生成库存行：初始余票 = 该段总容量。
     * 唯一索引 (instance_no, seat_no, segment_no) 兜底幂等，冲突直接忽略。
     */
    int insertOne(@Param("instanceNo") String instanceNo,
                  @Param("trainNo") String trainNo,
                  @Param("seatNo") String seatNo,
                  @Param("segmentNo") Integer segmentNo,
                  @Param("remain") Integer remain);

    /** 删除某实例全部库存行（实例过期清理用） */
    int deleteByInstanceNo(@Param("instanceNo") String instanceNo);
}

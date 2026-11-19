package com.smartcity.traffic.mapper;

import com.smartcity.traffic.entity.TrainInstanceRecord;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 班次实例 Mapper（表 train_instance）。
 *
 * <p>唯一索引 uk_train_date 保证"同班次同一天只有一个实例"，
 * 插入冲突即代表已存在，窗口任务靠这个保证幂等。</p>
 */
public interface TrainInstanceMapper {

    /** 按实例编号查，最常用 */
    TrainInstanceRecord selectByInstanceNo(@Param("instanceNo") String instanceNo);

    /** 按班次 + 运营日期查 */
    TrainInstanceRecord selectByTrainNoAndDate(@Param("trainNo") String trainNo,
                                               @Param("travelDate") LocalDate travelDate);

    /** 新增实例，冲突由唯一索引兜底 */
    int insert(TrainInstanceRecord record);

    /** 标记库存已预热 */
    int markPreheated(@Param("instanceNo") String instanceNo);

    /** 改状态（正常/停运） */
    int updateStatus(@Param("instanceNo") String instanceNo,
                     @Param("status") Integer status);

    /** 统计某日期区间内的实例数，用于窗口完整性判断 */
    long countInRange(@Param("fromDate") LocalDate fromDate,
                      @Param("toDate") LocalDate toDate);

    /** 查某一天的全部实例，用于重建 */
    List<TrainInstanceRecord> selectByDate(@Param("travelDate") LocalDate travelDate);

    /** 查已过期实例（运营日期早于 cutoff） */
    List<TrainInstanceRecord> selectExpired(@Param("cutoff") LocalDate cutoff);

    /** 删除实例 */
    int deleteByInstanceNo(@Param("instanceNo") String instanceNo);
}

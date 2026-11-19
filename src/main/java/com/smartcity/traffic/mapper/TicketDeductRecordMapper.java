package com.smartcity.traffic.mapper;

import com.smartcity.traffic.entity.DeductRecord;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 扣减流水 Mapper。
 */
public interface TicketDeductRecordMapper {

    /** 新增流水 */
    int insert(DeductRecord record);

    /** 按订单号查流水 */
    DeductRecord selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * CAS 改状态：只有当前状态在 expectStatuses 里才改成 newStatus。
     *
     * @return 影响行数，1 抢占成功，0 已被别人改走
     */
    int casUpdateStatus(@Param("orderNo") String orderNo,
                        @Param("expectStatuses") List<String> expectStatuses,
                        @Param("newStatus") String newStatus,
                        @Param("failReason") String failReason);

    /**
     * 定时对账：查中间态（扣减中/已扣减）且 update_time 早于 before 的记录
     */
    List<DeductRecord> selectIntermediate(@Param("before") LocalDateTime before,
                                          @Param("limit") int limit);
}

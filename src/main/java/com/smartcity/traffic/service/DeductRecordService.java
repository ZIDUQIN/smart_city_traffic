package com.smartcity.traffic.service;

import com.smartcity.traffic.entity.DeductRecord;

import java.time.LocalDate;
import java.util.List;

/**
 * 扣减流水服务。
 */
public interface DeductRecordService {

    /** 新增一条"扣减中"流水（含实例编号、运营日期、起讫站序） */
    DeductRecord create(String orderNo, Long userId, Long passengerId,
                        String trainNo, String instanceNo, LocalDate travelDate,
                        String seatNo, Integer fromSeq, Integer toSeq);

    /**
     * CAS 改状态。
     *
     * @return 1 成功；0 状态已被别人改走
     */
    int casUpdate(String orderNo, List<String> expectStatuses, String newStatus, String failReason);

    /** 按订单号查流水 */
    DeductRecord getByOrderNo(String orderNo);

    /** 定时对账：查中间态且 update_time 早于阈值的记录 */
    List<DeductRecord> listIntermediate(int staleMinutes, int limit);
}

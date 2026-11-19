package com.smartcity.traffic.service.impl;

import com.smartcity.traffic.entity.DeductRecord;
import com.smartcity.traffic.mapper.TicketDeductRecordMapper;
import com.smartcity.traffic.service.DeductRecordService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 扣减流水服务实现。
 */
@Service
public class DeductRecordServiceImpl implements DeductRecordService {

    private final TicketDeductRecordMapper mapper;

    public DeductRecordServiceImpl(TicketDeductRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public DeductRecord create(String orderNo, Long userId, Long passengerId,
                               String trainNo, String instanceNo, LocalDate travelDate,
                               String seatNo, Integer fromSeq, Integer toSeq) {
        LocalDateTime now = LocalDateTime.now();
        DeductRecord record = new DeductRecord();
        record.setOrderNo(orderNo);
        record.setUserId(userId);
        record.setPassengerId(passengerId);
        record.setTrainNo(trainNo);
        record.setInstanceNo(instanceNo);
        record.setTravelDate(travelDate);
        record.setSeatNo(seatNo);
        record.setFromSeq(fromSeq);
        record.setToSeq(toSeq);
        record.setStatus(DeductRecord.STATUS_DEDUCTING);
        record.setCreateTime(now);
        record.setUpdateTime(now);
        mapper.insert(record);
        return record;
    }

    @Override
    public int casUpdate(String orderNo, List<String> expectStatuses, String newStatus, String failReason) {
        return mapper.casUpdateStatus(orderNo, expectStatuses, newStatus, failReason);
    }

    @Override
    public DeductRecord getByOrderNo(String orderNo) {
        return mapper.selectByOrderNo(orderNo);
    }

    @Override
    public List<DeductRecord> listIntermediate(int staleMinutes, int limit) {
        return mapper.selectIntermediate(LocalDateTime.now().minusMinutes(staleMinutes), limit);
    }
}

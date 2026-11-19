package com.smartcity.traffic.service.impl;

import com.smartcity.traffic.mapper.StockMapper;
import com.smartcity.traffic.service.StockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 真实库存服务实现。
 */
@Service
public class StockServiceImpl implements StockService {

    private static final Logger log = LoggerFactory.getLogger(StockServiceImpl.class);

    private final StockMapper stockMapper;

    public StockServiceImpl(StockMapper stockMapper) {
        this.stockMapper = stockMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deductRealStock(String instanceNo, String seatNo, List<Integer> segments) {
        // 每个区段独立条件 UPDATE，影响 0 行说明该区段库存不足，抛异常整体回滚
        for (Integer segmentNo : segments) {
            int rows = stockMapper.deductOneSegment(instanceNo, seatNo, segmentNo);
            if (rows != 1) {
                throw new IllegalStateException("DB 区段库存扣减失败：segmentNo=" + segmentNo);
            }
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean restoreRealStock(String orderNo, String instanceNo, String seatNo, List<Integer> segments) {
        for (Integer segmentNo : segments) {
            // 第一道闸：幂等。唯一键登记的返回值就是答案，跟订单状态无关。
            // 订单状态会被人工改库、会回退、会被别的流程覆盖，唯一索引改不掉。
            int registered = stockMapper.insertRestoreRecord(orderNo, seatNo, segmentNo);
            if (registered != 1) {
                log.info("库存回补跳过（该段已补过）：orderNo={} seatNo={} segmentNo={}",
                        orderNo, seatNo, segmentNo);
                continue;
            }

            // 第二道闸：上界。remain 必须小于该段总容量才允许 +1。
            int rows = stockMapper.restoreOneSegment(instanceNo, seatNo, segmentNo);
            if (rows != 1) {
                // 整单抛出去，让事务回滚：绝不接受"一段补成、一段没补"的半成品库存。
                throw new IllegalStateException(
                        "库存回补被容量闸拦下：orderNo=" + orderNo + " seatNo=" + seatNo
                                + " segmentNo=" + segmentNo + "（remain 已等于或超过总容量）");
            }
            log.info("库存回补成功：orderNo={} seatNo={} segmentNo={}", orderNo, seatNo, segmentNo);
        }
        return true;
    }
}

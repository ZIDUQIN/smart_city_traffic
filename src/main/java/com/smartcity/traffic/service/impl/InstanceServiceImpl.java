package com.smartcity.traffic.service.impl;

import com.smartcity.traffic.common.InstanceNoUtil;
import com.smartcity.traffic.common.StockKeys;
import com.smartcity.traffic.entity.StockCapacityRecord;
import com.smartcity.traffic.entity.StockRecord;
import com.smartcity.traffic.entity.Train;
import com.smartcity.traffic.entity.TrainInstanceRecord;
import com.smartcity.traffic.mapper.StockCapacityMapper;
import com.smartcity.traffic.mapper.StockMapper;
import com.smartcity.traffic.mapper.TrainInstanceMapper;
import com.smartcity.traffic.mapper.TrainMapper;
import com.smartcity.traffic.service.InstanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 滚动窗口实现。
 *
 * <p>三步：建实例（唯一索引兜底）→ 从容量表抄库存行（唯一索引兜底）→ HSETNX 预热。
 * 全程可重复执行，任何一步失败下一轮会补上。</p>
 */
@Service
public class InstanceServiceImpl implements InstanceService {

    private static final Logger log = LoggerFactory.getLogger(InstanceServiceImpl.class);

    private static final Set<String> SEAT_SET =
            Set.of("SECOND_CLASS", "FIRST_CLASS", "BUSINESS");

    private final TrainMapper trainMapper;
    private final TrainInstanceMapper trainInstanceMapper;
    private final StockCapacityMapper stockCapacityMapper;
    private final StockMapper stockMapper;
    private final StringRedisTemplate redisTemplate;

    public InstanceServiceImpl(TrainMapper trainMapper,
                               TrainInstanceMapper trainInstanceMapper,
                               StockCapacityMapper stockCapacityMapper,
                               StockMapper stockMapper,
                               StringRedisTemplate redisTemplate) {
        this.trainMapper = trainMapper;
        this.trainInstanceMapper = trainInstanceMapper;
        this.stockCapacityMapper = stockCapacityMapper;
        this.stockMapper = stockMapper;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public int ensureWindow(int days) {
        LocalDate today = LocalDate.now();
        List<Train> trains = trainMapper.selectActiveAll();
        int created = 0;

        for (Train train : trains) {
            for (int offset = 0; offset < days; offset++) {
                LocalDate date = today.plusDays(offset);
                String instanceNo = InstanceNoUtil.of(train.getTrainNo(), date);

                TrainInstanceRecord instance = trainInstanceMapper.selectByInstanceNo(instanceNo);
                if (instance == null) {
                    TrainInstanceRecord record = new TrainInstanceRecord();
                    record.setInstanceNo(instanceNo);
                    record.setTrainNo(train.getTrainNo());
                    record.setTravelDate(date);
                    record.setStatus(TrainInstanceRecord.STATUS_NORMAL);
                    record.setStockPreheated(TrainInstanceRecord.PREHEATED_NO);
                    LocalDateTime now = LocalDateTime.now();
                    record.setCreateTime(now);
                    record.setUpdateTime(now);
                    try {
                        trainInstanceMapper.insert(record);
                        created++;
                    } catch (DuplicateKeyException e) {
                        // 另一实例/线程先建好了，本轮跳过即可
                        log.debug("实例已存在，跳过 instanceNo={}", instanceNo);
                    }
                    instance = trainInstanceMapper.selectByInstanceNo(instanceNo);
                }

                // 库存行可能上一轮没写全，这里每次都补一遍（幂等）
                ensureStockRows(instanceNo, train.getTrainNo());

                // 新实例建成后立刻写 Redis；已标记预热的实例不再重复写
                if (instance != null && !Integer.valueOf(TrainInstanceRecord.PREHEATED_YES)
                        .equals(instance.getStockPreheated())) {
                    preheatInstance(instanceNo);
                }
            }
        }
        return created;
    }

    /**
     * 从容量表抄出该实例的库存行，初始余票 = 该段总容量。
     * 已存在的 (席别, 区段) 不重插，避免动到已扣减的余票。
     */
    private void ensureStockRows(String instanceNo, String trainNo) {
        List<StockCapacityRecord> capacities = stockCapacityMapper.selectByTrainNo(trainNo);
        if (capacities.isEmpty()) {
            log.warn("班次缺少容量配置，无法生成库存 trainNo={}", trainNo);
            return;
        }

        List<StockRecord> existing = stockMapper.selectByInstanceNo(instanceNo);
        Set<String> exists = new HashSet<>(existing.size());
        for (StockRecord row : existing) {
            exists.add(row.getSeatNo() + "#" + row.getSegmentNo());
        }

        for (StockCapacityRecord capacity : capacities) {
            String key = capacity.getSeatNo() + "#" + capacity.getSegmentNo();
            if (exists.contains(key)) {
                continue;
            }
            try {
                stockMapper.insertOne(instanceNo, trainNo, capacity.getSeatNo(),
                        capacity.getSegmentNo(), capacity.getTotalCapacity());
            } catch (DuplicateKeyException e) {
                log.debug("库存行已存在 instanceNo={} seat={} seg={}",
                        instanceNo, capacity.getSeatNo(), capacity.getSegmentNo());
            }
        }
    }

    @Override
    public boolean preheatInstance(String instanceNo) {
        TrainInstanceRecord instance = trainInstanceMapper.selectByInstanceNo(instanceNo);
        if (instance == null) {
            log.warn("预热失败：实例不存在 instanceNo={}", instanceNo);
            return false;
        }

        List<StockRecord> rows = stockMapper.selectByInstanceNo(instanceNo);
        if (rows.isEmpty()) {
            log.warn("预热失败：库存行为空 instanceNo={}", instanceNo);
            return false;
        }

        for (StockRecord row : rows) {
            // HSETNX 语义：已存在的区段字段一律不覆盖
            redisTemplate.opsForHash().putIfAbsent(
                    StockKeys.stock(instanceNo, row.getSeatNo()),
                    String.valueOf(row.getSegmentNo()),
                    String.valueOf(row.getRemain()));
        }
        trainInstanceMapper.markPreheated(instanceNo);
        return true;
    }

    @Override
    public int cleanupExpired(int delayDays) {
        LocalDate cutoff = LocalDate.now().minusDays(delayDays);
        List<TrainInstanceRecord> expired = trainInstanceMapper.selectExpired(cutoff);
        int removed = 0;

        for (TrainInstanceRecord instance : expired) {
            String instanceNo = instance.getInstanceNo();

            // 先删 Redis（库存 Hash 与已扣标记 Set）；乘车人占位 key 靠 TTL 自愈
            for (String seat : SEAT_SET) {
                redisTemplate.delete(StockKeys.stock(instanceNo, seat));
                redisTemplate.delete(StockKeys.deducted(instanceNo, seat));
            }

            stockMapper.deleteByInstanceNo(instanceNo);
            trainInstanceMapper.deleteByInstanceNo(instanceNo);
            removed++;
            log.info("过期实例已清理 instanceNo={} travelDate={}",
                    instanceNo, instance.getTravelDate());
        }
        return removed;
    }

    @Override
    public List<TrainInstanceRecord> windowState(String trainNo, int days) {
        LocalDate today = LocalDate.now();
        List<TrainInstanceRecord> result = new ArrayList<>(days);
        for (int offset = 0; offset < days; offset++) {
            result.add(trainInstanceMapper.selectByTrainNoAndDate(trainNo, today.plusDays(offset)));
        }
        return result;
    }
}

package com.smartcity.traffic.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smartcity.traffic.common.InstanceNoUtil;
import com.smartcity.traffic.common.BizException;
import com.smartcity.traffic.common.StockKeys;
import com.smartcity.traffic.dto.RemainResponse;
import com.smartcity.traffic.dto.SeatOffer;
import com.smartcity.traffic.dto.StationVO;
import com.smartcity.traffic.dto.TrainRouteRow;
import com.smartcity.traffic.dto.TrainSearchVO;
import com.smartcity.traffic.entity.StockRecord;
import com.smartcity.traffic.mapper.StockMapper;
import com.smartcity.traffic.mapper.TrainMapper;
import com.smartcity.traffic.service.PriceService;
import com.smartcity.traffic.service.TicketService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 车票查询业务实现（B2）：本地缓存 → Redis stock Hash → DB stock 表，三级读链路。
 *
 * 与写链路同源：读的就是 stock 表及其 Redis 投影，不再有第二套库存。
 * 余票口径：涉及区段取 MIN；单区段直接取值。
 */
@Service
public class TicketServiceImpl implements TicketService {

    private static final Set<String> SEAT_SET = Set.of("SECOND_CLASS", "FIRST_CLASS", "BUSINESS");

    /** 席别展示顺序与中文名，前端按这个顺序渲染 */
    private static final List<String> SEAT_ORDER = List.of("SECOND_CLASS", "FIRST_CLASS", "BUSINESS");

    private static final Map<String, String> SEAT_NAMES = Map.of(
            "SECOND_CLASS", "二等座",
            "FIRST_CLASS", "一等座",
            "BUSINESS", "商务座");

    /** 空值缓存 key 前缀：null:train:{trainNo}，防缓存穿透 */
    private static final String NULL_KEY_PREFIX = "null:train:";

    /** 空值缓存过期时间（秒） */
    private static final long NULL_TTL_SECONDS = 60L;

    private final StockMapper stockMapper;
    private final StringRedisTemplate redisTemplate;
    private final PriceService priceService;
    private final TrainMapper trainMapper;

    /** 本地缓存：TTL 1 秒，只缓存查询结果，下单扣减不使用本地缓存 */
    private final Cache<String, RemainCacheValue> localCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.SECONDS)
            .maximumSize(10_000)
            .build();

    public TicketServiceImpl(StockMapper stockMapper, StringRedisTemplate redisTemplate,
                             PriceService priceService, TrainMapper trainMapper) {
        this.stockMapper = stockMapper;
        this.redisTemplate = redisTemplate;
        this.priceService = priceService;
        this.trainMapper = trainMapper;
    }

    @Override
    public RemainResponse queryRemain(String trainNo, LocalDate travelDate,
                                      Integer fromSeq, Integer toSeq, String seatNo) {
        validate(trainNo, fromSeq, toSeq, seatNo);

        LocalDate date = travelDate == null ? LocalDate.now() : travelDate;
        String instanceNo = InstanceNoUtil.of(trainNo, date);

        String localKey = instanceNo + ":" + seatNo + ":" + fromSeq + ":" + toSeq;

        // ① 本地缓存命中：1 秒内直接返回，不访问 Redis
        RemainCacheValue cached = localCache.getIfPresent(localKey);
        if (cached != null) {
            return build(trainNo, seatNo, fromSeq, toSeq, cached.remain(), "LOCAL");
        }

        // ② 空值缓存：该实例不存在时直接返回，防穿透
        if (Boolean.TRUE.equals(redisTemplate.hasKey(NULL_KEY_PREFIX + instanceNo))) {
            throw new IllegalArgumentException("该日期车次不存在或无余票数据");
        }

        // ③ Redis stock Hash：HMGET 涉及区段，取 MIN
        Integer redisRemain = readFromRedis(instanceNo, seatNo, fromSeq, toSeq);
        if (redisRemain != null) {
            localCache.put(localKey, new RemainCacheValue(redisRemain));
            return build(trainNo, seatNo, fromSeq, toSeq, redisRemain, "REDIS");
        }

        // ④ DB 兜底：回源 stock 表并回填 Redis
        int remain = readFromDbAndFillCache(instanceNo, seatNo, fromSeq, toSeq);
        localCache.put(localKey, new RemainCacheValue(remain));
        return build(trainNo, seatNo, fromSeq, toSeq, remain, "DB");
    }

    /**
     * 从 Redis 读区间余票：涉及区段取 MIN。
     *
     * @return 余票；任一段缺失（未预热）返回 null，交给 DB 兜底
     */
    private Integer readFromRedis(String instanceNo, String seatNo, Integer fromSeq, Integer toSeq) {
        List<String> fields = segmentFields(fromSeq, toSeq);
        List<String> values = redisTemplate.<String, String>opsForHash()
                .multiGet(stockHashKey(instanceNo, seatNo), fields);
        if (values == null || values.isEmpty()) {
            return null;
        }

        int min = Integer.MAX_VALUE;
        for (Object value : values) {
            if (value == null) {
                return null;
            }
            int v = Integer.parseInt(value.toString());
            if (v < min) {
                min = v;
            }
        }
        return min;
    }

    /** DB 回源：查 stock 表，回填整个席别的 Hash，再算区间 MIN */
    private int readFromDbAndFillCache(String instanceNo, String seatNo,
                                       Integer fromSeq, Integer toSeq) {
        List<StockRecord> rows = stockMapper.selectByInstanceNoAndSeatNo(instanceNo, seatNo);
        if (rows.isEmpty()) {
            // 库里也没有：写空值标记 60 秒，防穿透
            redisTemplate.opsForValue().set(NULL_KEY_PREFIX + instanceNo, "1",
                    NULL_TTL_SECONDS, TimeUnit.SECONDS);
            throw new IllegalArgumentException("该日期车次不存在或无余票数据");
        }

        // 回填整个席别全部区段
        Map<String, String> hash = new HashMap<>(rows.size());
        Map<Integer, Integer> remainBySegment = new HashMap<>(rows.size());
        for (StockRecord row : rows) {
            hash.put(String.valueOf(row.getSegmentNo()), String.valueOf(row.getRemain()));
            remainBySegment.put(row.getSegmentNo(), row.getRemain());
        }
        redisTemplate.opsForHash().putAll(stockHashKey(instanceNo, seatNo), hash);

        // 区间 MIN
        int min = Integer.MAX_VALUE;
        for (int segment = fromSeq; segment < toSeq; segment++) {
            Integer remain = remainBySegment.get(segment);
            if (remain == null) {
                throw new IllegalArgumentException("区间超出该车次运行范围");
            }
            if (remain < min) {
                min = remain;
            }
        }
        return min;
    }

    private void validate(String trainNo, Integer fromSeq, Integer toSeq, String seatNo) {
        if (trainNo == null || trainNo.isBlank()) {
            throw new IllegalArgumentException("车次不能为空");
        }
        if (fromSeq == null || toSeq == null || fromSeq < 1 || toSeq <= fromSeq) {
            throw new IllegalArgumentException("站序不合法：需满足 上车站序 >= 1 且 下车站序 > 上车站序");
        }
        if (seatNo == null || !SEAT_SET.contains(seatNo)) {
            throw new IllegalArgumentException("席别不合法，可选值：SECOND_CLASS、FIRST_CLASS、BUSINESS");
        }
    }

    /** 区段号转 Hash field 字符串列表 */
    private List<String> segmentFields(Integer fromSeq, Integer toSeq) {
        List<String> fields = new java.util.ArrayList<>();
        for (int i = fromSeq; i < toSeq; i++) {
            fields.add(String.valueOf(i));
        }
        return fields;
    }

    /**
     * 组装响应并附带票价。
     * 票价没配只影响展示，不该让余票查询失败，所以这里显式降级为 null。
     */
    private RemainResponse build(String trainNo, String seatNo, Integer fromSeq, Integer toSeq,
                                 int remain, String source) {
        RemainResponse response = new RemainResponse(trainNo, seatNo, fromSeq, toSeq, remain, source);
        try {
            response.setPrice(priceService.calculate(trainNo, seatNo, fromSeq, toSeq));
        } catch (BizException e) {
            response.setPrice(null);
        }
        return response;
    }

    // ---------- 按站查车次（前端订票页） ----------

    @Override
    public List<TrainSearchVO> search(String fromStation, String toStation, LocalDate travelDate) {
        if (fromStation == null || fromStation.isBlank()) {
            throw new IllegalArgumentException("出发站不能为空");
        }
        if (toStation == null || toStation.isBlank()) {
            throw new IllegalArgumentException("到达站不能为空");
        }
        if (fromStation.trim().equals(toStation.trim())) {
            throw new IllegalArgumentException("出发站与到达站不能相同");
        }

        LocalDate date = travelDate == null ? LocalDate.now() : travelDate;
        List<TrainRouteRow> rows = trainMapper.selectByStations(fromStation.trim(), toStation.trim());
        List<TrainSearchVO> result = new ArrayList<>(rows.size());

        for (TrainRouteRow row : rows) {
            TrainSearchVO vo = new TrainSearchVO();
            vo.setTrainNo(row.getTrainNo());
            vo.setInstanceNo(InstanceNoUtil.of(row.getTrainNo(), date));
            vo.setFromStation(fromStation.trim());
            vo.setToStation(toStation.trim());
            vo.setFromSeq(row.getFromSeq());
            vo.setToSeq(row.getToSeq());
            vo.setDepartTime(formatTime(row.getDepartTime()));
            vo.setArriveTime(formatTime(row.getArriveTime()));
            vo.setDurationMinutes(durationMinutes(row.getDepartTime(), row.getArriveTime()));

            List<SeatOffer> seats = new ArrayList<>(SEAT_ORDER.size());
            for (String seatNo : SEAT_ORDER) {
                seats.add(buildOffer(row, date, seatNo));
            }
            vo.setSeats(seats);
            result.add(vo);
        }
        return result;
    }

    @Override
    public boolean routeExists(String fromStation, String toStation) {
        if (fromStation == null || toStation == null
                || fromStation.isBlank() || toStation.isBlank()
                || fromStation.trim().equals(toStation.trim())) {
            return false;
        }
        Integer hit = trainMapper.selectRouteExists(fromStation.trim(), toStation.trim());
        return hit != null;
    }

    @Override
    public List<StationVO> stations() {
        return trainMapper.selectAllStations();
    }

    /**
     * 单个席别：余票查不到（实例缺失 / 未预热）统一按 0 处理，
     * 不能因为一个席别异常就让整条车次从列表里消失。
     * 票价独立计算，缺价时保持 null，前端据此置灰不可下单。
     */
    private SeatOffer buildOffer(TrainRouteRow row, LocalDate date, String seatNo) {
        int remain;
        BigDecimal price;
        try {
            RemainResponse r = queryRemain(row.getTrainNo(), date,
                    row.getFromSeq(), row.getToSeq(), seatNo);
            remain = r.getRemain();
            price = r.getPrice();
        } catch (RuntimeException e) {
            remain = 0;
            price = null;
        }
        if (price == null) {
            try {
                price = priceService.calculate(row.getTrainNo(), seatNo,
                        row.getFromSeq(), row.getToSeq());
            } catch (BizException e) {
                price = null;
            }
        }
        return new SeatOffer(seatNo, SEAT_NAMES.get(seatNo), remain, price);
    }

    private String formatTime(LocalTime time) {
        return time == null ? null : String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    /** 历时分钟：arrive 早于 depart 视为跨天，补 24 小时 */
    private Integer durationMinutes(LocalTime depart, LocalTime arrive) {
        if (depart == null || arrive == null) {
            return null;
        }
        long minutes = Duration.between(depart, arrive).toMinutes();
        if (minutes < 0) {
            minutes += 24 * 60;
        }
        return (int) minutes;
    }

    /** stock Hash key */
    private String stockHashKey(String instanceNo, String seatNo) {
        return StockKeys.stock(instanceNo, seatNo);
    }

    /** 本地缓存值包装 */
    private record RemainCacheValue(int remain) {
    }
}

package com.smartcity.traffic.service.impl;

import com.smartcity.traffic.common.AlarmService;
import com.smartcity.traffic.common.BizException;
import com.smartcity.traffic.common.BusinessMetrics;
import com.smartcity.traffic.common.CurrentUserHelper;
import com.smartcity.traffic.common.RateLimitException;
import com.smartcity.traffic.common.InstanceNoUtil;
import com.smartcity.traffic.common.SnowflakeIdGenerator;
import com.smartcity.traffic.common.StockKeys;
import com.smartcity.traffic.dto.OrderRequest;
import com.smartcity.traffic.dto.OrderResponse;
import com.smartcity.traffic.entity.DeductRecord;
import com.smartcity.traffic.entity.OrderRecord;
import com.smartcity.traffic.entity.Passenger;
import com.smartcity.traffic.entity.StockRecord;
import com.smartcity.traffic.mapper.OrderMapper;
import com.smartcity.traffic.mapper.PassengerMapper;
import com.smartcity.traffic.mapper.StockMapper;
import com.smartcity.traffic.mapper.TrainInstanceMapper;
import com.smartcity.traffic.mq.OrderTxArg;
import com.smartcity.traffic.mq.OrderTxProducer;
import com.smartcity.traffic.service.DeductRecordService;
import com.smartcity.traffic.service.OrderService;
import com.smartcity.traffic.service.PriceService;
import com.smartcity.traffic.service.RateLimitService;
import com.smartcity.traffic.service.RedisLuaService;
import com.smartcity.traffic.service.StockService;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 抢票下单服务（B2 区段模型）。
 *
 * 主流程严格按序：写流水 → 扣 Redis（Lua）→ 建订单 → 扣 DB 真实库存。
 * 库存全系统唯一：stock 表 + Redis stock Hash，读链路也读同一处。
 */
@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private static final Set<String> SEAT_SET = Set.of("SECOND_CLASS", "FIRST_CLASS", "BUSINESS");

    private final OrderTxProducer orderTxProducer;
    private final SnowflakeIdGenerator idGenerator;
    private final PassengerMapper passengerMapper;
    private final CurrentUserHelper currentUserHelper;
    private final DeductRecordService deductRecordService;
    private final RedisLuaService redisLuaService;
    private final OrderMapper orderMapper;
    private final StockMapper stockMapper;
    private final StockService stockService;
    private final TrainInstanceMapper trainInstanceMapper;
    private final PriceService priceService;
    private final RateLimitService rateLimitService;
    private final StringRedisTemplate redisTemplate;
    private final AlarmService alarmService;
    private final BusinessMetrics metrics;

    public OrderServiceImpl(OrderTxProducer orderTxProducer,
                            SnowflakeIdGenerator idGenerator,
                            PassengerMapper passengerMapper,
                            CurrentUserHelper currentUserHelper,
                            DeductRecordService deductRecordService,
                            RedisLuaService redisLuaService,
                            OrderMapper orderMapper,
                            StockMapper stockMapper,
                            StockService stockService,
                            TrainInstanceMapper trainInstanceMapper,
                            PriceService priceService,
                            RateLimitService rateLimitService,
                            StringRedisTemplate redisTemplate,
                            AlarmService alarmService,
                            BusinessMetrics metrics) {
        this.orderTxProducer = orderTxProducer;
        this.idGenerator = idGenerator;
        this.passengerMapper = passengerMapper;
        this.currentUserHelper = currentUserHelper;
        this.deductRecordService = deductRecordService;
        this.redisLuaService = redisLuaService;
        this.orderMapper = orderMapper;
        this.stockMapper = stockMapper;
        this.stockService = stockService;
        this.trainInstanceMapper = trainInstanceMapper;
        this.priceService = priceService;
        this.rateLimitService = rateLimitService;
        this.redisTemplate = redisTemplate;
        this.alarmService = alarmService;
        this.metrics = metrics;
    }

    @Override
    public OrderResponse createOrder(OrderRequest request) {
        validate(request);

        // 运营日期与实例编号：不传日期按当天算，实例编号 = 班次号-yyyyMMdd
        LocalDate travelDate = request.getTravelDate() == null
                ? LocalDate.now() : request.getTravelDate();
        String instanceNo = InstanceNoUtil.of(request.getTrainNo(), travelDate);

        // 外层：全局限流（按车次，令牌桶）。容量闸门优先——系统扛不住时，后面的判断都没有意义。
        // 超额请求在这里被挡掉，不查乘车人、不进预检、不发 MQ、不写流水。
        if (!rateLimitService.tryAcquireGlobal(request.getTrainNo())) {
            metrics.orderFail("限流-全局");
            throw new RateLimitException("当前抢票人数过多，请稍后再试");
        }

        // 内层：单用户限流（按 用户 + 车次），防同一用户反复刷单。
        Long userId = currentUserHelper.currentUserId();
        if (!rateLimitService.tryAcquire(userId, request.getTrainNo())) {
            metrics.orderFail("限流-单用户");
            throw new RateLimitException("操作过于频繁，请稍后再试");
        }

        if (!orderTxProducer.isAvailable()) {
            throw new BizException("消息服务未就绪，请稍后再试");
        }

        // 乘车人归属校验：必须是当前登录用户自己维护的乘车人
        Passenger passenger = passengerMapper.selectById(request.getPassengerId());
        if (passenger == null || !passenger.getUserId().equals(userId)) {
            throw new BizException("乘车人不存在或无权使用");
        }

        // 预检（只读 Lua）：判重 + 库存检查，不通过直接返回，不发 MQ、不写流水、不占 DB 连接
        List<Integer> preSegments = buildSegments(request.getFromSeq(), request.getToSeq());
        int checkResult = redisLuaService.check(instanceNo, request.getSeatNo(),
                request.getPassengerId(), preSegments);
        if (checkResult < 0) {
            // 业务失败也要计数：HTTP 层看不出来（都是 200），只有这里能区分
            metrics.orderFail(failReason((long) checkResult));
            return new OrderResponse(null, "已取消", failReason((long) checkResult));
        }

        String orderNo = String.valueOf(idGenerator.nextId());

        OrderTxArg arg = new OrderTxArg(orderNo, userId, request.getPassengerId(),
                request.getTrainNo(), instanceNo, travelDate,
                request.getSeatNo(), request.getFromSeq(), request.getToSeq());

        try {
            orderTxProducer.sendInTransaction(arg);
        } catch (Exception e) {
            log.error("事务消息发送失败 orderNo={}", orderNo, e);
            metrics.orderFail("系统异常");
            throw new BizException("下单失败，请稍后再试");
        }

        if (arg.isSuccess()) {
            metrics.orderSuccess();
            return new OrderResponse(orderNo, "已确认", "抢票成功");
        }
        metrics.orderFail(arg.getFailReason() == null ? "抢票失败" : arg.getFailReason());
        return new OrderResponse(orderNo, "已取消",
                arg.getFailReason() == null ? "抢票失败" : arg.getFailReason());
    }

    @Override
    public LocalTransactionState executeLocalSteps(OrderTxArg arg) {
        // 步骤0：按起讫站序算区段数组（fromSeq..toSeq-1）
        List<Integer> segments = buildSegments(arg.getFromSeq(), arg.getToSeq());

        // 步骤1：写流水（扣减中），失败无任何已成功操作，直接 ROLLBACK
        try {
            deductRecordService.create(arg.getOrderNo(), arg.getUserId(), arg.getPassengerId(),
                    arg.getTrainNo(), arg.getInstanceNo(), arg.getTravelDate(),
                    arg.getSeatNo(), arg.getFromSeq(), arg.getToSeq());
        } catch (Exception e) {
            log.error("步骤1 写流水失败 orderNo={}", arg.getOrderNo(), e);
            arg.setSuccess(false);
            arg.setFailReason("系统繁忙，请稍后再试");
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }

        // 步骤2：Lua 扣 Redis（判重 + 多区段检查 + 多区段扣减 + 已扣标记）
        long luaResult;
        try {
            luaResult = redisLuaService.deduct(arg.getInstanceNo(), arg.getSeatNo(),
                    arg.getPassengerId(), arg.getOrderNo(), segments);
        } catch (Exception e) {
            log.error("步骤2 扣 Redis 异常 orderNo={}", arg.getOrderNo(), e);
            markCanceled(arg, "系统繁忙，请稍后再试");
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }

        if (luaResult < 0) {
            // -1/-2/-3：Redis 未扣成功，CAS 改已取消，不回补
            markCanceled(arg, failReason(luaResult));
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }

        // 扣减成功：CAS 扣减中 → 已扣减
        int deductRows = deductRecordService.casUpdate(arg.getOrderNo(),
                List.of(DeductRecord.STATUS_DEDUCTING), DeductRecord.STATUS_DEDUCTED, null);
        if (deductRows == 0) {
            // 已被对账任务抢占，停止后续动作，交给对账
            log.warn("步骤2 后 CAS 已扣减失败，本单可能已被对账接管 orderNo={}", arg.getOrderNo());
            return LocalTransactionState.UNKNOW;
        }

        // 步骤3：建订单（待确认）。失败：回补 Redis + CAS 已补偿，禁止 cancelOrder
        try {
            orderMapper.insert(buildOrder(arg));
        } catch (Exception e) {
            log.error("步骤3 建订单失败 orderNo={}", arg.getOrderNo(), e);
            try {
                redisLuaService.rollback(arg.getInstanceNo(), arg.getSeatNo(),
                        arg.getPassengerId(), arg.getOrderNo(), segments);
                deductRecordService.casUpdate(arg.getOrderNo(),
                        List.of(DeductRecord.STATUS_DEDUCTED),
                        DeductRecord.STATUS_COMPENSATED, "订单创建失败");
            } catch (Exception ex) {
                // 补偿自身失败：保持已扣减，交定时任务
                log.error("步骤3 补偿失败，保持已扣减 orderNo={}", arg.getOrderNo(), ex);
                alarmService.send("步骤3补偿失败 orderNo=" + arg.getOrderNo());
            }
            arg.setSuccess(false);
            arg.setFailReason("系统繁忙，请稍后再试");
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }

        // 步骤4：扣 DB 真实库存（一个事务内按区段循环）
        try {
            stockService.deductRealStock(arg.getInstanceNo(), arg.getSeatNo(), segments);
        } catch (Exception e) {
            log.error("步骤4 扣 DB 库存失败 orderNo={}", arg.getOrderNo(), e);
            try {
                cancelOrder(arg.getOrderNo());
                redisLuaService.rollback(arg.getInstanceNo(), arg.getSeatNo(),
                        arg.getPassengerId(), arg.getOrderNo(), segments);
                deductRecordService.casUpdate(arg.getOrderNo(),
                        List.of(DeductRecord.STATUS_DEDUCTED),
                        DeductRecord.STATUS_CANCELED, "DB库存扣减失败");
            } catch (Exception ex) {
                log.error("步骤4 补偿失败，保持已扣减 orderNo={}", arg.getOrderNo(), ex);
                alarmService.send("步骤4补偿失败 orderNo=" + arg.getOrderNo());
            }
            arg.setSuccess(false);
            arg.setFailReason("系统繁忙，请稍后再试");
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }

        // DB 成功：订单 待确认 → 已确认
        int confirmRows = orderMapper.casUpdateStatus(arg.getOrderNo(),
                OrderRecord.STATUS_PENDING, OrderRecord.STATUS_CONFIRMED);
        if (confirmRows == 0) {
            // 订单已被对账取消，回滚 Redis 并抛异常
            safeRollback(arg, segments);
            throw new BizException("本单已超时被补偿任务接管，请重新下单");
        }

        // 流水 已扣减 → 已建单
        int createRows = deductRecordService.casUpdate(arg.getOrderNo(),
                List.of(DeductRecord.STATUS_DEDUCTED), DeductRecord.STATUS_CREATED, null);
        if (createRows == 0) {
            // 流水已被对账接管：取消订单 + 回补 + 抛异常，禁止 COMMIT
            cancelOrder(arg.getOrderNo());
            safeRollback(arg, segments);
            throw new BizException("本单已超时被补偿任务接管，请重新下单");
        }

        arg.setSuccess(true);
        return LocalTransactionState.COMMIT_MESSAGE;
    }

    @Override
    public void cancelOrder(String orderNo) {
        // 幂等：只在待确认时改已取消，已取消的再取消直接返回
        OrderRecord existing = orderMapper.selectByOrderNo(orderNo);
        if (existing == null) {
            return;
        }
        orderMapper.casUpdateStatus(orderNo, OrderRecord.STATUS_PENDING, OrderRecord.STATUS_CANCELED);
    }

    /**
     * 按班次实例预热：把 stock 表余票写进 Redis stock Hash。
     *
     * <p>只用 HSETNX（putIfAbsent）补缺失字段，已存在的字段一律不动，
     * 这样已售出座位不会被写回满票。整键覆盖只允许出现在"重建"场景。</p>
     *
     * @param travelDate 运营日期，为空按当天
     * @param seatNo     可选；为空时预热该实例全部席别
     * @return 新写入的区段条数
     */
    @Override
    public int preheat(String trainNo, LocalDate travelDate, String seatNo) {
        LocalDate date = travelDate == null ? LocalDate.now() : travelDate;
        String instanceNo = InstanceNoUtil.of(trainNo, date);

        // 实例不存在直接拒绝：没有这一天的班次，就不该有这一天的库存
        if (trainInstanceMapper.selectByInstanceNo(instanceNo) == null) {
            throw new BizException("该日期没有该班次：" + instanceNo);
        }

        List<StockRecord> rows = stockMapper.selectByInstanceNo(instanceNo);
        if (rows.isEmpty()) {
            throw new BizException("班次实例无库存数据：" + instanceNo);
        }

        // 按席别分组，每个席别一个 Hash
        Map<String, List<StockRecord>> grouped = new HashMap<>();
        for (StockRecord row : rows) {
            if (seatNo != null && !seatNo.equals(row.getSeatNo())) {
                continue;
            }
            grouped.computeIfAbsent(row.getSeatNo(), k -> new ArrayList<>()).add(row);
        }

        int count = 0;
        for (Map.Entry<String, List<StockRecord>> entry : grouped.entrySet()) {
            String key = stockHashKey(instanceNo, entry.getKey());
            for (StockRecord row : entry.getValue()) {
                Boolean created = redisTemplate.opsForHash().putIfAbsent(
                        key, String.valueOf(row.getSegmentNo()), String.valueOf(row.getRemain()));
                if (Boolean.TRUE.equals(created)) {
                    count++;
                }
            }
        }

        // 标记实例已预热，窗口任务据此判断这一天是否还需要补
        trainInstanceMapper.markPreheated(instanceNo);
        return count;
    }

    // ---------- 私有辅助 ----------

    /** 步骤2 失败统一处理：CAS 扣减中 → 已取消（Redis 未扣，不回补） */
    private void markCanceled(OrderTxArg arg, String reason) {
        deductRecordService.casUpdate(arg.getOrderNo(),
                List.of(DeductRecord.STATUS_DEDUCTING),
                DeductRecord.STATUS_CANCELED, reason);
        arg.setSuccess(false);
        arg.setFailReason(reason);
    }

    private String failReason(long luaResult) {
        if (luaResult == -1) {
            return "余票不足，抢票失败";
        }
        if (luaResult == -2) {
            return "库存未预热，请先预热";
        }
        if (luaResult == -3) {
            return "该乘车人已购买此车次车票";
        }
        return "抢票失败";
    }

    /** 构建待确认订单（含起讫站序） */
    private OrderRecord buildOrder(OrderTxArg arg) {
        LocalDateTime now = LocalDateTime.now();
        OrderRecord order = new OrderRecord();
        order.setOrderNo(arg.getOrderNo());
        order.setUserId(arg.getUserId());
        order.setPassengerId(arg.getPassengerId());
        order.setTrainNo(arg.getTrainNo());
        order.setInstanceNo(arg.getInstanceNo());
        order.setTravelDate(arg.getTravelDate());
        order.setSeatNo(arg.getSeatNo());
        order.setFromSeq(arg.getFromSeq());
        order.setToSeq(arg.getToSeq());
        // 按票价规则计算订单金额
        order.setAmount(priceService.calculate(arg.getTrainNo(), arg.getSeatNo(),
                arg.getFromSeq(), arg.getToSeq()));
        order.setStatus(OrderRecord.STATUS_PENDING);
        order.setCreateTime(now);
        order.setUpdateTime(now);
        return order;
    }

    /** 结尾 CAS 失败时的兜底回补：失败只告警，保持状态交对账 */
    private void safeRollback(OrderTxArg arg, List<Integer> segments) {
        try {
            redisLuaService.rollback(arg.getInstanceNo(), arg.getSeatNo(),
                    arg.getPassengerId(), arg.getOrderNo(), segments);
        } catch (Exception ex) {
            log.error("结尾回补失败 orderNo={}", arg.getOrderNo(), ex);
            alarmService.send("结尾回补失败 orderNo=" + arg.getOrderNo());
        }
    }

    /** 区段数组：fromSeq..toSeq-1 */
    private List<Integer> buildSegments(Integer fromSeq, Integer toSeq) {
        List<Integer> segments = new ArrayList<>();
        for (int i = fromSeq; i < toSeq; i++) {
            segments.add(i);
        }
        return segments;
    }

    /** 预热用 stock Hash key */
    private String stockHashKey(String instanceNo, String seatNo) {
        return StockKeys.stock(instanceNo, seatNo);
    }

    private void validate(OrderRequest request) {
        if (request == null || request.getPassengerId() == null) {
            throw new BizException("请选择乘车人");
        }
        if (request.getTrainNo() == null || request.getTrainNo().isBlank()) {
            throw new BizException("车次不能为空");
        }
        if (request.getSeatNo() == null || !SEAT_SET.contains(request.getSeatNo())) {
            throw new BizException("席别不合法");
        }
        if (request.getFromSeq() == null || request.getToSeq() == null
                || request.getFromSeq() < 1 || request.getToSeq() <= request.getFromSeq()) {
            throw new BizException("站序不合法");
        }
    }
}

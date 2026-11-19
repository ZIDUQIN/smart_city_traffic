package com.smartcity.traffic.service.impl;

import com.smartcity.traffic.common.AlarmService;
import com.smartcity.traffic.common.BizException;
import com.smartcity.traffic.common.CurrentUserHelper;
import com.smartcity.traffic.common.HmacSignUtil;
import com.smartcity.traffic.common.SnowflakeIdGenerator;
import com.smartcity.traffic.dto.RefundResponse;
import com.smartcity.traffic.entity.OrderRecord;
import com.smartcity.traffic.entity.PaymentRecord;
import com.smartcity.traffic.entity.RefundRecord;
import com.smartcity.traffic.enums.RefundQueryResult;
import com.smartcity.traffic.mapper.OrderMapper;
import com.smartcity.traffic.mapper.PaymentRecordMapper;
import com.smartcity.traffic.mapper.RefundRecordMapper;
import com.smartcity.traffic.service.MockPayChannelService;
import com.smartcity.traffic.service.RedisLuaService;
import com.smartcity.traffic.service.RefundService;
import com.smartcity.traffic.service.StockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 退票退款实现（Mock 渠道）。
 *
 * 核心原则：
 * 1. 订单状态是"是否占座"的唯一判据，必须先切状态出「已支付」，再回补库存；
 * 2. Redis 不参与 DB 事务，回补必须放在事务提交之后，否则事务回滚会造成库存虚高；
 * 3. 退款是外部调用，失败方向必须单向（最多钱晚到），绝不产生超卖。
 */
@Service
public class RefundServiceImpl implements RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundServiceImpl.class);

    private final RefundRecordMapper refundRecordMapper;
    private final OrderMapper orderMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final MockPayChannelService channel;
    private final RedisLuaService redisLuaService;
    private final StockService stockService;
    private final SnowflakeIdGenerator idGenerator;
    private final CurrentUserHelper currentUserHelper;
    private final AlarmService alarmService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final String hmacSecret;
    /** 手续费比例，真实 12306 按开车前时间阶梯，本项目无日期维度，用固定比例 */
    private final BigDecimal feeRate;
    /** 对账：停在「退款中」超过多少秒才纳入 */
    private final int reconcileTimeoutSeconds;
    /** 对账：单条最多自动处理几轮 */
    private final int reconcileMaxRetry;
    /** 对账：每轮最多处理多少条 */
    private final int reconcileBatchSize;
    /**
     * 对账：退款单账龄超过多少小时就不再重发。
     *
     * 渠道只保留有限期的流水（通常几个月），单子太老查不到时，
     * "查不到=请求没到" 这个假设就不成立，重发可能重复退钱，只能转人工。
     */
    private final int reconcileMaxAgeHours;

    public RefundServiceImpl(RefundRecordMapper refundRecordMapper,
                             OrderMapper orderMapper,
                             PaymentRecordMapper paymentRecordMapper,
                             MockPayChannelService channel,
                             RedisLuaService redisLuaService,
                             StockService stockService,
                             SnowflakeIdGenerator idGenerator,
                             CurrentUserHelper currentUserHelper,
                             AlarmService alarmService,
                             ObjectMapper objectMapper,
                             PlatformTransactionManager transactionManager,
                             @Value("${pay.mock.hmac-secret}") String hmacSecret,
                             @Value("${refund.fee-rate:0.00}") BigDecimal feeRate,
                             @Value("${refund.reconcile.timeout-seconds:300}") int reconcileTimeoutSeconds,
                             @Value("${refund.reconcile.max-retry:5}") int reconcileMaxRetry,
                             @Value("${refund.reconcile.batch-size:20}") int reconcileBatchSize,
                             @Value("${refund.reconcile.max-age-hours:168}") int reconcileMaxAgeHours) {
        this.refundRecordMapper = refundRecordMapper;
        this.orderMapper = orderMapper;
        this.paymentRecordMapper = paymentRecordMapper;
        this.channel = channel;
        this.redisLuaService = redisLuaService;
        this.stockService = stockService;
        this.idGenerator = idGenerator;
        this.currentUserHelper = currentUserHelper;
        this.alarmService = alarmService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.hmacSecret = hmacSecret;
        this.feeRate = feeRate;
        this.reconcileTimeoutSeconds = reconcileTimeoutSeconds;
        this.reconcileMaxRetry = reconcileMaxRetry;
        this.reconcileBatchSize = reconcileBatchSize;
        this.reconcileMaxAgeHours = reconcileMaxAgeHours;
    }

    @Override
    public RefundResponse applyRefund(String orderNo, String reason) {
        // ================= ① 前置校验 =================
        OrderRecord order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BizException("订单不存在");
        }
        // 归属校验：只能退自己的订单
        if (!order.getUserId().equals(currentUserHelper.currentUserId())) {
            throw new BizException("无权操作该订单");
        }
        // 只有已支付订单能退票；已确认是待支付，走 cancel
        if (!OrderRecord.STATUS_PAID.equals(order.getStatus())) {
            throw new BizException("只有已支付订单可以退票");
        }
        // 必须存在支付成功流水，否则不知道该退哪笔钱
        PaymentRecord payment = paymentRecordMapper.selectSuccessByOrderNo(orderNo);
        if (payment == null) {
            throw new BizException("未找到支付成功流水，无法退票");
        }

        // ================= ② 金额计算 =================
        BigDecimal amount = order.getAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("订单金额异常");
        }
        BigDecimal fee = amount.multiply(feeRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal refundAmount = amount.subtract(fee);
        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("退款金额异常");
        }

        // ================= ③ 事务：插退款流水 + 订单转退款中 =================
        // 事务里只做两件本地写操作，不碰 Redis、不发 HTTP：
        // 一旦事务回滚，外部副作用不会留下残迹。
        LocalDateTime now = LocalDateTime.now();
        RefundRecord record = new RefundRecord();
        String refundNo = String.valueOf(idGenerator.nextId());
        record.setRefundNo(refundNo);
        record.setOrderNo(orderNo);
        record.setPaymentNo(payment.getPaymentNo());
        record.setUserId(order.getUserId());
        record.setAmount(amount);
        record.setFee(fee);
        record.setRefundAmount(refundAmount);
        record.setStatus(RefundRecord.STATUS_REFUNDING);
        record.setReason(reason);
        record.setCallbackCount(0);
        // 库存回补与对账重试的初始状态（表列 NOT NULL，必须显式赋值）
        record.setStockRestored(0);
        record.setRetryCount(0);
        record.setCreateTime(now);
        record.setUpdateTime(now);

        Boolean ok = transactionTemplate.execute(status -> {
            try {
                refundRecordMapper.insert(record);
            } catch (DuplicateKeyException e) {
                // uk_order_no 冲突 = 该订单已有退款流水，防并发重复退票
                throw new BizException("该订单已提交退票，请勿重复操作");
            }
            // 状态闸门：只有仍是"已支付"才能转为"退款中"。
            // 这一步成功之后，该订单不再被视为占用座位。
            int rows = orderMapper.casUpdateStatus(orderNo,
                    OrderRecord.STATUS_PAID, OrderRecord.STATUS_REFUNDING);
            if (rows != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });

        if (!Boolean.TRUE.equals(ok)) {
            throw new BizException("退票失败，订单状态已变更，请刷新后重试");
        }

        // ================= ④ 事务提交后：回补库存 + 置回补标记 =================
        // 此时订单已是"退款中"，不再占座，可以安全地把座位放出去。
        //
        // 为什么放同一个事务：
        //   restoreRealStock 是 remain + 1，语义上不幂等，重复执行会多补库存（超卖）。
        //   所以必须和"标记已回补"绑在一起，让对账能判断到底补没补过。
        //   rollback.lua 本身幂等（SREM 返回 1 才真正 +1），放事务内重复调用无副作用。
        List<Integer> segments = segmentsOf(order);
        try {
            transactionTemplate.execute(status -> {
                redisLuaService.rollback(order.resolveInstanceNo(), order.getSeatNo(),
                        order.getPassengerId(), orderNo, segments);
                stockService.restoreRealStock(orderNo, order.resolveInstanceNo(),
                        order.getSeatNo(), segments);
                refundRecordMapper.markStockRestored(refundNo);
                return true;
            });
        } catch (Exception e) {
            // 事务回滚：DB 未补、标记未置，交由对账补偿
            alarmService.send("退票库存回补失败，等待对账补偿 orderNo=" + orderNo
                    + " refundNo=" + refundNo, e);
        }

        // ================= ⑤ 调用渠道退款（异步回调） =================
        // 渠道单号必须立刻落库：对账靠它区分「渠道没受理」（可重发）和「渠道已受理」（只能查）
        try {
            String channelRefundNo = channel.refund(record);
            if (channelRefundNo != null) {
                refundRecordMapper.updateChannelRefundNo(refundNo, channelRefundNo);
            }
        } catch (Exception e) {
            alarmService.send("退票渠道退款调用失败，渠道单号未落库 orderNo=" + orderNo
                    + " refundNo=" + refundNo, e);
        }

        return new RefundResponse(refundNo, orderNo, amount, fee, refundAmount,
                RefundRecord.STATUS_REFUNDING, reason);
    }

    @Override
    public boolean handleNotify(String rawBody, String signature) {
        // ① 验签：失败直接拒绝，不做任何业务
        if (!HmacSignUtil.verify(hmacSecret, rawBody, signature)) {
            log.warn("退款回调验签失败");
            return false;
        }

        // ② 解析报文
        JsonNode node;
        try {
            node = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            log.error("退款回调报文解析失败", e);
            return false;
        }
        String refundNo = node.path("refundNo").asText();
        String orderNo = node.path("orderNo").asText();
        String channelRefundNo = node.path("channelRefundNo").asText();
        BigDecimal callbackAmount;
        try {
            callbackAmount = new BigDecimal(node.path("amount").asText());
        } catch (Exception e) {
            log.error("退款回调金额解析失败", e);
            return false;
        }

        // ③ 加载数据：任一缺失都挂起等重试/人工，不返回成功
        RefundRecord record = refundRecordMapper.selectByRefundNo(refundNo);
        OrderRecord order = orderMapper.selectByOrderNo(orderNo);
        if (record == null || order == null) {
            alarmService.send("退款回调退款单/订单不存在 refundNo=" + refundNo + " orderNo=" + orderNo);
            return false;
        }

        // ④ 金额校验：必须与申请退票时算出的实退金额一致
        if (record.getRefundAmount().compareTo(callbackAmount) != 0) {
            alarmService.send("退款回调金额不符 refundNo=" + refundNo
                    + " expect=" + record.getRefundAmount() + " actual=" + callbackAmount);
            return false;
        }

        // ⑤ 幂等：已退款成功，只累加回调次数并应答 SUCCESS（渠道会重复回调）
        if (RefundRecord.STATUS_SUCCESS.equals(record.getStatus())) {
            refundRecordMapper.incrCallbackCount(refundNo);
            return true;
        }

        // ⑥ 事务内幂等更新：流水退款成功 + 订单已退款
        // 注意：库存在申请退票时已回补，回调里不再碰库存
        Boolean ok = transactionTemplate.execute(status -> {
            refundRecordMapper.incrCallbackCount(refundNo);
            int refundRows = refundRecordMapper.casSuccess(refundNo, channelRefundNo);
            if (refundRows != 1) {
                status.setRollbackOnly();
                return false;
            }
            int orderRows = orderMapper.casUpdateStatus(orderNo,
                    OrderRecord.STATUS_REFUNDING, OrderRecord.STATUS_REFUNDED);
            if (orderRows != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });

        if (!Boolean.TRUE.equals(ok)) {
            alarmService.send("退款回调落库失败 refundNo=" + refundNo + " orderNo=" + orderNo);
            return false;
        }
        return true;
    }

    @Override
    public RefundResponse status(String orderNo) {
        // 归属校验：退款进度只允许本人查看，非本人按“订单不存在”处理
        OrderRecord order = orderMapper.selectByOrderNo(orderNo);
        if (order == null || !order.getUserId().equals(currentUserHelper.currentUserId())) {
            throw new BizException("订单不存在");
        }
        RefundRecord record = refundRecordMapper.selectByOrderNo(orderNo);
        if (record == null) {
            // 没退过票：不是错误，返回明确状态给前端
            return new RefundResponse(null, orderNo, null, null, null, "未退票", null);
        }
        return new RefundResponse(record.getRefundNo(), record.getOrderNo(), record.getAmount(),
                record.getFee(), record.getRefundAmount(), record.getStatus(), record.getReason());
    }

    /** 区段数组：fromSeq..toSeq-1，与下单扣减、取消回补完全一致 */
    private List<Integer> segmentsOf(OrderRecord order) {
        List<Integer> segments = new ArrayList<>();
        for (int i = order.getFromSeq(); i < order.getToSeq(); i++) {
            segments.add(i);
        }
        return segments;
    }

    // ==================== 对账 ====================

    @Override
    public int reconcileOnce() {
        List<RefundRecord> list = refundRecordMapper.listStaleRefunding(
                reconcileTimeoutSeconds, reconcileBatchSize);
        if (list.isEmpty()) {
            return 0;
        }
        int handled = 0;
        for (RefundRecord record : list) {
            try {
                if (reconcileOne(record)) {
                    handled++;
                }
            } catch (Exception e) {
                // 单条异常不中断整轮
                alarmService.send("退款对账单条异常 refundNo=" + record.getRefundNo(), e);
            }
        }
        return handled;
    }

    /**
     * 单条对账。判定矩阵见 docs/退款对账任务改造提示词.md 第五节。
     *
     * 核心原则：
     * - 只有「渠道没有这笔退款单」才允许重发
     * - 「处理中」是正常态，只能等，不能重发、也不消耗重试额度
     * - 「已成功」说明回调丢了，本地修状态即可，绝不能再发退款（否则重复退钱）
     */
    private boolean reconcileOne(RefundRecord record) {
        // ① 超过最大重试：只在到达阈值那轮告警一次，之后静默等人工
        if (record.getRetryCount() != null && record.getRetryCount() >= reconcileMaxRetry) {
            if (record.getRetryCount() == reconcileMaxRetry) {
                alarmService.send("退款对账超过最大重试次数，需人工介入 refundNo="
                        + record.getRefundNo() + " orderNo=" + record.getOrderNo()
                        + " channelRefundNo=" + record.getChannelRefundNo());
                // 把计数推进到 maxRetry+1，作为「已经告过警」的水位线。
                // 少了这一步，计数会永远停在 maxRetry，每轮都满足 == 判断，
                // 变成每 5 分钟刷一次告警，而不是只告一次。
                refundRecordMapper.incrRetryCount(record.getRefundNo());
            }
            refundRecordMapper.touchUpdateTime(record.getRefundNo());
            return false;
        }

        // ② 库存没回补：需要动作，计入重试次数
        if (record.getStockRestored() == null || record.getStockRestored() == 0) {
            refundRecordMapper.incrRetryCount(record.getRefundNo());
            compensateStock(record);
        }

        // ③ 渠道单号为空 = 渠道根本没受理：重发
        if (record.getChannelRefundNo() == null || record.getChannelRefundNo().isEmpty()) {
            if (!allowResend(record)) {
                return false;
            }
            refundRecordMapper.incrRetryCount(record.getRefundNo());
            return resend(record);
        }

        // ④ 已有渠道单号：查询真实结果，按矩阵处理
        RefundQueryResult result = channel.queryRefund(record.getChannelRefundNo());
        switch (result) {
            case SUCCESS:
                // 回调丢了，本地补状态；绝不重发
                return settleSuccess(record);
            case PROCESSING:
                // 银行清算中，正常态：不计重试次数，只刷新时间等下一轮
                refundRecordMapper.touchUpdateTime(record.getRefundNo());
                return false;
            case NOT_EXIST:
                // 渠道没有这笔单：请求没到渠道，是唯一允许重发的场景。
                // 但账龄太大时这个假设不成立（渠道可能已清理流水），必须先过账龄闸门。
                if (!allowResend(record)) {
                    return false;
                }
                refundRecordMapper.incrRetryCount(record.getRefundNo());
                return resend(record);
            case FAILED:
                refundRecordMapper.casFailed(record.getRefundNo());
                alarmService.send("渠道退款失败，需人工处理 refundNo=" + record.getRefundNo());
                return true;
            default:
                return false;
        }
    }

    /**
     * 重发前的账龄闸门。
     *
     * 渠道只保留有限期的流水，一笔很久以前的退款单查不到，大概率是记录被清理了、
     * 钱其实早就退了，而不是"请求没到渠道"。这种情况重发就是重复退款。
     * 所以账龄一超限直接打上人工标记，本轮不做任何重发。
     *
     * @return true 允许重发；false 已转人工
     */
    private boolean allowResend(RefundRecord record) {
        LocalDateTime createTime = record.getCreateTime();
        if (createTime == null) {
            return true;
        }
        long ageHours = Duration.between(createTime, LocalDateTime.now()).toHours();
        if (ageHours <= reconcileMaxAgeHours) {
            return true;
        }
        // 账龄超限：渠道流水多半已清理，"查不到 = 请求没到" 不再可信，重发会重复退款
        // flagManual 带 retry_count < 999 的条件，只有第一次返回 1，天然只告警一次
        if (refundRecordMapper.flagManual(record.getRefundNo()) > 0) {
            alarmService.send("退款单账龄超限，禁止重发，需人工核对 refundNo=" + record.getRefundNo()
                    + " orderNo=" + record.getOrderNo()
                    + " 账龄=" + ageHours + "小时 阈值=" + reconcileMaxAgeHours + "小时");
        }
        return false;
    }

    /** 库存补偿：rollback.lua 幂等，restoreRealStock 与置标记同事务 */
    private void compensateStock(RefundRecord record) {
        OrderRecord order = orderMapper.selectByOrderNo(record.getOrderNo());
        if (order == null) {
            alarmService.send("退款对账找不到订单 refundNo=" + record.getRefundNo());
            return;
        }
        // 防御：只有订单确实在退票态才允许补库存，避免异常数据把库存补多
        if (!OrderRecord.STATUS_REFUNDING.equals(order.getStatus())
                && !OrderRecord.STATUS_REFUNDED.equals(order.getStatus())) {
            alarmService.send("退款对账订单状态异常，拒绝补库存 refundNo=" + record.getRefundNo()
                    + " status=" + order.getStatus());
            return;
        }
        List<Integer> segments = segmentsOf(order);
        try {
            transactionTemplate.execute(status -> {
                // Redis 已补过时 rollback 返回 0，不会重复 +1
                redisLuaService.rollback(order.resolveInstanceNo(), order.getSeatNo(),
                        order.getPassengerId(), order.getOrderNo(), segments);
                stockService.restoreRealStock(order.getOrderNo(), order.resolveInstanceNo(),
                        order.getSeatNo(), segments);
                refundRecordMapper.markStockRestored(record.getRefundNo());
                return true;
            });
            log.info("退款对账补库存成功 refundNo={} orderNo={}", record.getRefundNo(), record.getOrderNo());
        } catch (Exception e) {
            alarmService.send("退款对账库存补偿失败 refundNo=" + record.getRefundNo(), e);
        }
    }

    /** 重发退款：生成新的渠道单号并立刻落库，避免下一轮又被判为「渠道没受理」 */
    private boolean resend(RefundRecord record) {
        try {
            String newChannelRefundNo = channel.refund(record);
            if (newChannelRefundNo != null) {
                refundRecordMapper.updateChannelRefundNo(record.getRefundNo(), newChannelRefundNo);
            }
            log.info("退款对账重发成功 refundNo={} channelRefundNo={}",
                    record.getRefundNo(), newChannelRefundNo);
            return true;
        } catch (Exception e) {
            alarmService.send("退款对账重发失败 refundNo=" + record.getRefundNo(), e);
            return false;
        }
    }

    /**
     * 本地修复为退款成功（回调丢失场景）。
     * 与 handleNotify 的落库同构，但不累加 callback_count——对账不是回调，不该污染该指标。
     */
    private boolean settleSuccess(RefundRecord record) {
        Boolean ok = transactionTemplate.execute(status -> {
            int refundRows = refundRecordMapper.casSuccess(
                    record.getRefundNo(), record.getChannelRefundNo());
            if (refundRows != 1) {
                status.setRollbackOnly();
                return false;
            }
            int orderRows = orderMapper.casUpdateStatus(record.getOrderNo(),
                    OrderRecord.STATUS_REFUNDING, OrderRecord.STATUS_REFUNDED);
            if (orderRows != 1) {
                // 幂等分支：订单可能已被别的路径修好；只有确实是「已退款」才放行
                OrderRecord latest = orderMapper.selectByOrderNo(record.getOrderNo());
                if (latest == null || !OrderRecord.STATUS_REFUNDED.equals(latest.getStatus())) {
                    status.setRollbackOnly();
                    return false;
                }
            }
            return true;
        });
        if (!Boolean.TRUE.equals(ok)) {
            alarmService.send("退款对账修复状态失败 refundNo=" + record.getRefundNo()
                    + " orderNo=" + record.getOrderNo());
            return false;
        }
        log.info("退款对账修复成功（回调丢失） refundNo={} orderNo={}",
                record.getRefundNo(), record.getOrderNo());
        return true;
    }
}

package com.smartcity.traffic.service.impl;

import com.smartcity.traffic.common.AlarmService;
import com.smartcity.traffic.common.BizException;
import com.smartcity.traffic.common.CurrentUserHelper;
import com.smartcity.traffic.common.HmacSignUtil;
import com.smartcity.traffic.common.SnowflakeIdGenerator;
import com.smartcity.traffic.dto.PaymentStatusVO;
import com.smartcity.traffic.dto.PrepayResponse;
import com.smartcity.traffic.entity.OrderRecord;
import com.smartcity.traffic.entity.PaymentRecord;
import com.smartcity.traffic.mapper.OrderMapper;
import com.smartcity.traffic.mapper.PaymentRecordMapper;
import com.smartcity.traffic.service.MockPayChannelService;
import com.smartcity.traffic.service.PaymentService;
import com.smartcity.traffic.service.RedisLuaService;
import com.smartcity.traffic.service.StockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 支付服务实现（Mock 渠道，企业级统一下单 + 异步回调架构）。
 */
@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);

    private final PaymentRecordMapper paymentRecordMapper;
    private final OrderMapper orderMapper;
    private final MockPayChannelService channel;
    private final RedisLuaService redisLuaService;
    private final StockService stockService;
    private final SnowflakeIdGenerator idGenerator;
    private final CurrentUserHelper currentUserHelper;
    private final AlarmService alarmService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final String hmacSecret;

    public PaymentServiceImpl(PaymentRecordMapper paymentRecordMapper,
                              OrderMapper orderMapper,
                              MockPayChannelService channel,
                              RedisLuaService redisLuaService,
                              StockService stockService,
                              SnowflakeIdGenerator idGenerator,
                              CurrentUserHelper currentUserHelper,
                              AlarmService alarmService,
                              ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager,
                              @Value("${pay.mock.hmac-secret}") String hmacSecret) {
        this.paymentRecordMapper = paymentRecordMapper;
        this.orderMapper = orderMapper;
        this.channel = channel;
        this.redisLuaService = redisLuaService;
        this.stockService = stockService;
        this.idGenerator = idGenerator;
        this.currentUserHelper = currentUserHelper;
        this.alarmService = alarmService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.hmacSecret = hmacSecret;
    }

    @Override
    public PrepayResponse prepay(String orderNo) {
        OrderRecord order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BizException("订单不存在");
        }
        // 归属校验：只能给自己的订单发起支付
        if (!order.getUserId().equals(currentUserHelper.currentUserId())) {
            throw new BizException("无权操作该订单");
        }
        // 只有已确认（待支付）订单能发起支付
        if (!OrderRecord.STATUS_CONFIRMED.equals(order.getStatus())) {
            throw new BizException("当前订单状态不允许支付");
        }
        if (order.getAmount() == null) {
            throw new BizException("订单金额异常");
        }

        // 写待支付流水
        LocalDateTime now = LocalDateTime.now();
        PaymentRecord record = new PaymentRecord();
        String paymentNo = String.valueOf(idGenerator.nextId());
        record.setPaymentNo(paymentNo);
        record.setOrderNo(orderNo);
        record.setChannel(PaymentRecord.CHANNEL_MOCK);
        record.setAmount(order.getAmount());
        record.setStatus(PaymentRecord.STATUS_WAITING);
        record.setCallbackCount(0);
        record.setCreateTime(now);
        record.setUpdateTime(now);
        paymentRecordMapper.insert(record);

        // 生成支付凭证返回前端
        String payToken = channel.createPayToken(paymentNo);
        return new PrepayResponse(paymentNo, orderNo, order.getAmount(), payToken);
    }

    @Override
    public void confirmByToken(String payToken) {
        String paymentNo = channel.resolvePaymentNo(payToken);
        if (paymentNo == null) {
            throw new BizException("支付凭证无效");
        }
        PaymentRecord record = paymentRecordMapper.selectByPaymentNo(paymentNo);
        if (record == null || !PaymentRecord.STATUS_WAITING.equals(record.getStatus())) {
            throw new BizException("支付单不存在或已处理");
        }
        // 触发渠道异步回调
        channel.confirm(record);
    }

    @Override
    public boolean handleNotify(String rawBody, String signature) {
        // ① 验签：失败直接拒绝，不做任何业务
        if (!HmacSignUtil.verify(hmacSecret, rawBody, signature)) {
            log.warn("支付回调验签失败");
            return false;
        }

        // ② 解析报文
        JsonNode node;
        try {
            node = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            log.error("支付回调报文解析失败", e);
            return false;
        }
        String paymentNo = node.path("paymentNo").asText();
        String orderNo = node.path("orderNo").asText();
        // 金额属于外部输入，解析失败必须按 FAILURE 应答（与退款回调口径一致），
        // 不能让 NumberFormatException 冒到全局异常处理里返回 400
        BigDecimal callbackAmount;
        try {
            String amountText = node.path("amount").asText();
            if (amountText == null || amountText.isBlank()) {
                log.warn("支付回调缺少金额 orderNo={} paymentNo={}", orderNo, paymentNo);
                return false;
            }
            callbackAmount = new BigDecimal(amountText);
        } catch (Exception e) {
            log.warn("支付回调金额非法 orderNo={} paymentNo={} raw={}", orderNo, paymentNo, rawBody);
            return false;
        }

        PaymentRecord record = paymentRecordMapper.selectByPaymentNo(paymentNo);
        OrderRecord order = orderMapper.selectByOrderNo(orderNo);
        if (record == null || order == null) {
            // 数据不存在：挂起等重试/对账，不返回成功
            alarmService.send("支付回调订单/流水不存在 orderNo=" + orderNo);
            return false;
        }

        // ③ 金额与渠道校验：回调金额必须与订单、流水一致
        if (order.getAmount().compareTo(callbackAmount) != 0
                || record.getAmount().compareTo(callbackAmount) != 0
                || !PaymentRecord.CHANNEL_MOCK.equals(record.getChannel())) {
            // 不确认、不取消，挂起告警，等人工/对账
            alarmService.send("支付回调校验不通过 orderNo=" + orderNo);
            return false;
        }

        // ④ 已支付：重复回调幂等返回成功
        if (OrderRecord.STATUS_PAID.equals(order.getStatus())) {
            paymentRecordMapper.incrCallbackCount(paymentNo);
            return true;
        }

        // ⑤ 事务内幂等更新：流水成功 + 订单已支付 + 关闭其他待支付流水
        Boolean ok = transactionTemplate.execute(status -> {
            paymentRecordMapper.incrCallbackCount(paymentNo);
            int payRows = paymentRecordMapper.casSuccess(
                    paymentNo, node.path("channelTradeNo").asText());
            if (payRows != 1) {
                status.setRollbackOnly();
                return false;
            }
            int orderRows = orderMapper.casUpdateStatus(orderNo,
                    OrderRecord.STATUS_CONFIRMED, OrderRecord.STATUS_PAID);
            if (orderRows != 1) {
                status.setRollbackOnly();
                return false;
            }
            // 同订单其他待支付流水一并关闭
            for (PaymentRecord waiting : paymentRecordMapper.selectWaitingByOrderNo(orderNo)) {
                paymentRecordMapper.casClose(waiting.getPaymentNo());
            }
            return true;
        });

        if (!Boolean.TRUE.equals(ok)) {
            alarmService.send("支付回调落库失败 orderNo=" + orderNo);
            return false;
        }
        return true;
    }

    @Override
    public PaymentStatusVO status(String orderNo) {
        OrderRecord order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BizException("订单不存在");
        }
        // 归属校验：支付状态同样只允许本人查看
        if (!order.getUserId().equals(currentUserHelper.currentUserId())) {
            throw new BizException("订单不存在");
        }
        PaymentRecord success = paymentRecordMapper.selectSuccessByOrderNo(orderNo);
        String paymentStatus = success == null ? null : success.getStatus();
        return new PaymentStatusVO(orderNo, order.getStatus(), paymentStatus);
    }

    @Override
    public void userCancel(String orderNo) {
        OrderRecord order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BizException("订单不存在");
        }
        if (!order.getUserId().equals(currentUserHelper.currentUserId())) {
            throw new BizException("无权操作该订单");
        }
        // 已支付不可取消；已取消幂等返回
        if (OrderRecord.STATUS_PAID.equals(order.getStatus())) {
            throw new BizException("已支付订单不可取消");
        }
        if (OrderRecord.STATUS_CANCELED.equals(order.getStatus())) {
            return;
        }
        if (!OrderRecord.STATUS_CONFIRMED.equals(order.getStatus())) {
            throw new BizException("当前订单状态不允许取消");
        }

        // 条件更新订单为已取消
        int rows = orderMapper.casUpdateStatus(orderNo,
                OrderRecord.STATUS_CONFIRMED, OrderRecord.STATUS_CANCELED);
        if (rows != 1) {
            throw new BizException("订单取消失败，请刷新后重试");
        }

        List<Integer> segments = segmentsOf(order);
        // 回补 Redis（rollback.lua 幂等，含释放乘车人占位）
        redisLuaService.rollback(order.resolveInstanceNo(), order.getSeatNo(),
                order.getPassengerId(), orderNo, segments);
        // 回补 DB 真实库存（带幂等 + 容量两道闸）
        stockService.restoreRealStock(orderNo, order.resolveInstanceNo(), order.getSeatNo(), segments);
        // 关闭全部待支付流水
        for (PaymentRecord waiting : paymentRecordMapper.selectWaitingByOrderNo(orderNo)) {
            paymentRecordMapper.casClose(waiting.getPaymentNo());
        }
    }

    /** 区段数组：fromSeq..toSeq-1，与下单扣减时完全一致 */
    private List<Integer> segmentsOf(OrderRecord order) {
        List<Integer> segments = new ArrayList<>();
        for (int i = order.getFromSeq(); i < order.getToSeq(); i++) {
            segments.add(i);
        }
        return segments;
    }
}

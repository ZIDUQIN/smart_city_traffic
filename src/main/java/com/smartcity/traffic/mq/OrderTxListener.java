package com.smartcity.traffic.mq;

import com.smartcity.traffic.entity.DeductRecord;
import com.smartcity.traffic.service.DeductRecordService;
import com.smartcity.traffic.service.OrderService;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 事务消息监听器（原生 client 的 TransactionListener）。
 *
 * executeLocalTransaction：半消息落 Broker 后执行本地事务四步
 * checkLocalTransaction  ：Broker 回查，按流水状态映射，中间态一律 UNKNOWN
 */
@Component
public class OrderTxListener implements TransactionListener {

    private static final Logger log = LoggerFactory.getLogger(OrderTxListener.class);

    /**
     * 延迟获取 OrderService：OrderServiceImpl 依赖 OrderTxProducer，
     * 而 OrderTxProducer 又依赖本监听器，构造期直接注入会形成循环依赖。
     * 本地事务只在 Broker 回调时才用到 OrderService，延迟到调用时再解析，
     * 此时容器早已启动完成，不存在循环。
     */
    private final ObjectProvider<OrderService> orderServiceProvider;
    private final DeductRecordService deductRecordService;

    public OrderTxListener(ObjectProvider<OrderService> orderServiceProvider,
                           DeductRecordService deductRecordService) {
        this.orderServiceProvider = orderServiceProvider;
        this.deductRecordService = deductRecordService;
    }

    @Override
    public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        OrderTxArg txArg = (OrderTxArg) arg;
        return orderServiceProvider.getObject().executeLocalSteps(txArg);
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt msg) {
        String body = new String(msg.getBody(), StandardCharsets.UTF_8);
        String orderNo = extractOrderNo(body);
        if (orderNo == null) {
            // 消息体都解析不了，不能贸然 COMMIT/ROLLBACK，返回 UNKNOWN 等下一轮
            log.error("回查消息体解析失败，返回 UNKNOWN body={}", body);
            return LocalTransactionState.UNKNOW;
        }

        DeductRecord record = deductRecordService.getByOrderNo(orderNo);
        if (record == null) {
            // 流水都没有，本地事务未成功
            log.warn("回查：流水不存在，按 ROLLBACK 处理 orderNo={}", orderNo);
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }

        return switch (record.getStatus()) {
            // 成功终态 → COMMIT
            case DeductRecord.STATUS_CREATED -> LocalTransactionState.COMMIT_MESSAGE;
            // 失败终态 → ROLLBACK
            case DeductRecord.STATUS_COMPENSATED, DeductRecord.STATUS_CANCELED ->
                    LocalTransactionState.ROLLBACK_MESSAGE;
            // 中间态：结果未定，让 Broker 稍后再查，禁止误判
            case DeductRecord.STATUS_DEDUCTING, DeductRecord.STATUS_DEDUCTED,
                 DeductRecord.STATUS_COMPENSATING -> LocalTransactionState.UNKNOW;
            default -> LocalTransactionState.UNKNOW;
        };
    }

    /** 从 JSON 体里提取 orderNo（不依赖完整反序列化，回查更稳） */
    private String extractOrderNo(String body) {
        String key = "\"orderNo\":";
        int index = body.indexOf(key);
        if (index < 0) {
            return null;
        }
        int start = body.indexOf('"', index + key.length());
        if (start < 0) {
            return null;
        }
        int end = body.indexOf('"', start + 1);
        if (end < 0) {
            return null;
        }
        return body.substring(start + 1, end);
    }
}

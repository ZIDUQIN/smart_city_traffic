package com.smartcity.traffic.mq;

import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 事务消息生产者（TransactionMQProducer）。
 *
 * 半消息先落 Broker（消费者不可见），Broker 回调 OrderTxListener 执行本地事务，
 * 收到 COMMIT 后消息才投递给 DownstreamConsumer。
 */
@Component
public class OrderTxProducer {

    private static final Logger log = LoggerFactory.getLogger(OrderTxProducer.class);

    /** 消息 tag，下游消费者按此订阅 */
    public static final String TAG = "order";

    private final OrderTxListener orderTxListener;
    private final ExecutorService executorService;
    private final ObjectMapper objectMapper;

    @Value("${rocketmq.name-server}")
    private String nameServer;

    @Value("${rocketmq.producer-group}")
    private String producerGroup;

    @Value("${rocketmq.topic}")
    private String topic;

    private TransactionMQProducer producer;

    private volatile boolean available;

    public OrderTxProducer(OrderTxListener orderTxListener,
                           @Qualifier("mqTransactionExecutor") ExecutorService executorService,
                           ObjectMapper objectMapper) {
        this.orderTxListener = orderTxListener;
        this.executorService = executorService;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动放在 init 方法里由 Spring 调用；MQ 没起来时不拖垮整个应用。
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        try {
            TransactionMQProducer p = new TransactionMQProducer(producerGroup);
            p.setNamesrvAddr(nameServer);
            p.setExecutorService(executorService);
            p.setTransactionListener(orderTxListener);
            p.start();
            this.producer = p;
            this.available = true;
            log.info("事务消息生产者启动成功，group={}", producerGroup);
        } catch (Exception e) {
            log.error("事务消息生产者启动失败，下单接口将返回系统繁忙", e);
        }
    }

    /**
     * 发送半事务消息。消息体只放 orderNo（回查时用它查流水）。
     */
    public TransactionSendResult sendInTransaction(OrderTxArg arg) throws Exception {
        Map<String, String> body = Map.of("orderNo", arg.getOrderNo());
        byte[] bytes = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);

        org.apache.rocketmq.common.message.Message message =
                new org.apache.rocketmq.common.message.Message(topic, TAG, bytes);
        return producer.sendMessageInTransaction(message, arg);
    }

    public boolean isAvailable() {
        return available;
    }

    @PreDestroy
    public void destroy() {
        if (producer != null) {
            producer.shutdown();
        }
    }
}

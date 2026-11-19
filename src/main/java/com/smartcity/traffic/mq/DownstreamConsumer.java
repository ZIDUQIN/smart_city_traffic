package com.smartcity.traffic.mq;

import com.smartcity.traffic.common.AlarmService;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 下游消费者（成功路径：短信通知、积分、日志）。
 *
 * 幂等：按 orderNo 做 Redis SETNX 去重，重复消息直接 ACK。
 * 重试超过 3 次：记录日志 + 告警并 ACK，交定时任务兜底，不再无限重试。
 */
@Component
public class DownstreamConsumer implements MessageListenerConcurrently {

    private static final Logger log = LoggerFactory.getLogger(DownstreamConsumer.class);

    /** 幂等标记 key */
    private static final String DONE_KEY_PREFIX = "downstream:done:";

    /** 最大重试次数，超过就不再重试 */
    private static final int MAX_RETRY = 3;

    private final StringRedisTemplate redisTemplate;
    private final AlarmService alarmService;

    @Value("${rocketmq.name-server}")
    private String nameServer;

    @Value("${rocketmq.consumer-group}")
    private String consumerGroup;

    @Value("${rocketmq.topic}")
    private String topic;

    private DefaultMQPushConsumer consumer;

    public DownstreamConsumer(StringRedisTemplate redisTemplate, AlarmService alarmService) {
        this.redisTemplate = redisTemplate;
        this.alarmService = alarmService;
    }

    @PostConstruct
    public void init() {
        try {
            DefaultMQPushConsumer c = new DefaultMQPushConsumer(consumerGroup);
            c.setNamesrvAddr(nameServer);
            c.subscribe(topic, OrderTxProducer.TAG);
            c.registerMessageListener(this);
            c.start();
            this.consumer = c;
            log.info("下游消费者启动成功，group={}", consumerGroup);
        } catch (Exception e) {
            log.error("下游消费者启动失败", e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages,
                                                   ConsumeConcurrentlyContext context) {
        for (MessageExt msg : messages) {
            String orderNo = extractOrderNo(new String(msg.getBody(), StandardCharsets.UTF_8));
            if (orderNo == null) {
                log.error("消息体解析失败，丢弃 msgId={}", msg.getMsgId());
                continue;
            }

            // 重试超过 3 次：告警 + ACK，交定时任务兜底
            if (msg.getReconsumeTimes() >= MAX_RETRY) {
                log.error("【告警】下游消费重试超过 {} 次，停止重试 orderNo={}", MAX_RETRY, orderNo);
                alarmService.send("下游消费失败，已超最大重试 orderNo=" + orderNo);
                continue;
            }

            // 幂等：SETNX 抢占，已处理过的直接 ACK
            Boolean firstTime = redisTemplate.opsForValue().setIfAbsent(
                    DONE_KEY_PREFIX + orderNo, "1", Duration.ofHours(24));
            if (!Boolean.TRUE.equals(firstTime)) {
                log.info("重复消息，直接 ACK orderNo={}", orderNo);
                continue;
            }

            try {
                // 下游动作（演示）：短信、积分、日志
                log.info("下游动作 orderNo={}：发送购票成功短信", orderNo);
                log.info("下游动作 orderNo={}：增加积分", orderNo);
                log.info("下游动作 orderNo={}：写操作日志", orderNo);
            } catch (Exception e) {
                log.error("下游动作执行失败，等待重试 orderNo={}", orderNo, e);
                // 抢占标记删掉，让重试时能重新执行
                redisTemplate.delete(DONE_KEY_PREFIX + orderNo);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

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

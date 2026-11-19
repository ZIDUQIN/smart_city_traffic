package com.smartcity.traffic.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * RocketMQ 相关配置。
 * 原生 rocketmq-client 不依赖 starter，这里只提供事务回查所需的线程池。
 */
@Configuration
public class RocketMQConfig {

    /**
     * 事务消息回查线程池：Broker 回查、本地事务执行都在这个池里跑。
     */
    @Bean("mqTransactionExecutor")
    public ExecutorService mqTransactionExecutor() {
        return new ThreadPoolExecutor(
                8, 16, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                r -> {
                    Thread t = new Thread(r, "rocketmq-tx-check");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}

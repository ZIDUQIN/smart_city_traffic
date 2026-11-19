package com.smartcity.traffic.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 实例标识：端口 + 随机短码，例如 8081:7f3a2c91。
 *
 * 为什么需要：分布式锁如果 value 恒定（比如都写 "1"），在 Redis 里只能看出"锁在被持有"，
 * 看不出"被哪个实例持有"。多实例部署时排障第一句话就是"这轮是谁跑的"，
 * 所以锁的 value 必须能定位到具体进程。
 *
 * 随机码是必要的：两个实例可能配同一个端口（比如容器里都是 8080），
 * 光靠端口区分不了。
 */
@Component
public class InstanceId {

    private final String value;

    public InstanceId(@Value("${server.port:8081}") int port) {
        this.value = port + ":" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 形如 8081:7f3a2c91，进程内唯一且重启后变化 */
    public String get() {
        return value;
    }
}

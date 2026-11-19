package com.smartcity.traffic.voice.service;

import com.smartcity.traffic.voice.config.VoiceProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按配置选择 ASR 实现。
 *
 * <p>不用 @ConditionalOnProperty 逐个装配：mock 实现需要始终存在，
 * 切换供应商时不重启也能改配置试，路由集中在一处也更容易排查"到底用了谁"。</p>
 */
@Component
public class AsrServiceRouter {

    private final Map<String, AsrService> services = new HashMap<>();

    private final VoiceProperties properties;

    public AsrServiceRouter(List<AsrService> implementations, VoiceProperties properties) {
        this.properties = properties;
        for (AsrService service : implementations) {
            services.put(service.provider(), service);
        }
    }

    /** 按 voice.asr.provider 取实现；配置写错时退回 mock，不让语音入口整个不可用 */
    public AsrService route() {
        String provider = properties.getAsr().getProvider();
        AsrService service = services.get(provider);
        return service != null ? service : services.get("mock");
    }
}

package com.smartcity.traffic.voice.service.impl;

import com.smartcity.traffic.voice.dto.AsrResult;
import com.smartcity.traffic.voice.service.AsrService;
import org.springframework.stereotype.Service;

/**
 * 开发/演示用识别器：不调外部服务，把 debugText 当识别结果。
 *
 * <p>作用是把"识别"这一环从链路里摘出来，让站名纠错、意图解析、查票、前端交互
 * 在没有 ASR 密钥的情况下也能完整联调。</p>
 */
@Service
public class MockAsrService implements AsrService {

    @Override
    public AsrResult recognize(byte[] wav, String dialect, String mockText) {
        AsrResult result = new AsrResult();
        result.setRawText(mockText == null ? "" : mockText.trim());
        result.setDialect(dialect);
        result.setProvider(provider());
        result.setConfidence(0.9);
        result.setElapsedMs(0L);
        return result;
    }

    @Override
    public String provider() {
        return "mock";
    }
}

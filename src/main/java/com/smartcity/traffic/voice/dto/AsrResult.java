package com.smartcity.traffic.voice.dto;

/**
 * 语音识别结果。只做"音频 -> 文字"，不含任何纠错或意图信息。
 */
public class AsrResult {

    private String rawText;

    /** 实际使用的方言参数：auto/sichuan/shaanxi/yue/northeast */
    private String dialect;

    /** 实际提供方：mock/xfyun/aliyun */
    private String provider;

    /** 置信度 0~1，供应商不给时为 null */
    private Double confidence;

    private Long elapsedMs;

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }

    public String getDialect() {
        return dialect;
    }

    public void setDialect(String dialect) {
        this.dialect = dialect;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public Long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(Long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }
}

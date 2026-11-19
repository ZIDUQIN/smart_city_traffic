package com.smartcity.traffic.voice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 方言语音购票配置，前缀 voice。
 */
@ConfigurationProperties(prefix = "voice")
public class VoiceProperties {

    private Asr asr = new Asr();

    private Xfyun xfyun = new Xfyun();

    private RateLimit rateLimit = new RateLimit();

    private Llm llm = new Llm();

    /**
     * 可售日期窗口（天，含今天）。
     *
     * <p>必须与 {@code traffic.instance.window-days} 对齐：库里的班次实例只生成这么多天，
     * 语音窗口放得更宽只会让用户选到一个查不到车的日期。所以配置项直接引用它，不各写各的。</p>
     */
    private int windowDays = 7;

    public int getWindowDays() {
        return windowDays;
    }

    public void setWindowDays(int windowDays) {
        this.windowDays = windowDays;
    }

    public Asr getAsr() {
        return asr;
    }

    public void setAsr(Asr asr) {
        this.asr = asr;
    }

    public Xfyun getXfyun() {
        return xfyun;
    }

    public void setXfyun(Xfyun xfyun) {
        this.xfyun = xfyun;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    /**
     * 大模型兜底：只在本地规则"拿不准"时才调用，用来在候选名单里挑站名。
     *
     * <p>关掉它系统照常跑，只是同音错字这类场景会退回到"让用户点一下"。</p>
     */
    public static class Llm {

        private boolean enabled = true;

        /** 单次调用最多等几秒，超时立刻降级回规则结果，不能让用户干等 */
        private int timeoutSeconds = 3;

        private String model = "deepseek-chat";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class Asr {

        /** mock / xfyun / aliyun */
        private String provider = "mock";

        private int maxDurationSeconds = 30;

        private long maxBytes = 2097152L;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public int getMaxDurationSeconds() {
            return maxDurationSeconds;
        }

        public void setMaxDurationSeconds(int maxDurationSeconds) {
            this.maxDurationSeconds = maxDurationSeconds;
        }

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }
    }

    public static class Xfyun {

        private String appId;

        private String apiKey;

        private String apiSecret;

        private String host = "wss://iat.cn-huabei-1.xf-yun.com/v1";

        /** 是否把站名当热词注入识别端：开启后专有名词识别率明显更好 */
        private boolean hotwords = true;

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiSecret() {
            return apiSecret;
        }

        public void setApiSecret(String apiSecret) {
            this.apiSecret = apiSecret;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public boolean isHotwords() {
            return hotwords;
        }

        public void setHotwords(boolean hotwords) {
            this.hotwords = hotwords;
        }
    }

    public static class RateLimit {

        /** 单用户每分钟最多几次语音请求（识别和解析各算一次） */
        private int perUserPerMinute = 10;

        /** 全局每日调用上限：外部 ASR 是付费能力，到量就停，直接降级到打字/句式按钮 */
        private long dailyQuota = 5000;

        /** 日额度消耗到该比例时告警（0.8 = 80%） */
        private double dailyAlertRatio = 0.8;

        public int getPerUserPerMinute() {
            return perUserPerMinute;
        }

        public void setPerUserPerMinute(int perUserPerMinute) {
            this.perUserPerMinute = perUserPerMinute;
        }

        public long getDailyQuota() {
            return dailyQuota;
        }

        public void setDailyQuota(long dailyQuota) {
            this.dailyQuota = dailyQuota;
        }

        public double getDailyAlertRatio() {
            return dailyAlertRatio;
        }

        public void setDailyAlertRatio(double dailyAlertRatio) {
            this.dailyAlertRatio = dailyAlertRatio;
        }
    }
}

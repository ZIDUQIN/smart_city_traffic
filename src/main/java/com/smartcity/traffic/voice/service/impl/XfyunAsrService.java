package com.smartcity.traffic.voice.service.impl;

import com.smartcity.traffic.common.BizException;
import com.smartcity.traffic.voice.config.VoiceProperties;
import com.smartcity.traffic.voice.dto.AsrResult;
import com.smartcity.traffic.voice.service.AsrService;
import com.smartcity.traffic.voice.service.StationIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 讯飞「语音听写大模型（中英）V2.0」适配器。
 *
 * <p>与老版一句话听写（/v2/iat）的区别：走 wss://iat.cn-huabei-1.xf-yun.com/v1，
 * 方言由服务端自动判别（accent 固定 mandarin），站名等专有名词靠热词注入提升，
 * 不再需要为每种方言单独买授权。</p>
 *
 * <p>本类只做"音频 → 文字"。纠错、解析、查票全部在后面的链路里。</p>
 */
@Service
public class XfyunAsrService implements AsrService {

    private static final Logger log = LoggerFactory.getLogger(XfyunAsrService.class);

    /** 服务特性参数里的领域名，固定值 */
    private static final String DOMAIN = "spark_asr_v2";

    /** 每次发送的音频字节数：官方建议 1024 */
    private static final int FRAME_BYTES = 1024;

    /**
     * 相邻两帧间隔。官方建议 40ms，那是给"边说边发"的实时流用的；
     * 我们的音频是用户松手后整段发出去的，按实时节奏喂会白等 5～6 秒，
     * 老人等不起。压到 10ms 后整段 5 秒音频约 1.6 秒发完。
     */
    private static final int FRAME_INTERVAL_MS = 10;

    /** 建连上限 */
    private static final long CONNECT_TIMEOUT_MS = 6000L;

    /** 发完音频后等结果的上限 */
    private static final long RESULT_TIMEOUT_MS = 12000L;

    /** 热词上下文上限：官方限制 2KB，这里留余量 */
    private static final int HOTWORD_MAX_BYTES = 1980;

    private final VoiceProperties properties;

    private final StationIndex stationIndex;

    private final ObjectMapper objectMapper;

    public XfyunAsrService(VoiceProperties properties, StationIndex stationIndex, ObjectMapper objectMapper) {
        this.properties = properties;
        this.stationIndex = stationIndex;
        this.objectMapper = objectMapper;
    }

    @Override
    public AsrResult recognize(byte[] wav, String dialect, String mockText) {
        checkConfig();
        long start = System.currentTimeMillis();

        byte[] pcm = pcmData(wav);
        if (pcm.length == 0) {
            throw new BizException("录音内容为空，请再说一遍");
        }

        Collector collector = new Collector(objectMapper);
        WebSocket socket;
        try {
            socket = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                    .build()
                    .newWebSocketBuilder()
                    .buildAsync(URI.create(buildAuthUrl()), collector)
                    .get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // 握手失败时异常里带着服务端返回的 HTTP 状态码和报文，
            // 鉴权错、服务没开通、额度没领，都靠这一行才能区分出来
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String detail;
            if (cause instanceof WebSocketHandshakeException handshake) {
                detail = "HTTP " + handshake.getResponse().statusCode()
                        + " body=" + handshake.getResponse().body();
            } else {
                detail = cause.toString();
            }
            log.warn("讯飞建连失败: {}", detail);
            throw new BizException("语音服务连不上，请稍后再试");
        }

        try {
            sendAudio(socket, pcm, collector);
            collector.await(RESULT_TIMEOUT_MS);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("讯飞识别过程异常: {}", e.toString());
        } finally {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
            } catch (Exception ignore) {
                // 关闭失败不影响结果
            }
            socket.abort();
        }

        if (collector.error != null) {
            log.warn("讯飞返回业务错误: {}", collector.error);
            throw new BizException("语音服务暂时不可用，请稍后再试");
        }
        String text = collector.text().trim();
        if (text.isEmpty()) {
            throw new BizException("没听清，请再说一遍");
        }

        AsrResult result = new AsrResult();
        result.setRawText(text);
        result.setDialect(dialect);
        result.setProvider(provider());
        // 讯飞不回整体置信度，留空比编一个数字更诚实
        result.setConfidence(null);
        result.setElapsedMs(System.currentTimeMillis() - start);
        log.info("讯飞识别完成 pcmBytes={} textLen={} elapsed={}ms",
                pcm.length, text.length(), result.getElapsedMs());
        return result;
    }

    @Override
    public String provider() {
        return "xfyun";
    }

    // ---------------- 发送 ----------------

    private void sendAudio(WebSocket socket, byte[] pcm, Collector collector) throws Exception {
        int seq = 1;
        int offset = 0;
        boolean first = true;
        while (offset < pcm.length) {
            // 服务端已经回了错误（例如额度不足），继续推音频没有意义，
            // 还会把连接写崩，报出与真正原因无关的 Output closed
            if (collector.error != null) {
                return;
            }
            int len = Math.min(FRAME_BYTES, pcm.length - offset);
            byte[] chunk = Arrays.copyOfRange(pcm, offset, offset + len);
            int status = first ? 0 : 1;
            socket.sendText(frame(status, seq, Base64.getEncoder().encodeToString(chunk), first), true).join();
            offset += len;
            seq++;
            first = false;
            if (offset < pcm.length) {
                Thread.sleep(FRAME_INTERVAL_MS);
            }
        }
        // 结束帧：空音频 + status=2，服务端据此收尾
        socket.sendText(frame(2, seq, "", false), true).join();
    }

    private String frame(int status, int seq, String audioBase64, boolean withParameter) {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("app_id", properties.getXfyun().getAppId());
        header.put("status", status);
        root.put("header", header);

        if (withParameter) {
            Map<String, Object> iat = new LinkedHashMap<>();
            iat.put("domain", DOMAIN);
            iat.put("language", "zh_cn");
            // 大模型版方言自动判别，口音参数只接受这一个固定值
            iat.put("accent", "mandarin");
            Map<String, Object> resultShape = new LinkedHashMap<>();
            resultShape.put("encoding", "utf8");
            resultShape.put("compress", "raw");
            resultShape.put("format", "json");
            iat.put("result", resultShape);

            String context = hotwordContext();
            if (context != null) {
                iat.put("context", context);
            }

            Map<String, Object> parameter = new LinkedHashMap<>();
            parameter.put("iat", iat);
            root.put("parameter", parameter);
        }

        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("encoding", "raw");
        audio.put("sample_rate", 16000);
        audio.put("channels", 1);
        audio.put("bit_depth", 16);
        audio.put("seq", seq);
        audio.put("status", status);
        audio.put("audio", audioBase64);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("audio", audio);
        root.put("payload", payload);

        return objectMapper.writeValueAsString(root);
    }

    /**
     * 站名热词：把 61 个站名作为偏置词送给识别端，让解码优先吐出这些词。
     * 官方限制 context 长度 ≤2KB，超了按字节截断——宁可少几个站，也不能让整帧被拒。
     */
    private String hotwordContext() {
        if (!properties.getXfyun().isHotwords()) {
            return null;
        }
        List<StationIndex.Entry> entries = stationIndex.all();
        if (entries.isEmpty()) {
            return null;
        }

        final int tailBytes = "]}}}".getBytes(StandardCharsets.UTF_8).length;
        StringBuilder sb = new StringBuilder("{\"context\":{\"bias\":{\"hotword\":[");
        int bytes = sb.toString().getBytes(StandardCharsets.UTF_8).length;
        int count = 0;

        for (StationIndex.Entry e : entries) {
            String item = (count == 0 ? "" : ",")
                    + "{\"text\":\"" + e.getName() + "\",\"weight\":0.8}";
            int itemBytes = item.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + itemBytes + tailBytes > HOTWORD_MAX_BYTES) {
                break;
            }
            sb.append(item);
            bytes += itemBytes;
            count++;
        }

        if (count == 0) {
            return null;
        }
        sb.append("]}}}");
        log.debug("热词注入 {} 个站名，context 约 {} 字节", count, bytes);
        return sb.toString();
    }

    // ---------------- 鉴权 ----------------

    /**
     * 文档规定的签名串：host / date / request-line 三行，用 APISecret 做 HMAC-SHA256，
     * 再和 api_key 一起 base64，作为 URL 上的 authorization 参数。
     */
    private String buildAuthUrl() {
        VoiceProperties.Xfyun cfg = properties.getXfyun();
        URI base = URI.create(cfg.getHost());
        String host = base.getHost();
        String path = base.getPath();

        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC));
        String signatureOrigin = "host: " + host + "\n"
                + "date: " + date + "\n"
                + "GET " + path + " HTTP/1.1";
        String signature = hmacSha256Base64(signatureOrigin, cfg.getApiSecret());

        String authorizationOrigin = "api_key=\"" + cfg.getApiKey() + "\", "
                + "algorithm=\"hmac-sha256\", "
                + "headers=\"host date request-line\", "
                + "signature=\"" + signature + "\"";
        String authorization = Base64.getEncoder()
                .encodeToString(authorizationOrigin.getBytes(StandardCharsets.UTF_8));

        return cfg.getHost()
                + "?host=" + encode(host)
                + "&date=" + encode(date)
                + "&authorization=" + encode(authorization);
    }

    private String hmacSha256Base64(String content, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BizException("语音服务签名失败");
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ---------------- WAV ----------------

    /** 去掉 WAV 头只留 PCM：识别端要的是裸 16k/单声道/16bit 数据 */
    private byte[] pcmData(byte[] wav) {
        if (wav == null || wav.length < 44
                || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F') {
            return new byte[0];
        }
        int pos = 12;
        while (pos + 8 <= wav.length) {
            String id = new String(wav, pos, 4, StandardCharsets.US_ASCII);
            int size = ByteBuffer.wrap(wav, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (size < 0) {
                break;
            }
            if ("data".equals(id)) {
                int available = Math.min(size, wav.length - pos - 8);
                if (available <= 0) {
                    return new byte[0];
                }
                return Arrays.copyOfRange(wav, pos + 8, pos + 8 + available);
            }
            pos += 8 + size;
        }
        return new byte[0];
    }

    private void checkConfig() {
        VoiceProperties.Xfyun cfg = properties.getXfyun();
        if (isBlank(cfg.getAppId()) || isBlank(cfg.getApiKey()) || isBlank(cfg.getApiSecret())) {
            throw new BizException("未配置讯飞语音密钥，请设置 XFYUN_APP_ID / XFYUN_API_KEY / XFYUN_API_SECRET");
        }
        if (isBlank(cfg.getHost())) {
            throw new BizException("未配置讯飞识别地址 voice.xfyun.host");
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ---------------- 结果收集 ----------------

    /**
     * 按 sn 收集片段再拼接：服务端可能分多帧返回，同一 sn 重复到达时后者覆盖前者，
     * 保证不会把同一段话拼两遍。
     */
    static class Collector implements WebSocket.Listener {

        private final ObjectMapper mapper;

        private final StringBuilder buffer = new StringBuilder();

        private final Map<Integer, String> segments = new TreeMap<>();

        private final CountDownLatch done = new CountDownLatch(1);

        private volatile String error;

        private volatile boolean finished;

        Collector(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String json = buffer.toString();
                buffer.setLength(0);
                handle(json);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            done.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable throwable) {
            if (error == null) {
                error = throwable == null || throwable.getMessage() == null
                        ? "连接异常" : throwable.getMessage();
            }
            done.countDown();
        }

        private void handle(String json) {
            try {
                JsonNode root = mapper.readTree(json);

                JsonNode header = root.get("header");
                if (header != null) {
                    JsonNode codeNode = header.get("code");
                    int code = codeNode == null || codeNode.isNull() ? 0 : codeNode.asInt();
                    if (code != 0) {
                        JsonNode message = header.get("message");
                        error = message == null || message.isNull() ? "识别失败" : message.asText();
                        done.countDown();
                        return;
                    }
                    JsonNode statusNode = header.get("status");
                    if (statusNode != null && !statusNode.isNull() && statusNode.asInt() == 2) {
                        finished = true;
                    }
                }

                JsonNode payload = root.get("payload");
                if (payload != null) {
                    JsonNode result = payload.get("result");
                    if (result != null) {
                        JsonNode textNode = result.get("text");
                        if (textNode != null && !textNode.isNull()) {
                            String encoded = textNode.asText();
                            if (!encoded.isEmpty()) {
                                collect(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
                            }
                        }
                    }
                }

                if (finished) {
                    done.countDown();
                }
            } catch (Exception e) {
                // 单帧解析失败不影响整体，继续收后面的
            }
        }

        private void collect(String decoded) {
            JsonNode node = mapper.readTree(decoded);
            JsonNode snNode = node.get("sn");
            int sn = snNode == null || snNode.isNull() ? segments.size() + 1 : snNode.asInt();

            StringBuilder piece = new StringBuilder();
            JsonNode ws = node.get("ws");
            if (ws != null && ws.isArray()) {
                for (JsonNode segment : ws) {
                    JsonNode cw = segment.get("cw");
                    if (cw == null || !cw.isArray()) {
                        continue;
                    }
                    for (JsonNode word : cw) {
                        JsonNode w = word.get("w");
                        if (w != null && !w.isNull()) {
                            piece.append(w.asText());
                        }
                    }
                }
            }
            segments.put(sn, piece.toString());
        }

        String text() {
            StringBuilder sb = new StringBuilder();
            for (String piece : segments.values()) {
                sb.append(piece);
            }
            return sb.toString();
        }

        void await(long timeoutMs) throws InterruptedException {
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }
}

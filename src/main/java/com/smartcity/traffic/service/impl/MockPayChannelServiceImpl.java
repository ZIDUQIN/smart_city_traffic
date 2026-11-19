package com.smartcity.traffic.service.impl;

import com.smartcity.traffic.common.HmacSignUtil;
import com.smartcity.traffic.common.SnowflakeIdGenerator;
import com.smartcity.traffic.entity.PaymentRecord;
import com.smartcity.traffic.entity.RefundRecord;
import com.smartcity.traffic.enums.RefundQueryResult;
import com.smartcity.traffic.service.MockPayChannelService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 模拟支付渠道实现。
 *
 * 与真实渠道一致的部分：用户确认付款后，渠道侧异步发起带签名的回调，
 * 商户返回非成功应答时按次数重试。
 */
@Service
public class MockPayChannelServiceImpl implements MockPayChannelService {

    /** 回调最大重试次数 */
    private static final int MAX_RETRY = 3;

    /** 凭证 → 支付单号 */
    private final Map<String, String> tokenToPayment = new ConcurrentHashMap<>();

    /** 渠道侧退款结果：channelRefundNo → 结果，对账查询用（真实渠道换成 HTTP 查询接口） */
    private final Map<String, RefundQueryResult> refundResults = new ConcurrentHashMap<>();

    /**
     * 幂等表：我们的退款单号 refundNo → 渠道退款单号。
     *
     * 这就是真实渠道「商户退款单号」的作用：同一个 refundNo 提交多次，
     * 渠道只受理一次，后续请求直接把首次的单号还回去，不会重复退钱。
     * 没有这张表，重发一次就真的多退一笔。
     */
    private final Map<String, String> refundIdempotent = new ConcurrentHashMap<>();

    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    private final String hmacSecret;
    private final String notifyUrl;
    /** 退款回调地址：支付回调与退款回调是两个独立接口 */
    private final String refundNotifyUrl;
    /** 退款受理延迟（秒）：模拟真实渠道处理耗时，让「退款中」可被观察到 */
    private final int refundCallbackDelaySeconds;

    /** 回调异步执行器 */
    private final ExecutorService callbackExecutor = Executors.newFixedThreadPool(4);

    /** 回调 HTTP 客户端（JDK 自带） */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public MockPayChannelServiceImpl(SnowflakeIdGenerator idGenerator,
                                     ObjectMapper objectMapper,
                                     @Value("${pay.mock.hmac-secret}") String hmacSecret,
                                     @Value("${pay.mock.notify-url}") String notifyUrl,
                                     @Value("${refund.notify-url}") String refundNotifyUrl,
                                     @Value("${refund.mock.callback-delay-seconds:3}") int refundCallbackDelaySeconds) {
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.hmacSecret = hmacSecret;
        this.notifyUrl = notifyUrl;
        this.refundNotifyUrl = refundNotifyUrl;
        this.refundCallbackDelaySeconds = Math.max(0, refundCallbackDelaySeconds);
    }

    @Override
    public String createPayToken(String paymentNo) {
        // 模拟支付凭证：TOKEN + 雪花
        String payToken = "TOKEN" + idGenerator.nextId();
        tokenToPayment.put(payToken, paymentNo);
        return payToken;
    }

    @Override
    public String resolvePaymentNo(String payToken) {
        return tokenToPayment.get(payToken);
    }

    @Override
    public void confirm(PaymentRecord record) {
        // 渠道交易号：MOCK + 雪花
        String channelTradeNo = "MOCK" + idGenerator.nextId();

        // 构造回调报文（字段顺序固定，保证签名与验签用同一字符串）
        String rawBody;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderNo", record.getOrderNo());
            payload.put("paymentNo", record.getPaymentNo());
            payload.put("channelTradeNo", channelTradeNo);
            payload.put("amount", record.getAmount());
            rawBody = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("回调报文构造失败", e);
        }

        // 对原始报文 HMAC-SHA256 签名
        String signature = HmacSignUtil.sign(hmacSecret, rawBody);

        // 异步发起回调，不阻塞用户请求
        callbackExecutor.submit(() -> sendWithRetry(notifyUrl, rawBody, signature));
    }

    @Override
    public String refund(RefundRecord record) {
        String refundNo = record.getRefundNo();

        // 【幂等键】同一个退款单号重复提交，直接返回首次生成的渠道单号，不再执行退款。
        // 真实渠道靠「商户退款单号」做这件事：支付宝 out_request_no、微信 out_refund_no。
        // 这是重复退款的最后一道防线——即使本地判断错了、多发了一次，钱也只退一次。
        String existing = refundIdempotent.get(refundNo);
        if (existing != null) {
            return existing;
        }

        // 渠道退款单号：MOCKREFUND + 雪花，与支付交易号区分前缀，便于排障
        String channelRefundNo = "MOCKREFUND" + idGenerator.nextId();
        String winner = refundIdempotent.putIfAbsent(refundNo, channelRefundNo);
        if (winner != null) {
            // 并发下别的线程先登记了同一个 refundNo，退回它的单号，本次不重复退
            return winner;
        }

        // 渠道侧先把退款执行成功记下来（模拟即时到账），回调只负责通知商户。
        // 关键：即使回调丢失，这里也有结果，对账查得到「已成功」。
        refundResults.put(channelRefundNo, RefundQueryResult.SUCCESS);

        // 构造退款回调报文（字段顺序固定，保证签名与验签用同一字符串）
        String rawBody;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("refundNo", record.getRefundNo());
            payload.put("orderNo", record.getOrderNo());
            payload.put("channelRefundNo", channelRefundNo);
            // 渠道只关心实际退了多少钱
            payload.put("amount", record.getRefundAmount());
            rawBody = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("退款回调报文构造失败", e);
        }

        // 对原始报文 HMAC-SHA256 签名
        String signature = HmacSignUtil.sign(hmacSecret, rawBody);

        // 异步发起退款回调，不阻塞退票请求。
        // 先等一段时间再回调：真实渠道受理退款需要时间，商户在「退款中」停留一会儿才收到结果。
        callbackExecutor.submit(() -> {
            sleepSeconds(refundCallbackDelaySeconds);
            sendWithRetry(refundNotifyUrl, rawBody, signature);
        });

        return channelRefundNo;
    }

    @Override
    public RefundQueryResult queryRefund(String channelRefundNo) {
        if (channelRefundNo == null || channelRefundNo.isEmpty()) {
            return RefundQueryResult.NOT_EXIST;
        }
        // 真实渠道这里是 HTTP 查询；Mock 用内存表，重启后全为空 → 一律 NOT_EXIST
        return refundResults.getOrDefault(channelRefundNo, RefundQueryResult.NOT_EXIST);
    }

    @Override
    public void setRefundResult(String channelRefundNo, RefundQueryResult result) {
        // 测试辅助：让对账能走到 PROCESSING / FAILED 分支
        refundResults.put(channelRefundNo, result);
    }

    /** 发送回调，非 SUCCESS 应答时最多重试 3 次（同一目标、同一报文、同一签名） */
    private void sendWithRetry(String targetUrl, String rawBody, String signature) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(targetUrl))
                        .header("Content-Type", "application/json")
                        .header("X-Mock-Signature", signature)
                        .POST(HttpRequest.BodyPublishers.ofString(rawBody))
                        .build();
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("SUCCESS")) {
                    return;
                }
            } catch (Exception e) {
                // 网络异常继续重试
            }
            if (attempt < MAX_RETRY) {
                sleepOneSecond();
            }
        }
    }

    private void sleepOneSecond() {
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sleepSeconds(int seconds) {
        if (seconds <= 0) {
            return;
        }
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        callbackExecutor.shutdown();
    }
}

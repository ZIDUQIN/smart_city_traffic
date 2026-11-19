package com.smartcity.traffic.controller;

import com.smartcity.traffic.common.Result;
import com.smartcity.traffic.dto.PaymentStatusVO;
import com.smartcity.traffic.dto.PrepayResponse;
import com.smartcity.traffic.enums.RefundQueryResult;
import com.smartcity.traffic.service.MockPayChannelService;
import com.smartcity.traffic.service.PaymentService;
import com.smartcity.traffic.service.RefundService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付接口（前缀 /api/pay）。
 */
@RestController
@RequestMapping("/api/pay")
public class PaymentController {

    private final PaymentService paymentService;
    private final RefundService refundService;
    private final MockPayChannelService mockChannel;

    public PaymentController(PaymentService paymentService,
                             RefundService refundService,
                             MockPayChannelService mockChannel) {
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.mockChannel = mockChannel;
    }

    /** 统一下单：返回模拟支付凭证 */
    @PostMapping("/prepay")
    public Result<PrepayResponse> prepay(@RequestParam String orderNo) {
        return Result.success(paymentService.prepay(orderNo));
    }

    /** 模拟收银台确认付款（异步触发回调，立即返回处理中） */
    @PostMapping("/mock/confirm")
    public Result<String> confirm(@RequestParam String payToken) {
        paymentService.confirmByToken(payToken);
        return Result.success("支付处理中");
    }

    /**
     * 仅 Mock 渠道：注入一笔退款的查询结果。
     *
     * 为什么需要它：退款对账里「渠道处理中」和「渠道失败」两条分支，Mock 渠道永远返回成功、
     * 造不出这两种状态，等于没有运行时证据。有了这个入口才能把两条分支真跑一遍。
     * 真实渠道没有这个接口——它的状态本来就是渠道自己给出来的。
     *
     * @param result SUCCESS / PROCESSING / NOT_EXIST / FAILED
     */
    @PostMapping("/mock/refund-result")
    public Result<String> setRefundResult(@RequestParam String channelRefundNo,
                                          @RequestParam String result) {
        RefundQueryResult parsed;
        try {
            parsed = RefundQueryResult.valueOf(result);
        } catch (IllegalArgumentException e) {
            return Result.error(400, "非法结果值，只允许 SUCCESS/PROCESSING/NOT_EXIST/FAILED");
        }
        mockChannel.setRefundResult(channelRefundNo, parsed);
        return Result.success(channelRefundNo + " -> " + parsed.name());
    }

    /**
     * 渠道异步回调：接收原始报文与签名，返回渠道要求的应答。
     * 该接口在 SecurityConfig 匿名放行，靠 HMAC 保护。
     */
    @PostMapping("/notify")
    public String notify(@RequestBody String rawBody,
                         @RequestHeader("X-Mock-Signature") String signature) {
        boolean success = paymentService.handleNotify(rawBody, signature);
        // 渠道约定应答：成功 SUCCESS，否则 FAILURE（触发重试）
        return success ? "{\"code\":\"SUCCESS\"}" : "{\"code\":\"FAILURE\"}";
    }

    /** 查询支付状态（前端轮询兜底） */
    @GetMapping("/status/{orderNo}")
    public Result<PaymentStatusVO> status(@PathVariable String orderNo) {
        return Result.success(paymentService.status(orderNo));
    }

    /**
     * 渠道退款异步回调：与支付回调同样靠 HMAC 验签保护，SecurityConfig 匿名放行。
     * 应答规则与支付回调一致：成功 SUCCESS，否则 FAILURE 触发渠道重试。
     */
    @PostMapping("/refund/notify")
    public String refundNotify(@RequestBody String rawBody,
                               @RequestHeader("X-Mock-Signature") String signature) {
        boolean success = refundService.handleNotify(rawBody, signature);
        return success ? "{\"code\":\"SUCCESS\"}" : "{\"code\":\"FAILURE\"}";
    }
}

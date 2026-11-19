package com.smartcity.traffic.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 签名工具（JDK 原生，无外部依赖）。
 *
 * 模拟真实支付渠道的签名机制：渠道持密钥签名，回调端用同一密钥重算比对。
 */
public final class HmacSignUtil {

    private static final String ALGORITHM = "HmacSHA256";

    private HmacSignUtil() {
    }

    /**
     * 对内容计算 HMAC-SHA256，返回十六进制字符串。
     */
    public static String sign(String secret, String content) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(keySpec);
            byte[] bytes = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名失败", e);
        }
    }

    /**
     * 校验签名：用同一密钥重算并做恒定时间比较，防止时序攻击。
     */
    public static boolean verify(String secret, String content, String signature) {
        if (signature == null) {
            return false;
        }
        String expected = sign(secret, content);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}

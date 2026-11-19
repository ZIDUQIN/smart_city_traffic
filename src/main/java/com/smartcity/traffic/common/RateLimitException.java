package com.smartcity.traffic.common;

/**
 * 限流异常：请求超出限流阈值时抛出，与普通业务校验失败（400）区分开。
 * 全局异常处理将其映射为 429（Too Many Requests）。
 */
public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }
}

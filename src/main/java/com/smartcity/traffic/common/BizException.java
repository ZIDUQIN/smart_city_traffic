package com.smartcity.traffic.common;

/**
 * 业务异常：主流程里需要主动中止并把原因返回给调用方时抛出。
 */
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }

    public BizException(String message, Throwable cause) {
        super(message, cause);
    }
}

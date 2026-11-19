package com.smartcity.traffic.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理，统一返回 Result JSON。
 * 原则：业务异常给明确文案；未知异常只回通用文案，堆栈与细节写日志，绝不外泄。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 账号或密码错误 */
    @ExceptionHandler({BadCredentialsException.class, UsernameNotFoundException.class})
    public Result<Void> handleBadCredentials(Exception e) {
        return Result.error(401, "用户名或密码错误");
    }

    /** 参数非法 / 业务校验失败 */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public Result<Void> handleIllegalArgument(Exception e) {
        String msg = e.getMessage();
        return Result.error(400, msg == null || msg.isBlank() ? "请求参数不合法" : msg);
    }

    /** 业务异常（补偿接管、系统繁忙等） */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        return Result.error(400, e.getMessage());
    }

    /** 限流：请求过于频繁，返回 429 语义 */
    @ExceptionHandler(RateLimitException.class)
    public Result<Void> handleRateLimit(RateLimitException e) {
        return Result.error(429, e.getMessage());
    }

    /** 兜底异常：对外只给通用文案，内部细节只进日志 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("未处理异常", e);
        return Result.error(500, "服务器内部错误，请稍后重试");
    }
}

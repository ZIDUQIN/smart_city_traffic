package com.smartcity.traffic.enums;

/**
 * 渠道退款查询结果。
 *
 * 这四个值决定对账任务的动作，语义不能混：
 * - SUCCESS    钱已退出：本地直接修成「退款成功」，绝不能再发退款（否则重复退钱）
 * - PROCESSING 渠道受理中：银行清算的正常态，只能等，不能重发
 * - NOT_EXIST  渠道没有这笔退款单：请求没到渠道，这是唯一允许重发的情况
 * - FAILED     渠道明确失败：置「退款失败」并转人工
 */
public enum RefundQueryResult {

    /** 渠道已退款成功 */
    SUCCESS,

    /** 渠道受理中，尚未完成（银行清算，正常态） */
    PROCESSING,

    /** 渠道无此退款单（请求没到渠道） */
    NOT_EXIST,

    /** 渠道明确失败（余额不足、账户异常等） */
    FAILED
}

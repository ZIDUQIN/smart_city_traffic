-- 支付流水表（可重复执行）
-- 一次支付尝试一条记录；channel_trade_no 回调成功才回填，待支付时为 NULL。

DROP TABLE IF EXISTS `payment_record`;
CREATE TABLE `payment_record` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `payment_no`       VARCHAR(64)   NOT NULL COMMENT '支付单号（雪花），一次支付尝试一条',
    `order_no`         VARCHAR(64)   NOT NULL COMMENT '商户订单号',
    `channel`          VARCHAR(16)   NOT NULL COMMENT '支付渠道，固定 MOCK',
    `amount`           DECIMAL(10,2) NOT NULL COMMENT '支付金额，与订单金额一致',
    `status`           VARCHAR(16)   NOT NULL COMMENT '待支付/支付成功/支付失败/已关闭',
    `channel_trade_no` VARCHAR(64)   DEFAULT NULL COMMENT '模拟渠道交易号，回调成功才回填',
    `callback_count`   INT           NOT NULL DEFAULT 0 COMMENT '回调到达次数',
    `create_time`      DATETIME      NOT NULL COMMENT '创建时间',
    `update_time`      DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_payment_no` (`payment_no`),
    UNIQUE KEY `uk_channel_trade_no` (`channel_trade_no`),
    KEY `idx_order_no` (`order_no`),
    KEY `idx_status_update` (`status`, `update_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '支付流水表';

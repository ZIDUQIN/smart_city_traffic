-- 退款流水表（可重复执行）
-- 一次退票一条记录；channel_refund_no 申请时即落库（渠道返回就写），退款中时为 NULL。
-- uk_order_no 是幂等第一道闸门：一个订单只能存在一条退款流水，并发重复退票在数据库层直接冲突。
-- stock_restored 是库存回补的幂等标记：restoreRealStock 是 remain+1 不幂等，靠它保证对账不会重复补。
-- retry_count 只记录「需要动作却失败」的次数，PROCESSING 这类正常等待不计数。

DROP TABLE IF EXISTS `refund_record`;
CREATE TABLE `refund_record` (
    `id`                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `refund_no`          VARCHAR(64)   NOT NULL COMMENT '退款单号（雪花）',
    `order_no`           VARCHAR(64)   NOT NULL COMMENT '原订单号',
    `payment_no`         VARCHAR(64)   DEFAULT NULL COMMENT '原支付流水号',
    `user_id`            BIGINT        NOT NULL COMMENT '下单用户',
    `amount`             DECIMAL(10,2) NOT NULL COMMENT '原票款金额',
    `fee`                DECIMAL(10,2) NOT NULL COMMENT '手续费',
    `refund_amount`      DECIMAL(10,2) NOT NULL COMMENT '实退金额 = amount - fee',
    `status`             VARCHAR(16)   NOT NULL COMMENT '退款中/退款成功/退款失败',
    `channel_refund_no`  VARCHAR(64)   DEFAULT NULL COMMENT '渠道退款单号，申请时落库',
    `reason`             VARCHAR(128)  DEFAULT NULL COMMENT '退票原因',
    `callback_count`     INT           NOT NULL DEFAULT 0 COMMENT '回调到达次数',
    `stock_restored`     TINYINT       NOT NULL DEFAULT 0 COMMENT '库存是否已回补：0 未补 / 1 已补',
    `retry_count`        INT           NOT NULL DEFAULT 0 COMMENT '对账重试次数',
    `create_time`        DATETIME      NOT NULL COMMENT '创建时间',
    `update_time`        DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_refund_no` (`refund_no`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    UNIQUE KEY `uk_channel_refund_no` (`channel_refund_no`),
    KEY `idx_status_update` (`status`, `update_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '退款流水表';

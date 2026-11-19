-- 扣减流水表：记录"每一次 Redis 扣票"的来龙去脉
-- 作用：① MQ 补偿消息的落库依据  ② 定时对账的唯一判断依据
-- 没有这张表，系统只知道"余票少了一张"，不知道"该补给谁"

CREATE TABLE IF NOT EXISTS `ticket_deduct_record` (
    `id`           BIGINT       NOT NULL COMMENT '主键，雪花ID',
    `order_no`     VARCHAR(64)  NOT NULL COMMENT '订单号（未建单时也先占一个）',
    `user_id`      BIGINT       NOT NULL COMMENT '下单账号',
    `passenger_id` BIGINT       NOT NULL COMMENT '乘车人',
    `train_id`     BIGINT       NOT NULL COMMENT '车次',
    `seat_type`    VARCHAR(32)  NOT NULL COMMENT '席别',
    `from_seq`     INT          NOT NULL COMMENT '上车站序',
    `to_seq`       INT          NOT NULL COMMENT '下车站序',
    `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '1扣减中 2已建单 3已回补 4待人工处理 5扣减失败',
    `remark`       VARCHAR(255)          DEFAULT NULL COMMENT '状态说明/异常原因',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_no` (`order_no`),
    KEY `idx_status_create` (`status`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '扣减流水：MQ补偿与定时对账依据';

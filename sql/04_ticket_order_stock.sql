-- 抢票下单模块三张表（最终一致性 + 异步补偿 + 定时对账）
-- 注意：本脚本会重建 ticket_deduct_record（旧表结构作废），并新建 `order`、`stock` 两张表。

DROP TABLE IF EXISTS `ticket_deduct_record`;
DROP TABLE IF EXISTS `order`;
DROP TABLE IF EXISTS `stock`;

-- ① 扣减流水：先记账后动钱，所有补偿与对账都靠它
CREATE TABLE `ticket_deduct_record` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `order_no`     VARCHAR(64)  NOT NULL COMMENT '订单号（雪花生成）',
    `user_id`      BIGINT       NOT NULL COMMENT '下单账号',
    `passenger_id` BIGINT       NOT NULL COMMENT '乘车人ID（判重口径：乘车人+车次）',
    `train_no`     VARCHAR(32)  NOT NULL COMMENT '车次号，如 G1001',
    `seat_no`      VARCHAR(32)  NOT NULL COMMENT '席别：SECOND_CLASS/FIRST_CLASS/BUSINESS',
    `status`       VARCHAR(16)  NOT NULL COMMENT '扣减中/已扣减/已建单/补偿中/已补偿/已取消',
    `fail_reason`  VARCHAR(255) DEFAULT NULL COMMENT '失败原因',
    `create_time`  DATETIME     NOT NULL COMMENT '创建时间',
    `update_time`  DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_status_update` (`status`, `update_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '扣减流水表';

-- ② 订单表：初始 待确认，DB 真实库存扣成功后才 已确认
CREATE TABLE `order` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `order_no`     VARCHAR(64) NOT NULL COMMENT '订单号',
    `user_id`      BIGINT      NOT NULL COMMENT '下单账号',
    `passenger_id` BIGINT      NOT NULL COMMENT '乘车人ID',
    `train_no`     VARCHAR(32) NOT NULL COMMENT '车次号',
    `seat_no`      VARCHAR(32) NOT NULL COMMENT '席别',
    `status`       VARCHAR(16) NOT NULL COMMENT '待确认/已确认/已取消',
    `create_time`  DATETIME    NOT NULL COMMENT '创建时间',
    `update_time`  DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单表';

-- ③ 真实库存表：Redis 是预扣，最后一步才扣这里，remain 不允许为负
CREATE TABLE `stock` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `train_no`    VARCHAR(32) NOT NULL COMMENT '车次号',
    `seat_no`     VARCHAR(32) NOT NULL COMMENT '席别',
    `remain`      INT         NOT NULL COMMENT '剩余库存',
    `update_time` DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_train_seat` (`train_no`, `seat_no`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '真实库存表';

-- 演示数据（与 Redis 预热保持一致）
INSERT INTO `stock` (`train_no`, `seat_no`, `remain`, `update_time`)
VALUES ('G1001', 'SECOND_CLASS', 100, NOW()),
       ('G1001', 'FIRST_CLASS', 20, NOW()),
       ('G1001', 'BUSINESS', 5, NOW());

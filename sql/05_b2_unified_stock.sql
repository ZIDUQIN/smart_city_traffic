-- B2 改造：统一库存（单一数据源 + 相邻区段模型）
-- dev 环境重建；旧 seat_inventory 废弃，读链路与写链路统一到 stock 表。

DROP TABLE IF EXISTS `seat_inventory`;
DROP TABLE IF EXISTS `ticket_deduct_record`;
DROP TABLE IF EXISTS `order`;
DROP TABLE IF EXISTS `stock`;

-- ① 真实库存：车次 + 席别 + 相邻区段 唯一，读链路与写链路都指向它
CREATE TABLE `stock` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `train_no`    VARCHAR(32) NOT NULL COMMENT '车次号，如 G1001',
    `seat_no`     VARCHAR(32) NOT NULL COMMENT '席别：SECOND_CLASS/FIRST_CLASS/BUSINESS',
    `segment_no`  INT         NOT NULL COMMENT '相邻区段号，从 1 开始，1 表示站序1→站序2',
    `remain`      INT         NOT NULL COMMENT '该区段剩余票数',
    `update_time` DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_train_seat_segment` (`train_no`, `seat_no`, `segment_no`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '统一库存表';

-- ② 订单：带起讫站序，状态 待确认/已确认/已支付/已取消
CREATE TABLE `order` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `order_no`     VARCHAR(64) NOT NULL COMMENT '订单号',
    `user_id`      BIGINT      NOT NULL COMMENT '下单账号',
    `passenger_id` BIGINT      NOT NULL COMMENT '乘车人ID',
    `train_no`     VARCHAR(32) NOT NULL COMMENT '车次号',
    `seat_no`      VARCHAR(32) NOT NULL COMMENT '席别',
    `from_seq`     INT         NOT NULL COMMENT '上车站序',
    `to_seq`       INT         NOT NULL COMMENT '下车站序',
    `amount`       DECIMAL(10,2) NOT NULL COMMENT '订单金额（元）',
    `status`       VARCHAR(16) NOT NULL COMMENT '待确认/已确认/已支付/已取消',
    `create_time`  DATETIME    NOT NULL COMMENT '创建时间',
    `update_time`  DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单表';

-- ③ 扣减流水：带起讫站序，状态机仍为 6 值
CREATE TABLE `ticket_deduct_record` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `order_no`     VARCHAR(64)  NOT NULL COMMENT '订单号',
    `user_id`      BIGINT       NOT NULL COMMENT '下单账号',
    `passenger_id` BIGINT       NOT NULL COMMENT '乘车人ID',
    `train_no`     VARCHAR(32)  NOT NULL COMMENT '车次号',
    `seat_no`      VARCHAR(32)  NOT NULL COMMENT '席别',
    `from_seq`     INT          NOT NULL COMMENT '上车站序',
    `to_seq`       INT          NOT NULL COMMENT '下车站序',
    `status`       VARCHAR(16)  NOT NULL COMMENT '扣减中/已扣减/已建单/补偿中/已补偿/已取消',
    `fail_reason`  VARCHAR(255) DEFAULT NULL COMMENT '失败原因',
    `create_time`  DATETIME     NOT NULL COMMENT '创建时间',
    `update_time`  DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_status_update` (`status`, `update_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '扣减流水表';

-- 演示数据：G1001 两区段 × 三席别 = 6 行
-- 余量：二等 100 / 一等 20 / 商务 5（每个区段相同）
INSERT INTO `stock` (`train_no`, `seat_no`, `segment_no`, `remain`, `update_time`)
VALUES ('G1001', 'SECOND_CLASS', 1, 100, NOW()),
       ('G1001', 'SECOND_CLASS', 2, 100, NOW()),
       ('G1001', 'FIRST_CLASS', 1, 20, NOW()),
       ('G1001', 'FIRST_CLASS', 2, 20, NOW()),
       ('G1001', 'BUSINESS', 1, 5, NOW()),
       ('G1001', 'BUSINESS', 2, 5, NOW());

-- ============================================================
-- 基础主数据建表：station / train / train_station / passenger
-- 顺序：先跑本文件建表，再跑 08_train_data.sql 灌数据。
-- 注意：seat_inventory、ticket_order 两张早期表已废弃（说明见文内），不要再建；
--       订单与库存以 `order` / `stock` / `stock_capacity` 为准（见 04、05、07）。
-- ============================================================

-- ---------------------- 1. 车站 ----------------------
CREATE TABLE IF NOT EXISTS `station` (
    `id`            BIGINT       NOT NULL COMMENT '车站ID',
    `station_name`  VARCHAR(64)  NOT NULL COMMENT '站名，如 西安北',
    `station_code`  VARCHAR(16)  NOT NULL COMMENT '车站编码',
    `city`          VARCHAR(64)  DEFAULT NULL COMMENT '所属城市',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_station_code` (`station_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='车站表';

-- ---------------------- 2. 车次 ----------------------
CREATE TABLE IF NOT EXISTS `train` (
    `id`               BIGINT      NOT NULL COMMENT '车次ID',
    `train_no`         VARCHAR(16) NOT NULL COMMENT '车次号，如 G1001',
    `start_station_id` BIGINT      NOT NULL COMMENT '始发站ID',
    `end_station_id`   BIGINT      NOT NULL COMMENT '终到站ID',
    `depart_time`      TIME        NOT NULL COMMENT '始发时刻',
    `arrive_time`      TIME        NOT NULL COMMENT '终到时刻',
    `sale_start_time`  DATETIME    NOT NULL COMMENT '起售时间（放票时刻）',
    `sale_end_time`    DATETIME    NOT NULL COMMENT '停售时间',
    `status`           TINYINT     NOT NULL DEFAULT 1 COMMENT '1 正常，0 停运',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_train_no` (`train_no`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='车次表';

-- ---------------------- 3. 经停站与时刻 ----------------------
CREATE TABLE IF NOT EXISTS `train_station` (
    `id`           BIGINT   NOT NULL COMMENT '主键',
    `train_id`     BIGINT   NOT NULL COMMENT '车次ID',
    `station_id`   BIGINT   NOT NULL COMMENT '车站ID',
    `station_seq`  INT      NOT NULL COMMENT '站序：1、2、3…（区间计算基础）',
    `arrive_time`  TIME     DEFAULT NULL COMMENT '到点',
    `depart_time`  TIME     DEFAULT NULL COMMENT '发点',
    `stop_minutes` INT      NOT NULL DEFAULT 0 COMMENT '停站分钟',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_train_seq` (`train_id`, `station_seq`),
    KEY `idx_station` (`station_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='车次经停站表';

-- ---------------------- 4. 余票（已废弃，不再建） ----------------------
-- 早期按 train_id 建 seat_inventory，无法区分"车次实例（哪一天）"和"区段"，
-- 已被 stock + stock_capacity 取代（见 05_b2_unified_stock.sql、07_instance_migration.sql）。
-- seat_inventory 从未在库里存在过，这里保留位置说明，不要再建。

-- ---------------------- 5. 乘车人 ----------------------
CREATE TABLE IF NOT EXISTS `passenger` (
    `id`             BIGINT      NOT NULL COMMENT '乘车人ID',
    `user_id`        BIGINT      NOT NULL COMMENT '所属用户ID',
    `passenger_name` VARCHAR(64) NOT NULL COMMENT '姓名',
    `id_card`        VARCHAR(32) NOT NULL COMMENT '身份证号',
    `passenger_type` TINYINT     NOT NULL DEFAULT 1 COMMENT '1 成人，2 学生，3 儿童',
    `create_time`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id_card` (`user_id`, `id_card`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='乘车人表';

-- ---------------------- 6. 订单（已废弃，不再建） ----------------------
-- ticket_order 是早期骨架里的订单表：只有 train_id + seat_type，没有 instance_no、没有 travel_date，
-- 无法支撑"按车次实例、按区段"的库存核算，代码里也从未引用过。
-- 真实订单表是 `order`（见 04_ticket_order_stock.sql）。该表已于 2026-11-18 DROP，
-- 保留这段注释仅作变更记录，不要再建。
--
-- CREATE TABLE IF NOT EXISTS `ticket_order` (
--     `id`                 BIGINT        NOT NULL COMMENT '订单ID（雪花）',
--     `order_no`           VARCHAR(32)   NOT NULL COMMENT '业务订单号',
--     `user_id`            BIGINT        NOT NULL COMMENT '下单用户',
--     `train_id`           BIGINT        NOT NULL COMMENT '车次ID',
--     `from_seq`           INT           NOT NULL COMMENT '上车站序',
--     `to_seq`             INT           NOT NULL COMMENT '下车站序',
--     `seat_type`          VARCHAR(16)   NOT NULL COMMENT '席别',
--     `passenger_id`       BIGINT        NOT NULL COMMENT '乘车人ID',
--     `passenger_name`     VARCHAR(64)   NOT NULL COMMENT '乘车人姓名（冗余，便于查询）',
--     `passenger_id_card`  VARCHAR(32)   NOT NULL COMMENT '乘车人证件号（冗余）',
--     `amount`             DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '金额',
--     `status`             TINYINT       NOT NULL DEFAULT 0 COMMENT '0 待支付，1 已支付，2 已取消，3 超时关闭',
--     `create_time`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
--     `pay_deadline`       DATETIME      DEFAULT NULL COMMENT '支付截止时间',
--     `pay_time`           DATETIME      DEFAULT NULL COMMENT '支付时间',
--     PRIMARY KEY (`id`),
--     UNIQUE KEY `uk_order_no` (`order_no`),
--     KEY `idx_user_train` (`user_id`, `train_id`)
-- ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='车票订单表';

-- 演示数据不在这里插。真实车站、车次、经停、余票由 08_train_data.sql 提供，
-- 那里有上百趟车和完整的站序数据；这里再插一份 G1001 只会和真实数据打架。

-- ============================================================
-- 07 班次实例化迁移（第一批：数据层，不改代码）
-- 执行方式：mysql -e "SOURCE sql/07_instance_migration.sql"
-- 仅执行一次，重复执行会因备份表/索引已存在而失败
-- ============================================================

SET NAMES utf8mb4;

-- ---------- B0 备份 ----------
CREATE TABLE stock_bak_20261116 AS SELECT * FROM stock;
CREATE TABLE order_bak_20261116 AS SELECT * FROM `order`;
CREATE TABLE deduct_record_bak_20261116 AS SELECT * FROM ticket_deduct_record;

-- ---------- B1 建三张新表 ----------
CREATE TABLE train_instance (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  instance_no     VARCHAR(48)  NOT NULL COMMENT '实例编号，格式 班次号-yyyyMMdd',
  train_no        VARCHAR(32)  NOT NULL,
  travel_date     DATE         NOT NULL COMMENT '运营日期',
  status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 2=停运',
  stock_preheated TINYINT      NOT NULL DEFAULT 0 COMMENT '0=未预热 1=已预热',
  create_time     DATETIME     NOT NULL,
  update_time     DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_instance_no (instance_no),
  UNIQUE KEY uk_train_date (train_no, travel_date),
  KEY idx_travel_date (travel_date),
  KEY idx_train_date_status (train_no, travel_date, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班次实例';

CREATE TABLE stock_capacity (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  train_no       VARCHAR(32) NOT NULL,
  seat_no        VARCHAR(32) NOT NULL,
  segment_no     INT         NOT NULL,
  total_capacity INT         NOT NULL COMMENT '该段总座位数',
  PRIMARY KEY (id),
  UNIQUE KEY uk_capacity (train_no, seat_no, segment_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班次容量（模板层）';

CREATE TABLE ticket_price (
  id        BIGINT        NOT NULL AUTO_INCREMENT,
  train_no  VARCHAR(32)   NOT NULL,
  from_seq  INT           NOT NULL,
  to_seq    INT           NOT NULL,
  seat_no   VARCHAR(32)   NOT NULL,
  price     DECIMAL(10,2) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_price (train_no, from_seq, to_seq, seat_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='票价（按起讫站对）';

-- ---------- B2 灌容量表（初始值，不读实时 remain） ----------
INSERT INTO stock_capacity (train_no, seat_no, segment_no, total_capacity) VALUES
('G1001','SECOND_CLASS',1,50),
('G1001','SECOND_CLASS',2,50),
('G1001','FIRST_CLASS',1,20),
('G1001','FIRST_CLASS',2,20),
('G1001','BUSINESS',1,5),
('G1001','BUSINESS',2,5);

-- ---------- B3 灌票价表（按旧规则：每段单价 × 段数） ----------
-- 二等 50/段、一等 80/段、商务 150/段
INSERT INTO ticket_price (train_no, from_seq, to_seq, seat_no, price) VALUES
('G1001',1,2,'SECOND_CLASS',50.00),
('G1001',2,3,'SECOND_CLASS',50.00),
('G1001',1,3,'SECOND_CLASS',100.00),
('G1001',1,2,'FIRST_CLASS',80.00),
('G1001',2,3,'FIRST_CLASS',80.00),
('G1001',1,3,'FIRST_CLASS',160.00),
('G1001',1,2,'BUSINESS',150.00),
('G1001',2,3,'BUSINESS',150.00),
('G1001',1,3,'BUSINESS',300.00);

-- ---------- B4 改库存表 ----------
-- 必须先删旧唯一索引，否则后续加日期数据会冲突
ALTER TABLE stock DROP INDEX uk_train_seat_segment;
ALTER TABLE stock ADD COLUMN instance_no VARCHAR(48) NULL COMMENT '班次实例编号' AFTER id;
ALTER TABLE stock ADD COLUMN train_no_v2 VARCHAR(32) NULL COMMENT '冗余班次号，便于统计' AFTER train_no;
UPDATE stock SET instance_no = CONCAT(train_no, '-', '20261116'),
                 train_no_v2 = train_no
WHERE instance_no IS NULL;
ALTER TABLE stock MODIFY instance_no VARCHAR(48) NOT NULL;
ALTER TABLE stock MODIFY train_no_v2  VARCHAR(32) NOT NULL;
ALTER TABLE stock ADD UNIQUE KEY uk_instance_seat_segment (instance_no, seat_no, segment_no);
ALTER TABLE stock ADD KEY idx_train_no_v2 (train_no_v2);

-- ---------- B5 改订单表和流水表 ----------
ALTER TABLE `order`
  ADD COLUMN instance_no VARCHAR(48) NULL COMMENT '班次实例编号' AFTER train_no,
  ADD COLUMN travel_date DATE NULL COMMENT '运营日期' AFTER instance_no;

UPDATE `order`
SET instance_no = CONCAT(train_no, '-', DATE_FORMAT(create_time, '%Y%m%d')),
    travel_date = DATE(create_time)
WHERE instance_no IS NULL;

ALTER TABLE ticket_deduct_record
  ADD COLUMN instance_no VARCHAR(48) NULL AFTER train_no,
  ADD COLUMN travel_date DATE NULL AFTER instance_no;

UPDATE ticket_deduct_record
SET instance_no = CONCAT(train_no, '-', DATE_FORMAT(create_time, '%Y%m%d')),
    travel_date = DATE(create_time)
WHERE instance_no IS NULL;

-- ---------- B6 建今天的实例记录 ----------
-- stock_preheated=1：这一天的库存已在 Redis（满票），标记已预热避免任务重复处理
INSERT INTO train_instance (instance_no, train_no, travel_date, status, stock_preheated, create_time, update_time)
VALUES ('G1001-20261116', 'G1001', '2026-11-16', 1, 1, NOW(), NOW());

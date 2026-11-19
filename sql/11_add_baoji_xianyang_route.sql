-- ============================================================
-- 补线路：宝鸡南 <-> 咸阳秦都 <-> 西安北
--
-- 背景：库里宝鸡南只有 D2635 一趟去兰州的车，宝鸡与咸阳/西安之间没有直达。
-- 用户（宝鸡本地人）说"从宝鸡到咸阳"时查不到任何车，语音只能把他往西安北引，
-- 对外表现出来的现象就是"说宝鸡，被改成西安北"。
--
-- 新增两趟往返，经停咸阳秦都（西宝高铁上的实际经停站）：
--   D2636  宝鸡南 07:20 -> 咸阳秦都 08:02/08:04 -> 西安北 08:18
--   D2637  西安北 18:10 -> 咸阳秦都 18:24/18:26 -> 宝鸡南 19:10
-- ============================================================

-- 1. 车次模板（西安北=1，咸阳秦都=2，宝鸡南=3）
-- id 不是自增（生产由雪花算法生成），这里手工指定 102/103 接在现有 101 之后
INSERT INTO train (id, train_no, start_station_id, end_station_id, depart_time, arrive_time,
                   sale_start_time, sale_end_time, status)
VALUES (102, 'D2636', 3, 1, '07:20:00', '08:18:00', '2026-11-01 00:00:00', '2026-12-31 23:59:59', 1),
       (103, 'D2637', 1, 3, '18:10:00', '19:10:00', '2026-11-01 00:00:00', '2026-12-31 23:59:59', 1);

-- 2. 经停站
INSERT INTO train_station (id, train_id, station_id, station_seq, arrive_time, depart_time, stop_minutes)
VALUES (304, 102, 3, 1, NULL, '07:20:00', 0),
       (305, 102, 2, 2, '08:02:00', '08:04:00', 2),
       (306, 102, 1, 3, '08:18:00', NULL, 0),
       (307, 103, 1, 1, NULL, '18:10:00', 0),
       (308, 103, 2, 2, '18:24:00', '18:26:00', 2),
       (309, 103, 3, 3, '19:10:00', NULL, 0);

-- 3. 票价（按区段 + 席别，参考西宝高铁实际价位）
INSERT INTO ticket_price (train_no, from_seq, to_seq, seat_no, price) VALUES
('D2636', 1, 2, 'SECOND_CLASS',  30.00),
('D2636', 1, 2, 'FIRST_CLASS',   48.00),
('D2636', 1, 2, 'BUSINESS',      90.00),
('D2636', 1, 3, 'SECOND_CLASS',  46.00),
('D2636', 1, 3, 'FIRST_CLASS',   74.00),
('D2636', 1, 3, 'BUSINESS',     138.00),
('D2636', 2, 3, 'SECOND_CLASS',  20.00),
('D2636', 2, 3, 'FIRST_CLASS',   32.00),
('D2636', 2, 3, 'BUSINESS',      60.00),
('D2637', 1, 2, 'SECOND_CLASS',  20.00),
('D2637', 1, 2, 'FIRST_CLASS',   32.00),
('D2637', 1, 2, 'BUSINESS',      60.00),
('D2637', 1, 3, 'SECOND_CLASS',  46.00),
('D2637', 1, 3, 'FIRST_CLASS',   74.00),
('D2637', 1, 3, 'BUSINESS',     138.00),
('D2637', 2, 3, 'SECOND_CLASS',  30.00),
('D2637', 2, 3, 'FIRST_CLASS',   48.00),
('D2637', 2, 3, 'BUSINESS',      90.00);

-- 4. 库存上限（每席别每个区段）
INSERT INTO stock_capacity (train_no, seat_no, segment_no, total_capacity) VALUES
('D2636', 'SECOND_CLASS', 1, 50),
('D2636', 'SECOND_CLASS', 2, 50),
('D2636', 'FIRST_CLASS',  1, 20),
('D2636', 'FIRST_CLASS',  2, 20),
('D2636', 'BUSINESS',     1,  5),
('D2636', 'BUSINESS',     2,  5),
('D2637', 'SECOND_CLASS', 1, 50),
('D2637', 'SECOND_CLASS', 2, 50),
('D2637', 'FIRST_CLASS',  1, 20),
('D2637', 'FIRST_CLASS',  2, 20),
('D2637', 'BUSINESS',     1,  5),
('D2637', 'BUSINESS',     2,  5);

-- 5. 班次实例：与库里已生成的日期窗口对齐（当前是 11-16 ~ 11-23）
INSERT INTO train_instance (instance_no, train_no, travel_date, status, stock_preheated, create_time, update_time)
SELECT CONCAT(n.train_no, '-', DATE_FORMAT(d.travel_date, '%Y%m%d')),
       n.train_no, d.travel_date, 1, 1, NOW(), NOW()
FROM (SELECT 'D2636' AS train_no UNION ALL SELECT 'D2637') n
CROSS JOIN (SELECT DISTINCT travel_date FROM train_instance) d;

-- 6. 实例库存初始化：满票开局
INSERT INTO stock (instance_no, train_no, train_no_v2, seat_no, segment_no, remain, update_time)
SELECT i.instance_no, i.train_no, i.train_no, sc.seat_no, sc.segment_no, sc.total_capacity, NOW()
FROM train_instance i
JOIN stock_capacity sc ON sc.train_no = i.train_no
WHERE i.train_no IN ('D2636', 'D2637');

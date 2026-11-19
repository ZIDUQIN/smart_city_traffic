-- 库存回补幂等标记表
--
-- 背景：DB 库存回补是 remain + 1，本身不幂等。原来只靠"订单状态"判断补没补过，
-- 状态一旦被人工改库、状态回退或多实例时序绕过，就会重复回补，把库存推过总容量。
--
-- 这张表把"这一单的这个区段补过没有"落到数据库硬约束上：
--   INSERT IGNORE 返回 1 → 第一次补，执行加库存
--   INSERT IGNORE 返回 0 → 唯一键冲突，说明补过了，跳过
--
-- 为什么不挂在订单表上：订单会被清理（超时关单、运维清理），标记挂在会消失的记录上等于没有标记。

CREATE TABLE IF NOT EXISTS stock_restore_record (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_no VARCHAR(32) NOT NULL COMMENT '订单号',
  seat_no VARCHAR(32) NOT NULL COMMENT '席别',
  segment_no INT NOT NULL COMMENT '区段号',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次回补时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_seat_seg (order_no, seat_no, segment_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存回补幂等标记：一条记录代表这个订单的该区段已经回补过';

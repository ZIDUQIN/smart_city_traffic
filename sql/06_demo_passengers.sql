-- 演示数据：为 admin（user_id=1）插入 3 个乘车人
-- 显式指定短 ID（雪花生成的 ID 为 19 位长整型，不会与这些短 ID 冲突）

INSERT IGNORE INTO `passenger` (`id`, `user_id`, `passenger_name`, `id_card`, `passenger_type`,
                                `create_time`, `update_time`)
VALUES (1001, 1, '张三', '610101199001011234', 1, NOW(), NOW()),
       (1002, 1, '李四', '610101199002022345', 1, NOW(), NOW()),
       (1003, 1, '王芳', '610101199503033456', 2, NOW(), NOW());

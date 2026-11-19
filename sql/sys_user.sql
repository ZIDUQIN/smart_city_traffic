-- 用户表
CREATE TABLE IF NOT EXISTS `sys_user` (
    `id`          BIGINT       NOT NULL COMMENT '用户ID（雪花算法生成）',
    `username`    VARCHAR(64)  NOT NULL COMMENT '登录用户名',
    `password`    VARCHAR(100) NOT NULL COMMENT '密码（BCrypt 加密存储）',
    `nickname`    VARCHAR(64) DEFAULT NULL COMMENT '昵称',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用，0 禁用',
    `role`        VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN 管理员 / USER 普通用户',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

-- 初始用户：用户名 admin，密码 123456（下方为 BCrypt 哈希）
-- 若该哈希在你的环境无法登录，可写一个临时测试用 PasswordEncoder 生成后再替换
INSERT INTO `sys_user` (`id`, `username`, `password`, `nickname`, `status`, `role`)
VALUES (1, 'admin', '$2b$10$imkZj9PxXOKnXGMTqFsQF.W1PRCwT/rgxhVzJ2drz0wqx.JG5f58a', '管理员', 1, 'ADMIN');

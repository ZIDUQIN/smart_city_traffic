-- 用户角色：管理端点（实例维护 / 预热 / Mock 渠道注入）只允许 ADMIN
-- 历史数据默认 USER，只有显式授权才是管理员
ALTER TABLE `sys_user`
    ADD COLUMN `role` VARCHAR(16) NOT NULL DEFAULT 'USER'
        COMMENT '角色：ADMIN 管理员 / USER 普通用户'
        AFTER `status`;

UPDATE `sys_user` SET `role` = 'ADMIN' WHERE `username` = 'admin';

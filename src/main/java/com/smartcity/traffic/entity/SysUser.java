package com.smartcity.traffic.entity;

import java.time.LocalDateTime;

/**
 * 用户实体，对应表 sys_user。
 */
public class SysUser {

    /** 用户ID（雪花算法生成） */
    private Long id;

    /** 登录用户名 */
    private String username;

    /** BCrypt 加密后的密码 */
    private String password;

    /** 昵称 */
    private String nickname;

    /** 状态：1 启用，0 禁用 */
    private Integer status;

    /** 角色：ADMIN 管理员，USER 普通用户 */
    private String role;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}

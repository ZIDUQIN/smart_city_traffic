package com.smartcity.traffic.common;

import com.smartcity.traffic.entity.SysUser;
import com.smartcity.traffic.mapper.SysUserMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 当前登录用户工具：从 SecurityContext 取用户名，再查库拿到用户 ID。
 * 业务层需要 user_id 时统一调这里，避免每个 Service 各自处理。
 */
@Component
public class CurrentUserHelper {

    private final SysUserMapper sysUserMapper;

    public CurrentUserHelper(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    /**
     * 获取当前登录用户的 ID。
     *
     * @throws IllegalStateException 未登录或用户不存在
     */
    public Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new IllegalStateException("当前无登录用户");
        }
        SysUser user = sysUserMapper.selectByUsername(authentication.getName());
        if (user == null) {
            throw new IllegalStateException("当前登录用户不存在");
        }
        return user.getId();
    }
}

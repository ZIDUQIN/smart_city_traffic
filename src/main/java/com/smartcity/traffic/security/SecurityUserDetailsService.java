package com.smartcity.traffic.security;

import com.smartcity.traffic.entity.SysUser;
import com.smartcity.traffic.mapper.SysUserMapper;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * 从数据库加载用户并组装成 Spring Security 的 UserDetails。
 */
@Service
public class SecurityUserDetailsService implements UserDetailsService {

    private final SysUserMapper sysUserMapper;

    public SecurityUserDetailsService(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser sysUser = sysUserMapper.selectByUsername(username);
        if (sysUser == null) {
            throw new UsernameNotFoundException("用户名或密码错误");
        }

        boolean enabled = sysUser.getStatus() != null && sysUser.getStatus() == 1;
        // 角色来自数据库；历史数据/注册用户缺省 USER，只有显式 ADMIN 才拿到管理权限
        String role = (sysUser.getRole() == null || sysUser.getRole().isBlank())
                ? "USER" : sysUser.getRole().trim().toUpperCase();

        return User.builder()
                .username(sysUser.getUsername())
                .password(sysUser.getPassword())
                .authorities(Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role)))
                .accountLocked(false)
                .disabled(!enabled)
                .build();
    }
}

package com.smartcity.traffic.mapper;

import com.smartcity.traffic.entity.SysUser;
import org.apache.ibatis.annotations.Param;

/**
 * 用户 Mapper。
 */
public interface SysUserMapper {

    /**
     * 根据用户名查询用户（含密码，用于登录认证）。
     *
     * @param username 用户名
     * @return 用户，不存在返回 null
     */
    SysUser selectByUsername(@Param("username") String username);

    /**
     * 统计用户名出现次数，用于注册时判重。
     *
     * @param username 用户名
     * @return 数量
     */
    int countByUsername(@Param("username") String username);

    /**
     * 新增用户。
     *
     * @param sysUser 用户
     * @return 影响行数
     */
    int insert(SysUser sysUser);
}

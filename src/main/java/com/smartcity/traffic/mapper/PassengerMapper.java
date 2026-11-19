package com.smartcity.traffic.mapper;

import com.smartcity.traffic.entity.Passenger;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 乘车人 Mapper。
 */
public interface PassengerMapper {

    /** 新增乘车人 */
    int insert(Passenger passenger);

    /** 查询某用户下的全部乘车人 */
    List<Passenger> selectByUserId(@Param("userId") Long userId);

    /** 按主键查询 */
    Passenger selectById(@Param("id") Long id);

    /** 按用户 + 身份证号查询，用于判重 */
    Passenger selectByUserIdAndIdCard(@Param("userId") Long userId, @Param("idCard") String idCard);

    /** 更新乘车人（仅更新姓名、身份证、类型） */
    int update(Passenger passenger);

    /** 删除乘车人 */
    int deleteById(@Param("id") Long id);
}

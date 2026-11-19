package com.smartcity.traffic.service;

import com.smartcity.traffic.dto.PassengerRequest;
import com.smartcity.traffic.dto.PassengerVO;

import java.util.List;

/**
 * 乘车人业务接口。
 */
public interface PassengerService {

    /** 新增乘车人 */
    PassengerVO create(PassengerRequest request);

    /** 查询当前用户的乘车人列表 */
    List<PassengerVO> list();

    /** 修改乘车人 */
    PassengerVO update(Long id, PassengerRequest request);

    /** 删除乘车人 */
    void delete(Long id);
}

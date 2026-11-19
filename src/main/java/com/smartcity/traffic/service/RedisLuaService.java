package com.smartcity.traffic.service;

import java.util.List;

/**
 * Redis Lua 操作服务：扣减、回补、是否已扣查询。
 */
public interface RedisLuaService {

    /**
     * 执行 check.lua（只读预检：判重 + 多区段库存检查，不写数据）。
     *
     * @param segments 区段号数组，Lua 内逐个独立接收
     * @return 0 预检通过；-1 库存不足；-2 stock key/field 不存在；-3 乘车人重复购票
     */
    int check(String instanceNo, String seatNo, Long passengerId, List<Integer> segments);

    /**
     * 执行 deduct.lua（含乘车人判重、多区段扣减）。
     *
     * @param segments 区段号数组，Lua 内逐个独立接收
     * @return >=0 成功（扣减后最小余票）；-1 库存不足；-2 stock key/field 不存在；-3 乘车人重复购票
     */
    long deduct(String instanceNo, String seatNo, Long passengerId,
                 String orderNo, List<Integer> segments);

    /**
     * 执行 rollback.lua（含释放乘车人占位、多区段回补）。
     *
     * @return 1 已回补；0 已补过或从未扣过（幂等，不动库存）
     */
    int rollback(String instanceNo, String seatNo, Long passengerId,
                  String orderNo, List<Integer> segments);

    /**
     * SISMEMBER 查询该订单是否已在"已扣标记"集合里。
     */
    boolean isDeducted(String instanceNo, String seatNo, String orderNo);
}

package com.smartcity.traffic.service;

import java.util.List;

/**
 * 真实库存服务。
 */
public interface StockService {

    /**
     * 按区段扣 DB 真实库存：一个事务内逐条条件 UPDATE，
     * 任一条失败（库存不足/异常）整体回滚。
     *
     * @return true 全部区段扣减成功
     */
    boolean deductRealStock(String instanceNo, String seatNo, List<Integer> segments);

    /**
     * 按区段回补 DB 真实库存：一个事务内逐条 remain + 1，
     * 用于用户取消、支付超时关单、退款。
     *
     * <p>两道闸：</p>
     * <ul>
     *   <li>幂等：同一 orderNo 的同一区段只会真正 +1 一次（唯一键登记），
     *       不依赖订单状态，状态被绕过也不会多补；</li>
     *   <li>上界：remain 到总容量后拒绝回补并抛异常，宁可少补（对账能补回来），绝不多补（会超卖）。</li>
     * </ul>
     *
     * <p>注意：被容量闸拦下时抛 IllegalStateException，整个事务回滚（不会出现补了一半的库存），
     * 调用方必须捕获并告警。</p>
     *
     * @return true 全部区段回补成功（或已回补过而跳过）
     */
    boolean restoreRealStock(String orderNo, String instanceNo, String seatNo, List<Integer> segments);
}

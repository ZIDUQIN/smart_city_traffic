package com.smartcity.traffic.service;

import java.math.BigDecimal;

/**
 * 票价计算服务。
 */
public interface PriceService {

    /**
     * 按班次 + 席别 + 起讫站序查票价表。
     *
     * @param trainNo 班次号
     * @param seatNo  席别
     * @param fromSeq 上车站序
     * @param toSeq   下车站序
     * @return 订单金额（元）
     */
    BigDecimal calculate(String trainNo, String seatNo, Integer fromSeq, Integer toSeq);
}

package com.smartcity.traffic.mapper;

import com.smartcity.traffic.entity.PaymentRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 支付流水 Mapper（表 payment_record）。
 */
public interface PaymentRecordMapper {

    /** 新增支付流水 */
    int insert(PaymentRecord record);

    /** 按支付单号查流水 */
    PaymentRecord selectByPaymentNo(@Param("paymentNo") String paymentNo);

    /** 查该订单是否已有支付成功记录 */
    PaymentRecord selectSuccessByOrderNo(@Param("orderNo") String orderNo);

    /** 查该订单全部待支付流水 */
    List<PaymentRecord> selectWaitingByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 条件更新为支付成功并回填渠道交易号（仅待支付可改）。
     *
     * @return 1 首次成功，0 已不是待支付（幂等）
     */
    int casSuccess(@Param("paymentNo") String paymentNo,
                   @Param("channelTradeNo") String channelTradeNo);

    /** 回调次数 +1 */
    int incrCallbackCount(@Param("paymentNo") String paymentNo);

    /** 待支付 → 已关闭 */
    int casClose(@Param("paymentNo") String paymentNo);
}

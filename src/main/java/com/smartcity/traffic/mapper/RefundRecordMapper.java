package com.smartcity.traffic.mapper;

import com.smartcity.traffic.entity.RefundRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 退款流水 Mapper（表 refund_record）。
 */
public interface RefundRecordMapper {

    /** 新增退款流水；uk_order_no 冲突会抛 DuplicateKeyException */
    int insert(RefundRecord record);

    /** 按退款单号查流水 */
    RefundRecord selectByRefundNo(@Param("refundNo") String refundNo);

    /** 按订单号查流水（一个订单只有一条） */
    RefundRecord selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 退款中 → 退款成功，并回填渠道退款单号。
     * 条件更新，返回 0 表示已不是"退款中"（幂等判定）。
     */
    int casSuccess(@Param("refundNo") String refundNo,
                   @Param("channelRefundNo") String channelRefundNo);

    /**
     * 退款中 → 退款失败。
     * 当前 Mock 渠道不会走到，预留给真实渠道失败回调。
     */
    int casFailed(@Param("refundNo") String refundNo);

    /** 回调次数 +1，观测渠道重试次数 */
    int incrCallbackCount(@Param("refundNo") String refundNo);

    // ================= 以下为对账任务使用 =================

    /**
     * 扫超时仍停在「退款中」的记录。
     * 用 update_time 判定而非 create_time：PROCESSING 每轮会刷新 update_time，
     * 用 create_time 会让它永远落在扫描窗口里，每轮都被查一次。
     */
    List<RefundRecord> listStaleRefunding(@Param("timeoutSeconds") int timeoutSeconds,
                                          @Param("limit") int limit);

    /** 置库存已回补；条件 stock_restored = 0，重复调用无副作用 */
    int markStockRestored(@Param("refundNo") String refundNo);

    /** 落库 / 覆盖渠道退款单号（重发时用新单号覆盖旧单号） */
    int updateChannelRefundNo(@Param("refundNo") String refundNo,
                              @Param("channelRefundNo") String channelRefundNo);

    /** 刷新 update_time：让 PROCESSING 的记录离开本轮扫描窗口，等下一轮再查 */
    int touchUpdateTime(@Param("refundNo") String refundNo);

    /** 对账重试次数 +1（只对需要动作却失败的场景调用） */
    int incrRetryCount(@Param("refundNo") String refundNo);

    /**
     * 打上「已转人工」标记：retry_count 置为 999，让这条退出自动对账。
     *
     * 条件带 retry_count &lt; 999，所以只有第一次调用返回 1，之后返回 0。
     * 靠这个返回值判断要不要发告警——天然只告警一次，不需要额外的"是否已告警"字段。
     */
    int flagManual(@Param("refundNo") String refundNo);
}

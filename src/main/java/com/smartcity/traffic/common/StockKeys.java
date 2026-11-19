package com.smartcity.traffic.common;

/**
 * Redis 库存相关 key 的统一构造。
 *
 * <p>花括号里放的是<b>班次实例编号</b>（不是车次号）：同一实例的 stock / deducted /
 * passenger 三个 key 因此落在同一个 slot，集群下 Lua 多 key 操作不会 CROSSSLOT。</p>
 */
public final class StockKeys {

    private StockKeys() {
    }

    /** stock:{instanceNo}:{seatNo}，Hash，field=区段号 */
    public static String stock(String instanceNo, String seatNo) {
        return "stock:{" + instanceNo + "}:" + seatNo;
    }

    /** deducted:{instanceNo}:{seatNo}，Set，成员=orderNo */
    public static String deducted(String instanceNo, String seatNo) {
        return "deducted:{" + instanceNo + "}:" + seatNo;
    }

    /** passenger:dedup:{instanceNo}:{passengerId}，String，乘车人占位 */
    public static String passenger(String instanceNo, Long passengerId) {
        return "passenger:dedup:{" + instanceNo + "}:" + passengerId;
    }
}

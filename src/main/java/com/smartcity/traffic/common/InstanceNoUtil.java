package com.smartcity.traffic.common;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 班次实例编号工具。
 *
 * <p>规则：班次号-yyyyMMdd，例如 G1001-20261118。
 * 实例编号同时是 Redis hash tag，保证同一实例的 stock/deducted/passenger 三个 key
 * 落在同一个 slot 上。</p>
 */
public final class InstanceNoUtil {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private InstanceNoUtil() {
    }

    /** 由班次号 + 运营日期拼出实例编号 */
    public static String of(String trainNo, LocalDate travelDate) {
        if (trainNo == null || travelDate == null) {
            throw new IllegalArgumentException("班次号与运营日期不能为空");
        }
        return trainNo + "-" + travelDate.format(DATE_FMT);
    }

    /** 取出实例编号里的日期部分，用于回写到 travel_date */
    public static LocalDate travelDateOf(String instanceNo) {
        if (instanceNo == null) {
            return null;
        }
        int idx = instanceNo.lastIndexOf('-');
        if (idx < 0 || idx == instanceNo.length() - 1) {
            return null;
        }
        return LocalDate.parse(instanceNo.substring(idx + 1), DATE_FMT);
    }
}

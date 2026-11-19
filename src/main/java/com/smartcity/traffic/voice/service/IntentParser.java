package com.smartcity.traffic.voice.service;

import com.smartcity.traffic.common.BizException;
import com.smartcity.traffic.voice.dto.VoiceIntent;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图解析（纯规则）：日期、人数、席别。
 *
 * <p>站名不在这里解析——那是 StationCorrector 的职责。这里只产出"哪天、几张、什么座"。</p>
 */
@Component
public class IntentParser {

    /** 必须"大后天"排在"后天"之前，否则会被子串先匹配掉 */
    private static final Pattern DAY_WORD = Pattern.compile(
            "(大后天|后天|后儿个|后儿|明天|明日|明儿个|明儿天|明儿|今天|今日|今儿个|今儿)");

    private static final Pattern WEEK_WORD = Pattern.compile(
            "(这周|本周|这个礼拜|下周|下礼拜)(一|二|三|四|五|六|日|天)");

    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})月(\\d{1,2})(号|日)");

    private static final Pattern DAY_ONLY = Pattern.compile("(\\d{1,2})(号|日)");

    private static final Pattern COUNT = Pattern.compile(
            "(\\d+|[一二两三四五六])\\s*(?:张|个|位|人|口)(?:票)?");

    /**
     * 时刻：可选时段前缀 + 小时 + 点/时/冒号 + 可选分钟。
     * "十点半" / "下午三点" / "10:30" / "晚上七点一刻" 都能命中。
     */
    private static final Pattern CLOCK = Pattern.compile(
            "(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上)?"
                    + "([0-9]{1,2}|十[一二]?|[零一二两三四五六七八九])(?:点|时|:|：)"
                    + "(半|一刻|三刻|[0-9]{1,2}分?)?");

    private static final Map<String, Integer> CN_NUM = new HashMap<>();

    private static final Map<String, Integer> WEEK_OFFSET = new HashMap<>();

    static {
        CN_NUM.put("一", 1);
        CN_NUM.put("二", 2);
        CN_NUM.put("两", 2);
        CN_NUM.put("三", 3);
        CN_NUM.put("四", 4);
        CN_NUM.put("五", 5);
        CN_NUM.put("六", 6);

        WEEK_OFFSET.put("一", 1);
        WEEK_OFFSET.put("二", 2);
        WEEK_OFFSET.put("三", 3);
        WEEK_OFFSET.put("四", 4);
        WEEK_OFFSET.put("五", 5);
        WEEK_OFFSET.put("六", 6);
        WEEK_OFFSET.put("日", 7);
        WEEK_OFFSET.put("天", 7);
    }

    private static final int MAX_TICKETS = 5;

    /**
     * 解析意图。
     *
     * @param rawText 原始识别文本
     * @param today   基准日期（客户端上传或服务器当天）
     */
    public VoiceIntent parse(String rawText, LocalDate today) {
        VoiceIntent intent = new VoiceIntent();
        intent.setParserSource("rule");
        intent.setTravelDate(parseDate(rawText, today).toString());
        intent.setPassengerCount(parseCount(rawText));
        intent.setSeatPreference(parseSeat(rawText));
        intent.setDepartTimePreference(parseDepartTimePreference(rawText));
        return intent;
    }

    /** 日期：不说就是当天，不自动跳到明天 */
    LocalDate parseDate(String rawText, LocalDate today) {
        if (rawText == null || rawText.isBlank()) {
            return today;
        }

        Matcher m = DAY_WORD.matcher(rawText);
        if (m.find()) {
            String w = m.group(1);
            if (w.startsWith("大后")) {
                return today.plusDays(3);
            }
            if (w.startsWith("后")) {
                return today.plusDays(2);
            }
            if (w.startsWith("明")) {
                return today.plusDays(1);
            }
            return today;
        }

        Matcher wm = WEEK_WORD.matcher(rawText);
        if (wm.find()) {
            int offset = WEEK_OFFSET.getOrDefault(wm.group(2), 1);
            LocalDate monday = today.with(DayOfWeek.MONDAY);
            if (wm.group(1).contains("下")) {
                return monday.plusDays(7 + offset - 1);
            }
            LocalDate d = monday.plusDays(offset - 1);
            // 本周这天已经过了，就顺延到下一周的同一天
            return d.isBefore(today) ? d.plusDays(7) : d;
        }

        Matcher mm = MONTH_DAY.matcher(rawText);
        if (mm.find()) {
            try {
                LocalDate d = LocalDate.of(today.getYear(),
                        Integer.parseInt(mm.group(1)), Integer.parseInt(mm.group(2)));
                return d.isBefore(today) ? d.plusYears(1) : d;
            } catch (Exception e) {
                return today;
            }
        }

        Matcher dm = DAY_ONLY.matcher(rawText);
        if (dm.find()) {
            try {
                int day = Integer.parseInt(dm.group(1));
                LocalDate d = LocalDate.of(today.getYear(), today.getMonthValue(), day);
                return d.isBefore(today) ? d.plusMonths(1) : d;
            } catch (Exception e) {
                return today;
            }
        }

        return today;
    }

    /** 人数：不说就是 1，超过 5 张直接拒绝（老人口误概率高，宁可拦下） */
    int parseCount(String rawText) {
        if (rawText == null) {
            return 1;
        }
        Matcher m = COUNT.matcher(rawText);
        if (!m.find()) {
            return 1;
        }
        String token = m.group(1);
        int n;
        try {
            n = Integer.parseInt(token);
        } catch (NumberFormatException e) {
            n = CN_NUM.getOrDefault(token, 1);
        }
        if (n < 1) {
            return 1;
        }
        if (n > MAX_TICKETS) {
            throw new BizException("一次最多买 " + MAX_TICKETS + " 张票");
        }
        return n;
    }

    /** 席别：默认二等座 */
    String parseSeat(String rawText) {
        if (rawText == null) {
            return "SECOND_CLASS";
        }
        if (rawText.contains("一等")) {
            return "FIRST_CLASS";
        }
        if (rawText.contains("商务") || rawText.contains("头等")) {
            return "BUSINESS";
        }
        return "SECOND_CLASS";
    }

    /**
     * 时间偏好："什么时候走"。
     *
     * <p>返回值有四种形态：</p>
     * <ul>
     *   <li>{@code null} —— 没说，推荐阶段给最早的一趟并说明理由</li>
     *   <li>{@code EARLIEST} —— "最早 / 第一趟"</li>
     *   <li>{@code ASAP} —— "尽快 / 马上"，含义是"当前时刻之后最近的一趟"，不是当天第一趟</li>
     *   <li>{@code MORNING / AFTERNOON / EVENING} —— 只说了时段，没说具体点</li>
     *   <li>{@code HH:mm} —— 具体时刻</li>
     * </ul>
     *
     * <p>不带"上午/下午"只说"三点"时不擅自 +12：交给推荐阶段把 03:00 和 15:00
     * 都试一遍，取离真实车次更近的那个解释。老人说"三点"多半指下午三点，
     * 但猜错了就会推荐一趟凌晨的车，不如让数据替他决定。</p>
     */
    String parseDepartTimePreference(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        String text = rawText.trim();

        if (containsAny(text, "尽快", "马上", "立刻", "现在就", "越快越好", "越早越好", "赶时间")) {
            return "ASAP";
        }
        if (containsAny(text, "最早", "第一趟", "头一趟")) {
            return "EARLIEST";
        }

        Matcher m = CLOCK.matcher(text);
        if (m.find()) {
            int hour = parseHour(m.group(2));
            if (hour >= 0 && hour <= 23) {
                int minute = parseMinute(m.group(3));
                String period = m.group(1);
                if (period != null && hour < 12) {
                    if ("下午".equals(period) || "傍晚".equals(period) || "晚上".equals(period)) {
                        hour += 12;
                    } else if ("中午".equals(period) && hour < 6) {
                        hour += 12;
                    }
                }
                return String.format("%02d:%02d", hour, minute);
            }
        }

        if (containsAny(text, "上午", "早上", "早晨", "一早", "清早")) {
            return "MORNING";
        }
        if (containsAny(text, "中午", "下午")) {
            return "AFTERNOON";
        }
        if (containsAny(text, "傍晚", "晚上", "夜里")) {
            return "EVENING";
        }
        return null;
    }

    private int parseHour(String s) {
        if (s == null || s.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            // 中文数字，落到下面
        }
        switch (s) {
            case "零":
                return 0;
            case "一":
                return 1;
            case "两":
            case "二":
                return 2;
            case "三":
                return 3;
            case "四":
                return 4;
            case "五":
                return 5;
            case "六":
                return 6;
            case "七":
                return 7;
            case "八":
                return 8;
            case "九":
                return 9;
            case "十":
                return 10;
            case "十一":
                return 11;
            case "十二":
                return 12;
            default:
                return -1;
        }
    }

    private int parseMinute(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        if (s.contains("半")) {
            return 30;
        }
        if (s.contains("一刻")) {
            return 15;
        }
        if (s.contains("三刻")) {
            return 45;
        }
        try {
            int v = Integer.parseInt(s.replace("分", "").trim());
            return v >= 0 && v < 60 ? v : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean containsAny(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }
}

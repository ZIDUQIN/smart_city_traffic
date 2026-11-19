package com.smartcity.traffic.voice.service;

import java.util.regex.Pattern;

/**
 * 把一句口语清洗成"只剩方向信息"的短句，再交给站名纠错。
 *
 * <p>不做分词，只用正则剥掉日期、人数、席别、客套动词。
 * 保留"从、到、去、往、自、由"这些方向词——它们是切分出发/到达的依据。</p>
 */
public final class TextNormalizer {

    /** 人数：两张、三个人、5位、两口 */
    private static final Pattern COUNT = Pattern.compile(
            "(\\d+|[一二两三四五六])\\s*(张|个|位|人|口)(票)?");

    /** 绝对日期词 */
    private static final Pattern DAY_WORD = Pattern.compile(
            "(今天|今日|今儿个|今儿|明天|明日|明儿个|明儿天|明儿|后天|后儿个|后儿|大后天)");

    /** 周几：这周三、下礼拜五 */
    private static final Pattern WEEK_WORD = Pattern.compile(
            "(这周|本周|这个礼拜|下周|下礼拜)(一|二|三|四|五|六|日|天)");

    /** N月N号 */
    private static final Pattern MONTH_DAY = Pattern.compile("\\d{1,2}月\\d{1,2}(号|日)");

    /** 光说 N号 */
    private static final Pattern DAY_ONLY = Pattern.compile("\\d{1,2}(号|日)");

    /** 席别词 */
    private static final Pattern SEAT_WORD = Pattern.compile(
            "(最便宜|便宜的|便宜|省钱|一等座|一等|商务座|商务|头等座|头等|二等座|二等)");

    /** 客套话与动词：注意不能删"去/到/往/从"，它们承载方向 */
    private static final Pattern FILLER = Pattern.compile(
            "(我想|我要|想要|帮我|帮忙|麻烦|请问|给我|买一|买|订一|订|购买|坐车|乘坐|坐|车票|票|去往)");

    private TextNormalizer() {
    }

    /**
     * 清洗文本，返回可能为空的短句。
     * 例："我明儿个从西岸去北金西两张" -> "从西岸去北金西"
     */
    public static String strip(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;
        s = COUNT.matcher(s).replaceAll(" ");
        s = DAY_WORD.matcher(s).replaceAll(" ");
        s = WEEK_WORD.matcher(s).replaceAll(" ");
        s = MONTH_DAY.matcher(s).replaceAll(" ");
        s = DAY_ONLY.matcher(s).replaceAll(" ");
        s = SEAT_WORD.matcher(s).replaceAll(" ");
        s = FILLER.matcher(s).replaceAll(" ");
        // 标点、空白归一
        s = s.replaceAll("[，。！？、,.!?~\\s]+", " ");
        return s.trim();
    }
}

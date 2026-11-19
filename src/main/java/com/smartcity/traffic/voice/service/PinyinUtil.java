package com.smartcity.traffic.voice.service;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;

/**
 * 汉字转拼音工具：站名纠错的底座。
 *
 * <p>方言发音差异主要体现在声母和韵母（"西岸"听起来就是"西安"），
 * 所以只保留不带声调的纯拼音，不区分声调，反而能提高同音召回。</p>
 */
public final class PinyinUtil {

    private static final HanyuPinyinOutputFormat FORMAT;

    static {
        FORMAT = new HanyuPinyinOutputFormat();
        FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    private PinyinUtil() {
    }

    /**
     * 整串转拼音，只保留字母数字，无分隔符。
     * 例如 "西安北" -> "xianbei"，非汉字字符（数字、字母）原样保留。
     */
    public static String toPinyin(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (isChinese(c)) {
                String[] pinyin = safePinyin(c);
                if (pinyin == null || pinyin.length == 0) {
                    sb.append(c);
                } else {
                    sb.append(pinyin[0]);
                }
            } else if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
            // 标点、空格、语气词直接丢弃
        }
        return sb.toString();
    }

    /**
     * 首字母串，例如 "西安北" -> "xab"。
     */
    public static String initials(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (isChinese(c)) {
                String[] pinyin = safePinyin(c);
                if (pinyin == null || pinyin.length == 0) {
                    sb.append(c);
                } else {
                    sb.append(pinyin[0].charAt(0));
                }
            } else if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** 是否汉字 */
    public static boolean isChinese(char c) {
        return c >= 0x4E00 && c <= 0x9FA5;
    }

    /**
     * 单字拼音（无声调）；非汉字返回原字符小写。
     * 逐字比对时用它，比整串拼音更能反映"哪个字听错了"。
     */
    public static String syllable(char c) {
        if (isChinese(c)) {
            String[] p = safePinyin(c);
            return (p == null || p.length == 0) ? String.valueOf(c) : p[0];
        }
        return String.valueOf(Character.toLowerCase(c));
    }

    private static String[] safePinyin(char c) {
        try {
            return PinyinHelper.toHanyuPinyinStringArray(c, FORMAT);
        } catch (Exception e) {
            // 生僻字拿不到拼音时按原字处理，不能让整条链路失败
            return null;
        }
    }
}

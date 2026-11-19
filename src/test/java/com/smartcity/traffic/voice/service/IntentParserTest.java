package com.smartcity.traffic.voice.service;

import com.smartcity.traffic.common.BizException;
import com.smartcity.traffic.voice.dto.VoiceIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则解析回归：日期、人数、席别。
 * 基准日固定为 2026-11-17（周二），避免"今天"漂移导致断言失效。
 */
class IntentParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    private final IntentParser parser = new IntentParser();

    @Test
    @DisplayName("日期：绝对日期词")
    void parseDayWords() {
        assertThat(parser.parseDate("明天去北京", TODAY)).isEqualTo(TODAY.plusDays(1));
        assertThat(parser.parseDate("后天去北京", TODAY)).isEqualTo(TODAY.plusDays(2));
        assertThat(parser.parseDate("大后天去北京", TODAY)).isEqualTo(TODAY.plusDays(3));
        assertThat(parser.parseDate("今儿个去北京", TODAY)).isEqualTo(TODAY);
        assertThat(parser.parseDate("我去北京", TODAY)).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("日期：周几与月日")
    void parseWeekAndMonth() {
        // 2026-11-17 是周二，本周一是 11-16
        assertThat(parser.parseDate("这周五去北京", TODAY)).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(parser.parseDate("下周三去北京", TODAY)).isEqualTo(LocalDate.of(2026, 11, 25));
        // 本周一已经过了，应顺延到下周
        assertThat(parser.parseDate("这周一去北京", TODAY)).isEqualTo(LocalDate.of(2026, 11, 23));

        assertThat(parser.parseDate("12月1号去北京", TODAY)).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(parser.parseDate("20号去北京", TODAY)).isEqualTo(LocalDate.of(2026, 11, 20));
        // 5 号已经过了，落到下个月
        assertThat(parser.parseDate("5号去北京", TODAY)).isEqualTo(LocalDate.of(2026, 12, 5));
    }

    @Test
    @DisplayName("人数：中文数字、阿拉伯数字、默认 1")
    void parsePassengerCount() {
        assertThat(parser.parseCount("两张")).isEqualTo(2);
        assertThat(parser.parseCount("3位")).isEqualTo(3);
        assertThat(parser.parseCount("一口")).isEqualTo(1);
        assertThat(parser.parseCount("我去北京")).isEqualTo(1);
    }

    @Test
    @DisplayName("人数：超过 5 张直接拒绝")
    void rejectTooManyTickets() {
        assertThatThrownBy(() -> parser.parseCount("六张"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最多");
    }

    @Test
    @DisplayName("席别：一等/商务/默认二等")
    void parseSeatPreference() {
        assertThat(parser.parseSeat("要一等座")).isEqualTo("FIRST_CLASS");
        assertThat(parser.parseSeat("商务座")).isEqualTo("BUSINESS");
        assertThat(parser.parseSeat("头等")).isEqualTo("BUSINESS");
        assertThat(parser.parseSeat("最便宜的那种")).isEqualTo("SECOND_CLASS");
        assertThat(parser.parseSeat("随便")).isEqualTo("SECOND_CLASS");
    }

    @Test
    @DisplayName("整句：站名不进意图，只产出日期/人数/席别")
    void parseWholeSentence() {
        VoiceIntent intent = parser.parse("我明儿个从西岸去北金西两张一等座", TODAY);

        assertThat(intent.getTravelDate()).isEqualTo("2026-11-18");
        assertThat(intent.getPassengerCount()).isEqualTo(2);
        assertThat(intent.getSeatPreference()).isEqualTo("FIRST_CLASS");
        assertThat(intent.getParserSource()).isEqualTo("rule");
        assertThat(intent.getFromStation()).isNull();
        assertThat(intent.getToStation()).isNull();
    }
}

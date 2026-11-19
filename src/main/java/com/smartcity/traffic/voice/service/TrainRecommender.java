package com.smartcity.traffic.voice.service;

import com.smartcity.traffic.dto.SeatOffer;
import com.smartcity.traffic.dto.TrainSearchVO;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 按"什么时候走"从车次列表里挑一趟。
 *
 * <p>只挑，不写：挑出来的车次只是一个建议，用户点头之后仍然走原来的确认订单流程，
 * 下单时库存会被 Lua 再拦一次，不存在"推荐了就等于留座"。</p>
 *
 * <p>"尽快"与"最早"的区别：最早是当天第一趟（可能早就开走了），
 * 尽快是"现在之后还能赶上的最近一趟"，所以今天要按当前时刻过滤。</p>
 */
@Component
public class TrainRecommender {

    /** 留 20 分钟余量：现在出发去车站也赶不上 5 分钟后发车的车 */
    private static final int ASAP_BUFFER_MINUTES = 20;

    /**
     * 说具体时刻时最多容忍的偏差。
     *
     * <p>老人说"下午三点"是要三点走，不是"随便哪趟都行"。差 4 小时的车也当推荐，
     * 他很可能不看时刻就买了。超出这个窗口就只告知"最近的是几点"，不替他预选。</p>
     */
    private static final int CLOCK_TOLERANCE_MINUTES = 90;

    /**
     * @param trains     已查到的车次
     * @param preference 时间偏好码：null / EARLIEST / ASAP / MORNING / AFTERNOON / EVENING / HH:mm
     * @param date       出行日期
     * @param now        当前时刻；仅当出行日期是今天且偏好为 ASAP 时用于过滤
     * @param seatNo     按哪个席别判"有票"：推荐一趟已售罄的车没有意义
     */
    public Recommendation recommend(List<TrainSearchVO> trains, String preference,
                                    LocalDate date, LocalTime now, String seatNo) {
        if (trains == null || trains.isEmpty()) {
            return null;
        }
        List<TrainSearchVO> sorted = new ArrayList<>(trains);
        sorted.sort(Comparator.comparingInt(t -> minutes(t.getDepartTime())));

        // 优先在"该席别还有票"的车里挑；全都没票时退回整表，播报会说明没票
        List<TrainSearchVO> pool = new ArrayList<>();
        for (TrainSearchVO t : sorted) {
            if (seatRemain(t, seatNo) > 0) {
                pool.add(t);
            }
        }
        List<TrainSearchVO> candidates = pool.isEmpty() ? sorted : pool;

        boolean today = date != null && date.equals(LocalDate.now());
        int nowMinute = now == null ? 0 : now.getHour() * 60 + now.getMinute();

        // 没说 / 只说"最早"：当天第一趟
        if (preference == null || "EARLIEST".equals(preference)) {
            return new Recommendation(candidates.get(0), preference == null ? Reason.DEFAULT : Reason.CLOCK,
                    null, preference);
        }

        // 尽快：现在之后最近的一趟；今天已经没有可赶的车时说清楚，不要硬塞第一趟
        if ("ASAP".equals(preference)) {
            if (!today) {
                return new Recommendation(candidates.get(0), Reason.ASAP, null, preference);
            }
            for (TrainSearchVO t : candidates) {
                if (minutes(t.getDepartTime()) >= nowMinute + ASAP_BUFFER_MINUTES) {
                    return new Recommendation(t, Reason.ASAP, null, preference);
                }
            }
            return new Recommendation(null, Reason.NO_MATCH, "今天的车都发完了", preference);
        }

        // 只说了时段
        if ("MORNING".equals(preference) || "AFTERNOON".equals(preference) || "EVENING".equals(preference)) {
            int from;
            int to;
            String band;
            if ("MORNING".equals(preference)) {
                from = 0;
                to = 12 * 60;
                band = "上午";
            } else if ("AFTERNOON".equals(preference)) {
                from = 12 * 60;
                to = 18 * 60;
                band = "下午";
            } else {
                from = 18 * 60;
                to = 24 * 60;
                band = "晚上";
            }
            for (TrainSearchVO t : candidates) {
                int m = minutes(t.getDepartTime());
                if (m >= from && m < to) {
                    return new Recommendation(t, Reason.PERIOD, band, preference);
                }
            }
            return new Recommendation(null, Reason.NO_MATCH, band + "这个时段没有车", preference);
        }

        // 具体时刻：取发车时间离它最近的一趟
        int target = minutes(preference);
        if (target < 0) {
            return new Recommendation(candidates.get(0), Reason.DEFAULT, null, null);
        }
        // 不带"上午/下午"只说"三点"：同时试 03:00 与 15:00，取更近的那个解释。
        // 老人说"三点"多半指下午，但猜错就会推荐一趟凌晨的车，不如让真实车次替他决定
        int alt = target + 12 * 60;
        int bestIndex = -1;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            int m = minutes(candidates.get(i).getDepartTime());
            int diff = Math.abs(m - target);
            if (target < 12 * 60) {
                diff = Math.min(diff, Math.abs(m - alt));
            }
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIndex = i;
            }
        }
        if (bestIndex < 0) {
            return new Recommendation(candidates.get(0), Reason.DEFAULT, null, null);
        }
        // 差得太远就不算"接近"：把最近的一趟报给用户，但不预选，让他重新拿主意
        if (bestDiff > CLOCK_TOLERANCE_MINUTES) {
            return new Recommendation(candidates.get(bestIndex), Reason.CLOCK_FAR,
                    spokenTime(preference), preference);
        }
        return new Recommendation(candidates.get(bestIndex), Reason.CLOCK, preference, preference);
    }

    /** "15:00" -> "您说的下午3点"，用于播报里说清"您要的那个点" */
    private String spokenTime(String hhmm) {
        try {
            String[] parts = hhmm.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            String period = h < 12 ? "上午" : (h < 18 ? "下午" : "晚上");
            int h12 = h <= 12 ? h : h - 12;
            String minText = m == 0 ? "" : (m < 10 ? "0" + m : String.valueOf(m)) + "分";
            return "您说的" + period + h12 + "点" + minText;
        } catch (Exception e) {
            return "您说的" + hhmm;
        }
    }

    /** 某趟车某席别的余票；查不到按 0（"没票"而不是"未知"，避免推荐售罄的车） */
    private int seatRemain(TrainSearchVO train, String seatNo) {
        if (train == null || train.getSeats() == null || seatNo == null) {
            return 0;
        }
        for (SeatOffer s : train.getSeats()) {
            if (seatNo.equals(s.getSeatNo())) {
                return s.getRemain() == null ? 0 : s.getRemain();
            }
        }
        return 0;
    }

    /** "HH:mm" -> 当天第几分钟；解析不了给一个大数，排到最后 */
    private int minutes(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) {
            return 24 * 60 + 60;
        }
        try {
            String[] parts = hhmm.trim().split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return 24 * 60 + 60;
        }
    }

    /** 推荐的理由类型：播报文案在 VoiceSpeechBuilder 里拼 */
    public enum Reason {
        /** 用户没说时间，默认挑最早的一趟 */
        DEFAULT,
        /** 用户说"尽快" */
        ASAP,
        /** 用户只说了一个时段 */
        PERIOD,
        /** 用户说了一个具体时刻（或"最早"） */
        CLOCK,
        /** 用户说了具体时刻，但最近的车也差得太远：只告知，不预选 */
        CLOCK_FAR,
        /** 按用户的偏好没有可选车（例如今天的车都发完了） */
        NO_MATCH
    }

    public static final class Recommendation {

        private final TrainSearchVO train;

        private final Reason reason;

        /** 时段名（上午/下午/晚上）或时刻，供文案拼接；可为空 */
        private final String detail;

        private final String preference;

        public Recommendation(TrainSearchVO train, Reason reason, String detail, String preference) {
            this.train = train;
            this.reason = reason;
            this.detail = detail;
            this.preference = preference;
        }

        /** null 表示按这个偏好没有可选车 */
        public TrainSearchVO getTrain() {
            return train;
        }

        public Reason getReason() {
            return reason;
        }

        public String getDetail() {
            return detail;
        }

        public String getPreference() {
            return preference;
        }

        /** 算"推荐成功"：有车可买，且不是"差太远只告知"或"没有车" */
        public boolean isMatched() {
            return train != null && reason != Reason.NO_MATCH && reason != Reason.CLOCK_FAR;
        }
    }
}

package com.smartcity.traffic.voice.service;

import com.smartcity.traffic.dto.SeatOffer;
import com.smartcity.traffic.dto.TrainSearchVO;
import com.smartcity.traffic.voice.dto.VoiceIntent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * 播报文案生成。文案要"慢、短、带数字"，老人听得清；
 * 不用"查询结果""余票充足"这种书面词，改成"还有票""没票了"。
 */
@Component
public class VoiceSpeechBuilder {

    public String stationMissing(boolean missingFrom, boolean missingTo) {
        if (missingFrom && missingTo) {
            return "您是要从哪儿，到哪儿？";
        }
        if (missingFrom) {
            return "您是要从哪儿出发？";
        }
        if (missingTo) {
            return "您是要到哪儿？";
        }
        // 两头都听出来了，只是分不开是哪个站：让用户点一下候选
        return "您说的是哪个站？请点一下下面的名字。";
    }

    /**
     * 这条线路压根没有车。
     *
     * <p>必须和"今天卖完了"分开说：用户听到"没有查到车"会以为是自己站名说错了，
     * 而实际可能是这条线今天满员了、明天还有票。</p>
     */
    public String noRoute(String from, String to, LocalDate date) {
        return fmtDate(date) + "，没有找到从" + from + "到" + to + "的班次。"
                + "您是不是站名说得不一样？可以再说一遍。";
    }

    /** 有这条线，但今天这几趟一张票都不剩 */
    public String soldOut(String from, String to, LocalDate date, int trainCount) {
        return fmtDate(date) + "，从" + from + "到" + to + "的 " + trainCount + " 趟车都卖完了。"
                + "要不要看看明天的？";
    }

    public String outOfWindow(LocalDate date, int windowDays) {
        return "只能买今天往后 " + windowDays + " 天以内的票。您说的是 "
                + date.getMonthValue() + "月" + date.getDayOfMonth() + "号，离得太远了。";
    }

    public String trains(VoiceIntent intent, List<TrainSearchVO> trains) {
        String seatNo = intent.getSeatPreference();
        String earliest = null;
        BigDecimal minPrice = null;
        int maxRemain = 0;

        for (TrainSearchVO t : trains) {
            if (t.getDepartTime() != null && (earliest == null || t.getDepartTime().compareTo(earliest) < 0)) {
                earliest = t.getDepartTime();
            }
            if (t.getSeats() == null) {
                continue;
            }
            for (SeatOffer s : t.getSeats()) {
                if (!seatNo.equals(s.getSeatNo())) {
                    continue;
                }
                if (s.getPrice() != null && (minPrice == null || s.getPrice().compareTo(minPrice) < 0)) {
                    minPrice = s.getPrice();
                }
                if (s.getRemain() != null && s.getRemain() > maxRemain) {
                    maxRemain = s.getRemain();
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(fmtDate(LocalDate.parse(intent.getTravelDate()))
                .replace("，", " "));
        sb.append("，从").append(intent.getFromStation()).append("到").append(intent.getToStation());
        sb.append("，一共 ").append(trains.size()).append(" 趟车。");

        if (earliest != null) {
            sb.append("最早 ").append(speakTime(earliest)).append(" 发车。");
        }
        if (minPrice != null) {
            // 金额口径：播报的是成人全价预估，实际以收银台为准（可能选了儿童/学生票）
            sb.append(seatName(seatNo)).append(" 最低 ").append(minPrice.stripTrailingZeros().toPlainString())
                    .append(" 元，这是成人全价，最后按付款页面算。");
        }
        if (maxRemain > 0) {
            sb.append("还有票。");
        } else {
            sb.append("当天的票已经卖完了。");
        }
        return sb.toString();
    }

    public String corrected(String from, String to, LocalDate date, VoiceIntent intent) {
        String f = from == null ? "哪里" : from;
        String t = to == null ? "哪里" : to;
        return "从" + f + "到" + t + "，" + fmtDate(date) + "，" + intent.getPassengerCount()
                + " 张，" + seatName(intent.getSeatPreference());
    }

    /**
     * 推荐播报：先说"为什么是这趟"，再报时刻、席别、余票，最后问一句要不要。
     *
     * <p>不报车次号、不报历时、不报"余票充足"这类书面词：老人记不住也用不上，
     * 念多了只会盖住真正要确认的三件事——几点走、多少钱、有没有票。</p>
     */
    public String recommend(TrainRecommender.Recommendation rec, String seatNo, String from, String to) {
        if (rec == null) {
            return "";
        }
        // 站名先报一遍：不识字的人看不到屏幕上的"咸阳秦都"，
        // 他只知道"咸阳"，必须靠耳朵确认自己被安排到了哪个站
        String where = (from == null || to == null) ? "" : "从" + from + "到" + to + "。";
        if (!rec.isMatched()) {
            String detail = rec.getDetail() == null ? "没有合适的车" : rec.getDetail();
            // "差太远"不是没有车，是有车但不接近：把最近那趟的时刻报出来，让用户自己决定
            if (rec.getReason() == TrainRecommender.Reason.CLOCK_FAR && rec.getTrain() != null) {
                return where + detail + "前后没有车。最近的一趟是 " + speakTime(rec.getTrain().getDepartTime())
                        + "，差得比较远，您看要不要换个时间？";
            }
            return where + detail + "。您换个时间或者换个日子试试？";
        }
        TrainSearchVO t = rec.getTrain();
        String lead;
        switch (rec.getReason()) {
            case ASAP:
                lead = "最近能赶上的一趟";
                break;
            case PERIOD:
                lead = (rec.getDetail() == null ? "" : rec.getDetail()) + "最早的一趟";
                break;
            case CLOCK:
                lead = "离您说的时间最近的一趟";
                break;
            case DEFAULT:
            default:
                lead = "您没说几点走，给您挑最早的一趟";
                break;
        }

        int remain = 0;
        BigDecimal price = null;
        if (t.getSeats() != null) {
            for (SeatOffer s : t.getSeats()) {
                if (seatNo != null && seatNo.equals(s.getSeatNo())) {
                    remain = s.getRemain() == null ? 0 : s.getRemain();
                    price = s.getPrice();
                    break;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(where);
        sb.append(lead).append("：").append(speakTime(t.getDepartTime())).append(" 发车");
        sb.append("，").append(seatName(seatNo));
        if (remain > 0) {
            sb.append(" 还有 ").append(remain).append(" 张票");
            if (price != null) {
                sb.append("，").append(price.stripTrailingZeros().toPlainString()).append(" 元一张，成人全价");
            }
        } else {
            sb.append(" 已经没票了");
        }
        sb.append("。要买这趟吗？");
        return sb.toString();
    }

    public static String seatName(String seatNo) {
        if ("FIRST_CLASS".equals(seatNo)) {
            return "一等座";
        }
        if ("BUSINESS".equals(seatNo)) {
            return "商务座";
        }
        return "二等座";
    }

    /** 11月17号，周二 */
    public static String fmtDate(LocalDate date) {
        String[] week = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        DayOfWeek dow = date.getDayOfWeek();
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "号，" + week[dow.getValue() - 1];
    }

    /** "07:07" -> "7点07分" */
    private String speakTime(String hhmm) {
        try {
            String[] parts = hhmm.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            if (m == 0) {
                return h + "点";
            }
            return h + "点" + (m < 10 ? "0" : "") + m + "分";
        } catch (Exception e) {
            return hhmm;
        }
    }
}

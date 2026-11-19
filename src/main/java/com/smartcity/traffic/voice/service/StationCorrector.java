package com.smartcity.traffic.voice.service;

import com.smartcity.traffic.voice.config.VoiceProperties;
import com.smartcity.traffic.voice.dto.CorrectionResult;
import com.smartcity.traffic.voice.dto.DirectionalCandidates;
import com.smartcity.traffic.voice.dto.StationCandidate;
import com.smartcity.traffic.voice.llm.StationJudgeClient;
import com.smartcity.traffic.voice.llm.StationJudgeResult;
import com.smartcity.traffic.service.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 站名纠错：把"西岸""北金西"这种方言误识别的片段落到真实站名上。
 *
 * <p>五步固定顺序：切方向 → 打分 → 定日期（外部传入）→ 车次反查 → 分档决策。
 * 反查放在决策前，是为了把"文本像"升级成"业务成立"：同城两个站分数接近时，
 * 哪条线真通车就往哪边定，这是人耳听不出、只有系统能算出来的证据。</p>
 */
@Component
public class StationCorrector {

    private static final Logger log = LoggerFactory.getLogger(StationCorrector.class);

    /** 从A到B / 自A往B / 由A去B */
    private static final Pattern FROM_TO = Pattern.compile("(?:从|自|由)\\s*(.+?)\\s*(?:到|去|往)\\s*(.+)");

    /** 去B / 往B / 到B（没有出发词） */
    private static final Pattern TO_ONLY = Pattern.compile("^(?:去|往|到)\\s*(.+)");

    /** A到B（没有"从"字） */
    private static final Pattern A_TO_B = Pattern.compile("(.+?)\\s*到\\s*(.+)");

    /** 低于这个分不进候选列表，直接当"没听出来" */
    private static final double MIN_CANDIDATE = 0.40;

    /** 自动采用门槛 */
    private static final double AUTO_SCORE = 0.85;

    /** 与次高分的差距低于这个值就算"分不开"，交给用户点 */
    private static final double AUTO_GAP = 0.05;

    /**
     * 模糊匹配的分数上限。必须低于精确命中的 1.0：
     * 否则说了"西安北"，2 字窗口"西安"就先把同城的西安东/西安西一起顶到 1.0，
     * 三个站完全并列，系统反而分不出用户说的是哪个站。
     */
    private static final double FUZZY_CAP = 0.94;

    /**
     * 窗口覆盖率下限：匹配到的字数至少占站名的这个比例，这个窗口才算证据。
     *
     * <p>挡的是"上海"对上"上海虹桥/上海松江"这种：2 个字只占 4 字站名的一半，
     * 却因为"完全相同"加成 + 城市加成冲到和整词匹配一样的 0.94，
     * 结果上海红桥这种整词说法反而被两个"上海"前缀压成并列、系统不敢定站。
     * 0.6 让"西安"对"西安北"(0.67) 仍然有效，只掐掉"半截字对整站名"。</p>
     */
    private static final double MIN_WINDOW_COVERAGE = 0.6;

    /** 每个方向最多保留几个候选 */
    private static final int MAX_CANDIDATES = 5;

    /** 每个方向参与反查的候选数，最多 3×3 = 9 次查票 */
    private static final int PROBE_LIMIT = 3;

    /**
     * "唯一通车"也要文本分够高才自动采用。
     *
     * <p>反查是排除法，不是证实法：它只能说明候选里哪一组在业务上成立，
     * 不能证明用户说的就是这一组。0.55 太低——一个八竿子打不着的站，
     * 只因为"全库只有它通"就被顶上来，用户会拿到一张去错地方的票。
     * 抬到 0.75：同城近似（"咸阳"→"咸阳秦都" 0.88、"西安"→"西安北" 0.94）仍然通过，
     * 很明显不像的退回让用户点，点错了也是他自己的选择。</p>
     */
    private static final double UNIQUE_ADOPT_MIN = 0.75;

    /**
     * 说中了城市名（"咸阳"）时给该城市各站的分数。
     *
     * <p>压在 FUZZY_CAP 之下：它是"这个城市有这几个站"的补充信息，
     * 不该盖过用户实际说出站名（"咸阳北"）时拿到的更高分。</p>
     */
    private static final double CITY_SCORE = 0.88;

    private final StationIndex stationIndex;

    private final TicketService ticketService;

    private final StationJudgeClient stationJudgeClient;

    private final VoiceProperties properties;

    public StationCorrector(StationIndex stationIndex, TicketService ticketService,
                            StationJudgeClient stationJudgeClient, VoiceProperties properties) {
        this.stationIndex = stationIndex;
        this.ticketService = ticketService;
        this.stationJudgeClient = stationJudgeClient;
        this.properties = properties;
    }

    /**
     * @param cleaned 已去掉日期/人数/席别的短句，例如"从西岸去北金西"
     * @param date    用于查票的日期，反查只判"通不通车"，不影响站名正确性
     */
    public CorrectionResult correct(String cleaned, LocalDate date) {
        CorrectionResult result = decide(cleaned, date);
        // 规则拿不准、而且候选还在一手能数清的范围内，才值得花一次模型调用
        attachLlmDecision(cleaned, result, date);
        return result;
    }

    private CorrectionResult decide(String cleaned, LocalDate date) {
        CorrectionResult result = new CorrectionResult();
        DirectionalCandidates candidates = new DirectionalCandidates();

        Direction dir = splitDirection(cleaned);
        List<StationCandidate> fromC = dir.from == null ? List.of() : score(dir.from);
        List<StationCandidate> toC = dir.to == null ? List.of() : score(dir.to);

        candidates.setFrom(fromC);
        candidates.setTo(toC);
        result.setCandidates(candidates);

        if (fromC.isEmpty()) {
            result.getMissing().add("from");
        }
        if (toC.isEmpty()) {
            result.getMissing().add("to");
        }
        if (!result.getMissing().isEmpty()) {
            // 有一头完全没听出来，不查票，直接让用户补
            result.setNeedConfirm(true);
            return result;
        }

        Probe probe = probeRoutes(fromC, toC, date);

        StationCandidate f0 = fromC.get(0);
        StationCandidate t0 = toC.get(0);
        boolean top1HasTrain = probe.hasTrain(f0.getStationName(), t0.getStationName());

        if (top1HasTrain) {
            result.setChosenFrom(f0.getStationName());
            result.setChosenTo(t0.getStationName());
            // 同城两站都通车，才需要用户亲自点一下。
            // 第二个候选自己也得像（文本分够高）才算歧义：否则一句"郑州"里混进来的
            // "武汉"（只是音节擦边）会把本来清楚的线路拖成"您说的是哪个站"。
            boolean ambiguousFrom = fromC.size() > 1
                    && gap(fromC) < AUTO_GAP
                    && fromC.get(1).getScore() >= AUTO_SCORE
                    && probe.hasTrain(fromC.get(1).getStationName(), t0.getStationName());
            boolean ambiguousTo = toC.size() > 1
                    && gap(toC) < AUTO_GAP
                    && toC.get(1).getScore() >= AUTO_SCORE
                    && probe.hasTrain(f0.getStationName(), toC.get(1).getStationName());
            result.setNeedConfirm(ambiguousFrom || ambiguousTo);
            return result;
        }

        // 首选组合不通车，但恰好有一组通车：这是强证据，可以直接采用
        // —— 前提是这组本身的文本分够高，否则"唯一通车"可能只是数据稀疏的巧合
        Hit single = probe.singleHit();
        if (single != null) {
            double minScore = Math.min(scoreOf(single.from, fromC), scoreOf(single.to, toC));
            if (minScore >= UNIQUE_ADOPT_MIN) {
                result.setChosenFrom(single.from);
                result.setChosenTo(single.to);
                result.setNeedConfirm(false);
                log.debug("车次反查纠正站名：{} -> {}, {} -> {}（文本分 {}）",
                        f0.getStationName(), single.from, t0.getStationName(), single.to, minScore);
                return result;
            }
            // 文本分太低：通车不代表说的是这个站，退回让用户点一下
            log.debug("唯一通车但文本分过低（{}），退回点选", minScore);
            result.setNeedConfirm(true);
            return result;
        }

        if (probe.hitCount() == 0) {
            // 所有组合当天都没车：分数足够高就先用，避免无意义打扰
            boolean bothAuto = isAuto(fromC) && isAuto(toC);
            if (bothAuto) {
                result.setChosenFrom(f0.getStationName());
                result.setChosenTo(t0.getStationName());
                result.setNeedConfirm(false);
            } else {
                result.setNeedConfirm(true);
            }
            return result;
        }

        // 多组都通车、首选又没有车：只能让用户点
        result.setNeedConfirm(true);
        return result;
    }

    // ---------------- 模型兜底 ----------------

    /**
     * 规则拿不准时，让模型在候选里再挑一次。
     *
     * <p>只在 needConfirm 时才走到这里：候选清晰的情况一次模型都不调，
     * 所以正常查票链路不会因为模型变慢。</p>
     *
     * <p>模型说了什么不算数，算数的是"它说的那个站名能不能在候选里原样找到"，
     * 以及"这一组当天是不是真通车"。两条都过才采用，否则继续让用户点。</p>
     */
    private void attachLlmDecision(String cleaned, CorrectionResult result, LocalDate date) {
        if (!result.isNeedConfirm()) {
            return;
        }
        if (!properties.getLlm().isEnabled()) {
            return;
        }
        // 缺方向 = 根本没有候选，空着手问模型它也答不出来
        if (result.getMissing().contains("from") || result.getMissing().contains("to")) {
            return;
        }
        List<StationCandidate> fromC = result.getCandidates() == null ? null : result.getCandidates().getFrom();
        List<StationCandidate> toC = result.getCandidates() == null ? null : result.getCandidates().getTo();
        if (fromC == null || toC == null || fromC.isEmpty() || toC.isEmpty()) {
            return;
        }
        // 候选一眼就能分开的，不值得花一次模型调用
        if (!worthAsking(fromC) && !worthAsking(toC)) {
            return;
        }

        List<String> reachable = probeReachable(fromC, toC, date);
        StationJudgeResult judged = stationJudgeClient.judge(cleaned, fromC, toC, reachable);
        if (judged == null) {
            result.setModelFallback(true);
            return;
        }
        result.setModelUsed(true);

        String from = matchCandidate(fromC, judged.from());
        String to = matchCandidate(toC, judged.to());
        if (from == null || to == null || from.equals(to)) {
            // 模型也没把握，或者说了候选以外的站名：保持 needConfirm，让用户点
            log.debug("模型结果不可用（from={}, to={}），继续让用户确认", judged.from(), judged.to());
            return;
        }

        // 到这里采用模型的答案，不再拿"通不通车"否决它：
        // 用户说"从上海到广州"，库里本来就没有这条线，站名再对也查不到车；
        // 那该走的是"没有找到班次"，而不是揪着"您说的是哪个站"反复问。
        result.setChosenFrom(from);
        result.setChosenTo(to);
        result.setNeedConfirm(false);
        log.info("模型兜底定站：{} -> {}", from, to);
    }

    /**
     * 值不值得问模型：候选太多、首选分不高、或者前两名咬得很紧，都算"拿不准"。
     */
    private boolean worthAsking(List<StationCandidate> candidates) {
        if (candidates.size() >= 3) {
            return true;
        }
        Double top = candidates.get(0).getScore();
        if (top == null || top < AUTO_SCORE) {
            return true;
        }
        if (candidates.size() >= 2) {
            Double second = candidates.get(1).getScore();
            if (second == null || top - second < AUTO_GAP * 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * 列出候选之间当天真能走的组合，交给模型当参考。
     *
     * <p>只探前 3 个候选，最多 9 次轻量反查；模型是加分项，不值得为它把库扫一遍。</p>
     */
    private List<String> probeReachable(List<StationCandidate> fromC, List<StationCandidate> toC, LocalDate date) {
        List<String> reachable = new ArrayList<>();
        int fl = Math.min(PROBE_LIMIT, fromC.size());
        int tl = Math.min(PROBE_LIMIT, toC.size());
        for (int i = 0; i < fl; i++) {
            for (int j = 0; j < tl; j++) {
                String from = fromC.get(i).getStationName();
                String to = toC.get(j).getStationName();
                if (from == null || to == null || from.equals(to)) {
                    continue;
                }
                if (probeHasTrain(from, to, date)) {
                    reachable.add(from + "→" + to);
                }
            }
        }
        return reachable;
    }

    /**
     * 模型给的站名必须能在候选里原样找到，否则一律当它没说。
     */
    private String matchCandidate(List<StationCandidate> candidates, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String want = name.trim();
        for (StationCandidate each : candidates) {
            if (each.getStationName() != null && each.getStationName().equals(want)) {
                return each.getStationName();
            }
        }
        return null;
    }

    // ---------------- 方向切分 ----------------

    private Direction splitDirection(String cleaned) {
        String text = cleaned == null ? "" : cleaned.trim();
        if (text.isEmpty()) {
            return new Direction(null, null);
        }

        Matcher m = FROM_TO.matcher(text);
        if (m.find()) {
            return new Direction(trim(m.group(1)), trim(m.group(2)));
        }

        Matcher t = TO_ONLY.matcher(text);
        if (t.find()) {
            return new Direction(null, trim(t.group(1)));
        }

        Matcher a = A_TO_B.matcher(text);
        if (a.find()) {
            return new Direction(trim(a.group(1)), trim(a.group(2)));
        }

        // 只说一个地名：当作目的地，出发站必须问
        return new Direction(null, text);
    }

    private String trim(String s) {
        if (s == null) {
            return null;
        }
        String r = s.trim();
        return r.isEmpty() ? null : r;
    }

    // ---------------- 打分 ----------------

    private List<StationCandidate> score(String segment) {
        // 分两桶收集：城市名命中的站必须全部露面，不能被后面的名额截断挤掉；
        // 拼音模糊命中的按分数竞争剩下的名额
        List<StationCandidate> fuzzy = new ArrayList<>();
        List<StationCandidate> cityHits = new ArrayList<>();

        for (StationIndex.Entry e : stationIndex.all()) {
            String name = e.getName();
            // 片段里出现了完整站名：最硬的证据，直接满分并短路。
            // 少了这条，"西安北"会被 2 字窗口"西安"先顶到上限，和西安东/西安西并列，
            // 用户明明说对了站名却还要被追问一次。
            if (name != null && !name.isEmpty() && segment.contains(name)) {
                fuzzy.add(new StationCandidate(name, e.getCity(), 1.0, name));
                continue;
            }

            // 城市名命中：说的词里含完整的城市名（"咸阳"）。
            // 该城市下面所有站都要展示——用户说"咸阳"，他不知道咸阳有几个站、
            // 更不知道哪个站有车，只给一部分等于替他瞎猜
            boolean cityHit = e.getCity() != null && e.getCity().length() >= 2
                    && segment.contains(e.getCity());

            double best = 0.0;
            String matched = null;
            int maxLen = Math.min(5, segment.length());
            for (int len = 2; len <= maxLen; len++) {
                for (int i = 0; i + len <= segment.length(); i++) {
                    String sub = segment.substring(i, i + len);
                    if (!hasChinese(sub)) {
                        continue;
                    }
                    double v = scoreOne(e, segment, sub);
                    if (v > best) {
                        best = v;
                        matched = sub;
                    }
                }
            }

            // 拼音对"咸阳秦都"只有 50% 覆盖率，会被 MIN_WINDOW_COVERAGE 掐成 0 分；
            // 城市名命中时用兜底分把它捞回来
            if (cityHit && CITY_SCORE > best) {
                best = CITY_SCORE;
                matched = e.getCity();
            }

            if (cityHit) {
                cityHits.add(new StationCandidate(name, e.getCity(), round3(best), matched));
            } else if (best >= MIN_CANDIDATE) {
                fuzzy.add(new StationCandidate(name, e.getCity(), round3(best), matched));
            }
        }

        sortByScore(fuzzy);
        sortByScore(cityHits);

        // 城市命中的站全部保留，剩余名额给拼音结果；同名只留一个
        List<StationCandidate> out = new ArrayList<>(cityHits);
        for (StationCandidate c : fuzzy) {
            if (out.size() >= MAX_CANDIDATES) {
                break;
            }
            if (!containsName(out, c.getStationName())) {
                out.add(c);
            }
        }
        sortByScore(out);
        return out.size() > MAX_CANDIDATES ? new ArrayList<>(out.subList(0, MAX_CANDIDATES)) : out;
    }

    /**
     * 候选排序：分数降序，"实际匹配到几个字"多的优先，站名长的优先。
     *
     * <p>"上海红桥"里 上海虹桥 是靠 4 个字全对上，上海松江 只是 2 字前缀"上海"撞上，
     * 两者原始分都封顶了，只能靠匹配长度把更可信的挑出来。</p>
     */
    private void sortByScore(List<StationCandidate> list) {
        list.sort(Comparator.comparingDouble(StationCandidate::getScore).reversed()
                .thenComparingInt(c -> c.getMatchedText() == null ? 0 : -c.getMatchedText().length())
                .thenComparingInt(c -> c.getStationName() == null ? 0 : -c.getStationName().length()));
    }

    private boolean containsName(List<StationCandidate> list, String name) {
        for (StationCandidate c : list) {
            if (c.getStationName() != null && c.getStationName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单个片段对单个车站打分：在站名上滑同等长度的窗口，取最高分。
     * 片段比站名长时（"北金西西"），截取与站名等长的前段比较。
     */
    private double scoreOne(StationIndex.Entry e, String segment, String sub) {
        String name = e.getName();
        double best = 0.0;

        if (name.length() >= sub.length()) {
            int subLen = sub.length();
            double coverage = (double) subLen / name.length();
            if (coverage < MIN_WINDOW_COVERAGE) {
                return 0.0;
            }
            for (int i = 0; i + subLen <= name.length(); i++) {
                String win = name.substring(i, i + subLen);
                best = Math.max(best, windowScore(e, segment, win, sub, coverage));
            }
        } else {
            String win = sub.substring(0, name.length());
            best = windowScore(e, segment, name, win, 1.0);
        }
        return best;
    }

    private double windowScore(StationIndex.Entry e, String segment, String win, String sub, double coverage) {
        double charSim = charSim(win, sub);
        double pinyinSim = levSim(PinyinUtil.toPinyin(win), PinyinUtil.toPinyin(sub));
        double initialSim = levSim(PinyinUtil.initials(win), PinyinUtil.initials(sub));
        double lenMatch = win.length() == sub.length() ? 1.0 : 0.0;

        double v = 0.50 * charSim
                + 0.22 * pinyinSim
                + 0.13 * initialSim
                + 0.05 * lenMatch
                + 0.10 * coverage;

        if (win.equals(sub)) {
            v += 0.20;
        }
        if (e.getCity() != null && !e.getCity().isBlank() && segment.contains(e.getCity())) {
            v += 0.08;
        }
        // 只用 FUZZY_CAP 封顶：把 1.0 留给"片段里出现了完整站名"，两者之间要留出差距
        return Math.min(FUZZY_CAP, v);
    }

    /** 逐字拼音相似度：长的那个作分母，保证"少了字"和"多了字"都会被扣分 */
    private double charSim(String a, String b) {
        int n = Math.min(a.length(), b.length());
        if (n == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += syllableSim(PinyinUtil.syllable(a.charAt(i)), PinyinUtil.syllable(b.charAt(i)));
        }
        return sum / Math.max(a.length(), b.length());
    }

    /** 两个音节：完全相同 1.0；一个是另一个的前缀（jin / jing）给 0.8；其余按编辑距离 */
    private double syllableSim(String x, String y) {
        if (x.equals(y)) {
            return 1.0;
        }
        if (x.isEmpty() || y.isEmpty()) {
            return 0.0;
        }
        if (Math.abs(x.length() - y.length()) <= 2 && (x.startsWith(y) || y.startsWith(x))) {
            return 0.8;
        }
        return levSim(x, y);
    }

    private double levSim(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 0.0;
        }
        return 1.0 - (double) levenshtein(a, b) / max;
    }

    private int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    // ---------------- 决策 ----------------

    private boolean isAuto(List<StationCandidate> candidates) {
        if (candidates.isEmpty()) {
            return false;
        }
        double top = candidates.get(0).getScore();
        if (top < AUTO_SCORE) {
            return false;
        }
        return candidates.size() == 1 || gap(candidates) >= AUTO_GAP;
    }

    private double gap(List<StationCandidate> candidates) {
        if (candidates.size() < 2) {
            return 1.0;
        }
        return candidates.get(0).getScore() - candidates.get(1).getScore();
    }

    /** 从候选列表里取某个站名的文本分，取不到按 0 算 */
    private double scoreOf(String stationName, List<StationCandidate> candidates) {
        for (StationCandidate c : candidates) {
            if (c.getStationName().equals(stationName)) {
                return c.getScore();
            }
        }
        return 0.0;
    }

    // ---------------- 车次反查 ----------------

    private Probe probeRoutes(List<StationCandidate> fromC, List<StationCandidate> toC, LocalDate date) {
        Probe probe = new Probe();
        int fl = Math.min(PROBE_LIMIT, fromC.size());
        int tl = Math.min(PROBE_LIMIT, toC.size());

        for (int i = 0; i < fl; i++) {
            // 文本分明显不像的候选不参与反查。一句"郑州"里音节擦边混进来的"武汉"
            // 本身就不是用户说的站，让它参与"通不通车"的投票，会把唯一能走的线路搅成多组。
            if (fromC.get(i).getScore() < AUTO_SCORE) {
                continue;
            }
            for (int j = 0; j < tl; j++) {
                if (toC.get(j).getScore() < AUTO_SCORE) {
                    continue;
                }
                probe.query(fromC.get(i).getStationName(), toC.get(j).getStationName(), date);
            }
        }
        return probe;
    }

    /**
     * 反查某组站名当天通不通车。
     *
     * <p>只问"有没有车"，不走完整查票：完整查票会把每趟车的三种席别余票与票价都算一遍，
     * 而这个判断只生产一个布尔值。date 参数保留只为签名稳定——班次每天都跑，通车与否与日期无关。</p>
     */
    private boolean probeHasTrain(String from, String to, LocalDate date) {
        if (from == null || to == null || from.equals(to)) {
            return false;
        }
        try {
            return ticketService.routeExists(from, to);
        } catch (Exception e) {
            // 单组反查失败不影响其他组；站名纠错不能因为查票异常而整体失败
            log.debug("站名反查失败 {} -> {}: {}", from, to, e.getMessage());
            return false;
        }
    }

    private boolean hasChinese(String s) {
        for (char c : s.toCharArray()) {
            if (PinyinUtil.isChinese(c)) {
                return true;
            }
        }
        return false;
    }

    private double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    // ---------------- 内部结构 ----------------

    private static class Direction {
        private final String from;
        private final String to;

        Direction(String from, String to) {
            this.from = from;
            this.to = to;
        }
    }

    private static class Hit {
        private final String from;
        private final String to;

        Hit(String from, String to) {
            this.from = from;
            this.to = to;
        }
    }

    /** 反查结果缓存：同一个组合只查一次，避免决策阶段重复打库 */
    private class Probe {

        private final java.util.Map<String, Boolean> routes = new java.util.HashMap<>();

        private final java.util.Map<String, Hit> hitRoutes = new java.util.HashMap<>();

        void query(String from, String to, LocalDate date) {
            String key = key(from, to);
            if (routes.containsKey(key)) {
                return;
            }
            boolean hit = probeHasTrain(from, to, date);
            routes.put(key, hit);
            if (hit) {
                hitRoutes.put(key, new Hit(from, to));
            }
        }

        boolean hasTrain(String from, String to) {
            return Boolean.TRUE.equals(routes.get(key(from, to)));
        }

        int hitCount() {
            return hitRoutes.size();
        }

        Hit singleHit() {
            return hitRoutes.size() == 1 ? hitRoutes.values().iterator().next() : null;
        }

        private String key(String from, String to) {
            return from + "→" + to;
        }
    }
}

package com.smartcity.traffic.voice.controller;

import com.smartcity.traffic.common.BizException;
import com.smartcity.traffic.common.CurrentUserHelper;
import com.smartcity.traffic.common.Result;
import com.smartcity.traffic.dto.SeatOffer;
import com.smartcity.traffic.dto.TrainSearchVO;
import com.smartcity.traffic.service.TicketService;
import com.smartcity.traffic.voice.config.VoiceProperties;
import com.smartcity.traffic.voice.dto.AsrResult;
import com.smartcity.traffic.voice.dto.CorrectionResult;
import com.smartcity.traffic.voice.dto.DirectionalCandidates;
import com.smartcity.traffic.voice.dto.StationCandidate;
import com.smartcity.traffic.voice.dto.VoiceIntent;
import com.smartcity.traffic.voice.dto.VoiceInterpretRequest;
import com.smartcity.traffic.voice.dto.VoiceInterpretResponse;
import com.smartcity.traffic.voice.service.AsrService;
import com.smartcity.traffic.voice.service.AsrServiceRouter;
import com.smartcity.traffic.voice.service.IntentParser;
import com.smartcity.traffic.voice.service.StationCorrector;
import com.smartcity.traffic.voice.service.StationIndex;
import com.smartcity.traffic.voice.service.TextNormalizer;
import com.smartcity.traffic.voice.service.TrainRecommender;
import com.smartcity.traffic.voice.service.VoiceRateLimiter;
import com.smartcity.traffic.voice.service.VoiceSpeechBuilder;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 方言语音购票接口（前缀 /api/voice）。
 *
 * <p>两个端点都只读：识别出文字、解析出意图、查到车次，绝不下单。
 * 用户确认后由前端复用现有的确认订单弹窗，写操作仍然走原来的链路。</p>
 */
@RestController
@RequestMapping("/api/voice")
@EnableConfigurationProperties(VoiceProperties.class)
public class VoiceController {

    private static final Logger log = LoggerFactory.getLogger(VoiceController.class);

    /** "上次从这儿走"的候选分：压在自动采用门槛之下，必须用户点一下才算数 */
    private static final double REMEMBERED_SCORE = 0.55;

    private static final String[] DIALECTS = {"auto", "sichuan", "shaanxi", "yue", "northeast"};

    private final AsrServiceRouter asrServiceRouter;

    private final IntentParser intentParser;

    private final StationCorrector stationCorrector;

    private final StationIndex stationIndex;

    private final VoiceSpeechBuilder speechBuilder;

    private final VoiceRateLimiter rateLimiter;

    private final CurrentUserHelper currentUserHelper;

    private final TicketService ticketService;

    private final VoiceProperties properties;

    private final Environment environment;

    private final TrainRecommender trainRecommender;

    public VoiceController(AsrServiceRouter asrServiceRouter,
                           IntentParser intentParser,
                           StationCorrector stationCorrector,
                           StationIndex stationIndex,
                           VoiceSpeechBuilder speechBuilder,
                           VoiceRateLimiter rateLimiter,
                           CurrentUserHelper currentUserHelper,
                           TicketService ticketService,
                           VoiceProperties properties,
                           Environment environment,
                           TrainRecommender trainRecommender) {
        this.asrServiceRouter = asrServiceRouter;
        this.intentParser = intentParser;
        this.stationCorrector = stationCorrector;
        this.stationIndex = stationIndex;
        this.speechBuilder = speechBuilder;
        this.rateLimiter = rateLimiter;
        this.currentUserHelper = currentUserHelper;
        this.ticketService = ticketService;
        this.properties = properties;
        this.environment = environment;
        this.trainRecommender = trainRecommender;
    }

    /**
     * 生产环境把 mock 掐在启动阶段。
     *
     * <p>mock 模式会原样返回调用方传进来的 debugText，也就是"不用录音、
     * 传一句话就能得到任意识别结果"。留这个口子，"AI 只读、必须人点确认"的前提就不成立。
     * 校验放在代码里而不是配置约定上：配置会被改，代码不会。</p>
     */
    @PostConstruct
    void checkProductionSafety() {
        if (!isProduction()) {
            return;
        }
        if ("mock".equals(properties.getAsr().getProvider())) {
            throw new IllegalStateException(
                    "生产环境禁止 voice.asr.provider=mock：该模式会原样返回调用方传入的文本，任何人都能伪造识别结果");
        }
        log.info("语音模块生产检查通过：provider={} windowDays={}",
                properties.getAsr().getProvider(), properties.getWindowDays());
    }

    private boolean isProduction() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equals(profile) || "production".equals(profile)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 音频 -> 文字。mock 模式允许不带音频，只传 debugText。
     */
    @PostMapping("/recognize")
    public Result<AsrResult> recognize(
            @RequestParam(value = "audio", required = false) MultipartFile audio,
            @RequestParam(value = "dialect", defaultValue = "auto") String dialect,
            @RequestParam(value = "debugText", required = false) String debugText,
            @RequestParam(value = "sessionId", required = false) String sessionId) {

        Long userId = currentUserHelper.currentUserId();
        VoiceRateLimiter.Ticket ticket = rateLimiter.tryAcquire(userId);
        if (ticket == null) {
            return Result.error(429, "说得太快了，歇一下再说");
        }
        try {
            return doRecognize(audio, dialect, debugText, sessionId);
        } catch (RuntimeException e) {
            // 没听清、格式不对、语音服务异常，都不算用户用掉了一次额度
            rateLimiter.release(ticket);
            throw e;
        }
    }

    private Result<AsrResult> doRecognize(MultipartFile audio, String dialect,
                                          String debugText, String sessionId) {

        AsrService asr = asrServiceRouter.route();

        // 生产环境关掉调试通道：能传 debugText 就等于能伪造识别结果。
        // 这里放在代码层而不是靠配置约定——配置会被改，代码不会
        if (isProduction() && debugText != null && !debugText.isBlank()) {
            throw new BizException("生产环境已关闭调试通道");
        }

        byte[] wav = readAudio(audio, asr.provider());

        AsrResult result;
        long start = System.currentTimeMillis();
        try {
            result = asr.recognize(wav, normalizeDialect(dialect), debugText);
        } catch (UnsupportedOperationException e) {
            throw new BizException("当前语音服务不可用，请使用 mock 模式");
        }
        long elapsed = System.currentTimeMillis() - start;

        String text = result.getRawText();
        if (text == null || text.isBlank()) {
            throw new BizException("没听清，请再说一遍");
        }
        // 只记长度和耗时，不记识别内容：行程属于隐私
        log.info("voice recognize session={} provider={} bytes={} textLen={} elapsed={}ms",
                sessionId, asr.provider(), wav == null ? 0 : wav.length, text.length(), elapsed);
        return Result.success(result);
    }

    /**
     * 文字 -> 站名 + 意图 + 车次。用户点选站名后，把 fromStation/toStation 回传再做第二次调用。
     */
    @PostMapping("/interpret")
    public Result<VoiceInterpretResponse> interpret(@RequestBody VoiceInterpretRequest request) {

        Long userId = currentUserHelper.currentUserId();
        VoiceRateLimiter.Ticket ticket = rateLimiter.tryAcquire(userId);
        if (ticket == null) {
            return Result.error(429, "说得太快了，歇一下再说");
        }
        try {
            return doInterpret(request);
        } catch (RuntimeException e) {
            // 解析异常不算一次正常使用，退还额度
            rateLimiter.release(ticket);
            throw e;
        }
    }

    private Result<VoiceInterpretResponse> doInterpret(VoiceInterpretRequest request) {

        String text = request.getText();
        if (text == null || text.isBlank()) {
            throw new BizException("没有听到您说话，请再说一次");
        }

        LocalDate today = parseToday(request.getTodayHint());
        VoiceIntent intent = intentParser.parse(text, today);

        // 前端点"看看明天的"时会直接指定日期：以它为准，
        // 免得服务端再从"明天""后天"这些词里猜一遍
        String dateOverride = request.getTravelDate();
        if (dateOverride != null && !dateOverride.isBlank()) {
            try {
                LocalDate parsed = LocalDate.parse(dateOverride.trim());
                intent.setTravelDate(parsed.toString());
            } catch (DateTimeParseException ignored) {
                // 传了脏日期就当没传，继续用原话解析出来的那一天
            }
        }

        LocalDate date = LocalDate.parse(intent.getTravelDate());

        VoiceInterpretResponse response = new VoiceInterpretResponse();
        response.setIntent(intent);

        // 1. 日期窗口：超出可售范围直接拦下，不查票。
        //    窗口读配置（默认与系统班次实例窗口一致），不写死：库里只有几天实例就放几天
        int windowDays = Math.max(1, properties.getWindowDays());
        if (date.isBefore(today) || date.isAfter(today.plusDays(windowDays - 1))) {
            response.setStationCandidates(new DirectionalCandidates());
            response.setNeedStationConfirm(true);
            response.setEmptyReason("DATE_OUT_OF_WINDOW");
            response.setCorrectedText(speechBuilder.corrected(null, null, date, intent));
            response.setSpeech(speechBuilder.outOfWindow(date, windowDays));
            return Result.success(response);
        }

        // 2. 站名纠错（含车次反查）
        String cleaned = TextNormalizer.strip(text);
        CorrectionResult correction = stationCorrector.correct(cleaned, date);

        String from = firstNonBlank(request.getFromStation(), correction.getChosenFrom());
        String to = firstNonBlank(request.getToStation(), correction.getChosenTo());
        intent.setFromStation(from);
        intent.setToStation(to);

        // 2.1 没听出出发站时，把"上次走过的站"摆成候选：
        //     只是多给一个选项，绝不替用户决定（设计 5.7 ① "预填并确认"）
        if (from == null && isBlank(request.getFromStation())) {
            StationCandidate remembered = rememberStation(request.getLastUsedFrom());
            if (remembered != null) {
                correction.getCandidates().setFrom(new ArrayList<>(List.of(remembered)));
            }
        }

        // 2.2 用户已经点过的方向不再回吐候选：
        //     否则客户端点完一遍还会看到同一批名字，需要每个客户端各自过滤一次
        DirectionalCandidates candidates = correction.getCandidates();
        if (!isBlank(request.getFromStation())) {
            candidates.setFrom(new ArrayList<>());
        }
        if (!isBlank(request.getToStation())) {
            candidates.setTo(new ArrayList<>());
        }

        // 2.3 给候选补"按对面已知站反查，这条路有没有车"，并把有车的排到前面。
        //     老人说"咸阳"时几个咸阳站文本分几乎并列，他分不清哪个能到；
        //     只按字面相似度排会把没车的排前头，他点完才发现"没有查到车"
        markReachable(candidates, from, to);

        response.setStationCandidates(candidates);
        response.setCorrectedText(speechBuilder.corrected(from, to, date, intent));
        response.setMissingStations(correction.getMissing());
        // 站名到底靠规则定的还是模型兜底定的：前端不关心，排障时全靠这两个字段
        response.setModelUsed(correction.isModelUsed());
        response.setModelFallback(correction.isModelFallback());
        // 3. 是否需要用户确认站名：用户已经点选过就不再重复问
        boolean userPicked = !isBlank(request.getFromStation()) || !isBlank(request.getToStation());
        boolean needConfirm;
        if (from == null || to == null) {
            needConfirm = true;
        } else if (userPicked) {
            needConfirm = false;
        } else {
            needConfirm = correction.isNeedConfirm();
        }
        response.setNeedStationConfirm(needConfirm);

        if (needConfirm) {
            // 站名没定就不露车次：先让用户把站选对
            response.setTrains(new ArrayList<>());
            response.setEmptyReason("STATION_UNCONFIRMED");
            // 用"缺失且用户还没点选"决定问法：用户已点过的方向不再追问；
            // 两头都听出来了只是分不开时，让用户点候选
            response.setSpeech(speechBuilder.stationMissing(
                    correction.getMissing().contains("from") && isBlank(request.getFromStation()),
                    correction.getMissing().contains("to") && isBlank(request.getToStation())));
            return Result.success(response);
        }

        if (from.equals(to)) {
            throw new BizException("出发站和到达站不能是同一个站");
        }

        // 4. 查票：直接复用现有 search，返回结构与前端 state.trains 完全一致
        List<TrainSearchVO> trains = ticketService.search(from, to, date);
        response.setTrains(trains);

        // 4.1 一条车都没有：这条线路本身就不存在
        if (trains.isEmpty()) {
            response.setEmptyReason("NO_ROUTE");
            response.setSpeech(speechBuilder.noRoute(from, to, date));
            return Result.success(response);
        }

        // 4.2 有线路，但今天一张票都不剩。
        //     search 是按线路查车次的，那天有没有票只影响余票数字、不影响车次是否出现，
        //     所以"没这条线"和"今天卖完了"能干净分开——前者车次列表空，后者车次在但全售罄
        int soldOutCount = 0;
        for (TrainSearchVO t : trains) {
            if (isSoldOut(t)) {
                soldOutCount++;
            }
        }
        if (soldOutCount == trains.size()) {
            response.setEmptyReason("SOLD_OUT");
            response.setSoldOutTrainCount(soldOutCount);
            response.setCanAskTomorrow(true);
            response.setSpeech(speechBuilder.soldOut(from, to, date, soldOutCount));
            return Result.success(response);
        }

        // 5. 按"什么时候走"挑一趟。
        //    用户只说了"从哪到哪"、没说时间时，给最早的一趟，并在播报里说明理由——
        //    老人不需要理解"推荐算法"，只需要知道"为什么是这趟"
        TrainRecommender.Recommendation rec = trainRecommender.recommend(
                trains, intent.getDepartTimePreference(), date, LocalTime.now(),
                intent.getSeatPreference());
        response.setRecommendReason(rec == null ? null : rec.getReason().name());
        response.setRecommendSeatNo(intent.getSeatPreference());
        if (rec != null && rec.isMatched()) {
            response.setRecommendedTrain(rec.getTrain());
        }
        response.setSpeech(speechBuilder.recommend(rec, intent.getSeatPreference(), from, to));
        return Result.success(response);
    }

    /**
     * 这趟车在这个区间是不是一张票都没有了。
     *
     * <p>票价缺失不等于售罄（票价没配只影响能不能下单），所以只认余票；
     * 但如果所有席别票价都缺失，这趟车实际也没法卖，一并算售罄。</p>
     */
    private boolean isSoldOut(TrainSearchVO train) {
        List<SeatOffer> seats = train.getSeats();
        if (seats == null || seats.isEmpty()) {
            return true;
        }
        for (SeatOffer seat : seats) {
            if (seat.getRemain() != null && seat.getRemain() > 0 && seat.getPrice() != null) {
                return false;
            }
        }
        return true;
    }

    // ---------------- 音频校验 ----------------

    private byte[] readAudio(MultipartFile audio, String provider) {
        if (audio == null || audio.isEmpty()) {
            if ("mock".equals(provider)) {
                return null;
            }
            throw new BizException("没有收到录音，请按住话筒说话");
        }
        if (audio.getSize() > properties.getAsr().getMaxBytes()) {
            throw new BizException("录音太大了，一次最多 " + properties.getAsr().getMaxDurationSeconds() + " 秒");
        }
        byte[] bytes;
        try {
            bytes = audio.getBytes();
        } catch (IOException e) {
            throw new BizException("音频读取失败，请再说一次");
        }
        checkWav(bytes);
        return bytes;
    }

    /** 只接受 16k 单声道 16bit WAV：识别端按固定格式解码，格式不对要先拦下 */
    private void checkWav(byte[] b) {
        if (b.length < 44
                || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F'
                || b[8] != 'W' || b[9] != 'A' || b[10] != 'V' || b[11] != 'E') {
            throw new BizException("只支持 WAV 录音，请在浏览器里重新说一遍");
        }
        int channels = readUInt16(b, 22);
        int sampleRate = readUInt32(b, 24);
        int bits = readUInt16(b, 34);
        if (channels != 1 || sampleRate != 16000 || bits != 16) {
            throw new BizException("录音格式不对，需要 16k 单声道 16bit");
        }

        int pos = 12;
        int dataSize = -1;
        while (pos + 8 <= b.length) {
            String id = new String(b, pos, 4, StandardCharsets.US_ASCII);
            int size = readUInt32(b, pos + 4);
            if (size < 0) {
                break;
            }
            if ("data".equals(id)) {
                dataSize = Math.min(size, b.length - pos - 8);
                break;
            }
            pos += 8 + size;
        }
        if (dataSize <= 0) {
            throw new BizException("录音内容为空，请再说一遍");
        }

        double seconds = dataSize / 32000.0;
        if (seconds < 1.0) {
            throw new BizException("说话时间太短，请按住多说一会儿");
        }
        if (seconds > properties.getAsr().getMaxDurationSeconds()) {
            throw new BizException("录音太长，一次最多 " + properties.getAsr().getMaxDurationSeconds() + " 秒");
        }
    }

    private int readUInt16(byte[] b, int offset) {
        return (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8);
    }

    private int readUInt32(byte[] b, int offset) {
        return (b[offset] & 0xFF)
                | ((b[offset + 1] & 0xFF) << 8)
                | ((b[offset + 2] & 0xFF) << 16)
                | ((b[offset + 3] & 0xFF) << 24);
    }

    // ---------------- 其它 ----------------

    private String normalizeDialect(String dialect) {
        if (dialect == null || dialect.isBlank()) {
            return "auto";
        }
        String lower = dialect.trim().toLowerCase();
        for (String allowed : DIALECTS) {
            if (allowed.equals(lower)) {
                return lower;
            }
        }
        return "auto";
    }

    /**
     * 客户端日期只用作基准；与服务器差超过 1 天时以服务器为准，
     * 避免设备时区/改时间导致 7 天窗口算错。
     */
    private LocalDate parseToday(String hint) {
        LocalDate serverToday = LocalDate.now();
        if (hint == null || hint.isBlank()) {
            return serverToday;
        }
        try {
            LocalDate client = LocalDate.parse(hint.trim());
            if (Math.abs(ChronoUnit.DAYS.between(serverToday, client)) <= 1) {
                return client;
            }
        } catch (Exception ignored) {
            // 格式不对就用服务器日期
        }
        return serverToday;
    }

    /**
     * 给候选补一个"按对面已知站反查，这条路有没有车"，并把有车的排到前面。
     *
     * <p>锚点取"这一头已确定的值"或"本轮进来的第一名"，两头都用原始第一名，
     * 避免先算出发侧、后算到达侧时互相影响。</p>
     */
    private void markReachable(DirectionalCandidates candidates, String from, String to) {
        if (candidates == null) {
            return;
        }
        List<StationCandidate> fromC = candidates.getFrom();
        List<StationCandidate> toC = candidates.getTo();

        String fromAnchor = from != null ? from : (fromC.isEmpty() ? null : fromC.get(0).getStationName());
        String toAnchor = to != null ? to : (toC.isEmpty() ? null : toC.get(0).getStationName());

        if (!fromC.isEmpty() && toAnchor != null) {
            for (StationCandidate c : fromC) {
                c.setHasTrain(ticketService.routeExists(c.getStationName(), toAnchor));
            }
        }
        if (!toC.isEmpty() && fromAnchor != null) {
            for (StationCandidate c : toC) {
                c.setHasTrain(ticketService.routeExists(fromAnchor, c.getStationName()));
            }
        }
        sortReachableFirst(fromC);
        sortReachableFirst(toC);
    }

    /**
     * 分数接近的候选里，有车的排前面；分数差得远的，仍然按分数走。
     *
     * <p>不能一味"有车就往前顶"：用户说"宝鸡"，宝鸡南 0.94 没车、
     * 灵宝西 0.73 恰好有车，一味按有车排就会把灵宝西顶到第一，
     * 等于把用户说的"宝鸡"改成了"灵宝"，又回到当初那个坑里。
     * 只有差在一个档位以内（0.1）——也就是同城几站这种"本来就分不清"的情况——
     * 才让"能走"来决定先后。</p>
     */
    private void sortReachableFirst(List<StationCandidate> list) {
        if (list == null || list.size() < 2) {
            return;
        }
        List<StationCandidate> sorted = new ArrayList<>(list);
        sorted.sort(Comparator
                .comparingInt((StationCandidate c) -> -Math.round((float) (c.getScore() == null ? 0.0 : c.getScore() * 10)))
                .thenComparing(c -> !Boolean.TRUE.equals(c.getHasTrain()))
                .thenComparing(c -> c.getScore() == null ? 0.0 : -c.getScore())
                .thenComparing(c -> c.getStationName() == null ? "" : c.getStationName()));
        list.clear();
        list.addAll(sorted);
    }

    /**
     * 把"上次从这儿走"转成候选。站名必须真实存在，避免脏数据混进候选列表。
     * 分数压在自动采用门槛之下——它只是提供选项，最终仍由用户点。
     */
    private StationCandidate rememberStation(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        for (StationIndex.Entry e : stationIndex.all()) {
            if (e.getName().equals(trimmed)) {
                return new StationCandidate(e.getName(), e.getCity(), REMEMBERED_SCORE, "上次");
            }
        }
        return null;
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

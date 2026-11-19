package com.smartcity.traffic.voice.service;

import com.smartcity.traffic.dto.RemainResponse;
import com.smartcity.traffic.dto.StationVO;
import com.smartcity.traffic.dto.TrainSearchVO;
import com.smartcity.traffic.service.TicketService;
import com.smartcity.traffic.voice.dto.CorrectionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 站名纠错算法回归：用真实的 61 个站名，配一个可控的"通车表"。
 *
 * <p>重点不是"分数等于几"，而是三件事：方言同音能不能落到对站、
 * 同城多站分不开时会不会强制点选、缺站时会不会静默默认。</p>
 */
class StationCorrectorTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 18);

    private StationCorrector corrector;

    private final Set<String> routes = new HashSet<>();

    @BeforeEach
    void setUp() {
        List<StationVO> stations = loadStations();
        // 通车表：只认这几条。西安（非西安北）到北京西故意设为不通车，
        // 用来验证"文本像"不够、"业务成立"才是硬证据的判定顺序。
        routes.add("西安北→北京西");
        routes.add("北京西→西安北");
        routes.add("北京南→西安北");
        routes.add("西安北→北京南");
        // 上海方向只有上海虹桥通车，上海松江不通车：给 09 号那个整词同音用例留出判定依据
        routes.add("西安北→上海虹桥");

        StubTicketService ticket = new StubTicketService(stations, routes);
        StationIndex index = new StationIndex(ticket);
        corrector = new StationCorrector(index, ticket);
    }

    @Test
    @DisplayName("方言同音：西岸/北金西 应落到 西安北/北京西")
    void correctDialectHomophone() {
        CorrectionResult r = corrector.correct("从西岸去北金西", DATE);

        List<String> fromNames = names(r.getCandidates().getFrom());
        List<String> toNames = names(r.getCandidates().getTo());

        assertThat(fromNames).contains("西安北");
        assertThat(toNames).contains("北京西");
        // 北京西 必须是第一候选，否则"北金西"的识别就错了
        assertThat(r.getCandidates().getTo().get(0).getStationName()).isEqualTo("北京西");
        assertThat(r.getCandidates().getTo().get(0).getScore()).isGreaterThanOrEqualTo(0.85);
    }

    @Test
    @DisplayName("同城多站靠车次反查定下来，且不打扰用户")
    void correctByRouteProbe() {
        CorrectionResult r = corrector.correct("从西岸去北金西", DATE);

        // "西安"分数可能比"西安北"高，但西安到北京西不通车，只有西安北通车
        assertThat(r.getChosenFrom()).isEqualTo("西安北");
        assertThat(r.getChosenTo()).isEqualTo("北京西");
        assertThat(r.isNeedConfirm()).isFalse();
        assertThat(r.getMissing()).isEmpty();
    }

    @Test
    @DisplayName("说的是完整站名：直接采用，不能再让用户点一次")
    void exactStationNameAuto() {
        CorrectionResult r = corrector.correct("从西安北去北京西", DATE);

        // 用户说的就是真实站名，系统必须认得出来，
        // 不能因为"西安"这个 2 字前缀把同城三站顶成并列而反过来追问用户
        assertThat(r.getChosenFrom()).isEqualTo("西安北");
        assertThat(r.getChosenTo()).isEqualTo("北京西");
        assertThat(r.isNeedConfirm()).isFalse();
        assertThat(r.getMissing()).isEmpty();
    }

    @Test
    @DisplayName("只说目的地：出发站必须问，不能默认")
    void missingFromStation() {
        CorrectionResult r = corrector.correct("去北京", DATE);

        assertThat(r.getMissing()).contains("from");
        assertThat(r.isNeedConfirm()).isTrue();
        assertThat(r.getChosenFrom()).isNull();
    }

    @Test
    @DisplayName("同城两站都通车且分数接近：必须强制点选")
    void ambiguousSameCity() {
        CorrectionResult r = corrector.correct("从西安北去北京", DATE);

        List<String> toNames = names(r.getCandidates().getTo());
        assertThat(toNames).contains("北京南", "北京西");
        assertThat(r.isNeedConfirm()).isTrue();
    }

    @Test
    @DisplayName("完全听不出来：不给候选，直接进入兜底")
    void nothingRecognized() {
        CorrectionResult r = corrector.correct("随便说说", DATE);

        assertThat(r.isNeedConfirm()).isTrue();
        assertThat(r.getChosenFrom()).isNull();
        assertThat(r.getChosenTo()).isNull();
    }

    @Test
    @DisplayName("整词同音错字：上海红桥 必须定到 上海虹桥，不能被 上海 这 2 字前缀拉成并列")
    void wholeNameTypoBeatsShortPrefix() {
        CorrectionResult r = corrector.correct("从西安北去上海红桥", DATE);

        // "上海"只占"上海虹桥"的一半，不该拿它当证据；
        // 真正对上的是"上海红桥"整词（红/虹同音），所以第一候选必须是上海虹桥
        assertThat(r.getCandidates().getTo().get(0).getStationName()).isEqualTo("上海虹桥");
        assertThat(r.getChosenTo()).isEqualTo("上海虹桥");
        assertThat(r.isNeedConfirm()).isFalse();
    }

    private List<String> names(List<com.smartcity.traffic.voice.dto.StationCandidate> candidates) {
        return candidates.stream()
                .map(com.smartcity.traffic.voice.dto.StationCandidate::getStationName)
                .collect(Collectors.toList());
    }

    private List<StationVO> loadStations() {
        List<StationVO> list = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                StationCorrectorTest.class.getClassLoader().getResourceAsStream("stations.tsv"),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length >= 2 && !parts[0].isBlank()) {
                    list.add(new StationVO(null, parts[0].trim(), parts[1].trim()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取站名测试数据失败", e);
        }
        assertThat(list).hasSize(61);
        return list;
    }

    /** 只实现查票和车站列表；余票查询用不到 */
    private static class StubTicketService implements TicketService {

        private final List<StationVO> stations;

        private final Set<String> routes;

        StubTicketService(List<StationVO> stations, Set<String> routes) {
            this.stations = stations;
            this.routes = routes;
        }

        @Override
        public RemainResponse queryRemain(String trainNo, LocalDate travelDate,
                                          Integer fromSeq, Integer toSeq, String seatNo) {
            return null;
        }

        @Override
        public List<TrainSearchVO> search(String fromStation, String toStation, LocalDate travelDate) {
            if (routes.contains(fromStation + "→" + toStation)) {
                return List.of(new TrainSearchVO());
            }
            return List.of();
        }

        @Override
        public List<StationVO> stations() {
            return stations;
        }

        @Override
        public boolean routeExists(String fromStation, String toStation) {
            return routes.contains(fromStation + "→" + toStation);
        }
    }
}

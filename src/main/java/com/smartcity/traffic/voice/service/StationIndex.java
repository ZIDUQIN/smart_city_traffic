package com.smartcity.traffic.voice.service;

import com.smartcity.traffic.dto.StationVO;
import com.smartcity.traffic.service.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 站名索引：61 个站的拼音、首字母、二元组，启动后第一次使用时构建并常驻内存。
 *
 * <p>用懒加载而不是启动时查库：语音是旁路功能，不应在数据库未就绪时拖慢或阻断应用启动。</p>
 */
@Component
public class StationIndex {

    private static final Logger log = LoggerFactory.getLogger(StationIndex.class);

    private final TicketService ticketService;

    private volatile List<Entry> entries;

    public StationIndex(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    public List<Entry> all() {
        if (entries == null) {
            synchronized (this) {
                if (entries == null) {
                    entries = build();
                }
            }
        }
        return entries;
    }

    /** 站名是否在字典内（模型输出白名单校验用） */
    public boolean contains(String stationName) {
        if (stationName == null) {
            return false;
        }
        for (Entry e : all()) {
            if (e.name.equals(stationName)) {
                return true;
            }
        }
        return false;
    }

    private List<Entry> build() {
        List<StationVO> stations = ticketService.stations();
        List<Entry> list = new ArrayList<>(stations.size());
        for (StationVO vo : stations) {
            String name = vo.getStationName();
            if (name == null || name.isBlank()) {
                continue;
            }
            Entry e = new Entry();
            e.name = name.trim();
            e.city = vo.getCity();
            e.fullPinyin = PinyinUtil.toPinyin(e.name);
            e.initials = PinyinUtil.initials(e.name);
            e.bigrams = bigrams(e.name);
            list.add(e);
        }
        log.info("车站拼音索引构建完成，共 {} 条", list.size());
        return List.copyOf(list);
    }

    private Set<String> bigrams(String name) {
        Set<String> set = new LinkedHashSet<>();
        for (int i = 0; i + 1 < name.length(); i++) {
            set.add(name.substring(i, i + 2));
        }
        return set;
    }

    /** 一个车站的预计算特征 */
    public static class Entry {

        private String name;

        private String city;

        private String fullPinyin;

        private String initials;

        private Set<String> bigrams = new HashSet<>();

        public String getName() {
            return name;
        }

        public String getCity() {
            return city;
        }

        public String getFullPinyin() {
            return fullPinyin;
        }

        public String getInitials() {
            return initials;
        }

        public Set<String> getBigrams() {
            return bigrams;
        }
    }
}

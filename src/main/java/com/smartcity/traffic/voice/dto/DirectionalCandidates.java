package com.smartcity.traffic.voice.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 出发/到达两个方向的候选站名。
 */
public class DirectionalCandidates {

    private List<StationCandidate> from = new ArrayList<>();

    private List<StationCandidate> to = new ArrayList<>();

    public List<StationCandidate> getFrom() {
        return from;
    }

    public void setFrom(List<StationCandidate> from) {
        this.from = from == null ? new ArrayList<>() : from;
    }

    public List<StationCandidate> getTo() {
        return to;
    }

    public void setTo(List<StationCandidate> to) {
        this.to = to == null ? new ArrayList<>() : to;
    }
}

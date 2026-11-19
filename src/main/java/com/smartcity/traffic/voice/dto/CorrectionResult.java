package com.smartcity.traffic.voice.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 站名纠错的内部结论：候选 + 最终采用 + 是否需要用户确认。
 */
public class CorrectionResult {

    private DirectionalCandidates candidates = new DirectionalCandidates();

    /** 自动采用时非空；需要确认时为空 */
    private String chosenFrom;

    private String chosenTo;

    private boolean needConfirm;

    private List<String> missing = new ArrayList<>();

    /** 这轮是否真的调了大模型 */
    private boolean modelUsed;

    /** 调了但失败（超时/401/返回不合法），已退回规则结果 */
    private boolean modelFallback;

    public DirectionalCandidates getCandidates() {
        return candidates;
    }

    public void setCandidates(DirectionalCandidates candidates) {
        this.candidates = candidates;
    }

    public String getChosenFrom() {
        return chosenFrom;
    }

    public void setChosenFrom(String chosenFrom) {
        this.chosenFrom = chosenFrom;
    }

    public String getChosenTo() {
        return chosenTo;
    }

    public void setChosenTo(String chosenTo) {
        this.chosenTo = chosenTo;
    }

    public boolean isNeedConfirm() {
        return needConfirm;
    }

    public void setNeedConfirm(boolean needConfirm) {
        this.needConfirm = needConfirm;
    }

    public List<String> getMissing() {
        return missing;
    }

    public void setMissing(List<String> missing) {
        this.missing = missing == null ? new ArrayList<>() : missing;
    }

    public boolean isModelUsed() {
        return modelUsed;
    }

    public void setModelUsed(boolean modelUsed) {
        this.modelUsed = modelUsed;
    }

    public boolean isModelFallback() {
        return modelFallback;
    }

    public void setModelFallback(boolean modelFallback) {
        this.modelFallback = modelFallback;
    }
}

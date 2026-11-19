package com.smartcity.traffic.voice.dto;

import com.smartcity.traffic.dto.TrainSearchVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析结果。trains 与 /api/ticket/search 同构，前端可直接塞进 state.trains 复用确认订单弹窗。
 */
public class VoiceInterpretResponse {

    /** 人话版回显：从西安北到北京西，明天，两张，二等座 */
    private String correctedText;

    private DirectionalCandidates stationCandidates;

    private VoiceIntent intent;

    /** true 时禁止展示车次，必须先让用户点选站名 */
    private Boolean needStationConfirm;

    /** 缺失或待确认的方向：from / to */
    private List<String> missingStations = new ArrayList<>();

    private List<TrainSearchVO> trains = new ArrayList<>();

    /** 按"什么时候走"挑出的一趟；前端据此高亮该行并把对应席别标成选中 */
    private TrainSearchVO recommendedTrain;

    /** 推荐理由码：DEFAULT / ASAP / PERIOD / CLOCK / NO_MATCH */
    private String recommendReason;

    /** 推荐按哪个席别判"有票" */
    private String recommendSeatNo;

    private String speech;

    /** STATION_UNCONFIRMED / NO_ROUTE / SOLD_OUT / DATE_OUT_OF_WINDOW / null */
    private String emptyReason;

    /** 今天一趟都没票时，一共有几趟这样的车（emptyReason=SOLD_OUT 时才有意义） */
    private Integer soldOutTrainCount;

    /** 今天没票时是否该问用户"要不要看看明天的" */
    private Boolean canAskTomorrow;

    /** 这轮站名是模型兜底定的（规则没拿准，模型在候选里挑了） */
    private boolean modelUsed;

    /** 调了模型但失败（超时/无 key/返回不合法），已退回规则结果 */
    private boolean modelFallback;

    public String getCorrectedText() {
        return correctedText;
    }

    public void setCorrectedText(String correctedText) {
        this.correctedText = correctedText;
    }

    public DirectionalCandidates getStationCandidates() {
        return stationCandidates;
    }

    public void setStationCandidates(DirectionalCandidates stationCandidates) {
        this.stationCandidates = stationCandidates;
    }

    public VoiceIntent getIntent() {
        return intent;
    }

    public void setIntent(VoiceIntent intent) {
        this.intent = intent;
    }

    public Boolean getNeedStationConfirm() {
        return needStationConfirm;
    }

    public void setNeedStationConfirm(Boolean needStationConfirm) {
        this.needStationConfirm = needStationConfirm;
    }

    public List<String> getMissingStations() {
        return missingStations;
    }

    public void setMissingStations(List<String> missingStations) {
        this.missingStations = missingStations == null ? new ArrayList<>() : missingStations;
    }

    public List<TrainSearchVO> getTrains() {
        return trains;
    }

    public void setTrains(List<TrainSearchVO> trains) {
        this.trains = trains == null ? new ArrayList<>() : trains;
    }

    public TrainSearchVO getRecommendedTrain() {
        return recommendedTrain;
    }

    public void setRecommendedTrain(TrainSearchVO recommendedTrain) {
        this.recommendedTrain = recommendedTrain;
    }

    public String getRecommendReason() {
        return recommendReason;
    }

    public void setRecommendReason(String recommendReason) {
        this.recommendReason = recommendReason;
    }

    public String getRecommendSeatNo() {
        return recommendSeatNo;
    }

    public void setRecommendSeatNo(String recommendSeatNo) {
        this.recommendSeatNo = recommendSeatNo;
    }

    public String getSpeech() {
        return speech;
    }

    public void setSpeech(String speech) {
        this.speech = speech;
    }

    public String getEmptyReason() {
        return emptyReason;
    }

    public void setEmptyReason(String emptyReason) {
        this.emptyReason = emptyReason;
    }

    public Integer getSoldOutTrainCount() {
        return soldOutTrainCount;
    }

    public void setSoldOutTrainCount(Integer soldOutTrainCount) {
        this.soldOutTrainCount = soldOutTrainCount;
    }

    public Boolean getCanAskTomorrow() {
        return canAskTomorrow;
    }

    public void setCanAskTomorrow(Boolean canAskTomorrow) {
        this.canAskTomorrow = canAskTomorrow;
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

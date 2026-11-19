package com.smartcity.traffic.voice.llm;

/**
 * 模型对站名的判断结果。
 *
 * <p>from / to 必须原样出现在给定的候选名单里，业务层会逐字比对；
 * 模型若自己编了一个站名，这里对不上就会被丢弃。</p>
 *
 * @param from 选中的出发站，判断不出填 null
 * @param to   选中的到达站，判断不出填 null
 * @param ask  需要追问时的一句话，不需要追问填 null
 * @param why  一句话理由，只用于日志排查
 */
public record StationJudgeResult(String from, String to, String ask, String why) {
}

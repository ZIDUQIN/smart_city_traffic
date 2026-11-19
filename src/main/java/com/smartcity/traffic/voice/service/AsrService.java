package com.smartcity.traffic.voice.service;

import com.smartcity.traffic.voice.dto.AsrResult;

/**
 * 语音识别能力。实现只负责"音频 -> 文字"，纠错和意图解析都在下游统一处理，
 * 这样浏览器识别、打字兜底、第三方 ASR 走的是同一条链路。
 */
public interface AsrService {

    /**
     * @param wav      16k 单声道 16bit WAV 字节；mock 模式下可为 null
     * @param dialect  auto / sichuan / shaanxi / yue / northeast
     * @param mockText 仅 mock 使用，作为识别结果
     */
    AsrResult recognize(byte[] wav, String dialect, String mockText);

    /** 提供方标识，与 voice.asr.provider 对应 */
    String provider();
}

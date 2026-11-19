package com.smartcity.traffic.voice.service.impl;

import com.smartcity.traffic.voice.dto.AsrResult;
import com.smartcity.traffic.voice.service.AsrService;
import org.springframework.stereotype.Service;

/**
 * 阿里云语音识别适配器占位。
 *
 * <p>只保留接口，不实现：本期确定为讯飞，阿里用于兜底或多供应商切换时再补。
 * 显式抛 UnsupportedOperationException，避免被误当成可用实现。</p>
 */
@Service
public class AliyunAsrService implements AsrService {

    @Override
    public AsrResult recognize(byte[] wav, String dialect, String mockText) {
        throw new UnsupportedOperationException("阿里云语音识别尚未实现，请使用 mock 或 xfyun");
    }

    @Override
    public String provider() {
        return "aliyun";
    }
}

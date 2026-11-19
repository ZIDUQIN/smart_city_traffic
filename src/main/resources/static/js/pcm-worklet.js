/**
 * 录音处理器：把麦克风的浮点采样原样抛给主线程。
 * 独立文件是 AudioWorklet 的硬要求（必须通过 addModule 从 URL 加载）。
 */
class PcmProcessor extends AudioWorkletProcessor {
    process(inputs) {
        const input = inputs[0];
        if (input && input[0] && input[0].length) {
            // 必须拷贝：工作线程里的缓冲会复用，直接转移会拿到后续帧
            this.port.postMessage(new Float32Array(input[0]));
        }
        return true;
    }
}

registerProcessor('pcm-processor', PcmProcessor);

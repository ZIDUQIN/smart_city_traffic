/**
 * 方言语音购票 · 前端面板
 *
 * 职责边界：本文件只负责录音、展示、确认交互。
 * 站名纠错、意图解析、查票全部在后端；选完车次后复用 app.js 的确认订单弹窗，
 * 不做任何下单动作（写操作仍然由用户在确认弹窗里点）。
 *
 * 依赖 app.js 的全局：state / api / toast / esc / money / durationLabel / toISO /
 * openOrderModal / handleUnauthorized
 */
(function () {
    'use strict';

    /* ---------------- 常量 ---------------- */

    var DIALECTS = [
        ['auto', '自动'],
        ['sichuan', '四川话'],
        ['shaanxi', '关中话'],
        ['yue', '粤语'],
        ['northeast', '东北话']
    ];

    var SENTENCES = [
        '明天从西安去北京',
        '后天从西安北到上海虹桥',
        '我要去北京',
        '这周五去成都东 一张',
        '帮我买两张去南京南的',
        '今天去重庆北 一等座'
    ];

    var MAX_SECONDS = 30;

    /* ---------------- 状态 ---------------- */

    var vstate = {
        open: false,
        dialect: localStorage.getItem('voice.dialect') || 'auto',
        tts: localStorage.getItem('voice.tts') !== 'off',
        phase: 'IDLE',          // IDLE / STARTING / RECORDING / THINKING / CANDIDATES / TRAINS
        sessionId: '',
        rec: null,              // 当前录音器实例，不要和下面的推荐字段混用
        recommendation: null,   // 后端按"什么时候走"挑出的那一趟
        recommendSeat: '',      // 推荐按哪个席别判"有票"
        timer: null,
        startedAt: 0,
        wantStop: false,        // 还在申请麦克风时就松手了：准备好后立刻结束并上传
        wantCancel: false,      // 还在申请麦克风时就关面板了：准备好后直接丢弃
        lastText: '',
        lastDate: '',           // 上一轮查的是哪一天，点"看明天"时在这天基础上加一天
        intent: null,
        trains: [],
        pickedFrom: null,
        pickedTo: null,
        /** 对话阶段：TRAINS（选车次席别） / PASSENGER（问乘车人） / CONFIRM（核对下单） */
        stage: 'TRAINS',
        passengers: [],          // 只在问乘车人那一步才拉，别开着面板就去读别人的名单
        selectedTrain: null,
        selectedSeat: null,
        selectedPassenger: null
    };

    /* ---------------- 小工具 ---------------- */

    function el(id) {
        return document.getElementById(id);
    }

    function show(id) {
        var node = el(id);
        if (node) {
            node.classList.remove('hidden');
        }
    }

    function hide(id) {
        var node = el(id);
        if (node) {
            node.classList.add('hidden');
        }
    }

    function setHint(text) {
        var node = el('voice-hint');
        if (node) {
            node.textContent = text || '';
        }
    }

    /** 往对话流里追加一条气泡：role 只能是 'bot' / 'user' */
    function pushMsg(role, text) {
        var box = el('voice-chat');
        if (!box || !text) {
            return;
        }
        var row = document.createElement('div');
        row.className = 'vc-msg ' + (role === 'user' ? 'vc-user' : 'vc-bot');
        var bubble = document.createElement('div');
        bubble.className = 'vc-bubble';
        bubble.textContent = text;
        row.appendChild(bubble);
        box.appendChild(row);
        box.scrollTop = box.scrollHeight;
    }

    /**
     * 助手说话：同时写进对话流和念出来。
     *
     * <p>老人两边要对得上——屏幕上写的和耳朵听到的必须是同一句，
     * 所以播报和气泡只有这一个入口，不要再分开调 speak 和 setText。</p>
     */
    function say(text) {
        if (!text) {
            return;
        }
        pushMsg('bot', text);
        speak(text);
    }

    function setText(text) {
        var node = el('voice-text');
        if (node) {
            node.textContent = text || '';
        }
    }

    /** 把后端纠错后的结果摊给用户看：站名被改过才敢让人点确认 */
    function setUnderstood(text) {
        var node = el('voice-understood');
        if (!node) {
            return;
        }
        node.textContent = text ? '我听懂了：' + text : '';
    }

    function setPhase(phase) {
        vstate.phase = phase;
    }

    function newSessionId() {
        return 'v' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
    }

    /* ---------------- 打开 / 关闭 ---------------- */

    function resetPanel() {
        setText('');
        setUnderstood('');
        setHint('');
        hide('voice-candidates');
        hide('voice-trains');
        hide('voice-passengers');
        hide('voice-confirm-card');
        hide('voice-actions');
        hide('voice-fallback');
        hide('voice-typing');
        el('voice-candidates').innerHTML = '';
        el('voice-trains').innerHTML = '';
        el('voice-passengers').innerHTML = '';
        el('voice-confirm-card').innerHTML = '';
        el('voice-chat').innerHTML = '';
        el('voice-timer').textContent = '';
        el('voice-type-input').value = '';
        vstate.lastText = '';
        vstate.intent = null;
        vstate.trains = [];
        vstate.pickedFrom = null;
        vstate.pickedTo = null;
        vstate.wantStop = false;
        vstate.stage = 'TRAINS';
        vstate.passengers = [];
        vstate.selectedTrain = null;
        vstate.selectedSeat = null;
        vstate.selectedPassenger = null;
    }

    function openPanel() {
        vstate.open = true;
        vstate.sessionId = newSessionId();
        resetPanel();
        el('voice-modal').classList.remove('hidden');
        document.documentElement.classList.add('voice-lock');
        renderDialectLabel();
        renderTtsLabel();
        setPhase('IDLE');

        // getUserMedia 只在安全上下文可用：局域网 http 地址会直接失败，先说清楚
        if (!window.isSecureContext) {
            setHint('这个网址不能录音（浏览器只允许 https 或 localhost 录音），请用下面的打字输入');
            el('voice-mic').disabled = true;
            el('voice-mic').textContent = '不能录音';
        } else {
            el('voice-mic').disabled = false;
            el('voice-mic').textContent = '按住说话';
            setHint('按住下面的圆钮，说您要去哪儿');
        }
        pushMsg('bot', '您要去哪儿？按住下面的圆钮说一句，比如说"明天从西安去北京"。');
    }

    /**
     * 真正掐断播报。
     *
     * <p>只调 cancel() 在 Chrome 上经常拦不住"已经开口的长句"——它清的是队列，
     * 正在发声的那句仍会念完。先 pause 让引擎停住当前句，再 cancel 清队列，
     * 最后 resume 把队列清空的状态落实下来，三下才算真的停。</p>
     */
    function stopSpeaking() {
        try {
            var ss = window.speechSynthesis;
            if (!ss) {
                return;
            }
            ss.pause();
            ss.cancel();
            ss.resume();
        } catch (e) {
            // 忽略：部分浏览器没有 speechSynthesis
        }
    }

    function closePanel() {
        cancelRecording();
        stopSpeaking();
        vstate.open = false;
        el('voice-modal').classList.add('hidden');
        document.documentElement.classList.remove('voice-lock');
    }

    /* ---------------- 方言 / 播报 ---------------- */

    function dialectLabel(code) {
        for (var i = 0; i < DIALECTS.length; i++) {
            if (DIALECTS[i][0] === code) {
                return DIALECTS[i][1];
            }
        }
        return '自动';
    }

    function renderDialectLabel() {
        el('voice-dialect').textContent = '方言：' + dialectLabel(vstate.dialect);
    }

    function renderTtsLabel() {
        el('voice-tts').textContent = '播报：' + (vstate.tts ? '开' : '关');
    }

    function nextDialect() {
        var idx = 0;
        for (var i = 0; i < DIALECTS.length; i++) {
            if (DIALECTS[i][0] === vstate.dialect) {
                idx = i;
            }
        }
        idx = (idx + 1) % DIALECTS.length;
        vstate.dialect = DIALECTS[idx][0];
        localStorage.setItem('voice.dialect', vstate.dialect);
        renderDialectLabel();
        toast('方言已切换为' + DIALECTS[idx][1]);
    }

    function toggleTts() {
        vstate.tts = !vstate.tts;
        localStorage.setItem('voice.tts', vstate.tts ? 'on' : 'off');
        renderTtsLabel();
        if (!vstate.tts) {
            stopSpeaking();
        }
    }

    function speak(text) {
        // 窗口已经关了就不播：请求是异步回来的，关窗之后回调才到，
        // 只靠 closePanel 那次 cancel 拦不住新冒出来的这句话
        if (!vstate.tts || !text || !vstate.open) {
            return;
        }
        if (!('speechSynthesis' in window)) {
            return;
        }
        try {
            window.speechSynthesis.cancel();
            var utterance = new SpeechSynthesisUtterance(text);
            utterance.lang = 'zh-CN';
            utterance.rate = 0.9;
            window.speechSynthesis.speak(utterance);
        } catch (e) {
            // 播报失败不影响流程
        }
    }

    /* ---------------- 录音 ---------------- */

    async function startRecord() {
        if (vstate.phase === 'RECORDING' || vstate.phase === 'STARTING') {
            return;
        }
        if (!window.isSecureContext) {
            toast('当前网址不能录音，请用打字输入');
            return;
        }

        // 必须在申请麦克风之前清掉：否则 STARTING 期间松手的意图会被后面的赋值抹掉
        vstate.wantStop = false;
        vstate.wantCancel = false;
        setPhase('STARTING');
        setHint('正在准备麦克风…');

        var stream = null;
        var ctx = null;
        try {
            stream = await navigator.mediaDevices.getUserMedia({
                audio: {
                    channelCount: 1,
                    echoCancellation: true,
                    noiseSuppression: true
                }
            });
            ctx = new (window.AudioContext || window.webkitAudioContext)();
            await ctx.audioWorklet.addModule('/js/pcm-worklet.js?v=7');

            var source = ctx.createMediaStreamSource(stream);
            var node = new AudioWorkletNode(ctx, 'pcm-processor');
            var chunks = [];
            node.port.onmessage = function (event) {
                chunks.push(event.data);
            };
            source.connect(node);

            vstate.rec = {
                stream: stream,
                ctx: ctx,
                node: node,
                source: source,
                chunks: chunks,
                rate: ctx.sampleRate
            };
            vstate.startedAt = Date.now();
            setPhase('RECORDING');
            el('voice-mic').classList.add('recording');
            el('voice-mic').textContent = '松开结束';
            setHint('正在听…说完松开');
            el('voice-timer').textContent = '0s';
            vstate.timer = setInterval(tick, 200);

            // 面板在申请麦克风期间被关掉了：直接丢弃，不要发请求
            if (vstate.wantCancel) {
                vstate.wantCancel = false;
                vstate.wantStop = false;
                releaseRec();
                setPhase('IDLE');
                el('voice-mic').classList.remove('recording');
                el('voice-mic').textContent = '按住说话';
                el('voice-timer').textContent = '';
                return;
            }

            // 用户按得很快：还没准备好就松手了
            if (vstate.wantStop) {
                vstate.wantStop = false;
                await stopRecord();
            }
        } catch (e) {
            releaseRec();
            setPhase('IDLE');
            el('voice-mic').classList.remove('recording');
            el('voice-mic').textContent = '按住说话';
            setHint('没有拿到麦克风权限，可以用下面的打字输入');
            toast('没拿到麦克风：' + (e && e.message ? e.message : '请检查权限'));
        }
    }

    function tick() {
        // 双保险：录音已经结束时，这个计时器必须自己停掉
        if (vstate.phase !== 'RECORDING') {
            if (vstate.timer) {
                clearInterval(vstate.timer);
                vstate.timer = null;
            }
            el('voice-timer').textContent = '';
            return;
        }
        var seconds = Math.floor((Date.now() - vstate.startedAt) / 1000);
        el('voice-timer').textContent = seconds + 's';
        if (seconds >= MAX_SECONDS) {
            stopRecord();
        }
    }

    /** 每次换页内容都滚回顶部：老人不明白为什么"刚才那行字跑上去了" */
    function scrollBodyTop() {
        var stage = document.querySelector('.voice-stage');
        if (stage) {
            stage.scrollTop = 0;
        }
    }

    function releaseRec() {
        var rec = vstate.rec;
        vstate.rec = null;
        if (vstate.timer) {
            clearInterval(vstate.timer);
            vstate.timer = null;
        }
        if (!rec) {
            return;
        }
        try {
            rec.node.port.onmessage = null;
            rec.source.disconnect();
            rec.node.disconnect();
        } catch (e) {
            // 忽略
        }
        try {
            rec.stream.getTracks().forEach(function (track) {
                track.stop();
            });
        } catch (e) {
            // 忽略
        }
        try {
            rec.ctx.close();
        } catch (e) {
            // 忽略
        }
    }

    function cancelRecording() {
        if (vstate.phase === 'STARTING') {
            vstate.wantCancel = true;
            return;
        }
        releaseRec();
        if (vstate.phase === 'RECORDING') {
            setPhase('IDLE');
        }
        el('voice-mic').classList.remove('recording');
        el('voice-mic').textContent = window.isSecureContext ? '按住说话' : '不能录音';
        el('voice-timer').textContent = '';
    }

    async function stopRecord() {
        if (vstate.phase === 'STARTING') {
            vstate.wantStop = true;
            return;
        }
        if (vstate.phase !== 'RECORDING') {
            return;
        }

        var rec = vstate.rec;
        var durationMs = Date.now() - vstate.startedAt;
        releaseRec();
        el('voice-mic').classList.remove('recording');
        el('voice-mic').textContent = '按住说话';
        el('voice-timer').textContent = '';
        setPhase('IDLE');

        if (!rec) {
            return;
        }
        if (durationMs < 1000) {
            setHint('说话时间太短，按住多说一会儿');
            toast('说话太短了，按住多一秒');
            return;
        }

        var blob = encodeWav(rec.chunks, rec.rate);
        if (!blob) {
            setHint('录音失败，请再说一遍');
            return;
        }
        setPhase('THINKING');
        setHint('正在听懂您说的话…');
        await doRecognize(blob);
    }

    /**
     * 合帧 -> 下采样到 16k -> Int16 -> 44 字节 WAV 头。
     * 浏览器给的采样率不一定是 16k（多数是 48k），这里统一重采样，后端只认 16k。
     */
    function encodeWav(chunks, rate) {
        try {
            var total = 0;
            for (var i = 0; i < chunks.length; i++) {
                total += chunks[i].length;
            }
            if (!total) {
                return null;
            }

            var merged = new Float32Array(total);
            var offset = 0;
            for (var c = 0; c < chunks.length; c++) {
                merged.set(chunks[c], offset);
                offset += chunks[c].length;
            }

            var targetRate = 16000;
            var ratio = rate / targetRate;
            var outLen = Math.max(1, Math.floor(total / ratio));
            var pcm = new Int16Array(outLen);

            for (var s = 0; s < outLen; s++) {
                var start = Math.floor(s * ratio);
                var end = Math.min(total, Math.floor((s + 1) * ratio));
                var sum = 0;
                var count = 0;
                for (var j = start; j < end; j++) {
                    sum += merged[j];
                    count++;
                }
                var sample = count ? sum / count : 0;
                sample = Math.max(-1, Math.min(1, sample));
                pcm[s] = sample < 0 ? sample * 0x8000 : sample * 0x7FFF;
            }

            var dataBytes = pcm.length * 2;
            var buffer = new ArrayBuffer(44 + dataBytes);
            var view = new DataView(buffer);
            writeAscii(view, 0, 'RIFF');
            view.setUint32(4, 36 + dataBytes, true);
            writeAscii(view, 8, 'WAVE');
            writeAscii(view, 12, 'fmt ');
            view.setUint32(16, 16, true);
            view.setUint16(20, 1, true);
            view.setUint16(22, 1, true);
            view.setUint32(24, targetRate, true);
            view.setUint32(28, targetRate * 2, true);
            view.setUint16(32, 2, true);
            view.setUint16(34, 16, true);
            writeAscii(view, 36, 'data');
            view.setUint32(40, dataBytes, true);
            new Int16Array(buffer, 44, pcm.length).set(pcm);

            return new Blob([buffer], { type: 'audio/wav' });
        } catch (e) {
            return null;
        }
    }

    function writeAscii(view, offset, text) {
        for (var i = 0; i < text.length; i++) {
            view.setUint8(offset + i, text.charCodeAt(i));
        }
    }

    /* ---------------- 识别 / 解析 ---------------- */

    async function doRecognize(blob) {
        var form = new FormData();
        form.append('audio', blob, 'voice.wav');
        form.append('dialect', vstate.dialect);
        form.append('sessionId', vstate.sessionId);

        var payload = null;
        try {
            // 不能走 api()：它会把 body 当 JSON 序列化
            var res = await fetch('/api/voice/recognize', {
                method: 'POST',
                headers: { 'Authorization': 'Bearer ' + state.accessToken },
                body: form
            });
            if (res.status === 401) {
                closePanel();
                handleUnauthorized();
                return;
            }
            try {
                payload = await res.json();
            } catch (e) {
                payload = null;
            }
        } catch (e) {
            setPhase('IDLE');
            setHint('网络不太好，再按一次圆钮试试');
            toast('识别请求失败');
            return;
        }

        if (!payload || payload.code !== 200) {
            var message = payload && payload.message ? payload.message : '识别失败';
            setPhase('IDLE');
            setHint(message + '，可以重说，或者用下面的打字输入');
            show('voice-fallback');
            renderFallback();
            toast(message);
            return;
        }

        var data = payload.data || {};
        if (!data.rawText) {
            setPhase('IDLE');
            setHint('没听清，请再说一遍');
            show('voice-fallback');
            renderFallback();
            return;
        }

        await onHeard(data.rawText);
    }

    /**
     * 拿到一句话（听出来的或打出来的）：先看当前在哪一步，再决定交给谁。
     *
     * <p>问乘车人那一步用户说的是人名，如果还拿去查票会得到一堆无关车次，
     * 所以这里必须按阶段分流。</p>
     */
    async function onHeard(text) {
        var said = (text || '').trim();
        if (!said) {
            return;
        }
        pushMsg('user', said);
        if (vstate.stage === 'PASSENGER') {
            handlePassengerSpeech(said);
            return;
        }
        if (vstate.stage === 'CONFIRM') {
            // 在核对页上又说话：多半是发现选错了，先退回车次再按新说法查一遍
            vstate.stage = 'TRAINS';
            vstate.selectedPassenger = null;
            state.selectedPassengerIds = [];
            hide('voice-confirm-card');
            hide('voice-passengers');
        }
        await doInterpret(said);
    }

    async function doInterpret(text, dateOverride) {
        setPhase('THINKING');
        vstate.lastText = text;
        setHint('正在查车次…');
        var data;
        try {
            data = await api('/api/voice/interpret', {
                method: 'POST',
                body: {
                    text: text,
                    fromStation: vstate.pickedFrom || null,
                    toStation: vstate.pickedTo || null,
                    lastUsedFrom: localStorage.getItem('lastFrom') || null,
                    todayHint: toISO(new Date()),
                    // 点"看明天"时由前端指定日期，服务端不再从原话里猜
                    travelDate: dateOverride || null
                }
            });
        } catch (e) {
            setPhase('IDLE');
            // 后端已经给过完整说法（比如"说得太快了，歇一下再说"），不要再拼后缀
            setHint(e.message);
            show('voice-fallback');
            renderFallback();
            toast(e.message);
            return;
        }

        vstate.intent = data.intent || {};
        vstate.lastDate = vstate.intent.travelDate || '';
        vstate.trains = data.trains || [];
        vstate.recommendation = data.recommendedTrain || null;
        vstate.recommendSeat = data.recommendSeatNo || '';
        setUnderstood(data.correctedText);

        // 日期超出 7 天窗口：候选和车次都不给，只说明日期太远
        if (data.emptyReason === 'DATE_OUT_OF_WINDOW') {
            setPhase('IDLE');
            hide('voice-candidates');
            hide('voice-trains');
            hide('voice-actions');
            show('voice-fallback');
            renderFallback();
            setHint('这个日子太远，先换个近的');
            say(data.speech);
            return;
        }

        // 站名没确定：只给候选，绝不露车次
        if (data.needStationConfirm) {
            setPhase('CANDIDATES');
            // 站名还没定，这句"我听懂了"必然残缺（会写成"从哪里到哪里"），先不显示
            setUnderstood('');
            hide('voice-trains');
            hide('voice-actions');
            if (renderCandidates(data)) {
                hide('voice-fallback');
            } else {
                // 一个候选都点不了（比如只说了目的地、出发站又没听过）：
                // 给句式让用户自己重说一句，绝不替他填出发站
                show('voice-fallback');
                renderFallback();
            }
            setHint('请点一下下面的站名');
            say(data.speech);
            return;
        }

        vstate.pickedFrom = data.intent ? data.intent.fromStation : null;
        vstate.pickedTo = data.intent ? data.intent.toStation : null;
        hide('voice-candidates');

        // 这条线路压根没有车：如实说，并且绝不给任何车次
        if (data.emptyReason === 'NO_ROUTE') {
            hide('voice-trains');
            hide('voice-actions');
            hide('voice-tomorrow');
            setPhase('IDLE');
            show('voice-fallback');
            renderFallback();
            setHint('这个方向没有班次');
            say(data.speech);
            return;
        }

        // 有这条线，但今天一张票都不剩：只留一条"看明天"的出路
        if (data.emptyReason === 'SOLD_OUT') {
            hide('voice-trains');
            hide('voice-fallback');
            hide('voice-yes');
            show('voice-tomorrow');
            show('voice-actions');
            setPhase('IDLE');
            setHint('今天的票卖完了，要看看明天的吗');
            say(data.speech);
            return;
        }

        renderTrains(data);
        hide('voice-tomorrow');
        hide('voice-passengers');
        hide('voice-confirm-card');
        vstate.stage = 'TRAINS';
        show('voice-yes');
        show('voice-actions');
        hide('voice-fallback');
        setPhase('TRAINS');
        // 屏幕上写的话和耳朵听到的要一致，别让老人两边对不上
        setHint('想买哪趟就点哪趟，或者按住话筒说个时间');
        say(data.speech);
    }

    /* ---------------- 渲染 ---------------- */

    /** 返回 true 表示给出了可点的候选；false 表示一个都点不了，调用方要另给出路 */
    function renderCandidates(data) {
        var box = el('voice-candidates');
        var candidates = data.stationCandidates || {};
        var intent = data.intent || {};
        var missing = data.missingStations || [];
        var lastFrom = localStorage.getItem('lastFrom');
        var lastTo = localStorage.getItem('lastTo');
        var groups = [];

        // 已经定下来的方向不再重复问：否则点完站名还会看到同一批候选，永远走不到下一步
        if (!intent.fromStation) {
            if (candidates.from && candidates.from.length) {
                groups.push(['出发站', candidates.from, 'from']);
            } else if (missing.indexOf('from') >= 0 && lastFrom) {
                // 没听出出发站，就把上次走过的摆出来让人点一下：只提供选项，不替用户决定
                groups.push(['上次是从这儿走的', [{ stationName: lastFrom, city: '' }], 'from']);
            }
        }
        if (!intent.toStation) {
            if (candidates.to && candidates.to.length) {
                groups.push(['到达站', candidates.to, 'to']);
            } else if (missing.indexOf('to') >= 0 && lastTo) {
                groups.push(['上次是到这儿', [{ stationName: lastTo, city: '' }], 'to']);
            }
        }

        if (!groups.length) {
            hide('voice-candidates');
            return false;
        }

        box.innerHTML = '';
        groups.forEach(function (group) {
            var wrap = document.createElement('div');
            wrap.className = 'voice-cand-group';

            var label = document.createElement('div');
            label.className = 'voice-cand-label';
            label.textContent = group[0];
            wrap.appendChild(label);

            // 只保留和最高分接近的：分数差太远的会变成噪音（说"北京"却列出"宝鸡南"）
            var topScore = (group[1][0] && typeof group[1][0].score === 'number') ? group[1][0].score : null;
            var kept = group[1].filter(function (item) {
                if (topScore === null) {
                    return true;
                }
                return typeof item.score === 'number' && item.score >= topScore - 0.15;
            }).slice(0, 3);

            kept.forEach(function (item) {
                var btn = document.createElement('button');
                btn.type = 'button';
                btn.className = 'voice-cand-item';
                btn.innerHTML = '<span class="vc-name">' + esc(item.stationName) + '</span>' +
                    '<span class="vc-city">' + esc(item.city || '') + '</span>';
                btn.addEventListener('click', function () {
                    pickStation(group[2], item.stationName);
                });
                wrap.appendChild(btn);
            });

            box.appendChild(wrap);
        });
        box.classList.remove('hidden');
        scrollBodyTop();
        return true;
    }

    function pickStation(direction, name) {
        if (direction === 'from') {
            vstate.pickedFrom = name;
        } else {
            vstate.pickedTo = name;
        }
        pushMsg('user', direction === 'from' ? '从' + name + '走' : '到' + name);
        hide('voice-candidates');
        doInterpret(vstate.lastText);
    }

    function renderTrains(data) {
        var list = el('voice-trains');
        var trains = data.trains || [];
        var rec = data.recommendedTrain || null;
        var recSeat = data.recommendSeatNo || '';
        list.innerHTML = '';

        // 推荐的那一趟排到最前面，其余按原顺序跟在后面。
        // 只调整"显示顺序"，卡片上的 data-ti 仍指向真实下标——选中时不能错位
        var order = [];
        var recIndex = rec ? findTrainIndex(rec) : -1;
        if (recIndex >= 0) {
            order.push(recIndex);
        }
        for (var k = 0; k < trains.length; k++) {
            if (k !== recIndex) {
                order.push(k);
            }
        }

        order.forEach(function (ti) {
            var train = trains[ti];
            // 判定"是不是推荐的那一趟"：车次号 + 日期实例 + 发车时刻三者都一致才算。
            // 只看车次号会误标——同一趟车在别的日期的实例是另一班
            var isRec = ti === recIndex;

            var card = document.createElement('div');
            card.className = 'voice-train' + (isRec ? ' vt-rec' : '');

            var seatsHtml = (train.seats || []).map(function (seat, si) {
                var soldOut = seat.remain <= 0 || seat.price === null || seat.price === undefined;
                var priceText = (seat.price === null || seat.price === undefined)
                    ? '票价待定'
                    : '¥' + money(seat.price);
                var isRecSeat = isRec && recSeat && seat.seatNo === recSeat && !soldOut;
                return '<button type="button" class="vt-seat' + (soldOut ? ' vt-seat-off' : '') +
                    (isRecSeat ? ' vt-seat-rec' : '') + '"' +
                    ' data-ti="' + ti + '" data-si="' + si + '"' +
                    (soldOut ? ' disabled' : '') + '>' +
                    '<span class="vt-seat-name">' + esc(seat.seatName || SEAT_CLASS_NAME[seat.seatNo]) + '</span>' +
                    '<span class="vt-seat-price">' + priceText + '</span>' +
                    '<span class="vt-seat-remain">' + (seat.remain > 0 ? '余 ' + seat.remain : '售罄') + '</span>' +
                    '</button>';
            }).join('');

            card.innerHTML =
                '<div class="vt-head">' +
                (isRec ? '<span class="vt-badge">给您推荐</span>' : '') +
                '<span class="vt-no">' + esc(train.trainNo) + '</span>' +
                '<span class="vt-time">' + esc(train.departTime) + ' → ' + esc(train.arriveTime) + '</span>' +
                '<span class="vt-dur">' + durationLabel(train.durationMinutes) + '</span>' +
                '</div>' +
                '<div class="vt-seats">' + seatsHtml + '</div>';
            list.appendChild(card);
        });

        list.classList.remove('hidden');
        scrollBodyTop();
    }

    /** 车次卡片默认锁住：先点"对，就是这个"才能选，这是第三重确认 */
    function lockSeats(locked) {
        var list = el('voice-trains');
        if (!list) {
            return;
        }
        list.querySelectorAll('.vt-seat').forEach(function (btn) {
            if (locked) {
                btn.classList.add('vt-seat-locked');
                btn.dataset.locked = '1';
            } else {
                btn.classList.remove('vt-seat-locked');
                delete btn.dataset.locked;
            }
        });
    }

    function renderFallback() {
        var box = el('voice-fallback');
        if (box.dataset.ready === '1') {
            box.classList.remove('hidden');
            return;
        }
        box.innerHTML = '';
        SENTENCES.forEach(function (sentence) {
            var btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'voice-sentence';
            btn.textContent = sentence;
            btn.addEventListener('click', function () {
                onHeard(sentence);
            });
            box.appendChild(btn);
        });
        box.dataset.ready = '1';
        box.classList.remove('hidden');
    }

    /* ---------------- 选车次：交回 app.js ---------------- */

    /** 在车次列表里找推荐的那一趟：车次号 + 发车时刻 + 实例号三者一致才算 */
    function findTrainIndex(rec) {
        var trains = vstate.trains || [];
        for (var i = 0; i < trains.length; i++) {
            var t = trains[i];
            if (t.trainNo === rec.trainNo && t.departTime === rec.departTime
                && (!rec.instanceNo || !t.instanceNo || rec.instanceNo === t.instanceNo)) {
                return i;
            }
        }
        return -1;
    }

    /** 推荐的席别有票就用它；没票就退回第一个有票的席别，绝不选售罄的 */
    function findSeatIndex(trainIndex, seatNo) {
        var train = (vstate.trains || [])[trainIndex];
        if (!train) {
            return 0;
        }
        var seats = train.seats || [];
        var i;
        for (i = 0; i < seats.length; i++) {
            if (seats[i].seatNo === seatNo && seats[i].remain > 0) {
                return i;
            }
        }
        for (i = 0; i < seats.length; i++) {
            if (seats[i].remain > 0) {
                return i;
            }
        }
        return 0;
    }

    function pickTrain(trainIndex, seatIndex) {
        var intent = vstate.intent || {};
        var train = (vstate.trains || [])[trainIndex];
        var seat = train ? (train.seats || [])[seatIndex] : null;
        if (!train || !seat) {
            toast('这趟车的席别没取到，请重新说一次');
            return;
        }
        if (intent.travelDate) {
            // 必须先写日期：下单要用它，app.js 的其他入口也读这个
            state.searchDate = intent.travelDate;
        }
        state.trains = vstate.trains;
        state.selectedTrain = train;
        state.selectedSeat = seat;
        localStorage.setItem('lastFrom', intent.fromStation || '');
        if (intent.toStation) {
            localStorage.setItem('lastTo', intent.toStation);
        }

        vstate.selectedTrain = train;
        vstate.selectedSeat = seat;
        // 不再关面板跳 app.js 的确认弹窗：整个流程在对话窗口里走完，
        // 用户只需要在最后付款时自己动一下手
        askPassenger(train, seat);
    }

    /* ---------------- 选乘车人 ---------------- */

    async function askPassenger(train, seat) {
        vstate.stage = 'PASSENGER';
        hide('voice-trains');
        hide('voice-fallback');
        hide('voice-confirm-card');
        hide('voice-tomorrow');
        hide('voice-yes');
        // 留一个"不对，重说"：老人选到一半想反悔，得有路可退
        show('voice-no');
        show('voice-actions');

        var seatName = seat.seatName || SEAT_CLASS_NAME[seat.seatNo] || seat.seatNo;
        say('好，' + (train.trainNo || '') + '，' + seatName + '。用哪位乘车人的名字买？您可以点下面的名字，也可以直接说。');
        setHint('说一位乘车人的名字，或者点下面的名字');

        var list = [];
        try {
            list = (await api('/api/passengers')) || [];
        } catch (e) {
            list = [];
        }
        vstate.passengers = list;

        if (!list.length) {
            say('您还没有乘车人。得先到"乘车人"那个页面添加一位，再回来买票。');
            setHint('还没有乘车人，请先去添加');
            show('voice-fallback');
            renderFallback();
            return;
        }
        renderPassengerChoices(list);
    }

    function renderPassengerChoices(list) {
        var box = el('voice-passengers');
        box.innerHTML = '';

        var head = document.createElement('div');
        head.className = 'voice-cand-label';
        head.textContent = '点一位乘车人';
        box.appendChild(head);

        // 名字几十条就够点了，再多老人也翻不动
        list.slice(0, 30).forEach(function (p) {
            var btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'voice-cand-item';
            btn.innerHTML = '<span class="vc-name">' + esc(p.passengerName) + '</span>' +
                '<span class="vc-city">' + esc(p.idCard || '') + '</span>';
            btn.addEventListener('click', function () {
                selectPassenger(p);
            });
            box.appendChild(btn);
        });
        box.classList.remove('hidden');
    }

    function selectPassenger(p) {
        vstate.selectedPassenger = p;
        state.selectedPassengerIds = [p.id];
        hide('voice-passengers');
        showConfirmCard();
    }

    /**
     * 用户说的是名字：名字整段出现在原话里才算命中。
     *
     * <p>不做拼音/模糊：乘车人是隐私，宁可让他再点一下，也不能靠猜。
     * 命中多个就列出来让他点，这比模型猜更靠谱。</p>
     */
    function handlePassengerSpeech(text) {
        var said = String(text || '').replace(/[，。？！、,.?!\s]/g, '');
        if (!said) {
            say('没听清，您再说一遍名字？');
            return;
        }
        var list = vstate.passengers || [];
        if (!list.length) {
            say('您还没有乘车人，请先添加一位。');
            return;
        }

        // 说的是"换一趟""不买了"这类话，不该被当成人名去比对
        var RETURN_WORDS = ['换', '不买', '不要', '重说', '重来', '改', '取消'];
        for (var w = 0; w < RETURN_WORDS.length; w++) {
            if (said.indexOf(RETURN_WORDS[w]) >= 0) {
                backToTrains();
                return;
            }
        }

        var hits = list.filter(function (p) {
            var name = p.passengerName || '';
            if (!name) {
                return false;
            }
            return said.indexOf(name) >= 0 || name.indexOf(said) >= 0;
        });

        if (hits.length === 1) {
            selectPassenger(hits[0]);
            return;
        }
        if (hits.length > 1) {
            say('有 ' + hits.length + ' 位对得上，您点一下是哪位？');
            renderPassengerChoices(hits);
            return;
        }
        say('没找到这位乘车人。您可以说清楚点，或者点下面的名字。');
        renderPassengerChoices(list);
    }

    /* ---------------- 核对与下单 ---------------- */

    function showConfirmCard() {
        vstate.stage = 'CONFIRM';
        var train = vstate.selectedTrain;
        var seat = vstate.selectedSeat;
        var p = vstate.selectedPassenger;
        if (!train || !seat || !p) {
            return;
        }
        var date = (vstate.intent && vstate.intent.travelDate) || state.searchDate || '';
        var seatName = seat.seatName || SEAT_CLASS_NAME[seat.seatNo] || seat.seatNo;
        var price = (seat.price === null || seat.price === undefined) ? '待定' : '¥' + money(seat.price);

        var box = el('voice-confirm-card');
        box.innerHTML =
            '<div class="vcf-title">请核对一下</div>' +
            '<div class="vcf-line">' + esc(date) + '</div>' +
            '<div class="vcf-big">' + esc(train.fromStation) + ' ' + esc(train.departTime) +
            ' → ' + esc(train.toStation) + ' ' + esc(train.arriveTime) + '</div>' +
            '<div class="vcf-line">' + esc(train.trainNo) + '　' + esc(seatName) + '</div>' +
            '<div class="vcf-line">乘车人：' + esc(p.passengerName) + '</div>' +
            '<div class="vcf-price">' + price + '</div>' +
            '<div class="vcf-btns">' +
            '<button type="button" id="voice-order-confirm" class="btn btn-primary voice-big">确认下单</button>' +
            '<button type="button" id="voice-order-back" class="btn btn-ghost voice-big">再改改</button>' +
            '</div>';
        box.classList.remove('hidden');

        var okBtn = el('voice-order-confirm');
        var backBtn = el('voice-order-back');
        if (okBtn) {
            okBtn.addEventListener('click', doCreateOrder);
        }
        if (backBtn) {
            backBtn.addEventListener('click', backToTrains);
        }

        say('请核对：' + date + '，' + train.fromStation + ' ' + train.departTime +
            ' 到 ' + train.toStation + ' ' + train.arriveTime + '，' + seatName +
            '，乘车人 ' + p.passengerName + '，' + price + '。没问题就点确认下单。');
        setHint('核对没问题，就点"确认下单"');
        hide('voice-actions');
        hide('voice-fallback');
    }

    /** 老人在核对页发现不对，必须能退回去改，不能只能取消重来 */
    function backToTrains() {
        vstate.stage = 'TRAINS';
        vstate.selectedPassenger = null;
        state.selectedPassengerIds = [];
        hide('voice-confirm-card');
        hide('voice-passengers');
        if ((vstate.trains || []).length) {
            show('voice-trains');
            show('voice-actions');
            setHint('想买哪趟就点哪趟');
        } else {
            setHint('按住下面的圆钮，重新说一遍');
        }
        say('好，那您重新选一趟。');
    }

    /**
     * 下单：只在用户点了"确认下单"之后才走这里。
     *
     * <p>对话里说"确认""就这个"都不直接下单——模型没有写权限，
     * 也不允许一句口误就把票买了。</p>
     */
    async function doCreateOrder() {
        var train = vstate.selectedTrain;
        var seat = vstate.selectedSeat;
        var p = vstate.selectedPassenger;
        var date = (vstate.intent && vstate.intent.travelDate) || state.searchDate;
        if (!train || !seat || !p || !date) {
            toast('订单信息不全，请重新选一次');
            return;
        }

        var btn = el('voice-order-confirm');
        if (btn) {
            btn.disabled = true;
            btn.textContent = '正在下单…';
        }

        try {
            var r = await api('/api/orders', {
                method: 'POST',
                body: {
                    passengerId: p.id,
                    trainNo: train.trainNo,
                    travelDate: date,
                    seatNo: seat.seatNo,
                    fromSeq: train.fromSeq,
                    toSeq: train.toSeq
                }
            });
            if (!r || !r.orderNo) {
                throw new Error((r && r.message) || '没抢到票');
            }

            var orders = [{ orderNo: r.orderNo, amount: Number(seat.price), name: p.passengerName }];
            say('座位给您占住了，订单号 ' + r.orderNo + '。现在去付款，付完才算买好。');
            setHint('订单已生成，正在打开收银台');
            // 付款这一步交给用户自己：这里是唯一需要他动手的地方
            setTimeout(function () {
                closePanel();
                openPayModal(orders);
            }, 600);
        } catch (e) {
            if (btn) {
                btn.disabled = false;
                btn.textContent = '确认下单';
            }
            say('没订成：' + e.message + '。您可以再点一次，或者换一趟车。');
            setHint('下单失败：' + e.message);
        }
    }

    /* ---------------- 事件绑定 ---------------- */

    function bindEvents() {
        el('btn-voice').addEventListener('click', openPanel);
        el('voice-close').addEventListener('click', closePanel);
        el('voice-dialect').addEventListener('click', nextDialect);
        el('voice-tts').addEventListener('click', toggleTts);

        // 按住说话：pointer 事件统一处理鼠标与触摸
        var mic = el('voice-mic');
        mic.addEventListener('pointerdown', function (e) {
            e.preventDefault();
            try {
                mic.setPointerCapture(e.pointerId);
            } catch (err) {
                // 忽略：部分浏览器不支持捕获
            }
            startRecord();
        });
        mic.addEventListener('pointerup', function () {
            stopRecord();
        });
        mic.addEventListener('pointercancel', function () {
            cancelRecording();
        });
        // 不监听 pointerleave：老人手抖滑出圆钮，不该被当成"说完了"

        // 兜底：pointer capture 在不支持/失效时，松手事件会落到别的元素上，
        // 没有这一层录音会一直跑到 30 秒
        window.addEventListener('pointerup', function () {
            if (vstate.phase === 'RECORDING') {
                stopRecord();
            }
        });
        window.addEventListener('pointercancel', function () {
            if (vstate.phase === 'RECORDING') {
                cancelRecording();
            }
        });

        // 车次里的席别：解锁后才能点
        el('voice-trains').addEventListener('click', function (e) {
            var btn = e.target.closest ? e.target.closest('.vt-seat') : null;
            if (!btn || btn.disabled) {
                return;
            }
            if (btn.dataset.locked === '1') {
                toast('先点"就买这趟"');
                return;
            }
            pickTrain(Number(btn.dataset.ti), Number(btn.dataset.si));
        });

        el('voice-yes').addEventListener('click', function () {
            // 后端已经按"什么时候走"挑好了一趟：用户点头等于"就买这趟"，
            // 不用再让他在列表里找一遍座位——这正是老人最容易卡住的环节
            var rec = vstate.recommendation;
            if (rec) {
                var ti = findTrainIndex(rec);
                if (ti >= 0) {
                    pickTrain(ti, findSeatIndex(ti, vstate.recommendSeat));
                    return;
                }
            }
            // 没有推荐（比如车次列表为空）：退回手动点选
            lockSeats(false);
            hide('voice-actions');
            setHint('现在点您要坐的那趟车和座位');
        });

        el('voice-tomorrow').addEventListener('click', function () {
            // 今天没票：在原来那天的基础上往后挪一天重查。
            // 用年月日直接构造，不走 new Date('2026-11-18')——那个按 UTC 解析，时区一偏就少一天
            var ymd = (vstate.lastDate || '').split('-');
            var base = ymd.length === 3
                ? new Date(Number(ymd[0]), Number(ymd[1]) - 1, Number(ymd[2]))
                : new Date();
            base.setDate(base.getDate() + 1);
            pushMsg('user', '看看明天的');
            doInterpret(vstate.lastText, toISO(base));
        });

        el('voice-no').addEventListener('click', function () {
            // 已经在选人/核对阶段：只退回车次，别把辛苦查出来的车次清掉
            if (vstate.stage === 'PASSENGER' || vstate.stage === 'CONFIRM') {
                backToTrains();
                return;
            }
            vstate.pickedFrom = null;
            vstate.pickedTo = null;
            vstate.intent = null;
            vstate.trains = [];
            resetPanel();
            setPhase('IDLE');
            setHint('按住下面的圆钮，重新说一遍');
            pushMsg('bot', '那您重新说一遍要去哪儿。');
        });

        // 打字兜底
        el('voice-type-toggle').addEventListener('click', function () {
            show('voice-typing');
            el('voice-type-input').focus();
        });
        el('voice-type-send').addEventListener('click', sendTyped);
        el('voice-type-input').addEventListener('keydown', function (e) {
            if (e.key === 'Enter') {
                e.preventDefault();
                sendTyped();
            }
        });
    }

    function sendTyped() {
        var input = el('voice-type-input');
        var text = (input.value || '').trim();
        if (!text) {
            toast('先写一句要去哪儿');
            return;
        }
        onHeard(text);
    }

    /* ---------------- 启动 ---------------- */

    function init() {
        bindEvents();
        window.Voice = {
            open: openPanel,
            close: closePanel,
            isOpen: function () {
                return vstate.open;
            }
        };
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();

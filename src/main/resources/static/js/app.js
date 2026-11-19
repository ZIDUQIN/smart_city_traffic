/**
 * 智慧城市交通 · 车票预订前端逻辑
 *
 * 模块顺序：状态 → 通信 → 登录 → 页面切换 → 订票 → 订单 → 乘车人 → 运维 → 启动
 */
'use strict';

/* ============================================================
   状态
   ============================================================ */
const state = {
    accessToken: localStorage.getItem('accessToken') || '',
    refreshToken: localStorage.getItem('refreshToken') || '',
    username: '',
    page: 'book',
    searchDate: '',          // 查询日期 yyyy-MM-dd
    trains: [],              // 当前查询结果
    selectedTrain: null,
    selectedSeat: null,
    passengers: [],
    selectedPassengerIds: [],
    editingPassengerId: null,
    orders: [],
    orderFilter: '全部',
    refundOrderNo: '',
    payingOrderNo: '',
    payOrders: [],
    countdownTimer: null,
    countdownLeft: 900
};

const HOT_ROUTES = [
    ['西安北', '北京西'],
    ['西安北', '上海虹桥'],
    ['北京西', '广州南'],
    ['上海虹桥', '南京南'],
    ['成都东', '重庆北'],
    ['广州南', '长沙南']
];

const SEAT_CLASS_NAME = {
    'SECOND_CLASS': '二等座',
    'FIRST_CLASS': '一等座',
    'BUSINESS': '商务座'
};

const STATUS_BADGE = {
    '待确认': 'badge-gray',
    '已确认': 'badge-blue',
    '已支付': 'badge-green',
    '退款中': 'badge-orange',
    '已退款': 'badge-dark-green',
    '已取消': 'badge-gray'
};

const ORDER_TABS = ['全部', '已确认', '已支付', '退款中', '已退款', '已取消'];

/* ============================================================
   通信
   ============================================================ */
async function api(path, options) {
    const opts = Object.assign({}, options || {});
    const headers = Object.assign({}, opts.headers || {});
    if (state.accessToken) {
        headers['Authorization'] = 'Bearer ' + state.accessToken;
    }
    if (opts.body && typeof opts.body !== 'string') {
        headers['Content-Type'] = 'application/json';
        opts.body = JSON.stringify(opts.body);
    }
    opts.headers = headers;

    const res = await fetch(path, opts);

    let payload = null;
    try {
        payload = await res.json();
    } catch (e) {
        payload = null;
    }

    // HTTP 401：令牌缺失或过期，必须重新登录
    if (res.status === 401) {
        handleUnauthorized();
        throw new Error('登录已过期，请重新登录');
    }
    if (!payload) {
        throw new Error('服务端返回异常（HTTP ' + res.status + '）');
    }
    if (payload.code !== 200) {
        throw new Error(payload.message || '请求失败');
    }
    return payload.data;
}

function handleUnauthorized() {
    state.accessToken = '';
    state.refreshToken = '';
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    showLogin();
}

function sleep(ms) {
    return new Promise(function (resolve) {
        setTimeout(resolve, ms);
    });
}

let toastTimer = null;
function toast(message) {
    const el = $('#toast');
    el.textContent = message;
    el.classList.add('show');
    if (toastTimer) {
        clearTimeout(toastTimer);
    }
    toastTimer = setTimeout(function () {
        el.classList.remove('show');
    }, 2600);
}

/* ============================================================
   工具
   ============================================================ */
function $(selector) {
    return document.querySelector(selector);
}

function pad(n) {
    return n < 10 ? '0' + n : String(n);
}

function toISO(date) {
    return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate());
}

function esc(text) {
    if (text === null || text === undefined) {
        return '';
    }
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function money(value) {
    const num = Number(value);
    if (isNaN(num)) {
        return '0.00';
    }
    return num.toFixed(2);
}

function openModal(id) {
    const el = document.getElementById(id);
    if (el) {
        el.classList.remove('hidden');
    }
}

function closeModal(id) {
    const el = document.getElementById(id);
    if (el) {
        el.classList.add('hidden');
    }
}

function trainTypeLabel(trainNo) {
    if (!trainNo) {
        return '';
    }
    const first = trainNo.charAt(0).toUpperCase();
    if (first === 'G') {
        return '高铁';
    }
    if (first === 'D') {
        return '动车';
    }
    if (first === 'C') {
        return '城际';
    }
    return '普速';
}

function durationLabel(minutes) {
    if (minutes === null || minutes === undefined) {
        return '-';
    }
    const h = Math.floor(minutes / 60);
    const m = minutes % 60;
    if (h <= 0) {
        return m + '分';
    }
    return h + '小时' + (m > 0 ? m + '分' : '');
}

/* ============================================================
   登录 / 注册
   ============================================================ */
function showLogin() {
    $('#login-view').classList.remove('hidden');
    $('#app-view').classList.add('hidden');
}

function showApp() {
    $('#login-view').classList.add('hidden');
    $('#app-view').classList.remove('hidden');
}

async function loadMe() {
    const me = await api('/api/auth/me');
    state.username = (me && (me.username || me.nickname)) || '';
    $('#current-user').textContent = state.username;
}

async function doLogin() {
    const username = $('#login-username').value.trim();
    const password = $('#login-password').value.trim();
    $('#login-error').textContent = '';
    if (!username || !password) {
        $('#login-error').textContent = '请输入账号和密码';
        return;
    }
    const btn = $('#btn-login');
    btn.disabled = true;
    btn.textContent = '登录中...';
    try {
        const data = await api('/api/auth/login', {
            method: 'POST',
            body: {username: username, password: password}
        });
        state.accessToken = data.accessToken || '';
        state.refreshToken = data.refreshToken || '';
        localStorage.setItem('accessToken', state.accessToken);
        localStorage.setItem('refreshToken', state.refreshToken);
        await loadMe();
        showApp();
        switchPage('book');
        loadStations();
    } catch (e) {
        $('#login-error').textContent = e.message;
    } finally {
        btn.disabled = false;
        btn.textContent = '登录';
    }
}

async function doRegister() {
    const username = $('#reg-username').value.trim();
    const password = $('#reg-password').value.trim();
    const nickname = $('#reg-nickname').value.trim();
    $('#register-error').textContent = '';
    if (!username || !password) {
        $('#register-error').textContent = '请输入账号和密码';
        return;
    }
    if (!nickname) {
        $('#register-error').textContent = '请输入昵称';
        return;
    }
    const btn = $('#btn-register');
    btn.disabled = true;
    btn.textContent = '注册中...';
    try {
        await api('/api/auth/register', {
            method: 'POST',
            body: {username: username, password: password, nickname: nickname}
        });
        $('#login-username').value = username;
        $('#login-password').value = password;
        $('#login-panel').classList.remove('hidden');
        $('#register-panel').classList.add('hidden');
        await doLogin();
    } catch (e) {
        $('#register-error').textContent = e.message;
    } finally {
        btn.disabled = false;
        btn.textContent = '注册并登录';
    }
}

async function doLogout() {
    try {
        await api('/api/auth/logout', {
            method: 'POST',
            body: {refreshToken: state.refreshToken}
        });
    } catch (e) {
        // 退出失败不阻断，本地状态照样清掉
    }
    handleUnauthorized();
    toast('已退出登录');
}

/* ============================================================
   页面切换
   ============================================================ */
function switchPage(page) {
    state.page = page;
    ['book', 'orders', 'passengers', 'ops'].forEach(function (name) {
        const section = document.getElementById('page-' + name);
        if (section) {
            section.classList.toggle('hidden', name !== page);
        }
    });
    document.querySelectorAll('.nav-item').forEach(function (btn) {
        btn.classList.toggle('active', btn.dataset.page === page);
    });
    if (page === 'orders') {
        loadOrders();
    } else if (page === 'passengers') {
        loadPassengers();
    }
}

/* ============================================================
   订票
   ============================================================ */
async function loadStations() {
    try {
        const list = await api('/api/ticket/stations');
        state.stations = list || [];
        renderStationPanel('from-station');
        renderStationPanel('to-station');
    } catch (e) {
        toast('车站列表加载失败：' + e.message);
    }
}

/* ---------- 车站下拉（组合框） ----------
 * 点输入框或右侧三角都能展开：已选定站名时展开全量列表并高亮当前项，
 * 输入时按匹配度排序（完全相等 > 前缀 > 包含 > 城市），最匹配的置顶；
 * 点候选直接替换旧值，不用先删除；支持 ↑↓ + Enter 键盘选择。
 */
function rankStations(keyword) {
    const kw = (keyword || '').trim();
    if (!kw) {
        return state.stations.slice();
    }
    const exact = [], prefix = [], contains = [], city = [];
    state.stations.forEach(function (s) {
        const n = s.stationName || '';
        const c = s.city || '';
        if (n === kw) { exact.push(s); }
        else if (n.indexOf(kw) === 0) { prefix.push(s); }
        else if (n.indexOf(kw) >= 0) { contains.push(s); }
        else if (c.indexOf(kw) >= 0) { city.push(s); }
    });
    // 同档内：名字越短越接近用户输入；包含匹配再看关键字出现位置越靠前
    const byLen = function (a, b) {
        return (a.stationName || '').length - (b.stationName || '').length ||
            (a.stationName || '').localeCompare(b.stationName || '', 'zh');
    };
    prefix.sort(byLen);
    city.sort(byLen);
    contains.sort(function (a, b) {
        return (a.stationName.indexOf(kw) - b.stationName.indexOf(kw)) || byLen(a, b);
    });
    return exact.concat(prefix, contains, city);
}

function isSettledStation(input) {
    const v = input.value.trim();
    return !!v && state.stations.some(function (s) { return s.stationName === v; });
}

function renderStationPanel(inputId, mode) {
    const input = document.getElementById(inputId);
    const panel = document.getElementById(inputId + '-panel');
    if (!input || !panel) {
        return;
    }
    // mode='all'：全量列表并高亮当前选中；mode='filter'：按输入关键字排序过滤
    const list = mode === 'all' ? state.stations.slice() : rankStations(input.value);
    if (list.length === 0) {
        panel.innerHTML = '<div class="combo-empty">没有匹配的车站</div>';
        return;
    }
    const current = input.value.trim();
    panel.innerHTML = list.map(function (s) {
        const sel = mode === 'all' && s.stationName === current ? ' selected' : '';
        return '<button type="button" class="combo-item' + sel + '" data-name="' + esc(s.stationName) + '">' +
            esc(s.stationName) +
            (s.city ? '<span class="combo-city">' + esc(s.city) + '</span>' : '') +
            '</button>';
    }).join('');

    panel.querySelectorAll('.combo-item').forEach(function (item) {
        // 用 mousedown 而不是 click：click 之前 input 会先失焦，面板已经被关掉
        item.addEventListener('mousedown', function (e) {
            e.preventDefault();
            pickStation(inputId, item.dataset.name);
        });
    });
}

function pickStation(inputId, name) {
    const input = document.getElementById(inputId);
    const panel = document.getElementById(inputId + '-panel');
    input.value = name;
    closeStationPanel(inputId);
    // mousedown 已阻止失焦，输入框仍持有焦点，无需再 focus（避免触发 focus 重新展开）
}

function openStationPanel(inputId, mode) {
    const panel = document.getElementById(inputId + '-panel');
    if (state.stations.length === 0) {
        return;
    }
    renderStationPanel(inputId, mode);
    panel.classList.remove('hidden');
    syncComboArrow(inputId);
    if (mode === 'all') {
        // 显示后再滚动，隐藏状态下没有布局，scrollIntoView 不生效
        const selItem = panel.querySelector('.combo-item.selected');
        if (selItem) {
            selItem.scrollIntoView({ block: 'center' });
        }
    }
}

function closeStationPanel(inputId) {
    const panel = document.getElementById(inputId + '-panel');
    if (panel) {
        panel.classList.add('hidden');
    }
    syncComboArrow(inputId);
}

function syncComboArrow(inputId) {
    const arrow = document.getElementById(inputId + '-arrow');
    const panel = document.getElementById(inputId + '-panel');
    if (arrow && panel) {
        arrow.classList.toggle('open', !panel.classList.contains('hidden'));
    }
}

function moveComboFocus(inputId, delta) {
    const input = document.getElementById(inputId);
    const panel = document.getElementById(inputId + '-panel');
    if (panel.classList.contains('hidden')) {
        openStationPanel(inputId, isSettledStation(input) ? 'all' : 'filter');
        return;
    }
    const items = Array.prototype.slice.call(panel.querySelectorAll('.combo-item'));
    if (!items.length) {
        return;
    }
    let idx = -1;
    items.forEach(function (it, i) {
        if (it.classList.contains('focused')) { idx = i; }
    });
    idx = idx < 0 ? (delta > 0 ? 0 : items.length - 1) : (idx + delta + items.length) % items.length;
    items.forEach(function (it) { it.classList.remove('focused'); });
    items[idx].classList.add('focused');
    items[idx].scrollIntoView({ block: 'nearest' });
}

function bindStationCombo(inputId) {
    const input = document.getElementById(inputId);
    const panel = document.getElementById(inputId + '-panel');
    const arrow = document.getElementById(inputId + '-arrow');
    if (!input || !panel) {
        return;
    }

    function openByContext() {
        openStationPanel(inputId, isSettledStation(input) ? 'all' : 'filter');
    }
    input.addEventListener('focus', openByContext);
    input.addEventListener('click', function () {
        if (panel.classList.contains('hidden')) {
            openByContext();
        }
        if (isSettledStation(input)) {
            input.select(); // 已选站名时整段选中，直接打字即可覆盖
        }
    });
    input.addEventListener('input', function () {
        openStationPanel(inputId, 'filter');
    });
    input.addEventListener('blur', function () {
        setTimeout(function () {
            closeStationPanel(inputId);
        }, 120);
    });
    if (arrow) {
        arrow.addEventListener('mousedown', function (e) {
            e.preventDefault();
            if (panel.classList.contains('hidden')) {
                openStationPanel(inputId, 'all');
                input.focus();
                input.select(); // 选中旧值，直接打字即可替换
            } else {
                closeStationPanel(inputId);
            }
        });
    }
    input.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') {
            closeStationPanel(inputId);
        } else if (e.key === 'ArrowDown') {
            e.preventDefault();
            moveComboFocus(inputId, 1);
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            moveComboFocus(inputId, -1);
        } else if (e.key === 'Enter') {
            const focused = panel.querySelector('.combo-item.focused');
            if (!panel.classList.contains('hidden') && focused) {
                e.preventDefault();
                e.stopImmediatePropagation(); // 面板开着时 Enter 是选中，不触发外层的 Enter=查询
                pickStation(inputId, focused.dataset.name);
            }
        }
    });
}

function renderDateBar() {
    const bar = $('#date-bar');
    if (!state.searchDate) {
        state.searchDate = toISO(new Date());
    }
    const today = new Date();
    const weekNames = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
    let html = '';
    for (let i = 0; i < 7; i++) {
        const d = new Date(today.getFullYear(), today.getMonth(), today.getDate() + i);
        const iso = toISO(d);
        const main = i === 0 ? '今天' : pad(d.getMonth() + 1) + '/' + pad(d.getDate());
        const sub = weekNames[d.getDay()];
        const active = iso === state.searchDate ? ' active' : '';
        html += '<button class="date-btn' + active + '" data-date="' + iso + '">' +
            '<span class="date-main">' + main + '</span>' +
            '<span class="date-sub">' + sub + '</span>' +
            '</button>';
    }
    bar.innerHTML = html;
    bar.querySelectorAll('.date-btn').forEach(function (btn) {
        btn.addEventListener('click', function () {
            state.searchDate = btn.dataset.date;
            renderDateBar();
            if ($('#from-station').value.trim() && $('#to-station').value.trim()) {
                doSearch();
            }
        });
    });
}

function renderHotRoutes() {
    const box = $('#hot-routes');
    box.innerHTML = HOT_ROUTES.map(function (route) {
        return '<button class="hot-chip" data-from="' + esc(route[0]) + '" data-to="' + esc(route[1]) + '">' +
            esc(route[0]) + ' → ' + esc(route[1]) + '</button>';
    }).join('');
    box.querySelectorAll('.hot-chip').forEach(function (btn) {
        btn.addEventListener('click', function () {
            $('#from-station').value = btn.dataset.from;
            $('#to-station').value = btn.dataset.to;
            doSearch();
        });
    });
}

async function doSearch() {
    const from = $('#from-station').value.trim();
    const to = $('#to-station').value.trim();
    if (!from || !to) {
        toast('请选择出发站和到达站');
        return;
    }
    if (from === to) {
        toast('出发站与到达站不能相同');
        return;
    }
    const list = $('#train-list');
    list.innerHTML = '<div class="empty-tip">正在查询...</div>';
    try {
        const url = '/api/ticket/search?fromStation=' + encodeURIComponent(from) +
            '&toStation=' + encodeURIComponent(to) +
            '&travelDate=' + encodeURIComponent(state.searchDate);
        const data = await api(url);
        state.trains = data || [];
        if (state.trains.length === 0) {
            list.innerHTML = '<div class="empty-tip">该日期没有查到可售车次，试试换个日期或线路</div>';
            return;
        }
        renderTrainList();
    } catch (e) {
        list.innerHTML = '<div class="empty-tip">查询失败：' + esc(e.message) + '</div>';
    }
}

function renderTrainList() {
    const list = $('#train-list');
    let html = '';
    state.trains.forEach(function (train, ti) {
        let seatsHtml = '';
        train.seats.forEach(function (seat, si) {
            const soldOut = (seat.remain === null || seat.remain === undefined || seat.remain <= 0);
            const noPrice = (seat.price === null || seat.price === undefined);
            const disabled = soldOut || noPrice;
            let statusHtml = '';
            if (soldOut) {
                statusHtml = '<span class="seat-status status-none">无票</span>';
            } else if (seat.remain <= 5) {
                statusHtml = '<span class="seat-status status-few">仅剩 ' + seat.remain + ' 张</span>';
            } else {
                statusHtml = '<span class="seat-status status-much">余票 ' + seat.remain + ' 张</span>';
            }
            const priceHtml = noPrice
                ? '<span class="seat-price none">暂未开售</span>'
                : '<span class="seat-price">¥' + money(seat.price) + '</span>';
            seatsHtml += '<button class="seat-chip' + (disabled ? ' disabled' : '') + '"' +
                ' data-train="' + ti + '" data-seat="' + si + '"' +
                (disabled ? ' disabled' : '') + '>' +
                '<span class="seat-name">' + esc(seat.seatName || SEAT_CLASS_NAME[seat.seatNo] || seat.seatNo) + '</span>' +
                priceHtml + statusHtml +
                '</button>';
        });
        html += '<div class="train-row">' +
            '<div class="train-main">' +
            '<div class="train-no">' + esc(train.trainNo) + '</div>' +
            '<span class="train-type">' + trainTypeLabel(train.trainNo) + '</span>' +
            '</div>' +
            '<div>' +
            '<div class="train-route">' +
            '<div class="time-block">' +
            '<div class="time-big">' + esc(train.departTime) + '</div>' +
            '<div class="time-station">' + esc(train.fromStation) + '</div>' +
            '</div>' +
            '<div class="route-mid">' +
            '<div class="route-line"></div>' +
            '<div class="route-duration">' + durationLabel(train.durationMinutes) + '</div>' +
            '</div>' +
            '<div class="time-block">' +
            '<div class="time-big">' + esc(train.arriveTime) + '</div>' +
            '<div class="time-station">' + esc(train.toStation) + '</div>' +
            '</div>' +
            '</div>' +
            '<div class="seats">' + seatsHtml + '</div>' +
            '</div>' +
            '</div>';
    });
    list.innerHTML = html;

    list.querySelectorAll('.seat-chip:not(.disabled)').forEach(function (chip) {
        chip.addEventListener('click', function () {
            openOrderModal(Number(chip.dataset.train), Number(chip.dataset.seat));
        });
    });
}

/* ============================================================
   确认订单
   ============================================================ */
async function openOrderModal(trainIndex, seatIndex) {
    const train = state.trains[trainIndex];
    const seat = train.seats[seatIndex];
    if (!train || !seat || seat.remain <= 0 || seat.price === null) {
        toast('该席别当前不可预订');
        return;
    }
    state.selectedTrain = train;
    state.selectedSeat = seat;
    state.selectedPassengerIds = [];
    $('#order-error').textContent = '';
    $('#order-passenger-search').value = '';

    $('#order-summary').innerHTML =
        '<div class="summary-train">' +
        '<span class="summary-train-no">' + esc(train.trainNo) + '</span>' +
        '<span class="train-type">' + trainTypeLabel(train.trainNo) + '</span>' +
        '<span class="order-date">' + esc(state.searchDate) + '</span>' +
        '</div>' +
        '<div class="summary-line">' +
        '<span>' + esc(train.departTime) + ' ' + esc(train.fromStation) + '</span>' +
        '<span>→</span>' +
        '<span>' + esc(train.arriveTime) + ' ' + esc(train.toStation) + '</span>' +
        '</div>' +
        '<div class="summary-meta">' +
        '席别：' + esc(seat.seatName || SEAT_CLASS_NAME[seat.seatNo]) +
        '　历时：' + durationLabel(train.durationMinutes) +
        '　单价：¥' + money(seat.price) +
        '</div>';

    updateOrderTotal();
    $('#btn-submit-order').disabled = false;
    $('#btn-submit-order').textContent = '提交订单';

    openModal('order-modal');
    await loadPassengers(true);
    renderPassengerSelect();
}

async function loadPassengers(forOrder) {
    try {
        const list = await api('/api/passengers');
        state.passengers = list || [];
    } catch (e) {
        state.passengers = [];
        if (!forOrder) {
            toast('乘车人加载失败：' + e.message);
        }
    }
    if (!forOrder) {
        renderPassengerList();
    }
}

/** 乘车人筛选：姓名包含或证件号包含关键字 */
function matchPassenger(p, keyword) {
    if (!keyword) {
        return true;
    }
    const kw = keyword.trim();
    if (!kw) {
        return true;
    }
    const name = p.passengerName || '';
    const idCard = p.idCard || '';
    return name.indexOf(kw) >= 0 || idCard.indexOf(kw) >= 0;
}

/** 单次渲染上限：历史测试数据可能有上千条，全部渲染既慢又没法选 */
const PASSENGER_RENDER_LIMIT = 30;

/** 合计 = 单价 × 已选乘车人数 */
function updateOrderTotal() {
    const seat = state.selectedSeat;
    const count = state.selectedPassengerIds.length;
    const total = (seat && seat.price ? Number(seat.price) : 0) * count;
    $('#order-total').textContent = '¥' + money(total);
    $('#order-count').textContent = count > 0 ? '共 ' + count + ' 张票' : '';
}

function renderPassengerSelect() {
    const box = $('#order-passenger-list');
    const empty = $('#order-no-passenger');
    if (state.passengers.length === 0) {
        box.innerHTML = '';
        box.classList.add('hidden');
        empty.classList.remove('hidden');
        $('#btn-submit-order').disabled = true;
        $('#btn-goto-passenger').onclick = function () {
            closeModal('order-modal');
            switchPage('passengers');
        };
        return;
    }
    box.classList.remove('hidden');
    empty.classList.add('hidden');

    const kw = $('#order-passenger-search').value;
    const filtered = state.passengers.filter(function (p) {
        return matchPassenger(p, kw);
    });

    const typeMap = {1: '成人', 2: '学生', 3: '儿童'};
    const shown = filtered.slice(0, PASSENGER_RENDER_LIMIT);
    let html = shown.map(function (p) {
        const active = state.selectedPassengerIds.indexOf(p.id) >= 0 ? ' active' : '';
        return '<div class="select-item' + active + '" data-id="' + p.id + '">' +
            '<span class="radio-dot"></span>' +
            '<div class="select-item-main">' +
            '<div class="select-item-name">' + esc(p.passengerName) + '</div>' +
            '<div class="select-item-sub">' + esc(p.idCard) + '　' + (typeMap[p.passengerType] || '成人') + '</div>' +
            '</div>' +
            '</div>';
    }).join('');
    if (filtered.length === 0) {
        html = '<div class="list-hint">没有匹配的乘车人</div>';
    } else if (filtered.length > PASSENGER_RENDER_LIMIT) {
        html += '<div class="list-hint">共 ' + filtered.length + ' 位，仅显示前 ' +
            PASSENGER_RENDER_LIMIT + ' 位，请输入姓名或证件号缩小范围</div>';
    }
    box.innerHTML = html;

    box.querySelectorAll('.select-item').forEach(function (item) {
        item.addEventListener('click', function () {
            const id = Number(item.dataset.id);
            const idx = state.selectedPassengerIds.indexOf(id);
            if (idx >= 0) {
                state.selectedPassengerIds.splice(idx, 1);
            } else {
                state.selectedPassengerIds.push(id);
            }
            renderPassengerSelect();
        });
    });
    $('#btn-submit-order').disabled = state.selectedPassengerIds.length === 0;
    updateOrderTotal();
}

async function submitOrder() {
    const train = state.selectedTrain;
    const seat = state.selectedSeat;
    if (!train || !seat) {
        return;
    }
    if (state.selectedPassengerIds.length === 0) {
        $('#order-error').textContent = '请至少选择一位乘车人';
        return;
    }
    const btn = $('#btn-submit-order');
    btn.disabled = true;
    btn.textContent = '提交中...';
    $('#order-error').textContent = '';

    const nameMap = {};
    state.passengers.forEach(function (p) {
        nameMap[p.id] = p.passengerName;
    });

    const succeeded = [];
    const failed = [];

    // 后端下单接口一次只接收一位乘车人，这里逐个提交，再把成功的订单聚合成一次支付
    for (let i = 0; i < state.selectedPassengerIds.length; i++) {
        const pid = state.selectedPassengerIds[i];
        btn.textContent = '提交中 ' + (i + 1) + '/' + state.selectedPassengerIds.length;
        try {
            const result = await api('/api/orders', {
                method: 'POST',
                body: {
                    passengerId: pid,
                    trainNo: train.trainNo,
                    travelDate: state.searchDate,
                    seatNo: seat.seatNo,
                    fromSeq: train.fromSeq,
                    toSeq: train.toSeq
                }
            });
            if (result.status === '已确认') {
                succeeded.push({
                    orderNo: result.orderNo,
                    amount: Number(seat.price),
                    name: nameMap[pid] || ''
                });
            } else {
                failed.push({
                    name: nameMap[pid] || String(pid),
                    reason: result.message || '抢票失败'
                });
            }
        } catch (e) {
            failed.push({name: nameMap[pid] || String(pid), reason: e.message});
        }
    }

    if (succeeded.length === 0) {
        $('#order-error').textContent = failed.map(function (f) {
            return f.name + '：' + f.reason;
        }).join('；');
        btn.disabled = false;
        btn.textContent = '重新提交';
        return;
    }

    closeModal('order-modal');
    if (failed.length > 0) {
        toast('成功 ' + succeeded.length + ' 张，失败 ' + failed.length + ' 张（' +
            failed.map(function (f) {
                return f.name;
            }).join('、') + '）');
    } else {
        toast('抢票成功 ' + succeeded.length + ' 张，请完成支付');
    }
    openPayModal(succeeded);
}

/* ============================================================
   支付
   ============================================================ */
function openPayModal(orders) {
    state.payOrders = (orders || []).slice();
    const total = state.payOrders.reduce(function (sum, o) {
        return sum + Number(o.amount || 0);
    }, 0);
    $('#pay-amount').textContent = money(total);
    $('#pay-order-no').textContent = state.payOrders.length === 1
        ? state.payOrders[0].orderNo
        : state.payOrders.map(function (o) {
            return o.orderNo;
        }).join(' / ');
    $('#pay-ticket-count').textContent = '共 ' + state.payOrders.length + ' 张票';
    $('#pay-status').textContent = '';
    $('#pay-status').className = 'pay-status';
    $('#btn-confirm-pay').disabled = false;
    $('#btn-confirm-pay').textContent = '确认支付';
    $('#btn-confirm-pay').onclick = confirmPay;
    startCountdown(15 * 60);
    openModal('pay-modal');
}

function startCountdown(totalSeconds) {
    stopCountdown();
    state.countdownLeft = totalSeconds;
    renderCountdown();
    state.countdownTimer = setInterval(function () {
        state.countdownLeft -= 1;
        if (state.countdownLeft <= 0) {
            stopCountdown();
            $('#pay-countdown').textContent = '00:00';
            $('#pay-status').textContent = '支付窗口已结束，请到订单页确认状态';
            $('#pay-status').className = 'pay-status fail';
            $('#btn-confirm-pay').disabled = true;
            return;
        }
        renderCountdown();
    }, 1000);
}

function renderCountdown() {
    const m = Math.floor(state.countdownLeft / 60);
    const s = state.countdownLeft % 60;
    $('#pay-countdown').textContent = pad(m) + ':' + pad(s);
}

function stopCountdown() {
    if (state.countdownTimer) {
        clearInterval(state.countdownTimer);
        state.countdownTimer = null;
    }
}

async function confirmPay() {
    if (state.payOrders.length === 0) {
        return;
    }
    const btn = $('#btn-confirm-pay');
    const status = $('#pay-status');
    btn.disabled = true;
    btn.textContent = '支付中...';
    status.className = 'pay-status';
    status.textContent = '正在拉起支付...';
    try {
        for (let i = 0; i < state.payOrders.length; i++) {
            const orderNo = state.payOrders[i].orderNo;
            if (state.payOrders.length > 1) {
                status.textContent = '正在支付第 ' + (i + 1) + '/' + state.payOrders.length + ' 张...';
            }
            const prepay = await api('/api/pay/prepay?orderNo=' + encodeURIComponent(orderNo), {
                method: 'POST'
            });
            await api('/api/pay/mock/confirm?payToken=' + encodeURIComponent(prepay.payToken), {
                method: 'POST'
            });
        }
        status.textContent = '支付处理中，请稍候...';
        await pollPayStatus();
    } catch (e) {
        status.className = 'pay-status fail';
        status.textContent = e.message;
        btn.disabled = false;
        btn.textContent = '重试支付';
    }
}

async function pollPayStatus() {
    const status = $('#pay-status');
    const btn = $('#btn-confirm-pay');
    for (let i = 0; i < 15; i++) {
        await sleep(800);
        let paidCount = 0;
        for (let j = 0; j < state.payOrders.length; j++) {
            try {
                const st = await api('/api/pay/status/' + encodeURIComponent(state.payOrders[j].orderNo));
                if (st.orderStatus === '已支付') {
                    paidCount += 1;
                }
            } catch (e) {
                // 单次查询失败不中断轮询
            }
        }
        if (paidCount === state.payOrders.length) {
            stopCountdown();
            status.className = 'pay-status ok';
            status.textContent = '支付成功，共 ' + state.payOrders.length + ' 张票，可在「我的订单」查看';
            btn.textContent = '查看订单';
            btn.disabled = false;
            btn.onclick = function () {
                closeModal('pay-modal');
                switchPage('orders');
            };
            toast('支付成功');
            return;
        }
        if (state.payOrders.length > 1) {
            status.textContent = '支付处理中（已成功 ' + paidCount + '/' + state.payOrders.length + '）';
        }
    }
    status.className = 'pay-status';
    status.textContent = '支付处理中，请在「我的订单」确认最终状态';
    btn.disabled = false;
    btn.textContent = '重试支付';
}

/* ============================================================
   我的订单
   ============================================================ */
async function loadOrders() {
    const list = $('#order-list');
    list.innerHTML = '<div class="empty-tip">加载中...</div>';
    try {
        const data = await api('/api/orders/my');
        state.orders = data || [];
        renderOrderTabs();
        renderOrderList();
    } catch (e) {
        list.innerHTML = '<div class="empty-tip">加载失败：' + esc(e.message) + '</div>';
    }
}

function renderOrderTabs() {
    const box = $('#order-tabs');
    const counts = {};
    state.orders.forEach(function (o) {
        counts[o.status] = (counts[o.status] || 0) + 1;
    });
    box.innerHTML = ORDER_TABS.map(function (name) {
        const count = name === '全部' ? state.orders.length : (counts[name] || 0);
        const active = name === state.orderFilter ? ' active' : '';
        return '<button class="tab' + active + '" data-tab="' + name + '">' +
            name + ' ' + count + '</button>';
    }).join('');
    box.querySelectorAll('.tab').forEach(function (tab) {
        tab.addEventListener('click', function () {
            state.orderFilter = tab.dataset.tab;
            renderOrderTabs();
            renderOrderList();
        });
    });
}

function renderOrderList() {
    const list = $('#order-list');
    let orders = state.orders;
    if (state.orderFilter !== '全部') {
        orders = orders.filter(function (o) {
            return o.status === state.orderFilter;
        });
    }
    if (orders.length === 0) {
        list.innerHTML = '<div class="empty-tip">暂无订单</div>';
        return;
    }
    let html = '';
    orders.forEach(function (o) {
        const badge = STATUS_BADGE[o.status] || 'badge-gray';
        const route = (o.fromStation || '第 ' + o.fromSeq + ' 站') + ' → ' + (o.toStation || '第 ' + o.toSeq + ' 站');
        let actions = '';
        if (o.status === '已确认') {
            actions = '<button class="btn btn-primary" data-action="pay" data-order="' + esc(o.orderNo) + '" data-amount="' + money(o.amount) + '">去支付</button>' +
                '<button class="btn btn-ghost" data-action="cancel" data-order="' + esc(o.orderNo) + '">取消订单</button>';
        } else if (o.status === '已支付') {
            actions = '<button class="btn btn-ghost" data-action="refund" data-order="' + esc(o.orderNo) + '" data-amount="' + money(o.amount) + '">申请退票</button>';
        } else if (o.status === '退款中') {
            actions = '<button class="btn btn-ghost" data-action="refund-status" data-order="' + esc(o.orderNo) + '">查看退款进度</button>';
        }
        html += '<div class="order-card">' +
            '<div class="order-top">' +
            '<div class="order-train">' +
            '<span class="order-train-no">' + esc(o.trainNo) + '</span>' +
            '<span class="order-date">' + esc(o.travelDate) + '　' + esc(SEAT_CLASS_NAME[o.seatNo] || o.seatNo) + '</span>' +
            '</div>' +
            '<span class="badge ' + badge + '">' + esc(o.status) + '</span>' +
            '</div>' +
            '<div class="order-body">' +
            '<div>' +
            '<div class="order-route">' + esc(route) + '</div>' +
            '<div class="order-meta">订单号 ' + esc(o.orderNo) + '　创建于 ' + esc((o.createTime || '').replace('T', ' ').slice(0, 16)) + '</div>' +
            '</div>' +
            '<div class="order-amount">¥' + money(o.amount) + '</div>' +
            '</div>' +
            '<div class="order-foot">' +
            '<div></div>' +
            '<div class="order-actions">' + actions + '</div>' +
            '</div>' +
            '</div>';
    });
    list.innerHTML = html;

    list.querySelectorAll('[data-action]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            const action = btn.dataset.action;
            const orderNo = btn.dataset.order;
            if (action === 'pay') {
                openPayModal([{orderNo: orderNo, amount: Number(btn.dataset.amount)}]);
            } else if (action === 'cancel') {
                cancelOrder(orderNo);
            } else if (action === 'refund') {
                openRefundModal(orderNo, Number(btn.dataset.amount));
            } else if (action === 'refund-status') {
                showRefundStatus(orderNo);
            }
        });
    });
}

async function cancelOrder(orderNo) {
    if (!window.confirm('确认取消该订单？取消后座位将释放。')) {
        return;
    }
    try {
        await api('/api/orders/' + encodeURIComponent(orderNo) + '/cancel', {method: 'POST'});
        toast('订单已取消');
        loadOrders();
    } catch (e) {
        toast('取消失败：' + e.message);
    }
}

/* ---------- 退票 ---------- */
function openRefundModal(orderNo, amount) {
    state.refundOrderNo = orderNo;
    $('#refund-error').textContent = '';
    $('#refund-reason').value = '';
    $('#refund-info').innerHTML =
        '订单号：<span class="mono">' + esc(orderNo) + '</span><br>' +
        '票款金额：<strong>¥' + money(amount) + '</strong><br>' +
        '当前手续费为 0，退票后票款全额原路退回。';
    openModal('refund-modal');
}

async function submitRefund() {
    if (!state.refundOrderNo) {
        return;
    }
    const btn = $('#btn-submit-refund');
    const reason = $('#refund-reason').value.trim();
    btn.disabled = true;
    btn.textContent = '提交中...';
    $('#refund-error').textContent = '';
    try {
        const url = '/api/orders/' + encodeURIComponent(state.refundOrderNo) + '/refund' +
            (reason ? '?reason=' + encodeURIComponent(reason) : '');
        const data = await api(url, {method: 'POST'});
        closeModal('refund-modal');
        toast('退票已提交，正在退款（' + (data.status || '退款中') + '）');
        await loadOrders();
        waitRefundFinal(state.refundOrderNo);
    } catch (e) {
        $('#refund-error').textContent = e.message;
    } finally {
        btn.disabled = false;
        btn.textContent = '确认退票';
    }
}

/** 退款是异步的：先确认一次，若渠道还没回调就按 1.2 秒轮询，直到落到终态 */
async function waitRefundFinal(orderNo) {
    if (!orderNo) {
        return;
    }
    for (let i = 0; i < 10; i++) {
        try {
            const order = await api('/api/orders/' + encodeURIComponent(orderNo));
            if (order.status === '已退款') {
                toast('退款成功，票款已原路退回');
                loadOrders();
                return;
            }
            if (order.status !== '退款中') {
                loadOrders();
                return;
            }
            loadOrders();
        } catch (e) {
            // 单次查询失败不中断
        }
        await sleep(1200);
    }
    loadOrders();
}

async function showRefundStatus(orderNo) {
    try {
        const data = await api('/api/orders/' + encodeURIComponent(orderNo) + '/refund');
        const lines = [
            '退款状态：' + (data.status || '未知'),
            '票款金额：¥' + money(data.amount),
            '手续费：¥' + money(data.fee),
            '实退金额：¥' + money(data.refundAmount)
        ];
        if (data.refundNo) {
            lines.push('退款单号：' + data.refundNo);
        }
        if (data.reason) {
            lines.push('退票原因：' + data.reason);
        }
        window.alert(lines.join('\n'));
    } catch (e) {
        toast('查询失败：' + e.message);
    }
}

/* ============================================================
   乘车人
   ============================================================ */
function renderPassengerList() {
    const list = $('#passenger-list');
    if (state.passengers.length === 0) {
        list.innerHTML = '<div class="empty-tip">还没有乘车人，点击右上角「新增乘车人」添加</div>';
        return;
    }
    const kw = $('#passenger-search').value;
    const filtered = state.passengers.filter(function (p) {
        return matchPassenger(p, kw);
    });
    const typeMap = {1: '成人', 2: '学生', 3: '儿童'};
    const typeBadge = {1: 'badge-blue', 2: 'badge-orange', 3: 'badge-green'};
    const shown = filtered.slice(0, PASSENGER_RENDER_LIMIT);
    let html = shown.map(function (p) {
        return '<div class="passenger-card">' +
            '<div class="passenger-head">' +
            '<span class="passenger-name">' + esc(p.passengerName) + '</span>' +
            '<span class="badge ' + (typeBadge[p.passengerType] || 'badge-gray') + '">' +
            (typeMap[p.passengerType] || '成人') + '</span>' +
            '</div>' +
            '<div class="passenger-id">' + esc(p.idCard) + '</div>' +
            '<div class="passenger-actions">' +
            '<button class="btn btn-ghost" data-edit="' + p.id + '">编辑</button>' +
            '<button class="btn btn-ghost" data-delete="' + p.id + '">删除</button>' +
            '</div>' +
            '</div>';
    }).join('');
    if (filtered.length === 0) {
        html = '<div class="empty-tip">没有匹配的乘车人</div>';
    } else if (filtered.length > PASSENGER_RENDER_LIMIT) {
        html += '<div class="list-hint">共 ' + filtered.length + ' 位，仅显示前 ' +
            PASSENGER_RENDER_LIMIT + ' 位，可用上方搜索缩小范围</div>';
    }
    list.innerHTML = html;

    list.querySelectorAll('[data-edit]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            openPassengerModal(Number(btn.dataset.edit));
        });
    });
    list.querySelectorAll('[data-delete]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            deletePassenger(Number(btn.dataset.delete));
        });
    });
}

function openPassengerModal(id) {
    state.editingPassengerId = id || null;
    $('#passenger-error').textContent = '';
    $('#passenger-modal-title').textContent = id ? '编辑乘车人' : '新增乘车人';
    if (id) {
        const target = state.passengers.find(function (p) {
            return p.id === id;
        });
        // 列表里的身份证是脱敏的，编辑时告知用户需重新输入完整号码
        $('#p-name').value = target ? target.passengerName : '';
        $('#p-idcard').value = '';
        $('#p-idcard').placeholder = '请输入完整 18 位身份证号';
        $('#p-type').value = target ? String(target.passengerType) : '1';
    } else {
        $('#p-name').value = '';
        $('#p-idcard').value = '';
        $('#p-idcard').placeholder = '18 位身份证号';
        $('#p-type').value = '1';
    }
    openModal('passenger-modal');
}

async function savePassenger() {
    const name = $('#p-name').value.trim();
    const idCard = $('#p-idcard').value.trim();
    const type = Number($('#p-type').value);
    $('#passenger-error').textContent = '';
    if (!name) {
        $('#passenger-error').textContent = '请输入姓名';
        return;
    }
    if (!idCard) {
        $('#passenger-error').textContent = '请输入身份证号';
        return;
    }
    const btn = $('#btn-save-passenger');
    btn.disabled = true;
    btn.textContent = '保存中...';
    try {
        const body = {passengerName: name, idCard: idCard, passengerType: type};
        if (state.editingPassengerId) {
            await api('/api/passengers/' + state.editingPassengerId, {method: 'PUT', body: body});
        } else {
            await api('/api/passengers', {method: 'POST', body: body});
        }
        closeModal('passenger-modal');
        toast('保存成功');
        await loadPassengers(false);
    } catch (e) {
        $('#passenger-error').textContent = e.message;
    } finally {
        btn.disabled = false;
        btn.textContent = '保存';
    }
}

async function deletePassenger(id) {
    const target = state.passengers.find(function (p) {
        return p.id === id;
    });
    if (!window.confirm('确认删除乘车人「' + (target ? target.passengerName : '') + '」？')) {
        return;
    }
    try {
        await api('/api/passengers/' + id, {method: 'DELETE'});
        toast('已删除');
        await loadPassengers(false);
    } catch (e) {
        toast('删除失败：' + e.message);
    }
}

/* ============================================================
   运维
   ============================================================ */
async function loadInstanceWindow() {
    const trainNo = $('#ops-train-no').value.trim();
    const days = Number($('#ops-days').value) || 7;
    const box = $('#ops-window-result');
    if (!trainNo) {
        toast('请输入车次号');
        return;
    }
    box.textContent = '查询中...';
    box.className = 'ops-result';
    try {
        const list = await api('/api/instances/window?trainNo=' + encodeURIComponent(trainNo) +
            '&days=' + days);
        if (!list || list.length === 0) {
            box.textContent = '没有查到实例';
            return;
        }
        box.innerHTML = list.map(function (inst) {
            return '<div class="inst-row">' +
                '<span>' + esc(inst.travelDate) + '</span>' +
                '<span class="mono">' + esc(inst.instanceNo) + '</span>' +
                '<span>' + (inst.stockPreheated ? '已预热' : '未预热') + '</span>' +
                '</div>';
        }).join('');
    } catch (e) {
        box.className = 'ops-result ops-result-error';
        box.textContent = '查询失败：' + e.message;
    }
}

async function preheatInstance() {
    const instanceNo = $('#ops-preheat-instance').value.trim();
    const box = $('#ops-preheat-result');
    if (!instanceNo) {
        toast('请输入实例号，如 G652-20261117');
        return;
    }
    box.className = 'ops-result';
    box.textContent = '预热中...';
    try {
        const result = await api('/api/instances/' + encodeURIComponent(instanceNo) + '/preheat', {
            method: 'POST'
        });
        box.textContent = result ? '预热完成' : '预热未执行（实例可能不存在）';
    } catch (e) {
        box.className = 'ops-result ops-result-error';
        box.textContent = '预热失败：' + e.message;
    }
}

async function ensureWindow() {
    const box = $('#ops-ensure-result');
    box.className = 'ops-result';
    box.textContent = '处理中...';
    try {
        const count = await api('/api/instances/ensure?days=7', {method: 'POST'});
        box.textContent = '本次新建实例 ' + (count === null || count === undefined ? 0 : count) + ' 个';
    } catch (e) {
        box.className = 'ops-result ops-result-error';
        box.textContent = '补齐失败：' + e.message;
    }
}

async function cleanupInstances() {
    const delayDays = Number($('#ops-delay').value) || 0;
    const box = $('#ops-cleanup-result');
    box.className = 'ops-result';
    box.textContent = '清理中...';
    try {
        const count = await api('/api/instances/cleanup?delayDays=' + delayDays, {method: 'POST'});
        box.textContent = '清理实例 ' + (count === null || count === undefined ? 0 : count) + ' 个';
    } catch (e) {
        box.className = 'ops-result ops-result-error';
        box.textContent = '清理失败：' + e.message;
    }
}

/* ============================================================
   事件绑定与启动
   ============================================================ */
function bindEvents() {
    $('#btn-login').addEventListener('click', doLogin);
    $('#btn-register').addEventListener('click', doRegister);
    $('#btn-to-register').addEventListener('click', function () {
        $('#login-panel').classList.add('hidden');
        $('#register-panel').classList.remove('hidden');
        $('#login-error').textContent = '';
    });
    $('#btn-to-login').addEventListener('click', function () {
        $('#register-panel').classList.add('hidden');
        $('#login-panel').classList.remove('hidden');
        $('#register-error').textContent = '';
    });
    $('#login-password').addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
            doLogin();
        }
    });
    $('#btn-logout').addEventListener('click', doLogout);

    document.querySelectorAll('.nav-item').forEach(function (btn) {
        btn.addEventListener('click', function () {
            switchPage(btn.dataset.page);
        });
    });
    document.querySelectorAll('[data-close]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            closeModal(btn.dataset.close);
        });
    });
    document.querySelectorAll('.modal-mask').forEach(function (mask) {
        mask.addEventListener('click', function (e) {
            if (e.target === mask) {
                mask.classList.add('hidden');
            }
        });
    });

    bindStationCombo('from-station');
    bindStationCombo('to-station');
    $('#btn-search').addEventListener('click', doSearch);
    $('#from-station').addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
            doSearch();
        }
    });
    $('#to-station').addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
            doSearch();
        }
    });
    $('#btn-swap').addEventListener('click', function () {
        const from = $('#from-station').value;
        $('#from-station').value = $('#to-station').value;
        $('#to-station').value = from;
    });

    $('#btn-submit-order').addEventListener('click', submitOrder);
    $('#btn-confirm-pay').addEventListener('click', confirmPay);

    $('#btn-refresh-orders').addEventListener('click', loadOrders);
    $('#btn-submit-refund').addEventListener('click', submitRefund);

    $('#btn-add-passenger').addEventListener('click', function () {
        openPassengerModal(null);
    });
    $('#btn-save-passenger').addEventListener('click', savePassenger);
    $('#passenger-search').addEventListener('input', renderPassengerList);
    $('#order-passenger-search').addEventListener('input', renderPassengerSelect);

    $('#btn-ops-window').addEventListener('click', loadInstanceWindow);
    $('#btn-ops-preheat').addEventListener('click', preheatInstance);
    $('#btn-ops-ensure').addEventListener('click', ensureWindow);
    $('#btn-ops-cleanup').addEventListener('click', cleanupInstances);
}

async function init() {
    bindEvents();
    renderDateBar();
    renderHotRoutes();

    if (state.accessToken) {
        try {
            await loadMe();
            showApp();
            switchPage('book');
            loadStations();
            return;
        } catch (e) {
            // 令牌失效，退回登录页
        }
    }
    showLogin();
}

document.addEventListener('DOMContentLoaded', init);

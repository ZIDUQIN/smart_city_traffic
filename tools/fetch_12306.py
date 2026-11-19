#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
从 12306 抓取真实车次数据，生成可直接执行的 SQL 文件。

抓取内容：
  车次号、始发/终到站、出发/到达时刻、历时、以及真实经停站（用于拆段）

输出：
  sql/08_train_data.sql

用法：
  python tools/fetch_12306.py

说明：
  - 只用标准库 urllib + cookiejar，无需安装第三方包
  - 12306 需要先拿 Cookie（访问 leftTicket/init），否则直接请求会被反爬拦截
  - 生成的 SQL 是幂等的：开头会先删掉这批车次的旧数据，可重复执行
"""

import http.cookiejar
import json
import re
import sys
import time
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

BASE = "https://kyfw.12306.cn"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

# 查询用的运营日期（12306 只允许查未来一段时间内的票，取一个未来日期即可）
QUERY_DATE = "2026-11-20"

# 目标条数
TARGET_COUNT = 100

# 每个 OD 最多取多少条，避免单一线路占满
PER_OD_LIMIT = 8

# 车型过滤：只保留有商务/一等/二等座的高铁动车
TRAIN_PREFIX = ("G", "D", "C")

# 席别容量（沿用现有 G1001 的配置）
CAPACITY = {"SECOND_CLASS": 50, "FIRST_CLASS": 20, "BUSINESS": 5}

# 票价系数：二等座按每分钟 1.5 元估算，一等 1.6 倍，商务 3.0 倍
SECOND_PER_MINUTE = 1.5
FIRST_RATE = 1.6
BUSINESS_RATE = 3.0

# 要抓取的 OD 组合（用车站中文名，脚本自动翻译成电报码）
OD_LIST = [
    ("西安北", "北京西"),
    ("西安北", "上海虹桥"),
    ("西安北", "广州南"),
    ("西安北", "成都东"),
    ("西安北", "郑州东"),
    ("西安北", "武汉"),
    ("西安北", "兰州西"),
    ("北京西", "上海虹桥"),
    ("北京南", "天津南"),
    ("上海虹桥", "杭州东"),
    ("成都东", "重庆北"),
    ("广州南", "深圳北"),
    ("郑州东", "武汉"),
    ("北京西", "广州南"),
    ("西安北", "太原南"),
    ("北京西", "郑州东"),
    ("北京西", "石家庄"),
    ("上海虹桥", "南京南"),
    ("上海虹桥", "苏州北"),
    ("广州南", "长沙南"),
    ("深圳北", "厦门北"),
    ("杭州东", "南京南"),
    ("郑州东", "石家庄"),
    ("成都东", "西安北"),
]

# ============================== HTTP 基础 ==============================

cookie_jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(
    urllib.request.HTTPCookieProcessor(cookie_jar)
)


def http_get(url, referer=None):
    """带浏览器特征发 GET 请求，返回 utf-8 文本。"""
    headers = {
        "User-Agent": UA,
        "Referer": referer or (BASE + "/otn/leftTicket/init"),
        "X-Requested-With": "XMLHttpRequest",
        "Accept": "application/json, text/javascript, */*; q=0.01",
        "Accept-Language": "zh-CN,zh;q=0.9",
    }
    req = urllib.request.Request(url, headers=headers)
    return opener.open(req, timeout=30).read().decode("utf-8", "ignore")


# ============================== 车站字典 ==============================


def load_station_dict():
    """
    下载 12306 官方车站字典，解析出「电报码 -> 站名」和「站名 -> 电报码」。
    station_name.js 每条形如： @bjb|北京北|VAP|beijingbei|bjb|0
    字段：[0]=拼音简写 [1]=中文名 [2]=电报码 [3]=全拼 [4]=拼音简写 [5]=序号
    """
    raw = http_get(BASE + "/otn/resources/js/framework/station_name.js")
    code_to_name = {}
    name_to_code = {}
    for chunk in raw.split("@"):
        fields = chunk.split("|")
        if len(fields) < 4:
            continue
        name = fields[1].strip()
        code = fields[2].strip()
        if not name or not code:
            continue
        # 同名车站取第一个（例如个别重名站，业务上用主站即可）
        code_to_name.setdefault(code, name)
        name_to_code.setdefault(name, code)
    return code_to_name, name_to_code


# ============================== 抓取 ==============================


def query_left_ticket(from_code, to_code):
    """查两站之间的余票列表，返回 12306 原始的 result 数组（每项是 | 分隔的字符串）。"""
    url = (
        BASE + "/otn/leftTicket/query"
        "?leftTicketDTO.train_date=" + QUERY_DATE
        + "&leftTicketDTO.from_station=" + from_code
        + "&leftTicketDTO.to_station=" + to_code
        + "&purpose_codes=ADULT"
    )
    try:
        payload = json.loads(http_get(url))
    except Exception as exc:
        print("  [警告] 余票查询失败 %s->%s: %s" % (from_code, to_code, exc))
        return []
    data = payload.get("data") or {}
    return data.get("result") or []


def parse_left_row(row, from_code, to_code):
    """
    解析一条余票记录。
    字段位置（12306 固定格式）：
      [2] 车次内部编号  [3] 车次号  [6] 出发站电报码  [7] 到达站电报码
      [8] 出发时刻      [9] 到达时刻  [10] 历时
    """
    fields = row.split("|")
    if len(fields) < 11:
        return None

    train_code = fields[3].strip()
    if not train_code or not train_code.startswith(TRAIN_PREFIX):
        return None

    inner_no = fields[2].strip()
    if not inner_no:
        return None

    depart = fields[8].strip()
    arrive = fields[9].strip()
    duration = fields[10].strip()
    if not re.match(r"^\d{2}:\d{2}$", depart):
        return None
    if not re.match(r"^\d{2}:\d{2}$", arrive):
        return None

    return {
        "train_code": train_code,
        "inner_no": inner_no,
        "from_code": fields[6].strip() or from_code,
        "to_code": fields[7].strip() or to_code,
        "depart": depart,
        "arrive": arrive,
        "duration": duration,
    }


def query_stops(inner_no, from_code, to_code):
    """查某趟车的全部经停站。返回 list，每项含 station_name / arrive_time / start_time / stopover_time。"""
    url = (
        BASE + "/otn/czxx/queryByTrainNo"
        "?train_no=" + inner_no
        + "&from_station_telecode=" + from_code
        + "&to_station_telecode=" + to_code
        + "&depart_date=" + QUERY_DATE
    )
    try:
        payload = json.loads(http_get(url))
    except Exception as exc:
        print("  [警告] 经停站查询失败 %s: %s" % (inner_no, exc))
        return []
    data = payload.get("data") or {}
    return data.get("data") or []


def pick_three_stops(stops, from_name, to_name):
    """
    从经停站序列里，定位「出发站」和「到达站」，取它们中间的第一个真实经停站。
    这样每个车次就有 3 个站、2 个段，和现有 G1001 的结构一致。
    """
    idx_from = -1
    idx_to = -1
    for i, stop in enumerate(stops):
        if stop.get("station_name") == from_name and idx_from < 0:
            idx_from = i
            continue
        if stop.get("station_name") == to_name and idx_from >= 0:
            idx_to = i
            break

    # 必须至少有三个站，否则拆不出两个段
    if idx_from < 0 or idx_to < idx_from + 2:
        return None

    return stops[idx_from], stops[idx_from + 1], stops[idx_to]


# ============================== 工具函数 ==============================


def to_minutes(hhmm):
    """'08:30' 或 '08:30:00' -> 510"""
    if not hhmm:
        return None
    match = re.match(r"^(\d{1,2}):(\d{2})", hhmm.strip())
    if not match:
        return None
    return int(match.group(1)) * 60 + int(match.group(2))


def fmt_time(hhmm):
    """补成 HH:MM:SS，供数据库 time 字段使用；非法值返回 None。"""
    if not hhmm:
        return None
    hhmm = hhmm.strip()
    if not re.match(r"^\d{1,2}:\d{2}$", hhmm):
        return None
    parts = hhmm.split(":")
    return "%02d:%s:00" % (int(parts[0]), parts[1])


def stop_minutes_of(text):
    """'4分钟' -> 4；'----' -> 0"""
    if not text:
        return 0
    match = re.search(r"(\d+)", text)
    return int(match.group(1)) if match else 0


def city_of(station_name):
    """从站名推城市：西安北->西安、上海虹桥->上海、武汉->武汉"""
    for suffix in ("东", "西", "南", "北"):
        if station_name.endswith(suffix) and len(station_name) > 2:
            return station_name[:-1]
    for suffix in ("虹桥", "机场", "新区"):
        if station_name.endswith(suffix) and len(station_name) - len(suffix) >= 2:
            return station_name[:-len(suffix)]
    return station_name


def round10(value):
    """取整到 10 元，避免票价出现 137.5 这种数。"""
    return int(round(value / 10.0) * 10)


def price_plan(duration_minutes, first_leg_minutes):
    """
    按总历时和第一段历时的比例拆分三段票价。
    返回 {(from_seq,to_seq,seat_no): price}
    """
    total = max(duration_minutes, 20)
    ratio = max(min(first_leg_minutes / float(total), 0.95), 0.05)

    second_total = max(round10(total * SECOND_PER_MINUTE), 20)
    first_total = round10(second_total * FIRST_RATE)
    business_total = round10(second_total * BUSINESS_RATE)

    second_leg1 = max(round10(second_total * ratio), 10)
    second_leg2 = max(second_total - second_leg1, 10)

    first_leg1 = max(round10(first_total * ratio), 10)
    first_leg2 = max(first_total - first_leg1, 10)

    biz_leg1 = max(round10(business_total * ratio), 10)
    biz_leg2 = max(business_total - biz_leg1, 10)

    return {
        (1, 2, "SECOND_CLASS"): second_leg1,
        (2, 3, "SECOND_CLASS"): second_leg2,
        (1, 3, "SECOND_CLASS"): second_total,
        (1, 2, "FIRST_CLASS"): first_leg1,
        (2, 3, "FIRST_CLASS"): first_leg2,
        (1, 3, "FIRST_CLASS"): first_total,
        (1, 2, "BUSINESS"): biz_leg1,
        (2, 3, "BUSINESS"): biz_leg2,
        (1, 3, "BUSINESS"): business_total,
    }


# ============================== 主流程 ==============================


def main():
    print("[1/4] 初始化 Cookie ...")
    http_get(BASE + "/otn/leftTicket/init")
    if len(cookie_jar) == 0:
        print("  [警告] 没拿到 Cookie，后续请求可能被拦")
    else:
        print("  拿到 %d 个 Cookie" % len(cookie_jar))

    print("[2/4] 下载车站字典 ...")
    code_to_name, name_to_code = load_station_dict()
    print("  车站字典 %d 个" % len(name_to_code))

    print("[3/4] 抓取车次列表 ...")
    candidates = []
    seen_codes = set()
    for from_name, to_name in OD_LIST:
        from_code = name_to_code.get(from_name)
        to_code = name_to_code.get(to_name)
        if not from_code or not to_code:
            print("  [跳过] 车站字典里没有 %s 或 %s" % (from_name, to_name))
            continue

        rows = query_left_ticket(from_code, to_code)
        picked = 0
        for row in rows:
            parsed = parse_left_row(row, from_code, to_code)
            if not parsed:
                continue
            if parsed["train_code"] in seen_codes:
                continue
            # 排除凌晨出发的车次，保证时刻看起来正常
            depart_min = to_minutes(parsed["depart"])
            if depart_min is None or depart_min < 360 or depart_min > 1320:
                continue
            seen_codes.add(parsed["train_code"])
            parsed["from_name"] = from_name
            parsed["to_name"] = to_name
            candidates.append(parsed)
            picked += 1
            if picked >= PER_OD_LIMIT:
                break
        print("  %s -> %s：取到 %d 条（累计 %d）"
              % (from_name, to_name, picked, len(candidates)))
        time.sleep(0.6)

    print("[4/4] 逐条补经停站，生成 SQL ...")
    records = []
    for item in candidates:
        if len(records) >= TARGET_COUNT:
            break
        stops = query_stops(item["inner_no"], item["from_code"], item["to_code"])
        three = pick_three_stops(stops, item["from_name"], item["to_name"])
        if not three:
            print("  [跳过] %s 经停站不足 3 站" % item["train_code"])
            time.sleep(0.5)
            continue

        origin, middle, terminal = three
        origin_name = origin.get("station_name", item["from_name"])
        middle_name = middle.get("station_name", "")
        terminal_name = terminal.get("station_name", item["to_name"])

        origin_depart = fmt_time(origin.get("start_time"))
        middle_arrive = fmt_time(middle.get("arrive_time"))
        middle_depart = fmt_time(middle.get("start_time"))
        terminal_arrive = fmt_time(terminal.get("arrive_time"))

        if not all([origin_depart, middle_arrive, middle_depart, terminal_arrive]):
            print("  [跳过] %s 时刻不完整" % item["train_code"])
            time.sleep(0.5)
            continue

        d1 = to_minutes(middle_arrive) - to_minutes(origin_depart)
        d2 = to_minutes(terminal_arrive) - to_minutes(middle_depart)
        if d1 <= 0:
            d1 += 24 * 60
        if d2 <= 0:
            d2 += 24 * 60

        records.append({
            "train_no": item["train_code"],
            "origin_name": origin_name,
            "middle_name": middle_name,
            "terminal_name": terminal_name,
            "origin_depart": origin_depart,
            "middle_arrive": middle_arrive,
            "middle_depart": middle_depart,
            "terminal_arrive": terminal_arrive,
            "origin_stop": 0,
            "middle_stop": stop_minutes_of(middle.get("stopover_time")),
            "terminal_stop": 0,
            "leg1_minutes": d1,
            "leg2_minutes": d2,
        })
        print("  [%d] %s %s -> %s -> %s"
              % (len(records), item["train_code"], origin_name, middle_name, terminal_name))
        time.sleep(0.5)

    print("共生成 %d 条车次记录" % len(records))
    write_sql(records, name_to_code)
    print("已写入 sql/08_train_data.sql")


# ============================== 生成 SQL ==============================


def write_sql(records, name_to_code):
    """
    生成幂等 SQL：
      先删这批车次的旧数据（库存、实例、票价、经停、车次），再整批插入。
    所有外键 id 都用子查询获取，避免依赖自增序号。
    """
    if not records:
        raise SystemExit("没有抓到任何记录，未生成 SQL")

    train_nos = sorted({r["train_no"] for r in records})
    station_names = set()
    for rec in records:
        station_names.add(rec["origin_name"])
        station_names.add(rec["middle_name"])
        station_names.add(rec["terminal_name"])

    lines = []
    lines.append("-- 由 tools/fetch_12306.py 自动生成，数据来源 12306（查询日期 %s）" % QUERY_DATE)
    lines.append("-- 可重复执行：先清空这批车次的旧数据再整批插入")
    lines.append("")
    lines.append("SET NAMES utf8mb4;")
    lines.append("")

    in_list = ",".join("'%s'" % t for t in train_nos)

    lines.append("-- ========== 清理旧数据 ==========")
    lines.append("DELETE FROM `stock` WHERE train_no IN (%s);" % in_list)
    lines.append("DELETE FROM `train_instance` WHERE train_no IN (%s);" % in_list)
    lines.append("DELETE FROM `ticket_price` WHERE train_no IN (%s);" % in_list)
    lines.append("DELETE FROM `stock_capacity` WHERE train_no IN (%s);" % in_list)
    lines.append("DELETE FROM `train_station` WHERE train_id IN "
                 "(SELECT id FROM `train` WHERE train_no IN (%s));" % in_list)
    lines.append("DELETE FROM `train` WHERE train_no IN (%s);" % in_list)
    lines.append("")

    lines.append("-- ========== 手动分配 id（station/train/train_station 三张表没有自增） ==========")
    lines.append("SET @sid := (SELECT IFNULL(MAX(id), 0) FROM `station`);")
    lines.append("SET @tid := (SELECT IFNULL(MAX(id), 0) FROM `train`);")
    lines.append("SET @tsid := (SELECT IFNULL(MAX(id), 0) FROM `train_station`);")
    lines.append("")

    lines.append("-- ========== 车站 ==========")
    for idx, name in enumerate(sorted(station_names), start=1):
        # station_code 是非空且唯一的字段，优先用 12306 官方电报码；
        # 经停站接口只给中文名，字典里查不到的就临时编一个 S001 这类编号占位
        code = name_to_code.get(name) or ("S%03d" % idx)
        lines.append("SET @sid := @sid + 1;")
        lines.append(
            "INSERT INTO `station`(id, station_name, station_code, city) "
            "SELECT @sid, '%s', '%s', '%s' FROM DUAL "
            "WHERE NOT EXISTS (SELECT 1 FROM `station` "
            "WHERE station_name = '%s' OR station_code = '%s');"
            % (name, code, city_of(name), name, code)
        )
    lines.append("")

    lines.append("-- ========== 车次模板 ==========")
    for rec in records:
        lines.append("SET @tid := @tid + 1;")
        lines.append(
            "INSERT INTO `train`(id, train_no, start_station_id, end_station_id, "
            "depart_time, arrive_time, sale_start_time, sale_end_time, status) "
            "VALUES (@tid, '%s', "
            "(SELECT id FROM `station` WHERE station_name = '%s' LIMIT 1), "
            "(SELECT id FROM `station` WHERE station_name = '%s' LIMIT 1), "
            "'%s', '%s', '2026-11-01 00:00:00', '2026-12-31 23:59:59', 1);"
            % (rec["train_no"], rec["origin_name"], rec["terminal_name"],
               rec["origin_depart"], rec["terminal_arrive"])
        )
    lines.append("")

    lines.append("-- ========== 经停站（3 站 2 段） ==========")
    for rec in records:
        # 起点站：没有到达时刻
        lines.append("SET @tsid := @tsid + 1;")
        lines.append(
            "INSERT INTO `train_station`(id, train_id, station_id, station_seq, "
            "arrive_time, depart_time, stop_minutes) VALUES "
            "(@tsid, (SELECT id FROM `train` WHERE train_no = '%s' LIMIT 1), "
            "(SELECT id FROM `station` WHERE station_name = '%s' LIMIT 1), 1, NULL, '%s', 0);"
            % (rec["train_no"], rec["origin_name"], rec["origin_depart"])
        )
        # 中间站：有到达也有出发
        lines.append("SET @tsid := @tsid + 1;")
        lines.append(
            "INSERT INTO `train_station`(id, train_id, station_id, station_seq, "
            "arrive_time, depart_time, stop_minutes) VALUES "
            "(@tsid, (SELECT id FROM `train` WHERE train_no = '%s' LIMIT 1), "
            "(SELECT id FROM `station` WHERE station_name = '%s' LIMIT 1), 2, '%s', '%s', %d);"
            % (rec["train_no"], rec["middle_name"], rec["middle_arrive"],
               rec["middle_depart"], rec["middle_stop"])
        )
        # 终点站：没有出发时刻
        lines.append("SET @tsid := @tsid + 1;")
        lines.append(
            "INSERT INTO `train_station`(id, train_id, station_id, station_seq, "
            "arrive_time, depart_time, stop_minutes) VALUES "
            "(@tsid, (SELECT id FROM `train` WHERE train_no = '%s' LIMIT 1), "
            "(SELECT id FROM `station` WHERE station_name = '%s' LIMIT 1), 3, '%s', NULL, 0);"
            % (rec["train_no"], rec["terminal_name"], rec["terminal_arrive"])
        )
    lines.append("")

    lines.append("-- ========== 席位容量 ==========")
    for rec in records:
        values = []
        for seat_no, capacity in CAPACITY.items():
            values.append("('%s', '%s', 1, %d)" % (rec["train_no"], seat_no, capacity))
            values.append("('%s', '%s', 2, %d)" % (rec["train_no"], seat_no, capacity))
        lines.append(
            "INSERT INTO `stock_capacity`(train_no, seat_no, segment_no, total_capacity) "
            "VALUES %s;" % ",".join(values)
        )
    lines.append("")

    lines.append("-- ========== 票价（按历时估算，非 12306 实价） ==========")
    for rec in records:
        total_minutes = rec["leg1_minutes"] + rec["leg2_minutes"]
        plans = price_plan(total_minutes, rec["leg1_minutes"])
        rows = []
        for (from_seq, to_seq, seat_no), price in plans.items():
            rows.append("('%s', %d, %d, '%s', %.2f)"
                        % (rec["train_no"], from_seq, to_seq, seat_no, price))
        lines.append(
            "INSERT INTO `ticket_price`(train_no, from_seq, to_seq, seat_no, price) "
            "VALUES %s;" % ",".join(rows)
        )

    lines.append("")
    lines.append("SELECT COUNT(*) AS 车次总数 FROM `train`;")
    lines.append("")

    content = "\n".join(lines)
    with open("sql/08_train_data.sql", "w", encoding="utf-8", newline="\n") as fp:
        fp.write(content)


if __name__ == "__main__":
    main()

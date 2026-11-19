"""
退款对账两条"只有代码路径"的分支补测：

E. 渠道返回 PROCESSING —— 应只刷新 update_time，不重发、不消耗 retry_count、状态仍是退款中
F. 达到 max-retry(5) —— 应只告警一次，之后静默刷新 update_time，不再动状态

前置：Mock 渠道已支持通过 /api/pay/mock/refund-result 注入查询结果。
日志：断言读 logs/alarm.log（logback-spring.xml 已把告警单独落一份）。
"""
import json
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8081"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "smart-city-traffic"
ALARM_LOG = r"D:\IDEAproject\smart-city-traffic\logs\alarm.log"
TRAIN_NO, SEAT_NO = "G1001", "SECOND_CLASS"

PASS, FAIL = [], []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  PASS  " if cond else "  FAIL  ") + name + (" | " + detail if detail else ""))


def sql(q):
    r = subprocess.run([MYSQL, "-uroot", "-p123456", DB, "-e", q],
                       capture_output=True, text=True, encoding="utf-8", errors="ignore")
    return r.stdout


def one_value(q):
    """取单值查询的一行数据（跳过表头）"""
    out = sql(q).strip().split("\n")
    return out[1].strip() if len(out) > 1 else ""


def http(method, path, body=None, token=None, params=None):
    url = BASE + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = json.dumps(body).encode() if body is not None else None
    h = {}
    if data:
        h["Content-Type"] = "application/json"
    if token:
        h["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            txt = r.read().decode()
            return r.status, (json.loads(txt) if txt.startswith("{") else txt)
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            return e.code, json.loads(txt)
        except Exception:
            return e.code, txt


def login():
    _, d = http("POST", "/api/auth/login", {"username": "admin", "password": "123456"})
    return d["data"]["accessToken"]


def paid_order(token, pid):
    _, d = http("POST", "/api/orders", {"passengerId": pid, "trainNo": TRAIN_NO,
                                        "seatNo": SEAT_NO, "fromSeq": 1, "toSeq": 3}, token=token)
    order_no = (d.get("data") or {}).get("orderNo")
    if not order_no:
        raise RuntimeError("下单失败: " + str(d))
    _, p = http("POST", "/api/pay/prepay", token=token, params={"orderNo": order_no})
    http("POST", "/api/pay/mock/confirm", token=token, params={"payToken": (p.get("data") or {}).get("payToken")})
    for _ in range(20):
        time.sleep(0.3)
        _, s = http("GET", f"/api/pay/status/{order_no}", token=token)
        if (s.get("data") or {}).get("orderStatus") == "已支付":
            return order_no
    raise RuntimeError("支付未完成: " + order_no)


def make_refunding(order_no, refund_no, channel_no, retry, minutes_ago=10):
    """把订单和退款流水手动摆成"卡在退款中"的状态"""
    sql(f"UPDATE `order` SET status='退款中' WHERE order_no='{order_no}';")
    sql(f"""INSERT INTO refund_record (refund_no, order_no, payment_no, user_id, amount, fee,
            refund_amount, status, channel_refund_no, reason, callback_count, stock_restored,
            retry_count, create_time, update_time)
            VALUES ('{refund_no}', '{order_no}', NULL, 1, 100.00, 0.00, 100.00, '退款中',
                    '{channel_no}', 'branch-test', 0, 1, {retry},
                    NOW() - INTERVAL {minutes_ago} MINUTE, NOW() - INTERVAL {minutes_ago} MINUTE)""")


def wait_gone(prev, q, max_wait=80):
    """等到查询结果 != prev，返回用时秒数；超时返回 None"""
    for i in range(max_wait):
        time.sleep(1)
        if one_value(q) != prev:
            return i + 1
    return None


def main():
    token = login()
    # 预热库存（幂等）：重置数据后 Redis 是空的，不预热下单会直接失败
    http("POST", "/api/orders/preheat", token=token, params={"trainNo": TRAIN_NO})
    ts = int(time.time() * 1000)

    print("=== 场景 E：渠道返回 PROCESSING ===")
    oe = paid_order(token, 1001)
    ch_e = f"MOCKREFUND_PROC_{ts}"
    re_e = f"RE{ts}"
    make_refunding(oe, re_e, ch_e, 0)
    code, body = http("POST", "/api/pay/mock/refund-result", token=token,
                      params={"channelRefundNo": ch_e, "result": "PROCESSING"})
    print(f"  注入结果：http={code} {body}")
    check("注入接口可用", code == 200, str(body)[:120])

    before_upd = one_value(f"SELECT update_time FROM refund_record WHERE refund_no='{re_e}';")
    print(f"  处理前 update_time={before_upd}，等一轮 cron")
    used = wait_gone(before_upd, f"SELECT update_time FROM refund_record WHERE refund_no='{re_e}';")
    print(f"  update_time 已刷新，用时约 {used} 秒")

    st = one_value(f"SELECT status FROM refund_record WHERE refund_no='{re_e}';")
    rc = one_value(f"SELECT retry_count FROM refund_record WHERE refund_no='{re_e}';")
    chn = one_value(f"SELECT channel_refund_no FROM refund_record WHERE refund_no='{re_e}';")
    print(f"  status={st} retry_count={rc} channel_refund_no={chn}")
    check("E 状态仍是退款中（未被误判成功）", st == "退款中", st)
    check("E retry_count 未增加（PROCESSING 不消耗额度）", rc == "0", rc)
    check("E 渠道单号未被替换（未重发）", chn == ch_e, chn)

    print("\n=== 场景 F：达到 max-retry，只告警一次 ===")
    of = paid_order(token, 1002)
    ch_f = f"MOCKREFUND_OVER_{ts}"
    re_f = f"RF{ts}"
    make_refunding(of, re_f, ch_f, 5)
    print(f"  构造 retry_count=5、渠道单号查不到（NOT_EXIST）")

    before_upd = one_value(f"SELECT update_time FROM refund_record WHERE refund_no='{re_f}';")
    used = wait_gone(before_upd, f"SELECT update_time FROM refund_record WHERE refund_no='{re_f}';")
    print(f"  第一轮处理，用时约 {used} 秒")

    st_f = one_value(f"SELECT status FROM refund_record WHERE refund_no='{re_f}';")
    rc_f = one_value(f"SELECT retry_count FROM refund_record WHERE refund_no='{re_f}';")
    print(f"  status={st_f} retry_count={rc_f}")
    check("F 达阈值后不再改状态", st_f == "退款中", st_f)
    check("F 计数推进到 6（告警水位线，一次到位）", rc_f == "6", rc_f)

    # 日志断言：告警出现且只出现一次
    try:
        with open(ALARM_LOG, "r", encoding="utf-8", errors="ignore") as fh:
            content = fh.read()
        times1 = content.count(re_f)
        print(f"  alarm.log 中该退款单出现 {times1} 次")
        check("F 发出告警", times1 >= 1, f"出现 {times1} 次")
    except FileNotFoundError:
        check("F 发出告警", False, "alarm.log 不存在，日志配置可能未生效")

    # 再跑一轮，验证不会重复告警
    print("  回退 update_time，再等一轮，验证不会重复告警")
    sql(f"UPDATE refund_record SET update_time = NOW() - INTERVAL 10 MINUTE WHERE refund_no='{re_f}';")
    before_upd = one_value(f"SELECT update_time FROM refund_record WHERE refund_no='{re_f}';")
    used2 = wait_gone(before_upd, f"SELECT update_time FROM refund_record WHERE refund_no='{re_f}';")
    print(f"  第二轮处理，用时约 {used2} 秒")
    try:
        with open(ALARM_LOG, "r", encoding="utf-8", errors="ignore") as fh:
            content2 = fh.read()
        times2 = content2.count(re_f)
        check("F 告警只出现一次（第二轮不再刷）", times2 == times1, f"第一轮 {times1} 次，第二轮 {times2} 次")
    except FileNotFoundError:
        pass

    print("\n=== 场景 G：渠道明确失败 FAILED ===")
    og = paid_order(token, 1003)
    ch_g = f"MOCKREFUND_FAIL_{ts}"
    rg = f"RG{ts}"
    make_refunding(og, rg, ch_g, 0)
    code, body = http("POST", "/api/pay/mock/refund-result", token=token,
                      params={"channelRefundNo": ch_g, "result": "FAILED"})
    print(f"  注入 FAILED：http={code}")

    before_upd = one_value(f"SELECT update_time FROM refund_record WHERE refund_no='{rg}';")
    used_g = wait_gone(before_upd, f"SELECT update_time FROM refund_record WHERE refund_no='{rg}';")
    print(f"  第一轮处理，用时约 {used_g} 秒")

    st_g = one_value(f"SELECT status FROM refund_record WHERE refund_no='{rg}';")
    ord_g = one_value(f"SELECT status FROM `order` WHERE order_no='{og}';")
    rc_g = one_value(f"SELECT retry_count FROM refund_record WHERE refund_no='{rg}';")
    print(f"  refund_record.status={st_g}  order.status={ord_g}  retry_count={rc_g}")
    check("G 流水改为退款失败（CAS 成功）", st_g == "退款失败", st_g)
    check("G 订单仍是退款中（钱没退，不标已退款）", ord_g == "退款中", ord_g)
    check("G FAILED 不消耗重试额度（重发无意义）", rc_g == "0", rc_g)

    try:
        with open(ALARM_LOG, "r", encoding="utf-8", errors="ignore") as fh:
            c = fh.read()
        cnt = c.count("渠道退款失败")
        print(f"  alarm.log 中「渠道退款失败」共 {cnt} 次")
        check("G 发出失败告警", cnt >= 1, f"{cnt} 次")
    except FileNotFoundError:
        check("G 发出失败告警", False, "alarm.log 不存在")

    # 已改成终态，第二轮不应再被扫描（update_time 不再变化）
    before2 = one_value(f"SELECT update_time FROM refund_record WHERE refund_no='{rg}';")
    print("  再等一轮，确认终态不再被扫描")
    changed = wait_gone(before2, f"SELECT update_time FROM refund_record WHERE refund_no='{rg}';", max_wait=70)
    check("G 终态不再被扫描（update_time 未变）", changed is None,
          f"{changed} 秒后变化" if changed else "未变化")

    print("\n=== 汇总 ===")
    print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
    for f in FAIL:
        print("  未通过: " + f)


if __name__ == "__main__":
    main()

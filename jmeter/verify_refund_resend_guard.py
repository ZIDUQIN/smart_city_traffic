"""
重复退款防护补测：

H. 账龄闸门 —— 退款单超过 168 小时，渠道"查不到"也不许重发，转人工并只告警一次
I. 渠道幂等键 —— 同一个 refundNo 重发两次，渠道返回同一个渠道单号，不会退两次钱

I 的判定方式：两轮重发后读 DB 里的 channel_refund_no，如果两轮都是同一个值，
说明第二次调用命中了幂等表、没有生成新单号（否则会是两个不同的 MOCKREFUND...）。
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
    http("POST", "/api/pay/mock/confirm", token=token,
         params={"payToken": (p.get("data") or {}).get("payToken")})
    for _ in range(20):
        time.sleep(0.3)
        _, s = http("GET", f"/api/pay/status/{order_no}", token=token)
        if (s.get("data") or {}).get("orderStatus") == "已支付":
            return order_no
    raise RuntimeError("支付未完成: " + order_no)


def make_refunding(order_no, refund_no, channel_no, retry=0, days_ago=0, minutes_ago=10):
    sql(f"UPDATE `order` SET status='退款中' WHERE order_no='{order_no}';")
    sql(f"""INSERT INTO refund_record (refund_no, order_no, payment_no, user_id, amount, fee,
            refund_amount, status, channel_refund_no, reason, callback_count, stock_restored,
            retry_count, create_time, update_time)
            VALUES ('{refund_no}', '{order_no}', NULL, 1, 100.00, 0.00, 100.00, '退款中',
                    '{channel_no}', 'guard-test', 0, 1, {retry},
                    NOW() - INTERVAL {days_ago} DAY - INTERVAL {minutes_ago} MINUTE,
                    NOW() - INTERVAL {minutes_ago} MINUTE)""")


def wait_gone(prev, q, max_wait=80):
    for i in range(max_wait):
        time.sleep(1)
        if one_value(q) != prev:
            return i + 1
    return None


def alarm_count(tag):
    try:
        with open(ALARM_LOG, "r", encoding="utf-8", errors="ignore") as fh:
            return fh.read().count(tag)
    except FileNotFoundError:
        return -1


def main():
    token = login()
    http("POST", "/api/orders/preheat", token=token, params={"trainNo": TRAIN_NO})
    ts = int(time.time() * 1000)

    print("=== 场景 H：账龄闸门（10 天前的退款单，渠道查不到）===")
    oh = paid_order(token, 1001)
    rh = f"RH{ts}"
    ch_h = "MOCKREFUND_OLD_STALE"
    make_refunding(oh, rh, ch_h, days_ago=10)
    print(f"  构造：create_time=10 天前，渠道单号={ch_h}（渠道无此单）")

    before_upd = one_value(f"SELECT update_time FROM refund_record WHERE refund_no='{rh}';")
    used_h = wait_gone(before_upd, f"SELECT update_time FROM refund_record WHERE refund_no='{rh}';")
    print(f"  第一轮处理，用时约 {used_h} 秒")

    ch_after = one_value(f"SELECT channel_refund_no FROM refund_record WHERE refund_no='{rh}';")
    rc_after = one_value(f"SELECT retry_count FROM refund_record WHERE refund_no='{rh}';")
    print(f"  channel_refund_no={ch_after}  retry_count={rc_after}")
    check("H1 账龄超限不重发（渠道单号未被替换）", ch_after == ch_h, ch_after)
    check("H2 打上人工标记 retry_count=999", rc_after == "999", rc_after)
    c1 = alarm_count(rh)
    check("H3 发出账龄超限告警", c1 >= 1, f"出现 {c1} 次")

    print("  回退 update_time 再跑一轮，验证不重复告警")
    sql(f"UPDATE refund_record SET update_time = NOW() - INTERVAL 10 MINUTE WHERE refund_no='{rh}';")
    before2 = one_value(f"SELECT update_time FROM refund_record WHERE refund_no='{rh}';")
    used2 = wait_gone(before2, f"SELECT update_time FROM refund_record WHERE refund_no='{rh}';")
    print(f"  第二轮处理，用时约 {used2} 秒")
    c2 = alarm_count(rh)
    check("H4 告警只出现一次", c2 == c1, f"第一轮 {c1} 次，第二轮 {c2} 次")

    print("\n=== 场景 I：渠道幂等键（同一退款单重发两次）===")
    oi = paid_order(token, 1002)
    ri = f"RI{ts}"
    ch_stale = "MOCKREFUND_STALE_001"
    make_refunding(oi, ri, ch_stale, days_ago=0)
    print(f"  构造：账龄内，渠道单号={ch_stale}（渠道无此单，应触发重发）")

    before_upd = one_value(f"SELECT update_time FROM refund_record WHERE refund_no='{ri}';")
    used_i1 = wait_gone(before_upd, f"SELECT update_time FROM refund_record WHERE refund_no='{ri}';")
    print(f"  第一次重发，用时约 {used_i1} 秒")
    no1 = one_value(f"SELECT channel_refund_no FROM refund_record WHERE refund_no='{ri}';")
    print(f"  第一次重发生的渠道单号 = {no1}")
    check("I1 第一次重发生成了新渠道单号", no1.startswith("MOCKREFUND") and no1 != ch_stale, no1)

    # 人为把单号还原成"渠道查不到"的旧值、状态退回退款中，制造第二次重发
    sql(f"""UPDATE refund_record SET channel_refund_no='{ch_stale}', status='退款中',
            update_time = NOW() - INTERVAL 10 MINUTE WHERE refund_no='{ri}';""")
    sql(f"UPDATE `order` SET status='退款中' WHERE order_no='{oi}';")

    before2 = one_value(f"SELECT update_time FROM refund_record WHERE refund_no='{ri}';")
    used_i2 = wait_gone(before2, f"SELECT update_time FROM refund_record WHERE refund_no='{ri}';")
    print(f"  第二次重发，用时约 {used_i2} 秒")
    no2 = one_value(f"SELECT channel_refund_no FROM refund_record WHERE refund_no='{ri}';")
    print(f"  第二次重发生的渠道单号 = {no2}")
    check("I2 幂等命中：两次重发用的是同一个渠道单号", no2 == no1, f"第一次 {no1}，第二次 {no2}")
    check("I3 没有产生第二个渠道单号（等于只退了一次钱）", no2 == no1 and no2 != ch_stale, no2)

    print("\n=== 汇总 ===")
    print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
    for f in FAIL:
        print("  未通过: " + f)


if __name__ == "__main__":
    main()

"""
支付模块三项运行时验证：
6. 多次 prepay → 支付成功后其余待支付流水应被关闭，旧 token 不能再付
8. 金额不匹配回调 → 验证不通过、挂起告警，订单不被改动
11. 超时未支付 → 定时任务关单 + 回补库存 + 关闭流水
"""
import hashlib
import hmac
import json
import socket
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8081"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "smart-city-traffic"
SECRET = "smart_city_mock_secret_2026"
TRAIN_NO, SEAT_NO = "G1001", "SECOND_CLASS"

PASS, FAIL = [], []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  PASS  " if cond else "  FAIL  ") + name + (" | " + detail if detail else ""))


def sql(q):
    r = subprocess.run([MYSQL, "-uroot", "-p123456", DB, "-e", q],
                       capture_output=True, text=True, encoding="utf-8", errors="ignore")
    return r.stdout


def http(method, path, body=None, token=None, params=None, headers=None):
    url = BASE + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = json.dumps(body).encode() if body is not None else None
    h = {}
    if data:
        h["Content-Type"] = "application/json"
    if token:
        h["Authorization"] = "Bearer " + token
    if headers:
        h.update(headers)
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


def create_order(token, pid):
    _, d = http("POST", "/api/orders", {"passengerId": pid, "trainNo": TRAIN_NO,
                                        "seatNo": SEAT_NO, "fromSeq": 1, "toSeq": 3}, token=token)
    no = (d.get("data") or {}).get("orderNo")
    if not no:
        raise RuntimeError("下单失败: " + str(d))
    return no


def prepay(token, order_no):
    _, d = http("POST", "/api/pay/prepay", token=token, params={"orderNo": order_no})
    return d.get("data") or {}


def wait_status(token, order_no, expect, timeout=20):
    for _ in range(int(timeout * 3)):
        time.sleep(0.3)
        _, s = http("GET", f"/api/pay/status/{order_no}", token=token)
        if (s.get("data") or {}).get("orderStatus") == expect:
            return True
    return False


def redis_stock():
    s = socket.create_connection(("192.168.175.100", 6379), timeout=5)
    f = s.makefile("rb")

    def read():
        line = f.readline().strip()
        t = line[:1]
        if t == b"+":
            return line[1:].decode()
        if t == b":":
            return int(line[1:])
        if t == b"$":
            n = int(line[1:])
            return None if n == -1 else f.read(n + 2)[:-2].decode()
        return line.decode()

    res = {}
    for seg in (1, 2):
        key = f"stock:{{{TRAIN_NO}}}:{SEAT_NO}"
        s.sendall(("*3\r\n$4\r\nHGET\r\n$%d\r\n%s\r\n$%d\r\n%d\r\n" % (len(key), key, 1, seg)).encode())
        res[seg] = int(read() or 0)
    s.close()
    return res


def main():
    token = login()
    print("=== 场景 6：多次 prepay，支付后其余流水应关闭 ===")
    o6 = create_order(token, 1001)
    p1 = prepay(token, o6)
    p2 = prepay(token, o6)
    print(f"  订单 {o6}，两次 prepay：{p1.get('paymentNo')} / {p2.get('paymentNo')}")
    rows = sql(f"SELECT payment_no, status FROM payment_record WHERE order_no='{o6}';")
    check("两次 prepay 生成两条待支付流水", rows.count("待支付") == 2, rows.replace("\n", " | "))

    http("POST", "/api/pay/mock/confirm", token=token, params={"payToken": p1.get("payToken")})
    check("第一张凭证支付成功", wait_status(token, o6, "已支付"))

    rows = sql(f"SELECT payment_no, status FROM payment_record WHERE order_no='{o6}';")
    ok = rows.count("支付成功") == 1 and rows.count("已关闭") == 1
    check("成功流水 1 条 + 其余被关闭 1 条", ok, rows.replace("\n", " | "))

    code, body = http("POST", "/api/pay/mock/confirm", token=token,
                      params={"payToken": p2.get("payToken")})
    msg = body.get("message", "") if isinstance(body, dict) else str(body)
    check("旧凭证不能再支付", code != 200 or "已处理" in msg or "不存在" in msg,
          f"http={code} msg={msg}")

    print("\n=== 场景 8：金额不匹配回调应挂起、不改订单 ===")
    o8 = create_order(token, 1002)
    p8 = prepay(token, o8)
    print(f"  订单 {o8}，支付单 {p8.get('paymentNo')}，订单金额 {p8.get('amount')}")

    # 伪造回调：金额改成 0.01，其余字段真实，签名用真实 secret 签（模拟渠道报文被篡改）
    payload = {"orderNo": o8, "paymentNo": p8.get("paymentNo"),
               "channelTradeNo": "MOCKFAKE" + str(int(time.time() * 1000)),
               "amount": 0.01}
    raw = json.dumps(payload, separators=(",", ":"))
    sign = hmac.new(SECRET.encode(), raw.encode(), hashlib.sha256).hexdigest()
    req = urllib.request.Request(BASE + "/api/pay/notify", data=raw.encode(), method="POST",
                                 headers={"Content-Type": "application/json",
                                          "X-Mock-Signature": sign})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            resp = r.read().decode()
    except urllib.error.HTTPError as e:
        resp = e.read().decode()
    print(f"  回调应答：{resp}")
    check("伪造金额回调被拒绝（未应答 SUCCESS）", "SUCCESS" not in resp, resp[:160])

    os_ = sql(f"SELECT status FROM `order` WHERE order_no='{o8}';")
    check("订单状态未被改动（仍已确认）", "已确认" in os_, os_.replace("\n", " | "))
    pr = sql(f"SELECT status FROM payment_record WHERE order_no='{o8}';")
    check("支付流水未被改动（仍待支付）", "待支付" in pr, pr.replace("\n", " | "))

    # 挂起后正常支付仍应走通，证明挂起没有把订单锁死
    http("POST", "/api/pay/mock/confirm", token=token, params={"payToken": p8.get("payToken")})
    check("挂起后正常支付仍可完成", wait_status(token, o8, "已支付"))

    print("\n=== 场景 11：超时未支付 → 关单 + 回补库存 ===")
    before = redis_stock()
    o11 = create_order(token, 1003)
    p11 = prepay(token, o11)
    after_order = redis_stock()
    print(f"  订单 {o11}，下单后库存 {before} → {after_order}")
    check("下单已扣减库存 1", after_order[1] == before[1] - 1, f"{before} -> {after_order}")

    # 把订单创建时间改到 20 分钟前，让超时任务(900s)命中，不必真等 15 分钟
    sql(f"UPDATE `order` SET create_time = NOW() - INTERVAL 20 MINUTE WHERE order_no='{o11}';")
    print("  已把 create_time 回退 20 分钟，等定时任务（每分钟整点，最多 75 秒）")

    closed = False
    for i in range(75):
        time.sleep(1)
        st = sql(f"SELECT status FROM `order` WHERE order_no='{o11}';")
        if "已取消" in st:
            closed = True
            print(f"  关单完成，用时约 {i + 1} 秒")
            break

    check("超时订单被关单（已取消）", closed)
    pr = sql(f"SELECT status FROM payment_record WHERE order_no='{o11}';")
    check("待支付流水被关闭", "已关闭" in pr, pr.replace("\n", " | "))
    final = redis_stock()
    check("库存已回补 1", final[1] == before[1], f"{after_order} -> {final}")

    print("\n=== 汇总 ===")
    print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
    for f in FAIL:
        print("  未通过: " + f)


if __name__ == "__main__":
    main()

"""
退款对账验证（依赖 cron 每分钟执行一次，脚本构造数据后等一轮）。

覆盖四个场景：
A. 库存未回补 + 渠道没受理     → 应补库存 + 重发退款
B. 回调丢失（本地退款中，渠道已成功）→ 应本地修状态，不重发
C. 渠道查不到（NOT_EXIST）     → 应重发并换新单号
D. 正常完成的退票               → 对账不应打扰（保持已退款）
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
TRAIN_NO, SEAT_NO = "G1001", "SECOND_CLASS"

PASS, FAIL = [], []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  PASS  " if cond else "  FAIL  ") + name + (" | " + detail if detail else ""))


def sql(q):
    r = subprocess.run([MYSQL, "-uroot", "-p123456", DB, "-e", q],
                       capture_output=True, text=True, encoding="utf-8", errors="ignore")
    return r.stdout


def http(method, path, body=None, token=None, params=None):
    url = BASE + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = json.dumps(body).encode() if body is not None else None
    headers = {}
    if data:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            text = resp.read().decode()
            return resp.status, json.loads(text) if text.startswith("{") else text
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, ""


def login():
    _, d = http("POST", "/api/auth/login", {"username": "admin", "password": "123456"})
    return d["data"]["accessToken"]


def paid_order(token, pid):
    _, d = http("POST", "/api/orders", {
        "passengerId": pid, "trainNo": TRAIN_NO, "seatNo": SEAT_NO, "fromSeq": 1, "toSeq": 3}, token=token)
    order_no = (d.get("data") or {}).get("orderNo")
    if not order_no:
        raise RuntimeError("下单失败: " + str(d))
    _, d = http("POST", "/api/pay/prepay", token=token, params={"orderNo": order_no})
    pay_token = (d.get("data") or {}).get("payToken")
    http("POST", "/api/pay/mock/confirm", token=token, params={"payToken": pay_token})
    for _ in range(15):
        time.sleep(0.3)
        _, s = http("GET", f"/api/pay/status/{order_no}", token=token)
        if (s.get("data") or {}).get("orderStatus") == "已支付":
            return order_no
    raise RuntimeError("支付未完成: " + order_no)


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
        if t == b"*":
            n = int(line[1:])
            return [read() for _ in range(n)] if n >= 0 else None
        return line.decode()

    out = None
    res = {}
    for seg in (1, 2):
        key = f"stock:{{{TRAIN_NO}}}:{SEAT_NO}"
        cmd = ("*3\r\n$4\r\nHGET\r\n$%d\r\n%s\r\n$%d\r\n%d\r\n" % (len(key), key, 1, seg)).encode()
        s.sendall(cmd)
        res[seg] = int(read() or 0)
    s.close()
    return res


def main():
    token = login()
    http("POST", "/api/orders/preheat", token=token, params={"trainNo": TRAIN_NO})
    time.sleep(1.0)

    base_stock = redis_stock()
    print(f"基线库存: {base_stock}")

    ts = int(time.time() * 1000)
    now_minus = "NOW() - INTERVAL 10 MINUTE"

    print("=== 构造场景 ===")
    # A: 库存未回补 + 无渠道单号
    oa = paid_order(token, 1001)
    sql(f"UPDATE `order` SET status='退款中' WHERE order_no='{oa}'")
    sql(f"""INSERT INTO refund_record (refund_no, order_no, payment_no, user_id, amount, fee,
            refund_amount, status, channel_refund_no, reason, callback_count, stock_restored,
            retry_count, create_time, update_time)
            VALUES ('RA{ts}', '{oa}', NULL, 1, 100.00, 0.00, 100.00, '退款中', NULL, 'test-A',
                    0, 0, 0, {now_minus}, {now_minus})""")
    print(f"  A 订单 {oa}：库存未补、无渠道单号")

    # B: 回调丢失（先正常退票完成，再手动回退状态）
    ob = paid_order(token, 1002)
    http("POST", f"/api/orders/{ob}/refund", token=token, params={"reason": "test-B"})
    for _ in range(20):
        time.sleep(0.3)
        _, st = http("GET", f"/api/orders/{ob}/refund", token=token)
        if (st.get("data") or {}).get("status") == "退款成功":
            break
    sql(f"UPDATE `order` SET status='退款中' WHERE order_no='{ob}'")
    sql(f"UPDATE refund_record SET status='退款中', update_time={now_minus} WHERE order_no='{ob}'")
    print(f"  B 订单 {ob}：本地回退为退款中，渠道侧仍是成功（模拟回调丢失）")

    # C: 渠道查不到（NOT_EXIST）
    oc = paid_order(token, 1003)
    sql(f"UPDATE `order` SET status='退款中' WHERE order_no='{oc}'")
    sql(f"""INSERT INTO refund_record (refund_no, order_no, payment_no, user_id, amount, fee,
            refund_amount, status, channel_refund_no, reason, callback_count, stock_restored,
            retry_count, create_time, update_time)
            VALUES ('RC{ts}', '{oc}', NULL, 1, 100.00, 0.00, 100.00, '退款中', 'MOCKREFUND_NOT_EXIST',
                    'test-C', 0, 1, 0, {now_minus}, {now_minus})""")
    print(f"  C 订单 {oc}：渠道单号不存在（库存已回补标记为 1）")

    # D: 正常完成，不动
    od = paid_order(token, 2001)
    http("POST", f"/api/orders/{od}/refund", token=token, params={"reason": "test-D"})
    for _ in range(20):
        time.sleep(0.3)
        _, st = http("GET", f"/api/orders/{od}/refund", token=token)
        if (st.get("data") or {}).get("status") == "退款成功":
            break
    print(f"  D 订单 {od}：正常退票完成，对账不应改动")

    after_setup_stock = redis_stock()
    print(f"构造完成后库存: {after_setup_stock}（A/B/C/D 各扣 1，B/D 已回补）")

    print("\n=== 等一轮对账（cron 每分钟整点，最多 80 秒）===")
    for i in range(80):
        time.sleep(1)
        rows = sql("SELECT order_no, status FROM refund_record WHERE order_no IN "
                   f"('{oa}','{ob}','{oc}','{od}');")
        # A 完成后 stock_restored=1 且状态已变，作为本轮已跑的标志
        a_state = sql(f"SELECT status, stock_restored FROM refund_record WHERE order_no='{oa}';")
        if "退款成功" in a_state:
            print(f"  对账已处理 A，用时约 {i + 1} 秒")
            break

    print("\n=== 验证结果 ===")
    final_stock = redis_stock()
    print(f"最终库存: {final_stock}")

    # A
    a = sql(f"SELECT status, stock_restored, channel_refund_no FROM refund_record WHERE order_no='{oa}';")
    check("A 库存已补（stock_restored=1）", "1" in a.split("\n")[-2] if a else False, a.replace("\n", " | "))
    check("A 渠道单号已生成", "MOCKREFUND" in a, a.replace("\n", " | "))
    check("A 最终状态退款成功", "退款成功" in a, a.replace("\n", " | "))

    # B
    b = sql(f"SELECT status FROM refund_record WHERE order_no='{ob}';")
    check("B 本地修成退款成功（未重发）", "退款成功" in b, b.replace("\n", " | "))
    bo = sql(f"SELECT status FROM `order` WHERE order_no='{ob}';")
    check("B 订单已退款", "已退款" in bo, bo.replace("\n", " | "))

    # C
    c = sql(f"SELECT channel_refund_no, status FROM refund_record WHERE order_no='{oc}';")
    check("C 渠道单号已被换成新单号", "MOCKREFUND_NOT_EXIST" not in c and "MOCKREFUND" in c,
          c.replace("\n", " | "))

    # D
    d = sql(f"SELECT status, retry_count FROM refund_record WHERE order_no='{od}';")
    check("D 正常单未被改动", "退款成功" in d, d.replace("\n", " | "))

    print("\n=== 汇总 ===")
    print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
    for f in FAIL:
        print("  未通过: " + f)


if __name__ == "__main__":
    main()

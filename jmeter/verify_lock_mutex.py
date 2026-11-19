"""多实例互斥验证：制造一笔超时未支付订单，两个实例都会扫到它。

如果锁不生效，两个实例会各关单一次、各回补一次库存 → 库存被补两次（多一张票）。
锁生效时，只有一个实例动手，库存只回补一次。

判定靠三件事：
1. 订单最终是「已取消」而不是被改两次
2. DB 剩余库存回到初始值（只补一次），不是初始值 +1
3. 两个实例日志里「支付超时关单并回补」本轮只出现一次
"""
import json
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8081"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "smart-city-traffic"
LOG_8081 = r"D:\IDEAproject\smart-city-traffic\logs\app.log"
LOG_8082 = r"D:\IDEAproject\smart-city-traffic\logs-8082\app.log"
TRAIN_NO, SEAT_NO = "G1001", "SECOND_CLASS"
MARK = "支付超时关单并回补"

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
            return r.status, (json.loads(txt) if txt.strip().startswith("{") else txt)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def count_mark(path):
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as fh:
            return fh.read().count(MARK)
    except FileNotFoundError:
        return 0


def main():
    print("=== 准备 ===")
    _, d = http("POST", "/api/auth/login", {"username": "admin", "password": "123456"})
    token = d["data"]["accessToken"]
    http("POST", "/api/orders/preheat", token=token, params={"trainNo": TRAIN_NO})

    # 下单但不支付：订单停在「已确认」，占着库存
    _, o = http("POST", "/api/orders", {"passengerId": 1001, "trainNo": TRAIN_NO,
                                        "seatNo": SEAT_NO, "fromSeq": 1, "toSeq": 3}, token=token)
    order_no = (o.get("data") or {}).get("orderNo")
    if not order_no:
        raise RuntimeError("下单失败: " + str(o))
    print(f"  下单成功 orderNo={order_no}，未支付，订单应为「已确认」")

    stock_before = one_value(
        "SELECT remain FROM stock WHERE train_no='G1001' AND seat_no='SECOND_CLASS' AND segment_no=1;")
    print(f"  扣减后 DB 剩余（第 1 段）= {stock_before}")

    # 把创建时间推到 1000 秒前，超过 900 秒支付时限，两个实例都会扫到
    sql(f"""UPDATE `order` SET create_time = NOW() - INTERVAL 1000 SECOND
            WHERE order_no = '{order_no}';""")
    print("  已把订单创建时间改到 1000 秒前（超过 900 秒支付时限）")

    base8081 = count_mark(LOG_8081)
    base8082 = count_mark(LOG_8082)
    print(f"  基线：8081 已有 {base8081} 次关单记录，8082 已有 {base8082} 次")

    print("\n=== 等待两个实例各跑一轮（最多 90 秒）===")
    deadline = time.time() + 90
    status = ""
    while time.time() < deadline:
        time.sleep(3)
        status = one_value(f"SELECT status FROM `order` WHERE order_no='{order_no}';")
        if status == "已取消":
            break
    print(f"  订单最终状态 = {status}")

    # 再等 10 秒，确保另一实例的潜在重复动作也已完成（如果有的话）
    time.sleep(10)

    d1 = count_mark(LOG_8081) - base8081
    d2 = count_mark(LOG_8082) - base8082
    print(f"  本轮：8081 执行 {d1} 次，8082 执行 {d2} 次，合计 {d1 + d2} 次")

    stock_after = one_value(
        "SELECT remain FROM stock WHERE train_no='G1001' AND seat_no='SECOND_CLASS' AND segment_no=1;")
    print(f"  关单后 DB 剩余 = {stock_after}")

    print("\n=== 断言 ===")
    check("订单已被超时关单（已取消）", status == "已取消", status)
    check("两个实例合计只执行了一次关单", (d1 + d2) == 1, f"8081={d1}, 8082={d2}")
    check("库存只回补一次（回到扣减前的值）",
          int(stock_after) == int(stock_before) + 1,
          f"扣减后 {stock_before} → 关单后 {stock_after}（应 +1）")
    check("库存没有被补两次（不是 +2）",
          int(stock_after) != int(stock_before) + 2,
          f"若为 {int(stock_before) + 2} 说明两个实例都动手了")

    print("\n=== 汇总 ===")
    print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
    for f in FAIL:
        print("  未通过: " + f)


if __name__ == "__main__":
    main()

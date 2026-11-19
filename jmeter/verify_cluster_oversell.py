"""集群模式下的超卖验证：100 个独立用户并发抢 50 张票。

为什么用 100 个独立账号：单用户限流是令牌桶（容量 3、每秒补 1），
同一个 token 发 100 个请求只会放过 3 个，压不到库存。
每个用户只发 1 次请求就不会触发限流，才能测到真实的并发抢票。

这验证两件事：
1. deduct.lua 在集群上执行不出 CROSSSLOT（所有 key 同槽）
2. 并发下原子性没被破坏：50 张票只能卖给 50 个人，库存不会变负
"""
import json
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8081"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "smart-city-traffic"
TRAIN_NO, SEAT_NO = "G1001", "SECOND_CLASS"
USERS = 100
USER_START, PAX_START = 3001, 5001

lock = threading.Lock()
results = []


def sql(q):
    r = subprocess.run([MYSQL, "-uroot", "-p123456", DB, "-e", q],
                       capture_output=True, text=True, encoding="utf-8", errors="ignore")
    return r.stdout


def one_value(q):
    out = sql(q).strip().split("\n")
    return out[1].strip() if len(out) > 1 else ""


def http(method, path, body=None, token=None, params=None, timeout=25):
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
        with urllib.request.urlopen(req, timeout=timeout) as r:
            txt = r.read().decode()
            return r.status, (json.loads(txt) if txt.strip().startswith("{") else txt)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return 0, str(e)


def login_all():
    tokens = []
    for i in range(USERS):
        username = f"load{i + 1:03d}"
        _, d = http("POST", "/api/auth/login", {"username": username, "password": "123456"})
        if isinstance(d, dict):
            tk = (d.get("data") or {}).get("accessToken")
            if tk:
                tokens.append(tk)
    return tokens


def buy(token, passenger_id):
    code, resp = http("POST", "/api/orders", {
        "passengerId": passenger_id, "trainNo": TRAIN_NO, "seatNo": SEAT_NO,
        "fromSeq": 1, "toSeq": 3}, token=token)
    # 关键：本项目业务失败也是 HTTP 200 + code 200，真正的结果在 data.status。
    # "已确认"才是抢到票，"已取消"是被拦下的正常响应（余票不足/判重等）。
    status, message = "", ""
    if isinstance(resp, dict):
        data = resp.get("data") or {}
        status = data.get("status") or ""
        message = data.get("message") or resp.get("message") or ""
    with lock:
        results.append((status, message))


def main():
    print("=== 准备：登录 100 个压测账号 ===")
    tokens = login_all()
    print(f"  成功登录 {len(tokens)}/{USERS}")
    if not tokens:
        raise RuntimeError("没有拿到任何 token，检查 sql/06_load_test_users.sql 是否已执行")

    remain_before = one_value(
        "SELECT remain FROM stock WHERE train_no='G1001' AND seat_no='SECOND_CLASS' AND segment_no=1;")
    print(f"  抢票前第 1 段剩余 = {remain_before} 张（请求数 {len(tokens)}）")

    print("\n=== 同时发起下单 ===")
    t0 = time.time()
    # token 与乘车人一一对应：第 i 个用户（3001+i）持有乘客（5001+i）
    threads = [threading.Thread(target=buy, args=(tokens[i], PAX_START + i)) for i in range(len(tokens))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.time() - t0
    print(f"  耗时 {elapsed:.2f}s，QPS≈{len(tokens) / elapsed:.0f}")

    ok = sum(1 for s, _ in results if s == "已确认")
    other = [(s, m) for s, m in results if s != "已确认"]
    reasons = {}
    for s, m in other:
        key = f"{s}: {m}" if s else m
        reasons[key] = reasons.get(key, 0) + 1
    print(f"  抢到票 {ok} 笔，未抢到 {len(other)} 笔")
    for m, n in sorted(reasons.items(), key=lambda x: -x[1]):
        print(f"    未抢到原因: {m} × {n}")

    remain_after = one_value(
        "SELECT remain FROM stock WHERE train_no='G1001' AND seat_no='SECOND_CLASS' AND segment_no=1;")
    print(f"  抢票后第 1 段剩余 = {remain_after}")

    print("\n=== 断言 ===")
    p1 = ok == int(remain_before)
    p2 = int(remain_after) == 0
    p3 = int(remain_after) >= 0
    print(f"  {'PASS' if p1 else 'FAIL'}  抢到票的笔数正好等于票数（无超卖、无浪费）| 抢到 {ok}，票数 {remain_before}")
    print(f"  {'PASS' if p2 else 'FAIL'}  库存扣到 0 | 剩余 {remain_after}")
    print(f"  {'PASS' if p3 else 'FAIL'}  库存没有变负 | 剩余 {remain_after}")


if __name__ == "__main__":
    main()

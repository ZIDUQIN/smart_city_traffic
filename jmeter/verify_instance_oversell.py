"""实例化改造后的集群并发压测：100 个独立用户并发抢同一实例的 50 张票。

为什么用 100 个独立账号：单用户限流是令牌桶（容量 3、每秒补 1），
同一个 token 发 100 个请求只会放过 3 个，压不到库存。
每个用户只发 1 次请求就不会触发限流，才能测到真实的并发抢票。

验证五件事：
1. deduct.lua 在集群下正常执行（同一实例的 key 落在同一个槽）
2. 并发下不超卖：50 张票只卖 50 张，也不浪费
3. 库存不会变负
4. 订单落库的 instance_no / travel_date 全部正确
5. 相邻日期的实例完全隔离，不被误扣
"""
import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

BASE = "http://localhost:8081"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "smart-city-traffic"

TRAIN_NO = "G652"
TRAVEL_DATE = "2026-11-20"
INSTANCE = "G652-20261120"
NEIGHBOR = "G652-20261121"
SEAT_NO = "SECOND_CLASS"

USERS = 100
USER_START = 3001
PAX_START = 5001

lock = threading.Lock()
results = []


def sql(q):
    r = subprocess.run([MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4", DB, "-e", q],
                       capture_output=True, text=True, encoding="utf-8", errors="ignore")
    return r.stdout


def one_value(q):
    out = sql(q).strip().split("\n")
    return out[1].strip() if len(out) > 1 else ""


def http(method, path, body=None, token=None, params=None, timeout=30):
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
        username = "load%03d" % (USER_START - 3000 + i)
        _, d = http("POST", "/api/auth/login", {"username": username, "password": "123456"})
        if isinstance(d, dict):
            tk = (d.get("data") or {}).get("accessToken")
            if tk:
                tokens.append(tk)
    return tokens


def buy(token, passenger_id):
    code, resp = http("POST", "/api/orders", {
        "passengerId": passenger_id,
        "trainNo": TRAIN_NO,
        "travelDate": TRAVEL_DATE,
        "seatNo": SEAT_NO,
        "fromSeq": 1,
        "toSeq": 2,
    }, token=token)
    # 本项目业务失败也是 HTTP 200 + code 200，真正的结果在 data.status
    status, message = "", ""
    if isinstance(resp, dict):
        data = resp.get("data") or {}
        status = data.get("status") or ""
        message = data.get("message") or resp.get("message") or ""
    else:
        message = str(resp)[:80]
    with lock:
        results.append((status, message))


def main():
    print("=== 准备：登录 100 个压测账号 ===")
    tokens = login_all()
    print("  成功登录 %d/%d" % (len(tokens), USERS))
    if not tokens:
        raise RuntimeError("没有拿到任何 token，检查 sql/06_load_test_users.sql 是否已执行")

    remain_before = one_value(
        "SELECT remain FROM stock WHERE instance_no='%s' AND seat_no='%s' AND segment_no=1;" % (INSTANCE, SEAT_NO))
    neighbor_before = one_value(
        "SELECT remain FROM stock WHERE instance_no='%s' AND seat_no='%s' AND segment_no=1;" % (NEIGHBOR, SEAT_NO))
    print("  目标实例 %s 第 1 段剩余 = %s 张（并发请求 %d）" % (INSTANCE, remain_before, len(tokens)))
    print("  相邻实例 %s 第 1 段剩余 = %s 张（应完全不受影响）" % (NEIGHBOR, neighbor_before))

    print("\n=== 同时发起下单 ===")
    t0 = time.time()
    threads = [threading.Thread(target=buy, args=(tokens[i], PAX_START + i)) for i in range(len(tokens))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.time() - t0
    print("  耗时 %.2fs，QPS≈%.0f" % (elapsed, len(tokens) / elapsed))

    ok = sum(1 for s, _ in results if s == "已确认")
    other = [(s, m) for s, m in results if s != "已确认"]
    reasons = {}
    for s, m in other:
        key = "%s: %s" % (s, m) if s else m
        reasons[key] = reasons.get(key, 0) + 1
    print("  抢到票 %d 笔，未抢到 %d 笔" % (ok, len(other)))
    for m, n in sorted(reasons.items(), key=lambda x: -x[1]):
        print("    未抢到原因: %s × %d" % (m, n))

    remain_after = one_value(
        "SELECT remain FROM stock WHERE instance_no='%s' AND seat_no='%s' AND segment_no=1;" % (INSTANCE, SEAT_NO))
    seg2_after = one_value(
        "SELECT remain FROM stock WHERE instance_no='%s' AND seat_no='%s' AND segment_no=2;" % (INSTANCE, SEAT_NO))
    neighbor_after = one_value(
        "SELECT remain FROM stock WHERE instance_no='%s' AND seat_no='%s' AND segment_no=1;" % (NEIGHBOR, SEAT_NO))
    confirmed_rows = one_value(
        "SELECT COUNT(*) FROM `order` WHERE instance_no='%s' AND status='已确认';" % INSTANCE)
    bad_instance = one_value(
        "SELECT COUNT(*) FROM `order` WHERE instance_no='%s' AND train_no<>'%s';" % (INSTANCE, TRAIN_NO))
    null_instance = one_value(
        "SELECT COUNT(*) FROM `order` WHERE instance_no IS NULL;")
    bad_date = one_value(
        "SELECT COUNT(*) FROM `order` WHERE instance_no='%s' AND travel_date<>'%s';" % (INSTANCE, TRAVEL_DATE))

    print("  抢票后目标实例第 1 段剩余 = %s，第 2 段剩余 = %s" % (remain_after, seg2_after))
    print("  相邻实例第 1 段剩余 = %s" % neighbor_after)
    print("  订单表该实例已确认 = %s 笔" % confirmed_rows)

    print("\n=== 断言 ===")
    checks = []

    checks.append(("抢到票的笔数正好等于票数（无超卖、无浪费）",
                   ok == int(remain_before),
                   "抢到 %d，票数 %s" % (ok, remain_before)))

    checks.append(("库存扣到 0", int(remain_after or -1) == 0, "剩余 %s" % remain_after))

    checks.append(("库存没有变负", int(remain_after or -1) >= 0, "剩余 %s" % remain_after))

    checks.append(("1→2 的单只扣第 1 段，第 2 段不动", seg2_after == "50", "第 2 段 %s" % seg2_after))

    checks.append(("订单表已确认数与抢到的笔数一致", confirmed_rows == str(ok),
                   "订单 %s 笔，接口 %d 笔" % (confirmed_rows, ok)))

    checks.append(("订单的 instance_no 全部落在目标实例，且全库无 NULL",
                   bad_instance == "0" and null_instance == "0",
                   "串实例 %s 笔，全库 NULL %s 笔" % (bad_instance, null_instance)))

    checks.append(("订单的 travel_date 全部正确", bad_date == "0", "日期异常 %s 笔" % bad_date))

    checks.append(("相邻日期实例完全隔离", neighbor_after == neighbor_before,
                   "前 %s 后 %s" % (neighbor_before, neighbor_after)))

    passed = 0
    for name, cond, detail in checks:
        print("  %s  %s | %s" % ("PASS" if cond else "FAIL", name, detail))
        passed += 1 if cond else 0

    print("\n=== 汇总 ===")
    print("  通过 %d/%d" % (passed, len(checks)))


if __name__ == "__main__":
    main()

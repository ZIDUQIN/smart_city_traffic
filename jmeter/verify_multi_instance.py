"""双实例部署验证：100 个独立用户，一半打 8081、一半打 8082，并发抢同一实例的 50 张票。

验证六件事：
1. 两个实例都能正常下单（雪花 workerId 不同，不冲突）
2. 跨实例并发下不超卖：50 张票只卖 50 张
3. 库存不会变负，且两边看到的库存一致
4. 订单号全局唯一（两个实例生成的 ID 不撞）
5. 订单实例号、日期全部正确
6. 相邻日期实例完全隔离
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

BASE_A = "http://localhost:8081"
BASE_B = "http://localhost:8082"

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "smart-city-traffic"

TRAIN_NO = "G652"
TRAVEL_DATE = "2026-11-21"
INSTANCE = "G652-20261121"
NEIGHBOR = "G652-20261122"
SEAT_NO = "SECOND_CLASS"

USERS = 100
PAX_START = 5001
# 前 50 个用户打 8081，后 50 个打 8082
SPLIT = 50

lock = threading.Lock()
results = []


def sql(q):
    r = subprocess.run([MYSQL, "-uroot", "-p123456", "--default-character-set=utf8mb4", DB, "-e", q],
                       capture_output=True, text=True, encoding="utf-8", errors="ignore")
    return r.stdout


def one_value(q):
    out = sql(q).strip().split("\n")
    return out[1].strip() if len(out) > 1 else ""


def http(method, path, body=None, token=None, base=BASE_A, timeout=30):
    url = base + path
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
    """一半账号在 8081 登录，一半在 8082 登录，顺便验证两个实例的鉴权都正常。"""
    tokens = []
    for i in range(USERS):
        username = "load%03d" % (i + 1)
        base = BASE_A if i < SPLIT else BASE_B
        _, d = http("POST", "/api/auth/login", {"username": username, "password": "123456"}, base=base)
        if isinstance(d, dict):
            tk = (d.get("data") or {}).get("accessToken")
            if tk:
                tokens.append((tk, base))
    return tokens


def buy(token, base, passenger_id, idx):
    code, resp = http("POST", "/api/orders", {
        "passengerId": passenger_id,
        "trainNo": TRAIN_NO,
        "travelDate": TRAVEL_DATE,
        "seatNo": SEAT_NO,
        "fromSeq": 1,
        "toSeq": 2,
    }, token=token, base=base)
    status, message, order_no = "", "", ""
    if isinstance(resp, dict):
        data = resp.get("data") or {}
        status = data.get("status") or ""
        message = data.get("message") or resp.get("message") or ""
        order_no = data.get("orderNo") or ""
    else:
        message = str(resp)[:80]
    with lock:
        results.append((status, message, base, order_no))


def main():
    print("=== 前置：两个实例健康检查 ===")
    for base in (BASE_A, BASE_B):
        code, d = http("GET", "/actuator/health", base=base)
        state = ""
        if isinstance(d, dict):
            state = d.get("status", "")
        print("  %s health=%s status=%s" % (base, code, state))

    print("\n=== 准备：登录 100 个压测账号（一半各打一个实例）===")
    tokens = login_all()
    print("  成功登录 %d/%d（8081 侧 %d，8082 侧 %d）"
          % (len(tokens), USERS,
             sum(1 for _, b in tokens if b == BASE_A),
             sum(1 for _, b in tokens if b == BASE_B)))
    if len(tokens) < USERS:
        raise RuntimeError("登录不全，两个实例的鉴权可能有问题")

    remain_before = one_value(
        "SELECT remain FROM stock WHERE instance_no='%s' AND seat_no='%s' AND segment_no=1;" % (INSTANCE, SEAT_NO))
    neighbor_before = one_value(
        "SELECT remain FROM stock WHERE instance_no='%s' AND seat_no='%s' AND segment_no=1;" % (NEIGHBOR, SEAT_NO))
    print("  目标实例 %s 第 1 段剩余 = %s 张" % (INSTANCE, remain_before))
    print("  相邻实例 %s 第 1 段剩余 = %s 张" % (NEIGHBOR, neighbor_before))

    print("\n=== 同时发起下单（两个实例并发）===")
    t0 = time.time()
    threads = []
    for i, (tk, base) in enumerate(tokens):
        threads.append(threading.Thread(target=buy, args=(tk, base, PAX_START + i, i)))
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.time() - t0
    print("  耗时 %.2fs，QPS≈%.0f" % (elapsed, len(tokens) / elapsed))

    ok_a = sum(1 for s, _, b, _ in results if s == "已确认" and b == BASE_A)
    ok_b = sum(1 for s, _, b, _ in results if s == "已确认" and b == BASE_B)
    total_ok = ok_a + ok_b
    other = [(s, m) for s, m, _, _ in results if s != "已确认"]
    reasons = {}
    for s, m in other:
        key = "%s: %s" % (s, m) if s else m
        reasons[key] = reasons.get(key, 0) + 1
    print("  抢到票 %d 笔（8081: %d，8082: %d），未抢到 %d 笔" % (total_ok, ok_a, ok_b, len(other)))
    for m, n in sorted(reasons.items(), key=lambda x: -x[1]):
        print("    未抢到原因: %s × %d" % (m, n))

    order_nos = [o for s, _, _, o in results if s == "已确认" and o]
    dup_in_response = len(order_nos) - len(set(order_nos))

    remain_after = one_value(
        "SELECT remain FROM stock WHERE instance_no='%s' AND seat_no='%s' AND segment_no=1;" % (INSTANCE, SEAT_NO))
    seg2_after = one_value(
        "SELECT remain FROM stock WHERE instance_no='%s' AND seat_no='%s' AND segment_no=2;" % (INSTANCE, SEAT_NO))
    neighbor_after = one_value(
        "SELECT remain FROM stock WHERE instance_no='%s' AND seat_no='%s' AND segment_no=1;" % (NEIGHBOR, SEAT_NO))
    confirmed_rows = one_value(
        "SELECT COUNT(*) FROM `order` WHERE instance_no='%s' AND status='已确认';" % INSTANCE)
    dup_order_rows = one_value(
        "SELECT COUNT(*) FROM (SELECT order_no FROM `order` WHERE instance_no='%s' "
        "GROUP BY order_no HAVING COUNT(*) > 1) t;" % INSTANCE)
    bad_date = one_value(
        "SELECT COUNT(*) FROM `order` WHERE instance_no='%s' AND travel_date<>'%s';" % (INSTANCE, TRAVEL_DATE))

    print("  抢票后目标实例第 1 段剩余 = %s，第 2 段剩余 = %s" % (remain_after, seg2_after))
    print("  相邻实例第 1 段剩余 = %s" % neighbor_after)
    print("  订单表该实例已确认 = %s 笔" % confirmed_rows)

    print("\n=== 断言 ===")
    checks = [
        ("两个实例都真实参与了抢票",
         ok_a > 0 and ok_b > 0,
         "8081 抢到 %d，8082 抢到 %d" % (ok_a, ok_b)),

        ("抢到票的总数正好等于票数（跨实例无超卖）",
         total_ok == int(remain_before),
         "抢到 %d，票数 %s" % (total_ok, remain_before)),

        ("库存扣到 0", int(remain_after or -1) == 0, "剩余 %s" % remain_after),

        ("库存没有变负", int(remain_after or -1) >= 0, "剩余 %s" % remain_after),

        ("1→2 的单只扣第 1 段，第 2 段不动", seg2_after == "50", "第 2 段 %s" % seg2_after),

        ("订单表已确认数与抢到的笔数一致", confirmed_rows == str(total_ok),
         "订单 %s 笔，接口 %d 笔" % (confirmed_rows, total_ok)),

        ("订单号全局唯一（两个实例的雪花 ID 不冲突）",
         dup_in_response == 0 and dup_order_rows == "0",
         "接口重复 %d，库内重复 %s" % (dup_in_response, dup_order_rows)),

        ("订单的 travel_date 全部正确", bad_date == "0", "日期异常 %s 笔" % bad_date),

        ("相邻日期实例完全隔离", neighbor_after == neighbor_before,
         "前 %s 后 %s" % (neighbor_before, neighbor_after)),
    ]

    passed = 0
    for name, cond, detail in checks:
        print("  %s  %s | %s" % ("PASS" if cond else "FAIL", name, detail))
        passed += 1 if cond else 0

    print("\n=== 汇总 ===")
    print("  通过 %d/%d" % (passed, len(checks)))


if __name__ == "__main__":
    main()

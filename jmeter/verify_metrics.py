"""
监控端点验证：

1. /actuator/health 能看到 DB / Redis 组件状态（排障时一眼看出谁挂了）
2. /actuator/metrics 里能看到业务指标的名字
3. 抢到票后 traffic.order.success 有值
4. 重复乘车人下单后 traffic.order.fail 带上原因标签
5. 连续快速下单触发限流后，fail 的 reason=限流 出现
6. Actuator 自带的 HTTP 指标（http_server_requests）也在
"""
import json
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8081"
TRAIN_NO, SEAT_NO = "G1001", "SECOND_CLASS"
PASS, FAIL = [], []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  PASS  " if cond else "  FAIL  ") + name + (" | " + detail if detail else ""))


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
        txt = e.read().decode()
        try:
            return e.code, json.loads(txt)
        except Exception:
            return e.code, txt


def metric(name):
    """读一个指标的当前值；不存在返回 None"""
    code, d = http("GET", f"/actuator/metrics/{name}")
    if code != 200 or not isinstance(d, dict):
        return None
    return d


def metric_with_tags(name):
    code, d = http("GET", f"/actuator/metrics/{name}")
    if code != 200 or not isinstance(d, dict):
        return None
    return d


def login():
    _, d = http("POST", "/api/auth/login", {"username": "admin", "password": "123456"})
    return d["data"]["accessToken"]


def main():
    token = login()

    print("=== 1. 健康检查 ===")
    code, h = http("GET", "/actuator/health")
    print(f"  http={code} status={(h or {}).get('status')}")
    check("健康端点是 UP", code == 200 and (h or {}).get("status") == "UP", str(h)[:200])
    comps = (h or {}).get("components") or {}
    print(f"  组件: {sorted(comps.keys())}")
    check("能看到 db 组件", "db" in comps, str(sorted(comps.keys())))
    check("能看到 redis 组件", "redis" in comps, str(sorted(comps.keys())))
    check("组件状态均为 UP", all((v or {}).get("status") == "UP" for v in comps.values()),
          str({k: v.get("status") for k, v in comps.items()}))

    print("\n=== 2. 指标清单 ===")
    code, m = http("GET", "/actuator/metrics")
    names = (m or {}).get("names", []) if isinstance(m, dict) else []
    print(f"  共 {len(names)} 个指标")
    traffic = [n for n in names if n.startswith("traffic.")]
    print(f"  业务指标: {traffic}")
    check("指标端点可访问", code == 200, str(code))
    check("能看到 traffic.order.success", "traffic.order.success" in names, str(traffic))
    check("能看到 traffic.alarm.total", "traffic.alarm.total" in names, str(traffic))
    check("能看到 http 自带指标", any(n.startswith("http.server") for n in names),
          str([n for n in names if n.startswith("http")][:5]))

    print("\n=== 3. 下单成功计数 ===")
    http("POST", "/api/orders/preheat", token=token, params={"trainNo": TRAIN_NO})
    _, d = http("POST", "/api/orders", {"passengerId": 1001, "trainNo": TRAIN_NO,
                                        "seatNo": SEAT_NO, "fromSeq": 1, "toSeq": 3}, token=token)
    print(f"  下单返回: {d}")
    time.sleep(0.3)
    s = metric("traffic.order.success")
    val = None
    if s:
        val = [x for x in s.get("measurements", []) if x.get("statistic") == "COUNT"]
        val = val[0]["value"] if val else None
    check("traffic.order.success 已计数", val is not None and val >= 1, f"COUNT={val}")

    print("\n=== 4. 失败按原因分标签 ===")
    # 同一乘车人再下一单 → 触发「已购买」判重
    http("POST", "/api/orders", {"passengerId": 1001, "trainNo": TRAIN_NO,
                                 "seatNo": SEAT_NO, "fromSeq": 1, "toSeq": 3}, token=token)
    time.sleep(0.3)
    f = metric_with_tags("traffic.order.fail")
    tags = (f or {}).get("availableTags", [])
    print(f"  可用标签: {tags}")
    check("traffic.order.fail 带有 reason 标签", any(t.get("tag") == "reason" for t in tags), str(tags))
    vals = [t.get("values") for t in tags if t.get("tag") == "reason"]
    print(f"  reason 取值: {vals[0] if vals else None}")
    check("reason 出现了业务原因", bool(vals and vals[0]) and len(vals[0]) >= 1,
          str(vals[0] if vals else None))

    print("\n=== 5. 限流原因可区分 ===")
    # 连打 6 次，单用户桶容量 3、每秒补 1，必然触发限流
    for _ in range(6):
        http("POST", "/api/orders", {"passengerId": 1002, "trainNo": TRAIN_NO,
                                     "seatNo": SEAT_NO, "fromSeq": 1, "toSeq": 3}, token=token)
    time.sleep(0.3)
    f2 = metric_with_tags("traffic.order.fail")
    vals2 = [t.get("values") for t in (f2 or {}).get("availableTags", []) if t.get("tag") == "reason"]
    keys = vals2[0] if vals2 else []
    print(f"  reason 现有取值: {keys}")
    check("能区分出限流原因", any("限流" in str(k) for k in keys), str(keys))

    print("\n=== 汇总 ===")
    print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
    for f in FAIL:
        print("  未通过: " + f)


if __name__ == "__main__":
    main()

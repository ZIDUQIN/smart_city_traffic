"""Redis Cluster 切换后的应用层验证。

验证顺序：
1. 应用能连上集群（health 的 redis 组件 UP）
2. preheat 能往集群写库存
3. 下单能跑通 —— 这是最关键的一步：deduct.lua 在集群模式下执行，
   如果 key 跨槽会直接 CROSSSLOT 报错，下单必然失败
4. 库存扣减正确
"""
import json
import subprocess
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8081"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "smart-city-traffic"
TRAIN_NO = "G1001"

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


def http(method, path, body=None, token=None, params=None, timeout=20):
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


def main():
    print("=== 1. 应用健康检查 ===")
    code, data = http("GET", "/actuator/health")
    redis_state = ""
    if isinstance(data, dict):
        redis_state = (data.get("components", {}).get("redis", {}) or {}).get("status", "")
    check("health 接口可访问", code == 200, str(code))
    check("health 里 redis 组件 UP", redis_state == "UP", redis_state or str(data)[:80])

    print("\n=== 2. 登录并预热 ===")
    code, d = http("POST", "/api/auth/login", {"username": "admin", "password": "123456"})
    token = ((d.get("data") or {}).get("accessToken")) if isinstance(d, dict) else None
    check("能登录（token 已写进集群）", bool(token), "登录失败: " + str(d)[:100])
    if not token:
        finish()
        return

    code, p = http("POST", "/api/orders/preheat", token=token, params={"trainNo": TRAIN_NO})
    ok = isinstance(p, dict) and p.get("code") == 200
    check("preheat 能往集群写库存", ok, str(p)[:120])

    print("\n=== 3. 下单（这一步会执行 deduct.lua）===")
    remain_before = one_value(
        "SELECT remain FROM stock WHERE train_no='G1001' AND seat_no='SECOND_CLASS' AND segment_no=1;")
    print(f"  下单前 DB 第 1 段剩余 = {remain_before}")

    ok_count, fails = 0, []
    for pid in (1001, 1002, 1003):
        code, o = http("POST", "/api/orders", {
            "passengerId": pid, "trainNo": TRAIN_NO, "seatNo": "SECOND_CLASS",
            "fromSeq": 1, "toSeq": 3}, token=token)
        if isinstance(o, dict) and o.get("code") == 200 and (o.get("data") or {}).get("orderNo"):
            ok_count += 1
        else:
            fails.append(str(o)[:100])
    check("3 笔订单全部下单成功（Lua 在集群下正常执行）", ok_count == 3,
          f"成功 {ok_count}/3" + ("；失败详情: " + "; ".join(fails) if fails else ""))

    remain_after = one_value(
        "SELECT remain FROM stock WHERE train_no='G1001' AND seat_no='SECOND_CLASS' AND segment_no=1;")
    print(f"  下单后 DB 第 1 段剩余 = {remain_after}")
    delta = (int(remain_before) - int(remain_after)) if remain_before and remain_after else None
    check("库存按订单数扣减（3 单各占 1 张）", delta == 3, f"实际扣减 {delta} 张")

    finish()


def finish():
    print("\n=== 汇总 ===")
    print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
    for f in FAIL:
        print("  未通过: " + f)


if __name__ == "__main__":
    main()

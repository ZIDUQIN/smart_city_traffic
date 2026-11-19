"""
退票退款边界验证：
1. 并发退票：同一订单两个线程同时退，只能成功 1 次（uk_order_no + CAS 双重拦截）
2. 回调金额不符：挂起失败，不改状态
3. 未退票订单查退款进度：返回"未退票"
4. 不存在的订单退票：拒绝
5. 未支付（已确认）订单退票：拒绝
6. 非本人订单退票：拒绝
"""
import hashlib
import hmac
import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8081"
HMAC_SECRET = "smart_city_mock_secret_2026"
TRAIN_NO, SEAT_NO, FROM_SEQ, TO_SEQ = "G1001", "SECOND_CLASS", 1, 3

PASS, FAIL = [], []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  PASS  " if cond else "  FAIL  ") + name + (" | " + detail if detail else ""))


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
        text = e.read().decode()
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, text


def sign(content):
    return hmac.new(HMAC_SECRET.encode(), content.encode(), hashlib.sha256).hexdigest()


def login(user="admin", pwd="123456"):
    code, data = http("POST", "/api/auth/login", {"username": user, "password": pwd})
    assert data["code"] == 200, f"登录失败 {data}"
    return data["data"]["accessToken"]


def create_order(token, passenger_id):
    code, data = http("POST", "/api/orders", {
        "passengerId": passenger_id, "trainNo": TRAIN_NO, "seatNo": SEAT_NO,
        "fromSeq": FROM_SEQ, "toSeq": TO_SEQ}, token=token)
    o = (data.get("data") or {})
    return o.get("orderNo"), o.get("status"), data.get("message")


def pay(token, order_no):
    code, data = http("POST", "/api/pay/prepay", token=token, params={"orderNo": order_no})
    pay_token = (data.get("data") or {}).get("payToken")
    http("POST", "/api/pay/mock/confirm", token=token, params={"payToken": pay_token})
    for _ in range(15):
        time.sleep(0.3)
        _, st = http("GET", f"/api/pay/status/{order_no}", token=token)
        if (st.get("data") or {}).get("orderStatus") == "已支付":
            return True
    return False


def main():
    token = login()
    # edges 脚本独立运行，需自己预热：把 DB stock 加载进 Redis
    http("POST", "/api/orders/preheat", token=token, params={"trainNo": TRAIN_NO})

    print("=== 1. 并发退票（同一订单两个线程同时退）===")
    order_no, status, msg = create_order(token, 1002)
    assert order_no, f"下单失败 {msg}"
    assert pay(token, order_no), "支付失败"
    barrier = threading.Barrier(2)
    results = []
    lock = threading.Lock()

    def do_refund():
        barrier.wait()
        code, data = http("POST", f"/api/orders/{order_no}/refund", token=token, params={"reason": "并发"})
        with lock:
            results.append((data.get("code"), data.get("message")))

    t1 = threading.Thread(target=do_refund)
    t2 = threading.Thread(target=do_refund)
    t1.start(); t2.start(); t1.join(); t2.join()
    ok_cnt = sum(1 for c, _ in results if c == 200)
    check("并发退票恰好成功 1 次", ok_cnt == 1, f"结果={results}")

    time.sleep(1.0)
    _, st = http("GET", f"/api/orders/{order_no}/refund", token=token)
    refund = st.get("data") or {}
    refund_no = refund.get("refundNo")

    print("=== 2. 回调金额不符 ===")
    payload = {"refundNo": refund_no, "orderNo": order_no,
               "channelRefundNo": "MOCKREFUND_BAD_AMOUNT", "amount": 999.00}
    raw = json.dumps(payload, separators=(",", ":"))
    req = urllib.request.Request(BASE + "/api/pay/refund/notify", data=raw.encode(),
                                 headers={"Content-Type": "application/json",
                                          "X-Mock-Signature": sign(raw)}, method="POST")
    with urllib.request.urlopen(req, timeout=10) as resp:
        body = resp.read().decode()
    check("金额不符回调被拒", "FAILURE" in body, body)
    _, st = http("GET", f"/api/orders/{order_no}/refund", token=token)
    check("金额不符后状态未被改坏",
          (st.get("data") or {}).get("status") in ("退款成功", "退款中"),
          f"{st.get('data', {}).get('status')}")

    print("=== 3. 未退票订单查退款进度 ===")
    order3, _, _ = create_order(token, 1003)
    _, st = http("GET", f"/api/orders/{order3}/refund", token=token)
    check("未退票返回「未退票」", (st.get("data") or {}).get("status") == "未退票", str(st.get("data")))

    print("=== 4. 不存在的订单退票 ===")
    code, data = http("POST", "/api/orders/999999999999/refund", token=token)
    check("不存在订单被拒", data.get("code") != 200, f"{data.get('code')} {data.get('message')}")

    print("=== 5. 未支付订单退票 ===")
    order5 = order3  # 已确认但未支付
    code, data = http("POST", f"/api/orders/{order5}/refund", token=token)
    check("未支付订单被拒", data.get("code") != 200 and "已支付" in str(data.get("message")),
          f"{data.get('code')} {data.get('message')}")

    print("=== 6. 非本人订单退票 ===")
    username = "refundtest%d" % int(time.time())
    http("POST", "/api/auth/register", {"username": username, "password": "123456", "nickname": "退票测试"})
    other_token = login(username, "123456")
    code, data = http("POST", f"/api/orders/{order3}/refund", token=other_token)
    check("非本人订单被拒", data.get("code") != 200 and "无权" in str(data.get("message")),
          f"{data.get('code')} {data.get('message')}")

    print("\n=== 汇总 ===")
    print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
    for f in FAIL:
        print("  未通过: " + f)


if __name__ == "__main__":
    main()

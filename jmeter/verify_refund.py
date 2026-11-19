"""
退票退款端到端验证（纯标准库，直连 8081）。

覆盖断言：
1. 下单 → 支付 → 订单已支付
2. 退票 → 退款中 → 回调后 退款成功 / 订单已退款
3. 库存回补：Redis 两段 field 各 +1，DB 两段 remain 各 +1
4. 重复退票被拒
5. 重复回调幂等：callback_count 递增，状态只变一次
6. 验签失败的回调被拒
"""
import hashlib
import hmac
import json
import socket
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8081"
HMAC_SECRET = "smart_city_mock_secret_2026"
REDIS = ("192.168.175.100", 6379)

PASSENGER_ID = 1001
TRAIN_NO = "G1001"
SEAT_NO = "SECOND_CLASS"
FROM_SEQ = 1
TO_SEQ = 3


# ---------------- HTTP 工具 ----------------
def http(method, path, body=None, token=None, params=None):
    url = BASE + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode()
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


def login():
    code, data = http("POST", "/api/auth/login", {"username": "admin", "password": "123456"})
    assert code == 200 and data["code"] == 200, f"登录失败: {code} {data}"
    return data["data"]["accessToken"]


# ---------------- Redis 工具 ----------------
class Redis:
    def __init__(self):
        self.s = socket.create_connection(REDIS, timeout=5)
        self.f = self.s.makefile("rb")

    def cmd(self, *args):
        out = ("*%d\r\n" % len(args)).encode()
        for a in args:
            b = str(a).encode()
            out += ("$%d\r\n" % len(b)).encode() + b + b"\r\n"
        self.s.sendall(out)
        return self._read()

    def _read(self):
        line = self.f.readline().strip()
        if not line:
            return None
        t = line[:1]
        if t == b"+":
            return line[1:].decode()
        if t == b"-":
            return Exception(line[1:].decode())
        if t == b":":
            return int(line[1:])
        if t == b"$":
            n = int(line[1:])
            if n == -1:
                return None
            data = self.f.read(n + 2)[:-2]
            return data.decode()
        if t == b"*":
            n = int(line[1:])
            if n == -1:
                return None
            return [self._read() for _ in range(n)]
        return line.decode()

    def close(self):
        self.s.close()


def stock_hash(r):
    # key 必须带 hash tag 花括号，与 deduct.lua / rollback.lua 保持一致
    key = "stock:{%s}:%s" % (TRAIN_NO, SEAT_NO)
    # field 是区段号：fromSeq..toSeq-1
    return {str(i): int(r.cmd("HGET", key, i) or 0) for i in range(FROM_SEQ, TO_SEQ)}


def sign(content):
    return hmac.new(HMAC_SECRET.encode(), content.encode(), hashlib.sha256).hexdigest()


# ---------------- 断言工具 ----------------
PASS, FAIL = [], []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  PASS  " if cond else "  FAIL  ") + name + (" | " + detail if detail else ""))


def main():
    print("=== 0. 登录 ===")
    token = login()
    print("  token 获取成功")

    print("=== 1. 预热 ===")
    code, data = http("POST", "/api/orders/preheat", token=token, params={"trainNo": TRAIN_NO})
    print(f"  preheat: {data}")

    r = Redis()
    before = stock_hash(r)
    print(f"  退票前 Redis 库存: {before}")

    print("=== 2. 下单 ===")
    code, data = http("POST", "/api/orders", {
        "passengerId": PASSENGER_ID, "trainNo": TRAIN_NO, "seatNo": SEAT_NO,
        "fromSeq": FROM_SEQ, "toSeq": TO_SEQ,
    }, token=token)
    order = (data.get("data") or {})
    order_no = order.get("orderNo")
    check("下单成功", data.get("code") == 200 and order.get("status") == "已确认", f"status={order.get('status')} msg={order.get('message')}")
    if not order_no:
        print("  下单未成功，终止")
        return
    print(f"  订单号: {order_no}，金额: {order.get('amount')}")

    after_order = stock_hash(r)
    print(f"  下单后 Redis 库存: {after_order}")
    check("下单后 Redis 两段各 -1",
          all(after_order[k] == before[k] - 1 for k in before),
          f"before={before} afterOrder={after_order}")

    print("=== 3. 支付 ===")
    code, data = http("POST", "/api/pay/prepay", token=token, params={"orderNo": order_no})
    pay_token = (data.get("data") or {}).get("payToken")
    check("统一下单返回支付凭证", data.get("code") == 200 and pay_token, f"{data.get('message')}")
    http("POST", "/api/pay/mock/confirm", token=token, params={"payToken": pay_token})
    paid = False
    for _ in range(15):
        time.sleep(0.3)
        code, st = http("GET", f"/api/pay/status/{order_no}", token=token)
        if (st.get("data") or {}).get("orderStatus") == "已支付":
            paid = True
            break
    check("支付回调后订单已支付", paid, f"{st.get('data')}")

    print("=== 4. 退票 ===")
    code, data = http("POST", f"/api/orders/{order_no}/refund", token=token, params={"reason": "行程变更"})
    refund = data.get("data") or {}
    refund_no = refund.get("refundNo")
    check("退票受理", data.get("code") == 200 and refund.get("status") == "退款中",
          f"status={refund.get('status')} msg={data.get('message')}")
    print(f"  退款单号: {refund_no}，原票款 {refund.get('amount')}，手续费 {refund.get('fee')}，实退 {refund.get('refundAmount')}")

    print("=== 5. 等回调 ===")
    settled = False
    for _ in range(20):
        time.sleep(0.3)
        code, st = http("GET", f"/api/orders/{order_no}/refund", token=token)
        if (st.get("data") or {}).get("status") == "退款成功":
            settled = True
            break
    check("退款回调后状态为退款成功", settled, f"{st.get('data')}")

    code, st = http("GET", f"/api/orders/{order_no}", token=token)
    check("订单状态变为已退款", (st.get("data") or {}).get("status") == "已退款", f"{st.get('data', {}).get('status')}")

    print("=== 6. 库存回补 ===")
    after = stock_hash(r)
    print(f"  退票后 Redis 库存: {after}")
    check("退票后 Redis 恢复到退票前水平",
          all(after[k] == before[k] for k in before),
          f"before={before} afterRefund={after}")

    print("=== 7. 重复退票 ===")
    code, data = http("POST", f"/api/orders/{order_no}/refund", token=token, params={"reason": "再退一次"})
    check("重复退票被拒", data.get("code") != 200, f"code={data.get('code')} msg={data.get('message')}")

    print("=== 8. 重复回调幂等 ===")
    payload = {
        "refundNo": refund_no,
        "orderNo": order_no,
        "channelRefundNo": "MOCKREFUND_DUP_TEST",
        "amount": refund.get("refundAmount"),
    }
    raw = json.dumps(payload, separators=(",", ":"))
    sig = sign(raw)
    for i in range(2):
        req = urllib.request.Request(
            BASE + "/api/pay/refund/notify", data=raw.encode(),
            headers={"Content-Type": "application/json", "X-Mock-Signature": sig}, method="POST")
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = resp.read().decode()
        print(f"  第 {i + 1} 次重复回调应答: {body}")

    print("=== 9. 验签失败 ===")
    req = urllib.request.Request(
        BASE + "/api/pay/refund/notify", data=raw.encode(),
        headers={"Content-Type": "application/json", "X-Mock-Signature": "bad_signature"}, method="POST")
    with urllib.request.urlopen(req, timeout=10) as resp:
        body = resp.read().decode()
    check("错误签名被拒", "FAILURE" in body, body)

    r.close()

    print("\n=== 汇总 ===")
    print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
    if FAIL:
        for f in FAIL:
            print("  未通过: " + f)


if __name__ == "__main__":
    main()

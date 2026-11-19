"""
令牌桶限流脚本验证：直接对 Redis 跑 rate_limit.lua，验证算法行为。
不依赖应用启动，纯标准库实现。
用法：python verify_rate_limit.py
"""
import os
import socket
import time

HOST = "192.168.175.100"
PORT = 6379
LUA_PATH = r"D:\IDEAproject\smart-city-traffic\src\main\resources\lua\rate_limit.lua"

CAPACITY = 3
REFILL_PER_SECOND = 1
KEY = "rate:{1}:G1001_TEST"


class RedisClient:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=5)
        self.buf = b""

    def _encode(self, args):
        out = [f"*{len(args)}\r\n".encode()]
        for a in args:
            if isinstance(a, int):
                a = str(a)
            b = a.encode()
            out.append(f"${len(b)}\r\n".encode() + b + b"\r\n")
        return b"".join(out)

    def _read_line(self):
        while b"\r\n" not in self.buf:
            self.buf += self.sock.recv(65536)
        line, self.buf = self.buf.split(b"\r\n", 1)
        return line

    def _read_n(self, n):
        while len(self.buf) < n:
            self.buf += self.sock.recv(65536)
        data, self.buf = self.buf[:n], self.buf[n:]
        self._read_line()
        return data

    def execute(self, *args):
        self.sock.sendall(self._encode(args))
        prefix = self._read_line()
        kind, payload = prefix[:1], prefix[1:]
        if kind == b"+":
            return payload.decode()
        if kind == b"-":
            raise RuntimeError(payload.decode())
        if kind == b":":
            return int(payload)
        if kind == b"$":
            n = int(payload)
            if n == -1:
                return None
            return self._read_n(n).decode()
        if kind == b"*":
            n = int(payload)
            if n == -1:
                return None
            return [self.execute() for _ in range(n)]
        raise RuntimeError("未知响应: " + prefix.decode(errors="replace"))

    def close(self):
        self.sock.close()


def main():
    with open(LUA_PATH, "r", encoding="utf-8") as f:
        script = f.read()

    client = RedisClient(HOST, PORT)
    client.execute("DEL", KEY)

    sha = client.execute("SCRIPT", "LOAD", script)
    full_refill_ms = CAPACITY * 1000 // REFILL_PER_SECOND
    ttl_ms = full_refill_ms + 60_000

    def attempt():
        return client.execute(
            "EVALSHA", sha, 1, KEY,
            str(CAPACITY), str(REFILL_PER_SECOND),
            str(int(time.time() * 1000)), str(ttl_ms),
        )

    print(f"=== 场景1：连打 {CAPACITY + 2} 次，前 {CAPACITY} 次应放行 ===")
    for i in range(1, CAPACITY + 3):
        r = attempt()
        print(f"  第 {i} 次 -> {'放行' if r == 1 else '拒绝'}  桶状态={client.execute('HMGET', KEY, 'tokens', 'last_ts')}")

    print("\n=== 场景2：等 2.2 秒后应恢复约 2 个令牌，连打 3 次（2 放 1 拒）===")
    time.sleep(2.2)
    for i in range(1, 4):
        r = attempt()
        print(f"  第 {i} 次 -> {'放行' if r == 1 else '拒绝'}  桶状态={client.execute('HMGET', KEY, 'tokens', 'last_ts')}")

    print("\n=== 场景3：key 的 TTL 是否已设置（防堆积）===")
    print("  PTTL =", client.execute("PTTL", KEY), "ms")

    print("\n=== 场景4：不存在的桶首次请求应直接放行（满桶）===")
    other_key = KEY + "_FRESH"
    client.execute("DEL", other_key)
    r = client.execute(
        "EVALSHA", sha, 1, other_key,
        str(CAPACITY), str(REFILL_PER_SECOND),
        str(int(time.time() * 1000)), str(ttl_ms),
    )
    print(f"  首次请求 -> {'放行' if r == 1 else '拒绝'}  桶状态={client.execute('HMGET', other_key, 'tokens', 'last_ts')}")

    # ---------- 场景5：全局桶的"容量"语义 ----------
    # 注意：生产配置是 500/500，但 Python 单线程发送速率本身就接近 500/秒，
    # 补充速率追得上消耗，根本测不出拒绝——这是测试工具速率不足，不是脚本问题。
    # 所以把补充速率压到 1/秒，单独验证"桶容量 = 最大突发数"这一条语义。
    g_key = "rate:global:{G1001_TEST}"
    g_capacity = 500
    g_refill_slow = 1
    g_ttl_slow = g_capacity * 1000 // g_refill_slow + 60_000
    client.execute("DEL", g_key)

    print("\n=== 场景5：全局桶容量语义（容量500、故意把补充压到1/秒）===")
    allowed = 0
    rejected = 0
    for _ in range(g_capacity + 2):
        r = client.execute(
            "EVALSHA", sha, 1, g_key,
            str(g_capacity), str(g_refill_slow),
            str(int(time.time() * 1000)), str(g_ttl_slow),
        )
        if r == 1:
            allowed += 1
        else:
            rejected += 1
    print(f"  连打 {g_capacity + 2} 次 -> 放行 {allowed} 次，拒绝 {rejected} 次（期望 500 / 2）")
    print(f"  PTTL = {client.execute('PTTL', g_key)} ms（容量回满时间 + 60s 冗余）")

    # ---------- 场景6：令牌"补充速率"语义 ----------
    print("\n=== 场景6：补充速率语义（容量1、每秒补2）===")
    s_key = "rate:refill:TEST"
    client.execute("DEL", s_key)
    s_ttl = 1 * 1000 // 2 + 60_000

    def hit_s():
        return client.execute(
            "EVALSHA", sha, 1, s_key,
            "1", "2", str(int(time.time() * 1000)), str(s_ttl),
        )

    print(f"  第1次 -> {'放行' if hit_s() == 1 else '拒绝'}（满桶，期望放行）")
    print(f"  第2次（立刻） -> {'放行' if hit_s() == 1 else '拒绝'}（令牌已用尽，期望拒绝）")
    time.sleep(0.6)
    print(f"  等0.6秒后第3次 -> {'放行' if hit_s() == 1 else '拒绝'}（约补1.2个令牌，期望放行）")

    # ---------- 场景7：全局桶与单用户桶互不干扰 ----------
    print("\n=== 场景7：全局 key 与单用户 key 互不干扰 ===")
    user_key = "rate:{1}:G1001_TEST"
    client.execute("DEL", user_key)
    # 用极慢补充速率把全局桶彻底打空
    client.execute("DEL", g_key)
    for _ in range(g_capacity):
        client.execute("EVALSHA", sha, 1, g_key,
                       str(g_capacity), str(g_refill_slow),
                       str(int(time.time() * 1000)), str(g_ttl_slow))
    g_next = client.execute("EVALSHA", sha, 1, g_key,
                            str(g_capacity), str(g_refill_slow),
                            str(int(time.time() * 1000)), str(g_ttl_slow))
    u_first = client.execute("EVALSHA", sha, 1, user_key,
                             str(CAPACITY), str(REFILL_PER_SECOND),
                             str(int(time.time() * 1000)), str(ttl_ms))
    print(f"  全局桶已打空后再打一次 -> {'放行' if g_next == 1 else '拒绝'}（期望拒绝）")
    print(f"  同一时刻单用户桶首次请求 -> {'放行' if u_first == 1 else '拒绝'}（期望放行，证明两桶独立）")

    client.execute("DEL", KEY, other_key, g_key, s_key, user_key)
    client.close()
    print("\n清理完成")


if __name__ == "__main__":
    main()

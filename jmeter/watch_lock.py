"""采样三个分布式锁的 value，观察多实例抢锁结果。

锁 value 现在是「端口:随机码」，所以能直接看出这轮是谁执行的。
纯标准库实现（复用 reset_redis.py 里的 RESP 客户端），不需要 pip 装 redis。
"""
import socket
import time

HOST, PORT = "192.168.175.100", 6379
KEYS = ["reconcile:lock", "payment:timeout:lock", "refund:reconcile:lock"]
DURATION = 100  # 秒，覆盖至少一个完整的一分钟调度周期


class RedisClient:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=5)
        self.buf = b""

    def _encode(self, args):
        out = [f"*{len(args)}\r\n".encode()]
        for a in args:
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
        raise RuntimeError("未知响应: " + prefix.decode(errors="replace"))

    def close(self):
        self.sock.close()


def main():
    c = RedisClient(HOST, PORT)
    start = time.time()
    history = {k: [] for k in KEYS}

    print(f"采样 {DURATION} 秒，每秒读一次……\n")
    while time.time() - start < DURATION:
        for k in KEYS:
            v = c.execute("GET", k)
            ttl = c.execute("TTL", k)
            if v and (not history[k] or history[k][-1][1] != v):
                history[k].append((round(time.time() - start, 1), v, ttl))
                print(f"  +{round(time.time() - start, 1):>6}s  {k} = {v}  (TTL {ttl}s)")
        time.sleep(1)
    c.close()

    print("\n=== 汇总 ===")
    ok = True
    for k in KEYS:
        owners = sorted({h[1] for h in history[k]})
        print(f"\n{k}")
        if not history[k]:
            print("  整个采样期都没出现 —— 任务可能没在跑")
            ok = False
        else:
            print(f"  出现过 {len(owners)} 个持有者: {owners}")
            for h in history[k]:
                print(f"    +{h[0]}s -> {h[1]}  (TTL {h[2]}s)")

    print("\n=== 结论 ===")
    for k in KEYS:
        owners = sorted({h[1] for h in history[k]})
        if len(owners) == 1:
            print(f"{k}: 采样期内始终是同一个实例 {owners[0]} 持有（该实例稳定抢到锁）")
        elif len(owners) > 1:
            print(f"{k}: 出现过多个持有者 {owners}（跨分钟发生过交接，属正常竞争）")
        else:
            print(f"{k}: 未观测到持有")


if __name__ == "__main__":
    main()

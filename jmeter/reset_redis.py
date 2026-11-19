"""压测重置脚本（Redis Cluster 版）：清理 G1001 的库存、已扣标记、乘车人占位。

用法：重跑 05_b2_unified_stock.sql 后执行  python reset_redis.py，再预热、再压测。
保留 auth 令牌与 snowflake worker 注册，不影响登录态。纯标准库实现，无需 pip 安装。

集群版要点：
1. 任意节点只持有部分槽，所以要先把所有主节点找出来，逐个 SCAN
2. 同一个槽的 key 在同一个节点，但 DEL 发错节点会返回 MOVED，需要跟着重定向走
"""
import socket

SEEDS = [("192.168.175.100", 7000), ("192.168.175.100", 7001), ("192.168.175.100", 7002)]
PATTERNS = ("stock:{G1001}:*", "deducted:{G1001}:*", "passenger:dedup:{G1001}:*")


class ClusterClient:
    """最小可用的集群客户端：自动跟随 MOVED 重定向。"""

    def __init__(self, host, port):
        self.sock = None
        self.buf = b""
        self._connect(host, port)

    def _connect(self, host, port):
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass
        self.sock = socket.create_connection((host, port), timeout=5)
        self.buf = b""

    def _encode(self, args):
        out = [f"*{len(args)}\r\n".encode()]
        for a in args:
            b = str(a).encode()
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

    def _decode(self, prefix):
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
            return [self._decode(self._read_line()) for _ in range(n)]
        raise RuntimeError("未知响应: " + prefix.decode(errors="replace"))

    def execute(self, *args, _retry=3):
        self.sock.sendall(self._encode(args))
        prefix = self._read_line()
        if prefix.startswith(b"-MOVED") or prefix.startswith(b"-ASK"):
            # 形如 -MOVED 2723 192.168.175.100:7003
            if _retry <= 0:
                raise RuntimeError("重定向次数过多: " + prefix.decode(errors="replace"))
            target = prefix.split()[2].decode()
            host, port = target.rsplit(":", 1)
            self._connect(host, int(port))
            return self.execute(*args, _retry=_retry - 1)
        return self._decode(prefix)

    def close(self):
        if self.sock:
            self.sock.close()

    def masters(self):
        """从 CLUSTER NODES 里解析出所有在线主节点的地址。"""
        nodes = self.execute("CLUSTER", "NODES")
        result = []
        for line in nodes.splitlines():
            parts = line.split()
            if len(parts) < 8:
                continue
            flags = parts[2]
            link = parts[7]
            if "master" in flags and "fail" not in flags and link == "connected":
                addr = parts[1].split("@")[0]
                host, port = addr.rsplit(":", 1)
                result.append((host, int(port)))
        return result


def scan_all(client, pattern):
    cursor = "0"
    keys = []
    while True:
        cursor, batch = client.execute("SCAN", cursor, "MATCH", pattern, "COUNT", "1000")
        keys.extend(batch)
        if cursor == "0":
            return keys


def main():
    client = ClusterClient(*SEEDS[0])
    masters = client.masters()
    print(f"集群主节点: {[f'{h}:{p}' for h, p in masters]}")

    found = {}
    for host, port in masters:
        c = ClusterClient(host, port)
        for pattern in PATTERNS:
            try:
                found.setdefault(pattern, []).extend(scan_all(c, pattern))
            except RuntimeError as e:
                print(f"  扫描 {host}:{port} 的 {pattern} 失败: {e}")
        c.close()

    total = 0
    for pattern in PATTERNS:
        keys = found.get(pattern, [])
        for k in keys:
            try:
                total += client.execute("DEL", k)
            except RuntimeError as e:
                print(f"  删除 {k} 失败: {e}")
        print(f"{pattern}: 清理 {len(keys)} 个")

    client.close()
    print(f"完成，共删除 {total} 个 key")


if __name__ == "__main__":
    main()

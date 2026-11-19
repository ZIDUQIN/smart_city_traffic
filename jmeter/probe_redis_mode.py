"""探测 Redis 运行模式：单实例还是集群、版本、内存策略。纯标准库实现。"""
import socket


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
        if kind == b"*":
            n = int(payload)
            if n == -1:
                return None
            return [self.execute() for _ in range(n)]
        raise RuntimeError("未知响应: " + prefix.decode(errors="replace"))

    def close(self):
        self.sock.close()


def section(info_text, name):
    """从 INFO 输出里抓一段"""
    lines = []
    hit = False
    for line in info_text.splitlines():
        if line.startswith("# "):
            hit = line[2:].strip().lower() == name.lower()
            continue
        if hit and ":" in line:
            lines.append(line)
    return lines


c = RedisClient("192.168.175.100", 6379)

info = c.execute("INFO", "server")
print("=== 版本信息 ===")
for k in ("redis_version", "os", "tcp_port", "process_id", "config_file", "executable"):
    for line in section(info, "server"):
        if line.startswith(k + ":"):
            print("  " + line)
            break

info2 = c.execute("INFO", "cluster")
print("\n=== 集群 ===")
for line in section(info2, "cluster"):
    print("  " + line)

print("\n=== 内存策略 ===")
for key in ("maxmemory", "maxmemory-policy", "appendonly"):
    try:
        print(f"  {key} = {c.execute('CONFIG', 'GET', key)}")
    except Exception as e:
        print(f"  {key} 查询失败: {e}")

print(f"\n  DBSIZE = {c.execute('DBSIZE')}")

try:
    print(f"  cluster-enabled = {c.execute('CONFIG', 'GET', 'cluster-enabled')}")
except Exception as e:
    print(f"  cluster 配置查询失败: {e}")

c.close()

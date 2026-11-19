"""删掉指定 Redis 库存缓存，让下一次查询回源到 MySQL。

用法：python redis_del.py 'stock:{D2636-20261118}:SECOND_CLASS' ...
"""
import os
import sys

import paramiko

HOST = os.environ.get("REDIS_SSH_HOST", "192.168.175.100")
USER = os.environ.get("REDIS_SSH_USER", "root")
PASSWORD = os.environ.get("REDIS_SSH_PASSWORD", "")
CONTAINER = "redis-7000"
PORT = "7000"


def main():
    keys = sys.argv[1:]
    if not keys:
        print("没有要删的 key")
        return 1
    if not PASSWORD:
        print("请先设置环境变量 REDIS_SSH_PASSWORD（不要把密码写进仓库）")
        return 1

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PASSWORD, timeout=8)
    try:
        for key in keys:
            cmd = f"docker exec {CONTAINER} redis-cli -c -p {PORT} DEL '{key}'"
            _, stdout, _ = client.exec_command(cmd)
            print(f"{key} -> {stdout.read().decode('utf-8', 'ignore').strip()}")
    finally:
        client.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())

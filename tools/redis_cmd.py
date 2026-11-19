"""通用 Redis 集群命令：SSH 到宿主机，在 redis-7000 容器里用 redis-cli -c 执行。

用法：python redis_cmd.py "HGETALL 'stock:{D2636-20261118}:SECOND_CLASS'"
      python redis_cmd.py "EXISTS 'null:train:NOEXIST999-20261118'"
只供测试脚手架使用，命令原样透传。
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
    if len(sys.argv) < 2:
        print("usage: redis_cmd.py <redis command>")
        return 1
    if not PASSWORD:
        print("请先设置环境变量 REDIS_SSH_PASSWORD（不要把密码写进仓库）")
        return 1
    command = " ".join(sys.argv[1:])
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PASSWORD, timeout=8)
    try:
        cmd = f"docker exec {CONTAINER} redis-cli -c -p {PORT} {command}"
        _, stdout, stderr = client.exec_command(cmd)
        out = stdout.read().decode("utf-8", "ignore").strip()
        err = stderr.read().decode("utf-8", "ignore").strip()
        if out:
            print(out)
        if err:
            print("STDERR:", err)
    finally:
        client.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
import os
import sys

import paramiko

host = os.environ.get("REDIS_SSH_HOST", "192.168.175.100")
user = os.environ.get("REDIS_SSH_USER", "root")
pwd = os.environ.get("REDIS_SSH_PASSWORD", "")
if not pwd:
    sys.exit("请先设置环境变量 REDIS_SSH_PASSWORD（不要把密码写进仓库）")
cmd = " ".join(sys.argv[1:])

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(host, port=22, username=user, password=pwd, timeout=8)
stdin, stdout, stderr = client.exec_command(cmd, timeout=60)
out = stdout.read().decode("utf-8", "ignore")
err = stderr.read().decode("utf-8", "ignore")
client.close()
if out:
    print(out)
if err:
    print("STDERR:", err)

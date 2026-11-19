import os

import paramiko

password = os.environ.get("REDIS_SSH_PASSWORD", "")
if not password:
    raise SystemExit("请先设置环境变量 REDIS_SSH_PASSWORD（不要把密码写进仓库）")
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
try:
    c.connect(os.environ.get("REDIS_SSH_HOST", "192.168.175.100"),
              username=os.environ.get("REDIS_SSH_USER", "root"),
              password=password, timeout=8)
except Exception as e:
    print("SSH-FAIL", e); raise SystemExit
for cmd in ["docker ps --format '{{.Names}} {{.Status}}'", "free -g | head -2"]:
    i,o,e = c.exec_command(cmd)
    print("$", cmd)
    print(o.read().decode("utf-8","ignore"))
    err = e.read().decode("utf-8","ignore")
    if err.strip(): print("ERR:", err)
c.close()

"""在虚拟机里执行命令的辅助脚本（密码认证，走 paramiko）。

用法：
    python jmeter/ssh_vm.py "uname -a"
    python jmeter/ssh_vm.py "命令1; 命令2"

凭据可用环境变量覆盖：VM_HOST / VM_USER / VM_PASS
"""
import os
import sys

import paramiko

HOST = os.getenv("VM_HOST", "192.168.175.100")
USER = os.getenv("VM_USER", "root")
PASS = os.getenv("VM_PASS", "")
if not PASS:
    raise SystemExit("请先设置环境变量 VM_PASS（不要把密码写进仓库）")


def run(cmd, timeout=60):
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, port=22, username=USER, password=PASS, timeout=10)
    try:
        stdin, stdout, stderr = client.exec_command(cmd, timeout=timeout)
        out = stdout.read().decode("utf-8", errors="replace")
        err = stderr.read().decode("utf-8", errors="replace")
        code = stdout.channel.recv_exit_status()
        return code, out, err
    finally:
        client.close()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python jmeter/ssh_vm.py \"命令\"")
        sys.exit(2)
    code, out, err = run(sys.argv[1], timeout=int(os.getenv("VM_TIMEOUT", "60")))
    if out:
        print(out)
    if err:
        print("[stderr] " + err, file=sys.stderr)
    print(f"[exit={code}]")

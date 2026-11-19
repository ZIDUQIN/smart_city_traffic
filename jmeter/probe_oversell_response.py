"""并发探测下单失败响应的真实内容（库存已为 0 时使用）。"""
import json
import threading
import urllib.error
import urllib.request
from collections import Counter

BASE = "http://localhost:8081"
TOKEN = "751606f4cb5b438189d7469c60d856e0"
N = 100

results = []
lock = threading.Lock()
barrier = threading.Barrier(N)


def one(pid):
    body = json.dumps({"passengerId": pid, "trainNo": "G1001", "seatNo": "SECOND_CLASS",
                       "fromSeq": 1, "toSeq": 3}).encode()
    req = urllib.request.Request(BASE + "/api/orders", data=body, method="POST",
                                 headers={"Content-Type": "application/json",
                                          "Authorization": "Bearer " + TOKEN})
    try:
        barrier.wait()
        with urllib.request.urlopen(req, timeout=15) as r:
            txt = r.read().decode()
            n = len(txt.encode())
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        n = len(txt.encode())
    except Exception as e:
        txt, n = "EXC:" + str(e), 0
    with lock:
        results.append((n, txt))


ts = [threading.Thread(target=one, args=(3000 + i,)) for i in range(N)]
for t in ts:
    t.start()
for t in ts:
    t.join()

print("总样本", len(results))
for n, cnt in Counter(n for n, _ in results).most_common():
    samples = {txt for m, txt in results if m == n}
    print(f"\nbytes={n} 出现 {cnt} 次")
    for s in list(samples)[:3]:
        print("   ", s[:400])

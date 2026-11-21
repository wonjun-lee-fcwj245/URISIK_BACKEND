"""실험 6 기준선: Redis를 중단했을 때 추천 API가 실제로 얼마나 매달리는지(클라이언트 타임아웃 150초).
수정 전 코드(v3 = main HEAD)에서 Redis를 멈추고 추천 요청 5건을 동시에 보내 응답 시간·상태를 기록한다."""
import csv
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import exp6_redis as e6  # noqa: E402

RAW = e6.RAW
CSV_PATH = e6.CSV_PATH


def token(i):
    req = urllib.request.Request(f'http://localhost:8080/api/auth/test-token?memberId={i}', method='POST')
    return json.load(urllib.request.urlopen(req, timeout=10))['result']['accessToken']


def call(path, tok, out, idx):
    t0 = time.time()
    try:
        req = urllib.request.Request('http://localhost:8080' + path, headers={'Authorization': 'Bearer ' + tok})
        with urllib.request.urlopen(req, timeout=150) as r:
            r.read()
            status = r.status
    except urllib.error.HTTPError as e:
        status = e.code
    except Exception as e:
        status = 'ERR:' + type(e).__name__
    out[idx] = (status, round(time.time() - t0, 1))


def main():
    os.makedirs(RAW, exist_ok=True)
    e6.start_app('v3')
    toks = [token(i) for i in range(1, 6)]
    subprocess.run(['docker', 'stop', 'urisik-redis'], capture_output=True)
    time.sleep(3)
    res = [None] * 5
    paths = ['/api/recommendations/home/safe-recipes', '/api/recommendations/home/high-score',
             '/api/recommendations/home/safe-high-score', '/api/recommendations/home/wish', '/api/recommendations/home/high-score']
    ths = [threading.Thread(target=call, args=(paths[i], toks[i], res, i)) for i in range(5)]
    [t.start() for t in ths]
    [t.join() for t in ths]
    subprocess.run(['docker', 'start', 'urisik-redis'], capture_output=True)
    print('baseline (Redis down, client timeout 150s):', res)
    json.dump(res, open(os.path.join(RAW, 'baseline-150s.json'), 'w'))
    secs = [r[1] for r in res]
    rows = [['exp6', 'baseline_150s', 'v3', 1, 'statuses', json.dumps([r[0] for r in res]), 'Redis 중단, 추천 5건 동시'],
            ['exp6', 'baseline_150s', 'v3', 1, 'seconds_min', min(secs), ''],
            ['exp6', 'baseline_150s', 'v3', 1, 'seconds_max', max(secs), '']]
    with open(CSV_PATH, 'a', newline='', encoding='utf-8') as f:
        csv.writer(f).writerows(rows)


if __name__ == '__main__':
    main()

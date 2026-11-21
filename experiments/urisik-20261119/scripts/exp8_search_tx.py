"""실험 8: 검색 트랜잭션 범위 축소(#24) 전/후 비교.
수정 전(m = main 248edca)과 수정 후(sf = perf/#24 브랜치)를 라운드마다 번갈아 띄우고,
같은 조건(외부 API stub 20ms, ES 검색)에서 검색 단독 부하를 걸어 Hikari 점유·대기 시간과 요청 p95를 잰다.
  python exp8_search_tx.py            # 3라운드(1회차 워밍업 제외 2회 집계)
검색 응답이 수정 전/후에 같은지(결과·순서)도 질의 8개로 확인한다.
"""
import csv
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
RAW = os.path.join(ROOT, 'raw', 'exp8')
sys.path.insert(0, HERE)
import exp5_connhold as e5  # noqa: E402
e5.RAW = RAW  # 실험 5 원본을 덮어쓰지 않도록 저장 위치를 바꾼다

CONDS = [('search_unique_100rps', 100, 'unique'), ('search_unique_10rps', 10, 'unique'), ('search_repeat_100rps', 100, 'repeat')]
VARIANTS = ('m', 'sf')
CHECK_KEYWORDS = ['김치찌개', '된장찌개', '두부', '삼겹살', '비빔밥', '불고기', '잡채', '김치찌게']


def switch(variant):
    env = dict(os.environ, APP_VARIANT=variant, EXP2_STUB='1')
    r = subprocess.run([sys.executable, os.path.join(HERE, 'exp2_runner.py'), 'switch', '--engine', 'elasticsearch'],
                       env=env, cwd=HERE, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if r.returncode != 0:
        raise SystemExit(r.stdout + r.stderr)
    time.sleep(8)


def token(i=1):
    req = urllib.request.Request(f'http://localhost:8080/api/auth/test-token?memberId={i}', method='POST')
    return json.load(urllib.request.urlopen(req, timeout=10))['result']['accessToken']


def fetch_search(tok, kw):
    url = 'http://localhost:8080/api/recipes/search?keyword=' + urllib.parse.quote(kw) + '&page=0&size=10'
    req = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + tok})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode('utf-8')


def response_hashes():
    tok = token()
    out = {}
    for kw in CHECK_KEYWORDS:
        body = fetch_search(tok, kw)
        out[kw] = hashlib.sha256(body.encode('utf-8')).hexdigest()[:16]
    return out


def main():
    os.makedirs(RAW, exist_ok=True)
    hashes = {}
    for run_no in (1, 2, 3):
        order = VARIANTS if run_no % 2 == 1 else VARIANTS[::-1]
        for variant in order:
            switch(variant)
            e5.restart_stub(20)
            if run_no == 3:
                hashes[variant] = response_hashes()
            for label, rate, mode in CONDS:
                vals = e5.run_cond(f'{variant}_{label}', 'search', rate, run_no, mode)
                rows = [['exp8', label, variant, run_no, k, v,
                         f'api=search;rate={rate}rps;60s;kw={mode};warmup={"yes" if run_no == 1 else "no"}'] for k, v in vals.items()]
                e5.append(rows)
                print(variant, label, 'run', run_no, json.dumps(vals), flush=True)
    same = hashes.get('m') == hashes.get('sf')
    json.dump({'hashes': hashes, 'identical': same}, open(os.path.join(RAW, 'response-hashes.json'), 'w'), ensure_ascii=False, indent=1)
    e5.append([['exp8', 'response_identical', 'm_vs_sf', 1, 'queries_identical',
                sum(1 for k in CHECK_KEYWORDS if hashes['m'][k] == hashes['sf'][k]), f'of {len(CHECK_KEYWORDS)}']])
    print('response identical:', same, flush=True)
    print('EXP8_DONE', flush=True)


if __name__ == '__main__':
    main()

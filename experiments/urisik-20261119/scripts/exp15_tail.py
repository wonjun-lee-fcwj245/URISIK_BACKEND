"""실험 15: 3.5초대 꼬리 지연의 원인 찾기. 10 RPS 캐시 미적중 검색을 요청별로 기록하면서 앱 로그를 같이 받아,
느린 요청이 생긴 시각 전후(±2초)의 앱 로그를 맞춰 본다.
  APP_VARIANT=sf python exp15_tail.py     # sf + OSIV 끔(기본)
"""
import csv
import datetime
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
RAW = os.path.join(ROOT, 'raw', 'exp15')
sys.path.insert(0, HERE)
import exp10_osiv as x10  # noqa: E402
import exp5_connhold as e5  # noqa: E402

VARIANT = os.environ.get('APP_VARIANT', 'sf')
RATE = os.environ.get('TAIL_RATE', '10')


def main():
    os.makedirs(RAW, exist_ok=True)
    x10.start_app(VARIANT)
    e5.restart_stub(20)
    csv_path = os.path.join(RAW, f'tail-{VARIANT}-{RATE}rps.csv')
    t0 = datetime.datetime.now(datetime.timezone.utc)
    env = dict(os.environ, API='search', RATE=RATE, DURATION_S='60', USERS='100', KW_MODE='unique', SALT=str(time.time_ns()))
    k6 = subprocess.run(['k6', 'run', '--no-color', '-q', '--out', 'csv=' + csv_path, os.path.join(HERE, 'exp5-connhold.js')],
                        env=env, capture_output=True, text=True)
    if k6.returncode != 0:
        raise SystemExit(f'k6 실패(returncode={k6.returncode}): 부분 CSV를 분석하지 않고 중단')
    rows = []
    for r in csv.DictReader(open(csv_path, encoding='utf-8')):
        if r['metric_name'] == 'http_req_duration' and r.get('name') == 'target':
            rows.append((float(r['timestamp']), float(r['metric_value'])))
    rows.sort()
    since = t0.strftime('%Y-%m-%dT%H:%M:%SZ')
    logs = subprocess.run(['docker', 'logs', '--timestamps', '--since', since, 'urisik-app'], capture_output=True, text=True, errors='replace')
    text = logs.stdout + logs.stderr
    open(os.path.join(RAW, f'app-{VARIANT}-{RATE}rps.log'), 'w', encoding='utf-8').write(text)
    lines = []
    for l in text.splitlines():
        m = re.match(r'(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)\.(\d+)Z\s+(.*)', l)
        if m:
            ts = datetime.datetime.strptime(m.group(1), '%Y-%m-%dT%H:%M:%S').replace(tzinfo=datetime.timezone.utc).timestamp() + float('0.' + m.group(2)[:6])
            lines.append((ts, m.group(3)))
    slow = [(t, v) for t, v in rows if v > 1500]
    print(VARIANT, 'rate', RATE, 'requests', len(rows), 'slow(>1.5s)', len(slow), flush=True)
    print('느린 요청 완료 시각(UTC)과 소요(ms):', [(datetime.datetime.fromtimestamp(t, datetime.timezone.utc).strftime('%H:%M:%S'), round(v)) for t, v in slow[:20]], flush=True)
    # 각 느린 요청의 시작 시각 = 완료 - 소요. 그 전후 2초의 앱 로그에서 WARN/ERROR/특이 줄을 보여 준다.
    shown = set()
    for t, v in slow[:3]:
        start = t - v / 1000.0
        print(f'--- 느린 요청 시작 {datetime.datetime.fromtimestamp(start, datetime.timezone.utc).strftime("%H:%M:%S")} 전후 로그 ---', flush=True)
        for ts, msg in lines:
            if start - 2 <= ts <= t + 1 and ('WARN' in msg or 'ERROR' in msg) and msg not in shown:
                shown.add(msg)
                print('  ', datetime.datetime.fromtimestamp(ts, datetime.timezone.utc).strftime('%H:%M:%S.%f')[:-3], msg[:230], flush=True)
    print('TAIL_DONE', flush=True)


if __name__ == '__main__':
    main()

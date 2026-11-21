"""실험 15b: 외부 API stub을 호스트가 아니라 도커 네트워크 안(자바 stub 컨테이너)에 두고 10 RPS 미적중 검색을 다시 잰다.
`Connect timed out`(3.5초 꼬리)이 호스트 경로(host-gateway) 때문인지 확인한다. 앱은 stub.yml(extra_hosts) 없이 띄워 DNS 별칭으로 연결한다.
  APP_VARIANT=sf python exp15b_netstub.py
"""
import csv
import json
import os
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
REPO = os.path.abspath(os.path.join(ROOT, '..', '..'))
RAW = os.path.join(ROOT, 'raw', 'exp15')
VARIANT = os.environ.get('APP_VARIANT', 'sf')
RATE = os.environ.get('TAIL_RATE', '10')
NAME = 'urisik-stubnet'


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace', **kw)


def main():
    os.makedirs(RAW, exist_ok=True)
    # 호스트의 파이썬 stub 중지(혼동 방지), 자바 stub 컨테이너 기동
    sh(['powershell.exe', '-NoProfile', '-Command',
        "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'foodsafety_stub' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"])
    sh(['docker', 'rm', '-f', NAME])
    env = dict(os.environ, MSYS_NO_PATHCONV='1')
    r = sh(['docker', 'run', '-d', '--name', NAME, '--network', 'urisik_backend_default',
            '--network-alias', 'openapi.foodsafetykorea.go.kr', '-v', os.path.join(HERE, 'exp15').replace('\\', '/') + ':/stub',
            'gradle:8.5-jdk17', 'java', '/stub/Stub.java', '80', '20'], env=env)
    if r.returncode != 0:
        raise SystemExit(r.stdout + r.stderr)
    time.sleep(12)
    print('stub container:', sh(['docker', 'logs', NAME]).stdout.strip()[-80:], flush=True)
    # 앱: stub.yml(extra_hosts) 없이, OSIV 끔
    files = ['-f', os.path.join(REPO, 'docker-compose.local.yml'), '-f', os.path.join(HERE, 'compose.variant.yml'),
             '-f', os.path.join(HERE, 'exp2', 'engine.yml'), '-f', os.path.join(HERE, 'exp10', 'osiv-off.yml')]
    env2 = dict(os.environ, APP_VARIANT=VARIANT, SEARCH_ENGINE='elasticsearch')
    r = sh(['docker', 'compose'] + files + ['up', '-d', '--no-build', '--pull', 'never', '--force-recreate', 'app'], env=env2, cwd=REPO)
    if r.returncode != 0:
        raise SystemExit(r.stdout + r.stderr)
    t = time.time()
    while time.time() - t < 400:
        if 'Started UrisikBackendApplication' in sh(['docker', 'logs', 'urisik-app']).stdout:
            break
        time.sleep(3)
    time.sleep(8)
    csv_path = os.path.join(RAW, f'tail-netstub-{VARIANT}-{RATE}rps.csv')
    env3 = dict(os.environ, API='search', RATE=RATE, DURATION_S='60', USERS='100', KW_MODE='unique', SALT=str(time.time_ns()))
    sh(['k6', 'run', '--no-color', '-q', '--out', 'csv=' + csv_path, os.path.join(HERE, 'exp5-connhold.js')], env=env3)
    rows = []
    for r in csv.DictReader(open(csv_path, encoding='utf-8')):
        if r['metric_name'] == 'http_req_duration' and r.get('name') == 'target':
            rows.append(float(r['metric_value']))
    rows.sort()
    p95 = rows[int(len(rows) * 0.95) - 1]
    print('net-stub', VARIANT, 'rate', RATE, 'requests', len(rows), 'median', round(rows[len(rows) // 2]), 'p95', round(p95),
          'slow(>1.5s)', sum(1 for v in rows if v > 1500), flush=True)
    for k in ('successful_without_retry', 'successful_with_retry', 'failed_with_retry', 'failed_without_retry'):
        with urllib.request.urlopen(f'http://localhost:8080/actuator/metrics/resilience4j.retry.calls?tag=kind:{k}', timeout=5) as x:
            print('  retry', k, json.load(x)['measurements'][0]['value'], flush=True)
    print('NETSTUB_DONE', flush=True)


if __name__ == '__main__':
    main()

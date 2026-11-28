"""실험 24: Redis 타임아웃 값에 따른 앱 첫 기동 성공률.

  EXP24_IMAGE=main45 python exp24_redis_startup.py --attempts 8 --values 200ms,1s

배경: #39로 SPRING_DATA_REDIS_TIMEOUT·CONNECT_TIMEOUT을 200ms로 두자, 실험 23 중 앱이 기동 중 Redis 연결 초기화 시간 초과
(`Connection initialization timed out after 200 millisecond(s)`)로 실패해 재시작된 적이 있다(웹 서버는 Redis 리스너보다 먼저 뜨므로 200 응답이 와도 이후 기동이 실패할 수 있다).

방법: 앱 컨테이너만 새로 만들어(Redis는 그대로) 시도마다 두 값을 번갈아 쓴다. 'Started UrisikBackendApplication'이
'Application run failed'보다 먼저 나오면 첫 기동 성공으로 센다. 기동 시간도 기록한다.
"""
import argparse
import csv
import os
import subprocess
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
REPO = os.path.abspath(os.path.join(ROOT, '..', '..'))
FULL = bool(os.environ.get('EXP24_FULL'))   # 1이면 MySQL·Redis·ES·Kafka·ZooKeeper·앱을 모두 새로 만든다(실험 4 실행기와 같은 조건, 호스트 부하가 가장 큼)
WITH_REDIS = bool(os.environ.get('EXP24_WITH_REDIS'))   # 1이면 Redis도 앱과 함께 새로 만든다(전체 스택을 한꺼번에 올리는 배포 상황)
LABEL = os.environ.get('EXP24_LABEL', 'exp24c_redis_startup_full_stack' if FULL else ('exp24b_redis_startup_with_redis' if WITH_REDIS else 'exp24_redis_startup'))
IMAGE = os.environ.get('EXP24_IMAGE', 'main45')
RAW = os.path.join(ROOT, 'raw', LABEL)
CSV_PATH = os.path.join(ROOT, 'results.csv')


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace', **kw)


def write_overlay(value):
    p = os.path.join(RAW, f'overlay-{value}.yml')
    open(p, 'w', encoding='utf-8').write(
        f'services:\n  app:\n    environment:\n      - SPRING_DATA_REDIS_TIMEOUT={value}\n      - SPRING_DATA_REDIS_CONNECT_TIMEOUT={value}\n')
    return p


def attempt(value, overlay):
    env = dict(os.environ, APP_VARIANT=IMAGE)
    files = ['-f', os.path.join(REPO, 'docker-compose.local.yml'), '-f', os.path.join(HERE, 'compose.variant.yml'), '-f', overlay]
    t0 = time.time()
    r = sh(['docker', 'compose'] + files + ['up', '-d', '--no-build', '--pull', 'never', '--force-recreate'] + ([] if FULL else ['--no-deps']) + (['mysql', 'redis', 'elasticsearch', 'kafka', 'zookeeper', 'app'] if FULL else (['redis', 'app'] if WITH_REDIS else ['app'])), env=env, cwd=REPO)
    if r.returncode != 0:
        return {'outcome': 'compose_error', 'detail': (r.stderr or r.stdout)[:150], 'seconds': ''}
    outcome, detail = 'timeout_150s', ''
    while time.time() - t0 < 150:
        logs = sh(['docker', 'logs', 'urisik-app'])
        text = logs.stdout + logs.stderr
        ok, bad = text.find('Started UrisikBackendApplication'), text.find('Application run failed')
        if ok != -1 and (bad == -1 or ok < bad):
            outcome = 'first_start_ok'
            break
        if bad != -1:
            outcome = 'first_start_failed'
            i = text.find('Connection initialization timed out')
            detail = 'connection initialization timed out' if i != -1 else 'other (see log)'
            break
        time.sleep(2)
    return {'outcome': outcome, 'detail': detail, 'seconds': round(time.time() - t0, 1)}


def main(attempts, values):
    os.makedirs(RAW, exist_ok=True)
    overlays = {v: write_overlay(v) for v in values}
    rows, summary = [], {v: {'first_start_ok': 0, 'first_start_failed': 0, 'other': 0} for v in values}
    for n in range(1, attempts + 1):
        for v in (values if n % 2 else list(reversed(values))):
            r = attempt(v, overlays[v])
            print(f'attempt {n} timeout={v}: {r}', flush=True)
            summary[v][r['outcome'] if r['outcome'] in summary[v] else 'other'] += 1
            rows.append([LABEL, f'timeout_{v}', IMAGE, n, 'outcome', r['outcome'], r['detail']])
            rows.append([LABEL, f'timeout_{v}', IMAGE, n, 'seconds_to_outcome', r['seconds'], ''])
    new = not os.path.exists(CSV_PATH)
    with open(CSV_PATH, 'a', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        if new:
            w.writerow(['experiment', 'scenario', 'variant', 'run', 'metric', 'value', 'note'])
        w.writerows(rows)
    print('SUMMARY', summary, flush=True)
    print('EXP24_DONE', flush=True)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--attempts', type=int, default=8)
    ap.add_argument('--values', default='200ms,1s')
    a = ap.parse_args()
    main(a.attempts, a.values.split(','))

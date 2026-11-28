"""실험 6: 부하 중 Redis 중단·복구 (수정 전 v3 vs 수정 후 redisfix)
  python exp6_redis.py --variant v3|redisfix --run-no N
부하 시작 후 약 30초에 Redis 중단, 약 30초 뒤에 복구. 단계(before/down/after)별·API별 오류율과 성공 응답 p95를 results.csv에 기록한다."""
import argparse
import csv
import os
import statistics as st
import subprocess
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
REPO = os.path.abspath(os.path.join(ROOT, '..', '..'))
# 재측정 옵션(없으면 원래 실험 6과 같다): EXP6_IMAGE(앱 이미지), EXP6_OVERLAY(타임아웃 덮어쓰기), EXP6_STUB=net(도커 네트워크 안 stub), EXP6_RAW(결과 폴더), EXP6_EXPERIMENT(results.csv 실험 이름)
RAW = os.path.join(ROOT, 'raw', os.environ.get('EXP6_RAW', 'exp6'))
EXPERIMENT = os.environ.get('EXP6_EXPERIMENT', 'exp6')
CSV_PATH = os.path.join(ROOT, 'results.csv')
APIS = ('search', 'recommend', 'detail', 'mealplan')


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace', **kw)


def wait_ready(timeout=300):
    """앱 응답 + 시작 완료 로그 + 20초간 재시작 없음(실험 4 실행기와 같은 대기, 웹 서버가 Redis 리스너보다 먼저 떠서 200이 와도 기동이 실패할 수 있음)."""
    import exp4_runner
    exp4_runner.wait_ready(timeout)


def start_app(variant):
    files = ['-f', os.path.join(REPO, 'docker-compose.local.yml'), '-f', os.path.join(HERE, 'compose.variant.yml'),
             '-f', os.path.join(HERE, 'exp2', 'engine.yml')]
    if os.environ.get('EXP6_STUB') != 'net':   # net이면 호스트 stub 경로(extra_hosts)를 쓰지 않고 도커 네트워크 안 stub 컨테이너를 쓴다
        files += ['-f', os.path.join(HERE, 'exp2', 'stub.yml')]
    if variant.startswith('redisfix') or os.environ.get('EXP6_OVERLAY'):
        files += ['-f', os.path.join(HERE, os.environ.get('EXP6_OVERLAY', 'exp6/redis-timeout.yml'))]
    env = dict(os.environ, APP_VARIANT=os.environ.get('EXP6_IMAGE', variant), SEARCH_ENGINE='elasticsearch')
    sh(['docker', 'start', 'urisik-redis'])
    r = sh(['docker', 'compose'] + files + ['up', '-d', '--no-build', '--pull', 'never', '--force-recreate', 'app'], env=env, cwd=REPO)
    if r.returncode != 0:
        raise SystemExit(r.stdout + r.stderr)
    wait_ready()
    time.sleep(8)


def run(variant, run_no):
    os.makedirs(RAW, exist_ok=True)
    start_app(variant)
    label = f'{variant}_run{run_no}'
    csv_out = os.path.join(RAW, f'{label}.full.csv')
    if os.path.exists(csv_out):
        os.remove(csv_out)
    # 예열(Redis 정상): 30 RPS 20초
    sh(['k6', 'run', '--no-color', '-q', '-e', 'RATE=30', '-e', 'DURATION_S=20', os.path.join(HERE, 'exp6-redisfail.js')])
    proc = subprocess.Popen(['k6', 'run', '--no-color', '-q', '--out', 'csv=' + csv_out, os.path.join(HERE, 'exp6-redisfail.js')],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    # 부하가 실제로 시작될 때까지(CSV에 요청이 쌓일 때까지) 기다린다
    t_wait = time.time()
    while time.time() - t_wait < 120:
        if os.path.exists(csv_out) and os.path.getsize(csv_out) > 200000:
            break
        time.sleep(1)
    t0 = time.time()
    time.sleep(30)
    t_down = time.time()
    sh(['docker', 'stop', 'urisik-redis'])
    time.sleep(max(0, 30 - (time.time() - t_down)))
    t_up = time.time()
    sh(['docker', 'start', 'urisik-redis'])
    out = proc.communicate()[0]
    open(os.path.join(RAW, label + '.log'), 'w', encoding='utf-8').write(out or '')
    sh(['docker', 'start', 'urisik-redis'])
    return label, csv_out, t_down, t_up


def analyze(csv_out, t_down, t_up, variant, run_no):
    rows = []
    with open(csv_out, newline='', encoding='utf-8') as f:
        for r in csv.DictReader(f):
            if r['metric_name'] == 'http_req_duration' and r.get('name') in APIS:
                rows.append((float(r['timestamp']), r['name'], int(r['status'] or 0), float(r['metric_value'])))

    def phase(ts):
        return 'before' if ts < t_down else ('down' if ts < t_up else 'after')

    out = []
    for ph in ('before', 'down', 'after'):
        for api in APIS + ('all',):
            sel = [x for x in rows if phase(x[0]) == ph and (api == 'all' or x[1] == api)]
            if not sel:
                continue
            ok = sorted(x[3] for x in sel if x[2] == 200)
            n = len(sel)
            vals = {
                'n': n,
                'fail_rate': round(1 - len(ok) / n, 4),
                'status0_timeout_rate': round(sum(1 for x in sel if x[2] == 0) / n, 4),
                'status5xx_rate': round(sum(1 for x in sel if x[2] >= 500) / n, 4),
                'p50_success_ms': round(st.median(ok), 1) if ok else '',
                'p95_success_ms': round(ok[max(int(len(ok) * 0.95) - 1, 0)], 1) if len(ok) >= 20 else '',
            }
            for k, v in vals.items():
                out.append([EXPERIMENT, ph + '_' + api, variant, run_no, k, v, 'rate=60rps;redis down ~30s;client timeout 5s'])
    # 복구 시간: Redis 복구 후 연속 5초 동안 모든 요청이 200이 되는 첫 시점까지
    after = sorted(x for x in rows if x[0] >= t_up)
    rec = ''
    by_sec = {}
    for ts, _, status, _ in after:
        by_sec.setdefault(int(ts - t_up), []).append(status == 200)
    for s0 in sorted(by_sec):
        if all(all(by_sec.get(s, [True])) for s in range(s0, s0 + 5)):
            rec = s0
            break
    out.append([EXPERIMENT, 'recovery', variant, run_no, 'seconds_until_5s_all_200', rec, 'Redis 복구 후'])
    with open(CSV_PATH, 'a', newline='', encoding='utf-8') as f:
        csv.writer(f).writerows(out)
    return out


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--variant', required=True)
    ap.add_argument('--run-no', type=int, required=True)
    a = ap.parse_args()
    label, csv_out, td, tu = run(a.variant, a.run_no)
    out = analyze(csv_out, td, tu, a.variant, a.run_no)
    for r in out:
        if r[1].endswith('_all') or r[1] == 'recovery':
            print(r[1], r[4], r[5])
    os.remove(csv_out)

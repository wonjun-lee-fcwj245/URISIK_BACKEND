"""실험 4 실행기: 설정 전환 → 예열 → 자원 수집 + k6 단계 부하 → 단계별 지표를 results.csv에 기록.

사용:
  python exp4_runner.py arrival --cfg default --run-no 1
  python exp4_runner.py legacy  --cfg tuned            # 기존 overall-load-test.js(VU 방식) 대조 1회
"""
import argparse
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
RAW = os.path.join(ROOT, 'raw', 'exp4')
CSV_PATH = os.path.join(ROOT, 'results.csv')
IMAGE = os.environ.get('APP_VARIANT', 'v4')
P95_LIMIT_MS = 1000
ERR_LIMIT = 0.01


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace', **kw)


def compose_files(cfg, nocpu=False):
    files = ['-f', os.path.join(REPO, 'docker-compose.local.yml'),
             '-f', os.path.join(HERE, 'compose.variant.yml')]
    if not nocpu:   # nocpu: 기존 보고서와 같은 조건(컨테이너 CPU 제한 없음)
        files += ['-f', os.path.join(HERE, 'exp4', 'cpus.yml')]
    if os.environ.get('EXP4_STUB'):   # 외부 API(식품안전나라)를 호스트의 stub으로 연결해 인터넷 변동을 제거
        files += ['-f', os.path.join(HERE, 'exp2', 'stub.yml')]
    return files + ['-f', os.path.join(HERE, 'exp4', f'cfg-{cfg}.yml')]


def wait_ready(timeout=300):
    start = time.time()
    while time.time() - start < timeout:
        try:
            req = urllib.request.Request('http://localhost:8080/api/auth/test-token?memberId=1', method='POST')
            with urllib.request.urlopen(req, timeout=5) as r:
                if r.status == 200:
                    return
        except Exception:
            pass
        time.sleep(3)
    raise RuntimeError('app not ready')


def switch(cfg, nocpu=False):
    env = dict(os.environ, APP_VARIANT=IMAGE)
    files = compose_files(cfg, nocpu)
    cfg_dump = sh(['docker', 'compose'] + files + ['config'], env=env, cwd=REPO).stdout
    # docker compose config는 .env 값을 풀어서 출력하므로 비밀 값(비밀번호, JWT 키 등)을 마스킹한 뒤 저장한다
    import re as _re
    cfg_dump = _re.sub(r'^(\s*[A-Z0-9_]*(?:PASSWORD|SECRETKEY|SECRET|JWT_KEY|API_KEY|ACCESS_KEY|SECRET_KEY)[A-Z0-9_]*\s*:\s*).*$',
                       lambda m: m.group(1) + '"<masked>"', cfg_dump, flags=_re.M)
    os.makedirs(RAW, exist_ok=True)
    open(os.path.join(RAW, f'compose-config-{cfg}{"-nocpu" if nocpu else ""}.yml'), 'w', encoding='utf-8').write(cfg_dump)
    # 매 회차 DB·앱을 새로 띄워 커넥션·캐시 상태를 같게 맞춘다 (Redis는 FLUSHALL)
    r = sh(['docker', 'compose'] + files + ['up', '-d', '--no-build', '--pull', 'never', '--force-recreate',
                                            'mysql', 'redis', 'elasticsearch', 'kafka', 'zookeeper', 'app'],
           env=env, cwd=REPO)
    if r.returncode != 0:
        raise SystemExit(r.stdout + r.stderr)
    wait_ready()
    sh(['docker', 'exec', 'urisik-redis', 'redis-cli', 'FLUSHALL'])
    time.sleep(10)


def warm():
    """집계하지 않는 예열: 200 rps 30초."""
    sh(['k6', 'run', '--no-color', '-q', '-e', 'RATES=200', '-e', 'STAGE_S=30', '-e', 'GAP_S=0',
        os.path.join(HERE, 'exp4-arrival.js')])


def run_k6(label, script, env_extra):
    stop = os.path.join(RAW, f'{label}.stop')
    if os.path.exists(stop):
        os.remove(stop)
    col = subprocess.Popen([sys.executable, os.path.join(HERE, 'exp4_collect.py'),
                            '--out', os.path.join(RAW, f'{label}.resources.csv'), '--stop-file', stop])
    env = dict(os.environ, **env_extra)
    jout = os.path.join(RAW, f'{label}.json')
    r = sh(['k6', 'run', '--no-color', '--summary-export', jout, script], env=env)
    open(os.path.join(RAW, f'{label}.log'), 'w', encoding='utf-8').write(r.stdout + '\n' + r.stderr)
    open(stop, 'w').close()
    col.wait(timeout=60)
    os.remove(stop)
    return json.load(open(jout, encoding='utf-8'))['metrics']


def append(rows):
    new = not os.path.exists(CSV_PATH)
    with open(CSV_PATH, 'a', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        if new:
            w.writerow(['experiment', 'scenario', 'variant', 'run', 'metric', 'value', 'note'])
        w.writerows(rows)


def arrival(cfg, run_no, rates, stage_s):
    switch(cfg)
    warm()
    label = f'arrival_{cfg}_run{run_no}'
    m = run_k6(label, os.path.join(HERE, 'exp4-arrival.js'), {'RATES': rates, 'STAGE_S': str(stage_s)})
    rows, best = [], 0
    for rate in map(int, rates.split(',')):
        sc = f'r{rate}'
        def c(name):
            return m.get(f'{name}{{scenario:{sc}}}', {}).get('count', 0)
        n = c('http_reqs')
        dur = m.get(f'http_req_duration{{scenario:{sc}}}', {})
        ok, n404, n5xx, other, dropped = c('status_200'), c('status_404'), c('status_5xx'), c('status_other'), c('dropped_iterations')
        done = ok + n404 + n5xx + other
        err = (n5xx + other) / done if done else 1
        vals = {
            'target_rps': rate, 'achieved_rps': round(n / stage_s, 1), 'ok200_rps': round(ok / stage_s, 1),
            'p50_ms': dur.get('med'), 'p95_ms': dur.get('p(95)'), 'p99_ms': dur.get('p(99)'),
            'non200_rate': round(1 - ok / done, 4) if done else 1, 'rate_404': round(n404 / done, 4) if done else 0,
            'err_5xx_other_rate': round(err, 4), 'dropped_iterations': dropped,
        }
        passed = (dur.get('p(95)') or 1e9) <= P95_LIMIT_MS and err <= ERR_LIMIT and dropped <= rate * stage_s * 0.01
        vals['meets_slo'] = int(passed)
        if passed:
            best = rate
        rows += [['exp4', f'arrival_{sc}', cfg, run_no, k, v, f'stage_s={stage_s}'] for k, v in vals.items()]
        print(label, sc, json.dumps(vals))
    rows.append(['exp4', 'arrival_summary', cfg, run_no, 'max_rps_p95_le_1s', best,
                 f'p95<={P95_LIMIT_MS}ms, 5xx+기타<={ERR_LIMIT:.0%}, dropped<=1%'])
    for ep in ('search', 'recommend', 'detail', 'mealplan'):
        d = m.get(f'http_req_duration{{ep:{ep}}}', {})
        rows.append(['exp4', f'arrival_ep_{ep}', cfg, run_no, 'p95_ms_all_stages', d.get('p(95)'), ''])
    append(rows)
    print(label, 'max_rps_p95_le_1s =', best)


def legacy(cfg, nocpu=False, run_no=1):
    switch(cfg, nocpu)
    tag = 'legacy-vu-nocpu' if nocpu else 'legacy-vu'
    label = f'{tag}_{cfg}' + (f'_run{run_no}' if nocpu else '')
    m = run_k6(label, os.path.join(REPO, 'k6', 'scripts', 'overall-load-test.js'), {})
    total = m['http_reqs']['count']
    dur = m['http_req_duration']
    failed = m['http_req_failed']['value']
    scen = 'legacy_vu_3000_nocpu' if nocpu else 'legacy_vu_3000'
    note = '기존 overall-load-test.js 그대로, 4분30초(setup 제외)' + (', 컨테이너 CPU 제한 없음' if nocpu else '')
    rows = [['exp4', scen, cfg, run_no, k, v, note] for k, v in {
        'rps_4m30_avg': round(total / 270, 1), 'p95_ms_all': dur.get('p(95)'),
        'checks_rate_404_as_ok': m['checks']['value'], 'http_req_failed_rate': failed,
        'success_2xx_rps_est': round(total * (1 - failed) / 270, 1),
    }.items()]
    append(rows)
    print(label, rows)


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest='cmd')
    a = sub.add_parser('arrival')
    a.add_argument('--cfg', required=True)
    a.add_argument('--run-no', type=int, required=True)
    a.add_argument('--rates', default='100,200,400,600,800,1000,1200,1500')
    a.add_argument('--stage-s', type=int, default=60)
    l = sub.add_parser('legacy')
    l.add_argument('--cfg', required=True)
    l.add_argument('--nocpu', action='store_true')
    l.add_argument('--run-no', type=int, default=1)
    args = p.parse_args()
    if args.cmd == 'arrival':
        arrival(args.cfg, args.run_no, args.rates, args.stage_s)
    else:
        legacy(args.cfg, args.nocpu, args.run_no)


if __name__ == '__main__':
    main()

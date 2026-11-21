"""실험 4 재설계 실행기: 설정 전환 → 예열 → (자원 수집 + Docker 네트워크 안 k6 컨테이너) → 단계별 지표를 results.csv에 기록.
  python exp4b_runner.py arrival --cfg default --run-no 1
성공(200)만으로 p95를 계산하고, 404와 5xx·연결 실패를 따로 센다. SLO = p95(성공 응답) ≤ 1000ms, 비200 ≤ 1%, 드롭 ≤ 1%."""
import argparse, csv, json, os, subprocess, sys, time
import exp4_runner as e4

HERE = e4.HERE
RAW = os.path.join(e4.ROOT, 'raw', os.environ.get('EXP4_RAW', 'exp4b'))   # 다른 실험이 exp4b 원본을 덮어쓰지 않게 폴더를 분리한다
LABEL = os.environ.get('EXP4_LABEL', 'exp4b')
CSV_PATH = e4.CSV_PATH
SCRIPT_DIR = HERE.replace(chr(92), '/')
RAW_DIR = RAW.replace(chr(92), '/')
K6_CPUS = '3.5'
RATES = os.environ.get('EXP4_RATES', '300,350,400,450,500,550,600')
STAGE_S = 60


def k6_container(env_extra, label, summary):
    subprocess.run(['docker', 'rm', '-f', 'urisik-k6'], capture_output=True)
    cmd = ['docker', 'run', '--rm', '--name', 'urisik-k6', '--network', 'urisik_backend_default', '--cpus', K6_CPUS,
           '-v', f'{SCRIPT_DIR}:/scripts', '-v', f'{RAW_DIR}:/out']
    for k, v in env_extra.items():
        cmd += ['-e', f'{k}={v}']
    cmd += ['grafana/k6:latest', 'run', '--no-color', '--summary-export', f'/out/{summary}', '/scripts/exp4b-arrival.js']
    return subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')


def append(rows):
    new = not os.path.exists(CSV_PATH)
    with open(CSV_PATH, 'a', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        if new:
            w.writerow(['experiment', 'scenario', 'variant', 'run', 'metric', 'value', 'note'])
        w.writerows(rows)


def arrival(cfg, run_no):
    os.makedirs(RAW, exist_ok=True)
    e4.switch(cfg)
    # 예열(집계 제외): 200 RPS 30초
    k6_container({'RATES': '200', 'STAGE_S': '30', 'GAP_S': '0'}, 'warm', 'warm.json')
    label = f'arrival_{cfg}_run{run_no}'
    stop = os.path.join(RAW, f'{label}.stop')
    if os.path.exists(stop):
        os.remove(stop)
    col = subprocess.Popen([sys.executable, os.path.join(HERE, 'exp4_collect.py'), '--out', os.path.join(RAW, f'{label}.resources.csv'), '--stop-file', stop])
    open(os.path.join(RAW, f'{label}.k6start.txt'), 'w').write(str(time.time()))   # 단계 시각 계산용
    r = k6_container({'RATES': RATES, 'STAGE_S': str(STAGE_S)}, label, f'{label}.json')
    open(os.path.join(RAW, f'{label}.log'), 'w', encoding='utf-8').write((r.stdout or '') + '\n' + (r.stderr or ''))
    open(stop, 'w').close()
    col.wait(timeout=60)
    os.remove(stop)
    m = json.load(open(os.path.join(RAW, f'{label}.json'), encoding='utf-8'))['metrics']
    rows, best, first_fail = [], 0, None
    for rate in map(int, RATES.split(',')):
        sc = f'r{rate}'
        def cnt(name): return m.get(f'{name}{{scenario:{sc}}}', {}).get('count', 0)
        n = cnt('http_reqs'); ok = cnt('status_200'); n404 = cnt('status_404'); n5 = cnt('status_5xx'); oth = cnt('status_other'); drop = cnt('dropped_iterations')
        done = ok + n404 + n5 + oth
        succ = m.get(f'http_req_duration{{scenario:{sc},expected_response:true}}', {})
        allm = m.get(f'http_req_duration{{scenario:{sc}}}', {})
        p95s = succ.get('p(95)')
        non200 = 1 - ok / done if done else 1
        vals = {'target_rps': rate, 'achieved_rps': round(n / STAGE_S, 1), 'ok200_rps': round(ok / STAGE_S, 1),
                'p95_success_ms': p95s, 'p99_success_ms': succ.get('p(99)'), 'p50_success_ms': succ.get('med'),
                'p95_all_ms': allm.get('p(95)'), 'non200_rate': round(non200, 4), 'rate_404': round(n404 / done, 4) if done else 0,
                'rate_5xx': round(n5 / done, 4) if done else 0, 'rate_conn_fail_other': round(oth / done, 4) if done else 0,
                'dropped_iterations': drop}
        passed = p95s is not None and p95s <= 1000 and non200 <= 0.01 and drop <= rate * STAGE_S * 0.01
        vals['meets_slo'] = int(passed)
        if passed:
            best = rate
        elif first_fail is None:
            first_fail = rate
        rows += [[LABEL, f'arrival_{sc}', cfg, run_no, k, v, f'stage_s={STAGE_S};k6=container({K6_CPUS}cpus)'] for k, v in vals.items()]
        print(label, sc, json.dumps(vals))
    rows.append([LABEL, 'arrival_summary', cfg, run_no, 'max_rps_slo', best, 'p95(success)<=1000ms, non200<=1%, drop<=1%'])
    rows.append([LABEL, 'arrival_summary', cfg, run_no, 'first_failing_rps', first_fail if first_fail else '', ''])
    append(rows)
    print(label, 'max_rps_slo =', best, 'first_failing =', first_fail)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest='cmd')
    a = sub.add_parser('arrival'); a.add_argument('--cfg', required=True); a.add_argument('--run-no', type=int, required=True)
    args = p.parse_args()
    arrival(args.cfg, args.run_no)

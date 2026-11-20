"""실험 5: API별 커넥션 점유 시간. Hikari 타이머(hikaricp.connections.usage = 커넥션을 빌려 쓴 시간, acquire = 빌리려고 기다린 시간)를
부하 전후로 읽어 요청당 평균을 구한다. Hikari 지표는 풀 단위라 API별 태그가 없으므로 API마다 단독 부하를 건다.
  python exp5_connhold.py            # 조건 5개 × 3회(1회차 워밍업)
"""
import csv, json, os, subprocess, sys, time, urllib.request
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
RAW = os.path.join(ROOT, 'raw', 'exp5')
CSV_PATH = os.path.join(ROOT, 'results.csv')
PY = sys.executable


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace', **kw)


def metric(name):
    with urllib.request.urlopen(f'http://localhost:8080/actuator/metrics/{name}', timeout=5) as r:
        d = json.load(r)
    m = {x['statistic']: x['value'] for x in d['measurements']}
    return m.get('COUNT', 0.0), m.get('TOTAL_TIME', 0.0), m.get('MAX', 0.0)


def restart_stub(delay_ms):
    ps = ("Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'foodsafety_stub' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }")
    sh(['powershell.exe', '-NoProfile', '-Command', ps])
    time.sleep(1)
    stub = os.path.join(HERE, 'exp2', 'foodsafety_stub.py')
    sh(['powershell.exe', '-NoProfile', '-Command',
        f"Start-Process -WindowStyle Hidden -FilePath '{PY}' -ArgumentList '\"{stub}\" --port 80 --delay-ms {delay_ms}'"])
    time.sleep(2)


def run_cond(label, api, rate, run_no):
    u0, u1 = metric('hikaricp.connections.usage'), None
    a0 = metric('hikaricp.connections.acquire')
    jout = os.path.join(RAW, f'{label}_run{run_no}.json')
    env = dict(os.environ, API=api, RATE=str(rate), DURATION_S='60', USERS='100')
    r = sh(['k6', 'run', '--no-color', '-q', '--summary-export', jout, os.path.join(HERE, 'exp5-connhold.js')], env=env)
    open(os.path.join(RAW, f'{label}_run{run_no}.log'), 'w', encoding='utf-8').write(r.stdout + r.stderr)
    time.sleep(2)
    u1, a1 = metric('hikaricp.connections.usage'), metric('hikaricp.connections.acquire')
    m = json.load(open(jout, encoding='utf-8'))['metrics']
    reqs = m['http_reqs{name:target}']['count'] if 'http_reqs{name:target}' in m else None
    d = m['http_req_duration{name:target}']
    n_req = m['iterations']['count']
    borrow = u1[0] - u0[0]
    vals = {
        'requests': n_req,
        'borrows_per_request': round(borrow / n_req, 3),
        'usage_ms_per_borrow': round((u1[1] - u0[1]) / borrow * 1000, 2) if borrow else '',
        'usage_ms_per_request': round((u1[1] - u0[1]) / n_req * 1000, 2),
        'acquire_ms_per_borrow': round((a1[1] - a0[1]) / max(a1[0] - a0[0], 1) * 1000, 3),
        'usage_max_ms': round(u1[2] * 1000, 1),
        'req_avg_ms': round(d['avg'], 2), 'req_p95_ms': round(d['p(95)'], 2),
        'fail_rate': m['http_req_failed{name:target}']['value'],
    }
    return vals


def append(rows):
    new = not os.path.exists(CSV_PATH)
    with open(CSV_PATH, 'a', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        if new:
            w.writerow(['experiment', 'scenario', 'variant', 'run', 'metric', 'value', 'note'])
        w.writerows(rows)


def main():
    os.makedirs(RAW, exist_ok=True)
    conds = [('search_stub20', 'search', 20), ('search_stub300', 'search', 300),
             ('recommend', 'recommend', None), ('detail', 'detail', None), ('mealplan', 'mealplan', None)]
    for run_no in (1, 2, 3):
        for label, api, delay in conds:
            if delay is not None:
                restart_stub(delay)
            vals = run_cond(label, api, 100, run_no)
            append([['exp5', label, 'v4', run_no, k, v, f'api={api};rate=100rps;60s;warmup={"yes" if run_no == 1 else "no"}'] for k, v in vals.items()])
            print(label, 'run', run_no, json.dumps(vals))
    print('CONNHOLD_DONE')


if __name__ == '__main__':
    main()

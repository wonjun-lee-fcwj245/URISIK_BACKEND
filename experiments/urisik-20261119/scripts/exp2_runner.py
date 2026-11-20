"""실험 2 실행기: DB 재적재, 검색 엔진 전환, 지연 측정, 기존 스크립트 대조.

사용:
  python exp2_runner.py reseed --sql ../raw/exp2/seed-5k-s42.sql      # DB 재생성 → 시드 적재 → FULLTEXT 인덱스 → ES 재인덱싱
  python exp2_runner.py latency --engine elasticsearch --vus 10 --run-no 1
  python exp2_runner.py legacy --engine mysql                           # k6/scripts/search-before-test.js
  python exp2_runner.py legacy --engine elasticsearch                   # k6/scripts/search-test.js
"""
import argparse
import csv
import json
import os
import subprocess
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
REPO = os.path.abspath(os.path.join(ROOT, '..', '..'))
RAW = os.path.join(ROOT, 'raw', 'exp2')
CSV_PATH = os.path.join(ROOT, 'results.csv')
IMAGE = os.environ.get('APP_VARIANT', 'v4')


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace', **kw)


def mysql(sql, db='urisik'):
    r = subprocess.run(['docker', 'exec', '-i', 'urisik-mysql', 'sh', '-c',
                        f'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B {db}'],
                       input=sql.encode('utf-8'), capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode('utf-8', 'replace'))
    return r.stdout.decode('utf-8')


def up_app(engine):
    env = dict(os.environ, APP_VARIANT=IMAGE, SEARCH_ENGINE=engine)
    files = ['-f', os.path.join(REPO, 'docker-compose.local.yml'),
             '-f', os.path.join(HERE, 'compose.variant.yml'), '-f', os.path.join(HERE, 'exp2', 'engine.yml')]
    if os.environ.get('EXP2_STUB'):   # 외부 API를 호스트의 stub 서버(고정 지연)로 연결
        files += ['-f', os.path.join(HERE, 'exp2', 'stub.yml')]
    r = sh(['docker', 'compose'] + files +
           ['up', '-d', '--no-build', '--pull', 'never', '--force-recreate', 'app'], env=env, cwd=REPO)
    if r.returncode != 0:
        raise SystemExit(r.stdout + r.stderr)


def wait_started(timeout=400):
    start = time.time()
    while time.time() - start < timeout:
        if 'Started UrisikBackendApplication' in sh(['docker', 'logs', 'urisik-app']).stdout:
            return
        time.sleep(3)
    raise RuntimeError('app not started')


def wait_token(timeout=300):
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


def es_count():
    try:
        with urllib.request.urlopen('http://localhost:9200/recipes/_count?q=type:RECIPE', timeout=5) as r:
            return json.load(r)['count']
    except Exception:
        return -1


def reseed(sql_path, engine='elasticsearch'):
    t0 = time.time()
    mysql('DROP DATABASE IF EXISTS urisik; CREATE DATABASE urisik CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;', db='')
    up_app(engine)
    wait_started()          # ddl-auto=update가 빈 DB에 테이블을 만든다
    with open(sql_path, 'rb') as f:
        r = subprocess.run(['docker', 'exec', '-i', 'urisik-mysql', 'sh', '-c',
                            'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" urisik'],
                           stdin=f, capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode('utf-8', 'replace'))
    mysql(open(os.path.join(REPO, 'db', 'init', 'add-fulltext-index.sql'), encoding='utf-8').read())
    n = int(mysql('select count(*) from recipe').strip())
    up_app(engine)          # 재시작 시 RecipeIndexBatch가 ES를 재인덱싱
    wait_token()
    start = time.time()
    while es_count() < n and time.time() - start < 600:
        time.sleep(5)
    print(f'reseeded {os.path.basename(sql_path)}: recipe={n}, es={es_count()}, {time.time()-t0:.0f}s')


def switch(engine):
    up_app(engine)
    wait_token()
    time.sleep(10)


def append(rows):
    new = not os.path.exists(CSV_PATH)
    with open(CSV_PATH, 'a', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        if new:
            w.writerow(['experiment', 'scenario', 'variant', 'run', 'metric', 'value', 'note'])
        w.writerows(rows)


def latency(engine, vus, run_no, dataset):
    label = f'latency_{dataset}_{engine}_vu{vus}_run{run_no}'
    jout = os.path.join(RAW, f'{label}.json')
    r = sh(['k6', 'run', '--no-color', '--summary-export', jout, os.path.join(HERE, 'exp2-latency.js')],
           env=dict(os.environ, VUS=str(vus)))
    open(os.path.join(RAW, f'{label}.log'), 'w', encoding='utf-8').write(r.stdout + '\n' + r.stderr)
    m = json.load(open(jout, encoding='utf-8'))['metrics']
    d = m['http_req_duration{name:search}']
    f = m['http_req_failed{name:search}']
    vals = {'p50_ms': d['med'], 'p95_ms': d['p(95)'], 'p99_ms': d['p(99)'], 'avg_ms': d['avg'],
            'req_count': m['iterations']['count'], 'fail_rate': f['value']}
    append([['exp2', f'latency_vu{vus}', engine, run_no, k, v, f'dataset={dataset};warmup={"yes" if run_no == 1 else "no"}']
            for k, v in vals.items()])
    print(label, json.dumps(vals))


def legacy(engine, dataset):
    script = 'search-before-test.js' if engine == 'mysql' else 'search-test.js'
    label = f'legacy_{dataset}_{engine}'
    jout = os.path.join(RAW, f'{label}.json')
    r = sh(['k6', 'run', '--no-color', '--summary-export', jout, os.path.join(REPO, 'k6', 'scripts', script)], cwd=REPO)
    open(os.path.join(RAW, f'{label}.log'), 'w', encoding='utf-8').write(r.stdout + '\n' + r.stderr)
    m = json.load(open(jout, encoding='utf-8'))['metrics']
    d = m['http_req_duration']
    fail = m['http_req_failed']['value']
    n = m['http_reqs']['count']
    if fail > 0.01 or n < 1000:
        # 앱/인프라 불안정으로 보이는 실행은 집계하지 않는다
        print(f'INVALID_LEGACY {label}: reqs={n}, failed={fail:.2%} (not recorded)')
        os.rename(jout, jout + '.invalid')
        raise SystemExit(f'legacy run invalid: reqs={n}, failed={fail:.2%}')
    vals = {'p50_ms': d['med'], 'p95_ms': d['p(95)'], 'avg_ms': d['avg'], 'req_count': n, 'fail_rate': fail}
    append([['exp2', 'legacy_script', engine, 1, k, v, f'{script};dataset={dataset}'] for k, v in vals.items()])
    print(label, json.dumps(vals))


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest='cmd')
    s = sub.add_parser('reseed')
    s.add_argument('--sql', required=True)
    w = sub.add_parser('switch')
    w.add_argument('--engine', required=True)
    lt = sub.add_parser('latency')
    lt.add_argument('--engine', required=True)
    lt.add_argument('--vus', type=int, required=True)
    lt.add_argument('--run-no', type=int, required=True)
    lt.add_argument('--dataset', default='5k')
    lg = sub.add_parser('legacy')
    lg.add_argument('--engine', required=True)
    lg.add_argument('--dataset', default='5k')
    a = p.parse_args()
    os.makedirs(RAW, exist_ok=True)
    if a.cmd == 'reseed':
        reseed(a.sql)
    elif a.cmd == 'switch':
        switch(a.engine)
    elif a.cmd == 'latency':
        latency(a.engine, a.vus, a.run_no, a.dataset)
    else:
        legacy(a.engine, a.dataset)


if __name__ == '__main__':
    main()

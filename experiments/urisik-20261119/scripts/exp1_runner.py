"""실험 1 실행기: 변형(variant) 전환, 회차 실행, 원본 저장, results.csv 기록.

사용 예:
  python exp1_runner.py switch v3
  python exp1_runner.py run --scenario A --variant v3 --kind review --vus 100 --mode same --reps 5
"""
import argparse
import csv
import datetime as dt
import json
import os
import re
import subprocess
import time
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
REPO = os.path.abspath(os.path.join(ROOT, '..', '..'))
RAW = os.path.join(ROOT, 'raw', 'exp1')
CSV_PATH = os.path.join(ROOT, 'results.csv')
STATE = os.path.join(RAW, 'id-state.json')
K6_SCRIPT = os.path.join(ROOT, 'scripts', 'exp1-concurrency.js')
OVERRIDE = os.path.join(ROOT, 'scripts', 'compose.variant.yml')
COMPOSE = os.path.join(REPO, 'docker-compose.local.yml')

KINDS = {
    # kind: (target table, counter column, child table, child fk, has score)
    'review': ('recipe', 'review_count', 'recipe_review', 'recipe_id', True),
    'treview': ('transformed_recipe', 'review_count', 'transformed_recipe_review', 'transformed_recipe_id', True),
    'wish': ('recipe', 'wish_count', 'personal_wish_list', 'recipe_id', False),
    'twish': ('transformed_recipe', 'wish_count', '`personal-transformed-recipe-wishlist`', 'transformed_recipe_id', False),
}
TAGS = {'review': 'createReview', 'treview': 'createTransformedReview', 'wish': 'addWish', 'twish': 'addTransformedWish'}


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace', **kw)


def mysql(sql):
    # SQL은 stdin으로 전달 (백틱이 셸 명령 치환으로 해석되지 않도록)
    r = sh(['docker', 'exec', '-i', 'urisik-mysql', 'sh', '-c',
            'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B urisik'], input=sql)
    if r.returncode != 0:
        raise RuntimeError(r.stderr)
    return [line.split('\t') for line in r.stdout.strip().splitlines() if line]


def innodb_counts():
    rows = mysql("select name,count from information_schema.INNODB_METRICS where name in ('lock_deadlocks','lock_timeouts')")
    return {k: int(v) for k, v in rows}


def wait_ready(timeout=300):
    start = time.time()
    while time.time() - start < timeout:
        try:
            req = urllib.request.Request('http://localhost:8080/api/auth/test-token?memberId=1', method='POST')
            with urllib.request.urlopen(req, timeout=5) as resp:
                if resp.status == 200:
                    return time.time() - start
        except Exception:
            pass
        time.sleep(3)
    raise RuntimeError('app not ready')


def switch(variant):
    env = dict(os.environ, APP_VARIANT=variant)
    r = sh(['docker', 'compose', '-f', COMPOSE, '-f', OVERRIDE, 'up', '-d', '--no-build', '--pull', 'never',
            '--force-recreate', 'app'], env=env, cwd=REPO)
    print(r.stdout, r.stderr)
    if r.returncode != 0:
        raise SystemExit(r.returncode)
    waited = wait_ready()
    img = sh(['docker', 'inspect', 'urisik-app', '--format', '{{.Config.Image}}']).stdout.strip()
    print(f'switched to {variant} (image={img}), ready in {waited:.0f}s')
    # 워밍업 직후 JIT 영향 최소화를 위해 잠시 대기
    time.sleep(10)


def alloc(kind, n):
    os.makedirs(RAW, exist_ok=True)
    state = {'recipe': 1001, 'transformed_recipe': 1}
    if os.path.exists(STATE):
        state = json.load(open(STATE))
    table = KINDS[kind][0]
    start = state[table]
    state[table] = start + n
    limit = 4990 if table == 'recipe' else 120
    if start + n - 1 > limit:
        raise RuntimeError(f'{table} id pool exhausted')
    json.dump(state, open(STATE, 'w'))
    return list(range(start, start + n))


def keep_requests(src, dst, tag):
    with open(src, newline='', encoding='utf-8') as f, open(dst, 'w', newline='', encoding='utf-8') as g:
        rd = csv.DictReader(f)
        w = csv.writer(g)
        w.writerow(['timestamp', 'duration_ms', 'status'])
        for row in rd:
            if row['metric_name'] == 'http_req_duration' and row.get('name') == tag:
                w.writerow([row['timestamp'], row['metric_value'], row.get('status', '')])
    os.remove(src)


def warm():
    """앱 재시작 직후 JIT·커넥션 풀 예열. 집계하지 않는다."""
    for kind in ('review', 'wish'):
        ids = alloc(kind, 1)
        env = dict(os.environ, KIND=kind, VUS='50', IDS=str(ids[0]))
        sh(['k6', 'run', '--no-color', '-q', K6_SCRIPT], env=env)
    print('warmed up')


def append_csv(rows):
    new = not os.path.exists(CSV_PATH)
    with open(CSV_PATH, 'a', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        if new:
            w.writerow(['experiment', 'scenario', 'variant', 'run', 'metric', 'value', 'note'])
        w.writerows(rows)


def run_once(scenario, variant, kind, vus, mode, run_no):
    table, counter, child, fk, has_score = KINDS[kind]
    ids = alloc(kind, 1 if mode == 'same' else vus)
    id_list = ','.join(map(str, ids))
    label = f'{scenario}_{variant}_{kind}_vu{vus}_{mode}_run{run_no}'

    # 대상 행 카운터를 0으로 초기화 (시드 값이 실제 리뷰 수와 무관하므로 정합성 비교를 위해)
    reset = f'{counter}=0' + (', avg_score=0' if has_score else '')
    mysql(f'update {table} set {reset} where id in ({id_list})')
    pre_children = int(mysql(f'select count(*) from {child} where {fk} in ({id_list})')[0][0])

    before = innodb_counts()
    since = dt.datetime.now(dt.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')  # Z 필수: 없으면 docker가 로컬 시각으로 해석
    json_out = os.path.join(RAW, f'{label}.json')
    log_out = os.path.join(RAW, f'{label}.log')
    env = dict(os.environ, KIND=kind, VUS=str(vus), IDS=id_list)
    full_csv = os.path.join(RAW, f'{label}.full.csv')
    r = sh(['k6', 'run', '--no-color', '--summary-export', json_out, '--out', f'csv={full_csv}', K6_SCRIPT], env=env)
    with open(log_out, 'w', encoding='utf-8') as f:
        f.write(r.stdout + '\n' + r.stderr)
    # 요청별 원본: 대상 API의 http_req_duration만 남긴다 (회차 합산 p95/p99 계산용)
    keep_requests(full_csv, os.path.join(RAW, f'{label}.requests.csv'), TAGS[kind])
    time.sleep(2)
    after = innodb_counts()

    applog = sh(['docker', 'logs', 'urisik-app', '--since', since])
    applog_text = applog.stdout + applog.stderr
    n1213 = len(re.findall(r'SQL Error: 1213', applog_text))
    n1205 = len(re.findall(r'SQL Error: 1205', applog_text))
    with open(os.path.join(RAW, f'{label}.applog-errors.txt'), 'w', encoding='utf-8') as f:
        for line in applog_text.splitlines():
            if re.search(r'SQL Error|Deadlock|Lock wait|ERROR|INSIDE_RETRY', line):
                f.write(line + '\n')
    if os.environ.get('EXP_SAVE_FULL_LOG'):   # 필터 없이 전체 앱 로그(스택 트레이스 포함) 저장
        with open(os.path.join(RAW, f'{label}.applog-full.txt'), 'w', encoding='utf-8') as f:
            f.write(applog_text)

    # 정합성 검증 SQL
    if has_score:
        q = (f'select t.id, t.{counter}, count(c.id), t.avg_score, round(coalesce(avg(c.score),0),1), '
             f'round(coalesce(avg(c.score),0),4) from {table} t left join {child} c on c.{fk}=t.id '
             f'where t.id in ({id_list}) group by t.id, t.{counter}, t.avg_score')
    else:
        q = (f'select t.id, t.{counter}, count(c.id) from {table} t left join {child} c on c.{fk}=t.id '
             f'where t.id in ({id_list}) group by t.id, t.{counter}')
    rows = mysql(q)
    with open(os.path.join(RAW, f'{label}.integrity.tsv'), 'w', encoding='utf-8') as f:
        f.write(q + '\n')
        for row in rows:
            f.write('\t'.join(row) + '\n')
    count_mismatch = sum(1 for row in rows if int(row[1]) != int(row[2]))
    avg_mismatch = sum(1 for row in rows if has_score and abs(float(row[3]) - float(row[4])) > 1e-9) if has_score else ''
    avg_maxdiff = max((abs(float(row[3]) - float(row[5])) for row in rows), default=0) if has_score else ''
    children = sum(int(row[2]) for row in rows) - pre_children
    counter_total = sum(int(row[1]) for row in rows)

    if after['lock_deadlocks'] > before['lock_deadlocks']:
        st = sh(['docker', 'exec', 'urisik-mysql', 'sh', '-c',
                 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW ENGINE INNODB STATUS\\G"'])
        with open(os.path.join(RAW, f'{label}.innodb-status.txt'), 'w', encoding='utf-8') as f:
            f.write(st.stdout)

    summ = json.load(open(json_out, encoding='utf-8'))['metrics']
    tag = TAGS[kind]
    dur = summ.get(f'http_req_duration{{name:{tag}}}', {})
    def cnt(name):
        return summ.get(name, {}).get('count', 0)

    m = {
        'requests': vus,
        'success_200': cnt('target_ok'),
        'duplicate_4xx': cnt('target_duplicate'),
        'error_5xx': cnt('target_5xx'),
        'error_other': cnt('target_other'),
        'mysql_1213_applog': n1213,
        'mysql_1205_applog': n1205,
        'innodb_lock_deadlocks_delta': after['lock_deadlocks'] - before['lock_deadlocks'],
        'innodb_lock_timeouts_delta': after['lock_timeouts'] - before['lock_timeouts'],
        'p50_ms': dur.get('med'),
        'p95_ms': dur.get('p(95)'),
        'p99_ms': dur.get('p(99)'),
        'max_ms': dur.get('max'),
        'rows_inserted': children,
        'counter_total': counter_total,
        'counter_mismatch_targets': count_mismatch,
        'avg_mismatch_targets_round1': avg_mismatch,
        'avg_max_abs_diff_vs_true': avg_maxdiff,
    }
    note = f'kind={kind};vus={vus};mode={mode};ids={ids[0]}..{ids[-1]};warmup={"yes" if run_no == 1 else "no"}'
    append_csv([['exp1', scenario, variant, run_no, k, v, note] for k, v in m.items()])
    print(label, json.dumps(m, ensure_ascii=False))
    return m


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest='cmd')
    s = sub.add_parser('switch')
    s.add_argument('variant')
    r = sub.add_parser('run')
    r.add_argument('--scenario', required=True)
    r.add_argument('--variant', required=True)
    r.add_argument('--kind', required=True, choices=list(KINDS))
    r.add_argument('--vus', type=int, required=True)
    r.add_argument('--mode', choices=['same', 'distinct'], default='same')
    r.add_argument('--reps', type=int, default=5)
    r.add_argument('--run-no', type=int, help='지정 시 해당 회차 1회만 실행 (번갈아 실행용)')
    sub.add_parser('warm')
    a = p.parse_args()
    if a.cmd == 'switch':
        switch(a.variant)
    elif a.cmd == 'run':
        img = sh(['docker', 'inspect', 'urisik-app', '--format', '{{.Config.Image}}']).stdout.strip()
        if img != f'urisik-app:{a.variant}':
            raise SystemExit(f'running image {img} != urisik-app:{a.variant}')
        runs = [a.run_no] if a.run_no else range(1, a.reps + 1)
        for i in runs:
            run_once(a.scenario, a.variant, a.kind, a.vus, a.mode, i)
            time.sleep(3)
    elif a.cmd == 'warm':
        warm()


if __name__ == '__main__':
    main()

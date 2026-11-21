"""실험 1 집계: results.csv + raw/exp1 원본 → 요약 표(마크다운)와 요약 JSON.

- 회차 지표는 워밍업(1회차)을 뺀 2~5회차의 median, min, max
- p95/p99는 회차별 값의 median과 함께, 2~5회차 요청 원본을 합친(pooled) 값도 계산
- 앱 로그 1213/1205 건수는 회차의 요청 시각 구간으로 다시 걸러 계산
  (초기 회차는 docker logs --since 시간대 버그로 9시간치 로그를 읽어 값이 누적되어 있었음)
"""
import csv
import collections
import glob
import json
import math
import os
import re
import statistics as st
import sys
from datetime import datetime, timezone

sys.stdout.reconfigure(encoding='utf-8')
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..')
RAW = os.path.join(ROOT, 'raw', 'exp1')


def pct(vals, p):
    """k6와 같은 선형 보간 백분위수."""
    v = sorted(vals)
    if not v:
        return None
    k = (len(v) - 1) * p / 100
    f, c = math.floor(k), math.ceil(k)
    return v[f] if f == c else v[f] + (v[c] - v[f]) * (k - f)


def recount(label):
    req = os.path.join(RAW, f'{label}.requests.csv')
    log = os.path.join(RAW, f'{label}.applog-errors.txt')
    ts = [int(r['timestamp']) for r in csv.DictReader(open(req, encoding='utf-8'))]
    lo, hi = min(ts) - 2, max(ts) + 3
    n1213 = n1205 = 0
    for line in open(log, encoding='utf-8', errors='replace'):
        m = re.match(r'(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)', line)
        if not m:
            continue
        t = datetime.strptime(m.group(1), '%Y-%m-%dT%H:%M:%S').replace(tzinfo=timezone.utc).timestamp()
        if lo <= t <= hi:
            n1213 += 'SQL Error: 1213' in line
            n1205 += 'SQL Error: 1205' in line
    return n1213, n1205


def main(variants_filter=None):
    rows = list(csv.DictReader(open(os.path.join(ROOT, 'results.csv'), encoding='utf-8')))
    data = collections.defaultdict(lambda: collections.defaultdict(dict))
    for r in rows:
        if r['experiment'] != 'exp1' or not r['run'].isdigit():
            continue
        note = dict(kv.split('=', 1) for kv in r['note'].split(';'))
        key = (r['scenario'], r['variant'], note['kind'], int(note['vus']), note['mode'])
        data[key][int(r['run'])][r['metric']] = r['value']

    summary, out_rows = [], []
    for key in sorted(data, key=lambda k: (k[0], k[2], k[3], k[1])):
        sc, var, kind, vus, mode = key
        if variants_filter and var not in variants_filter:
            continue
        runs = data[key]
        meas = [n for n in sorted(runs) if n != 1]
        label = lambda n: f'{sc}_{var}_{kind}_vu{vus}_{mode}_run{n}'
        def series(metric, cast=float):
            return [cast(runs[n][metric]) for n in meas if runs[n].get(metric) not in (None, '')]
        pooled = []
        rec = []
        for n in meas:
            pooled += [float(x['duration_ms']) for x in csv.DictReader(open(os.path.join(RAW, f'{label(n)}.requests.csv'), encoding='utf-8'))]
            rec.append(recount(label(n)))
        def mmm(xs):
            return {'median': st.median(xs), 'min': min(xs), 'max': max(xs)} if xs else None
        s = {
            'scenario': sc, 'variant': var, 'kind': kind, 'vus': vus, 'mode': mode, 'runs': meas,
            'success': mmm(series('success_200')),
            'error_5xx': mmm(series('error_5xx')),
            'duplicate_4xx': mmm(series('duplicate_4xx')),
            'innodb_deadlocks': mmm(series('innodb_lock_deadlocks_delta')),
            'innodb_lock_timeouts': mmm(series('innodb_lock_timeouts_delta')),
            'applog_1213': mmm([a for a, _ in rec]),
            'applog_1205': mmm([b for _, b in rec]),
            'p50_ms': mmm(series('p50_ms')), 'p95_ms': mmm(series('p95_ms')), 'p99_ms': mmm(series('p99_ms')),
            'pooled_n': len(pooled), 'pooled_p50_ms': pct(pooled, 50), 'pooled_p95_ms': pct(pooled, 95),
            'pooled_p99_ms': pct(pooled, 99),
            'counter_mismatch_targets': mmm(series('counter_mismatch_targets')),
            'avg_mismatch_targets': mmm(series('avg_mismatch_targets_round1')),
            'avg_max_abs_diff_vs_true': mmm(series('avg_max_abs_diff_vs_true')),
            'rows_inserted': mmm(series('rows_inserted')),
        }
        summary.append(s)
        for k in ('pooled_p50_ms', 'pooled_p95_ms', 'pooled_p99_ms', 'pooled_n'):
            out_rows.append(['exp1', sc, var, 'pooled', k, s[k], f'kind={kind};vus={vus};mode={mode};runs=2-5'])
        out_rows.append(['exp1', sc, var, 'pooled', 'applog_1213_recount_median', s['applog_1213']['median'],
                         f'kind={kind};vus={vus};mode={mode};runs=2-5'])

    json.dump(summary, open(os.path.join(ROOT, 'raw', 'exp1-summary.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    with open(os.path.join(ROOT, 'results.csv'), 'a', newline='', encoding='utf-8') as f:
        csv.writer(f).writerows(out_rows)

    def fm(d, digits=0):
        if not d:
            return '-'
        f = lambda x: f'{x:.{digits}f}'
        return f(d['median']) if d['min'] == d['max'] else f"{f(d['median'])} ({f(d['min'])}~{f(d['max'])})"
    print('| 시나리오 | 경로 | VU | 변형 | 성공 | 5xx | InnoDB 교착 | 로그 1213 | 1205 | p95 회차 median (min~max) ms | p95 / p99 합산 ms (n) | 카운터 불일치 | 평균 불일치 대상 |')
    print('|---|---|---|---|---|---|---|---|---|---|---|---|---|')
    for s in summary:
        print(f"| {s['scenario']} | {s['kind']}{'·분산' if s['mode']=='distinct' else ''} | {s['vus']} | {s['variant']} | "
              f"{fm(s['success'])}/{s['vus']} | {fm(s['error_5xx'])} | {fm(s['innodb_deadlocks'])} | {fm(s['applog_1213'])} | "
              f"{fm(s['applog_1205'])} | {fm(s['p95_ms'])} | {s['pooled_p95_ms']:.0f} / {s['pooled_p99_ms']:.0f} ({s['pooled_n']}) | "
              f"{fm(s['counter_mismatch_targets'])} | {fm(s['avg_mismatch_targets'])} |")


if __name__ == '__main__':
    main(sys.argv[1:] or None)

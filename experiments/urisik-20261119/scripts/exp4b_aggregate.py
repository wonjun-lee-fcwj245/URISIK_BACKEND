"""실험 4 재설계(exp4b) 집계: results.csv + raw/exp4b → 마크다운 표.
- 설정당 2~4회차(1회차는 워밍업이라 제외)를 집계한다.
- SLO 최대 RPS(median, min~max), 단계별 SLO 통과 횟수, 단계별 성공 응답 p95 median, 실패 유형(5xx/연결 실패/404) 합계,
  드롭 합계, 자원(앱 CPU, Hikari 대기·사용, k6 컨테이너 CPU 포화 여부)
"""
import csv, collections, glob, json, os, statistics as st, sys
sys.stdout.reconfigure(encoding='utf-8')
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
rows = list(csv.DictReader(open(os.path.join(ROOT, 'results.csv'), encoding='utf-8')))
stage = collections.defaultdict(lambda: collections.defaultdict(dict))   # (cfg,run) -> rate -> metrics
summ = collections.defaultdict(dict)
for r in rows:
    if r['experiment'] != 'exp4b':
        continue
    cfg, run = r['variant'], int(r['run'])
    if r['scenario'] == 'arrival_summary':
        summ[cfg].setdefault(run, {})[r['metric']] = r['value']
    elif r['scenario'].startswith('arrival_r'):
        rate = int(r['scenario'][9:])
        try:
            stage[(cfg, run)][rate][r['metric']] = float(r['value'])
        except ValueError:
            stage[(cfg, run)][rate][r['metric']] = None
cfgs = ['default', 'hikari', 'tuned']
valid = {c: sorted(r for r in summ[c] if r >= 2) for c in cfgs}
print('집계 회차:', valid)
print('\n### SLO 최대 RPS\n')
print('| 설정 | 회차별 | median | min~max |')
print('|---|---|---|---|')
for c in cfgs:
    v = [float(summ[c][r]['max_rps_slo']) for r in valid[c]]
    print(f"| {c} | {', '.join(f'{x:.0f}' for x in v)} | **{st.median(v):.0f}** | {min(v):.0f}~{max(v):.0f} |")
def sustained(c, r, start=0):
    # 낮은 단계부터 연속으로 SLO를 통과한 마지막 단계(처음 실패하기 직전). 하나도 통과 못 하면 0
    best = 0
    for rt in sorted(stage[(c, r)]):
        if rt < start:
            continue
        if int(stage[(c, r)][rt]['meets_slo']):
            best = rt
        else:
            break
    return best
print('\n### 연속 통과 최대 RPS (낮은 단계부터 처음 실패 직전까지)\n')
print('| 설정 | 회차별 | median | min~max |')
print('|---|---|---|---|')
for c in cfgs:
    v = [sustained(c, r) for r in valid[c]]
    print(f"| {c} | {', '.join(str(x) for x in v)} | **{st.median(v):.0f}** | {min(v)}~{max(v)} |")
print('\n### 연속 통과 최대 RPS — 첫 단계(300, 회차 안 추가 워밍업) 제외, 350부터\n')
print('| 설정 | 회차별 | median | min~max |')
print('|---|---|---|---|')
for c in cfgs:
    v = [sustained(c, r, 350) for r in valid[c]]
    print(f"| {c} | {', '.join(str(x) for x in v)} | **{st.median(v):.0f}** | {min(v)}~{max(v)} |")
print('\n### 단계 통과율 (회차 × 단계 중 SLO 통과 비율)\n')
print('| 설정 | 350~450 | 500~600 | 350~600 전체 (300 제외) |')
print('|---|---|---|---|')
for c in cfgs:
    lo = [int(stage[(c, r)][rt]['meets_slo']) for r in valid[c] for rt in stage[(c, r)] if 350 <= rt <= 450]
    hi = [int(stage[(c, r)][rt]['meets_slo']) for r in valid[c] for rt in stage[(c, r)] if rt >= 500]
    al = lo + hi  # 300 단계 제외
    print(f"| {c} | {sum(lo)}/{len(lo)} | {sum(hi)}/{len(hi)} | {sum(al)}/{len(al)} ({sum(al)/len(al):.0%}) |")
rates = sorted({rt for k in stage for rt in stage[k]})
print('\n### 단계별 SLO 통과 횟수 / 성공 응답 p95 median (min~max) ms\n')
print('| 설정 | ' + ' | '.join(f'{rt}' for rt in rates) + ' |')
print('|---|' + '---|' * len(rates))
for c in cfgs:
    cells = []
    for rt in rates:
        ps = [stage[(c, r)][rt]['p95_success_ms'] for r in valid[c] if rt in stage[(c, r)] and stage[(c, r)][rt].get('p95_success_ms') is not None]
        ok = sum(int(stage[(c, r)][rt]['meets_slo']) for r in valid[c] if rt in stage[(c, r)])
        cells.append(f"{ok}/{len(valid[c])} · {st.median(ps):.0f} ({min(ps):.0f}~{max(ps):.0f})" if ps else '-')
    print(f'| {c} | ' + ' | '.join(cells) + ' |')
tot = collections.Counter()
for c in cfgs:
    for r in valid[c]:
        for rt, m in stage[(c, r)].items():
            tot['non200_stages_gt0'] += (m.get('non200_rate') or 0) > 0
            tot['404'] += (m.get('rate_404') or 0) > 0
            tot['5xx'] += (m.get('rate_5xx') or 0) > 0
            tot['conn_fail'] += (m.get('rate_conn_fail_other') or 0) > 0
            tot['stages'] += 1
print('\n### 실패 유형(단계 수 기준, 전체 단계 %d개)\n' % tot['stages'], dict(tot))
print('\n### 자원 (회차별 최댓값의 median)\n')
print('| 설정 | 앱 CPU max | MySQL CPU max | k6 컨테이너 CPU max (제한 350%) | Hikari active max | Hikari pending max |')
print('|---|---|---|---|---|---|')
def f(x):
    try: return float(str(x).rstrip('%'))
    except Exception: return None
for c in cfgs:
    agg = collections.defaultdict(list)
    for r in valid[c]:
        p = os.path.join(ROOT, 'raw', 'exp4b', f'arrival_{c}_run{r}.resources.csv')
        if not os.path.exists(p):
            continue
        d = list(csv.DictReader(open(p, encoding='utf-8')))
        for key, col in (('app', 'urisik-app_cpu'), ('mysql', 'urisik-mysql_cpu'), ('k6', 'urisik-k6_cpu'), ('act', 'hikari_active'), ('pend', 'hikari_pending')):
            vals = [f(x[col]) for x in d if col in x and f(x[col]) is not None]
            if vals:
                agg[key].append(max(vals))
    g = lambda k: f"{st.median(agg[k]):.0f}" if agg[k] else '-'
    print(f"| {c} | {g('app')}% | {g('mysql')}% | {g('k6')}% | {g('act')} | {g('pend')} |")

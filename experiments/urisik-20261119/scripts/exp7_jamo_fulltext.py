"""실험 7: MySQL만으로 오타 검색이 되는가 — 자모로 분해한 제목 컬럼 + FULLTEXT(ngram)
앱 코드는 바꾸지 않는다. 로컬 DB에 title_jamo 컬럼과 FULLTEXT(ngram) 인덱스를 추가하고(끝나면 삭제),
실험 2와 같은 오타 질의·기존 질의 세트로 DB에 직접 질의해 정답률을 ES+Nori+Jamo와 비교한다.
  python exp7_jamo_fulltext.py
"""
import csv
import json
import os
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding='utf-8')
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
RAW = os.path.join(ROOT, 'raw', 'exp7')
CSV_PATH = os.path.join(ROOT, 'results.csv')
sys.path.insert(0, HERE)
from exp2_typos import decompose  # noqa: E402
import exp2_score as sc  # noqa: E402


def mysql(sql, db='urisik', raw=False):
    r = subprocess.run(['docker', 'exec', '-i', 'urisik-mysql', 'sh', '-c',
                        'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B ' + db],
                       input=sql.encode('utf-8'), capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode('utf-8', 'replace'))
    return r.stdout.decode('utf-8')


def esc(s):
    return s.replace('\\', '\\\\').replace("'", "''")


def query_many(queries, col_expr_builder):
    """질의마다 표식 행을 찍어 한 세션으로 실행하고 결과를 질의별로 나눈다. 전체 소요 시간도 잰다."""
    stmts = []
    for i, q in enumerate(queries):
        stmts.append(f"select '###{i}';")
        stmts.append(col_expr_builder(q))
    t0 = time.time()
    out = mysql('\n'.join(stmts))
    elapsed = time.time() - t0
    res = [[] for _ in queries]
    cur = None
    for line in out.splitlines():
        if line.startswith('###'):
            cur = int(line[3:])
            continue
        if '\t' not in line or cur is None:
            continue
        rid, title = line.split('\t', 1)
        res[cur].append(('recipe', int(rid), title))
    return res, elapsed


def main():
    os.makedirs(RAW, exist_ok=True)
    # 1) 컬럼과 인덱스 추가, 자모 값 채우기
    cols = mysql("show columns from recipe like 'title_jamo'")
    if not cols.strip():
        mysql('alter table recipe add column title_jamo varchar(600) null')
    rows = [l.split('\t', 1) for l in mysql('select id, title from recipe order by id').splitlines() if l]
    batch = []
    for rid, title in rows:
        batch.append(f"update recipe set title_jamo='{esc(decompose(title))}' where id={rid};")
    mysql('\n'.join(batch))
    idx = mysql("show index from recipe where Key_name='ft_title_jamo'")
    t0 = time.time()
    if not idx.strip():
        mysql('alter table recipe add fulltext index ft_title_jamo (title_jamo) with parser ngram')
    print('index build s', round(time.time() - t0, 1), 'rows', len(rows))

    def ft_jamo(q):
        j = esc(decompose(q))
        return f"select id, title from recipe where match(title_jamo) against ('{j}' in natural language mode) limit 5;"

    legacy = json.load(open(os.path.join(ROOT, '..', '..', 'k6', 'data', 'search-queries.json'), encoding='utf-8'))
    out_rows = []
    summary = {}
    timing = {}
    for seed in ('42', '7', '2026'):
        label = f'seed{seed}_5korig'
        base = json.load(open(os.path.join(ROOT, 'raw', 'exp2', f'eval-{label}.json'), encoding='utf-8'))
        typo = json.load(open(os.path.join(ROOT, 'raw', 'exp2', f'typo-queries-seed{seed}.json'), encoding='utf-8'))['queries']
        tq = [x['typo'] for x in typo]
        res_t, el_t = query_many(tq, ft_jamo)
        res_l, _ = query_many([q['keyword'] for q in legacy], ft_jamo)
        timing[seed] = el_t / len(tq) * 1000
        methods = {'like': base['typo']['like'], 'fulltext': base['typo']['fulltext'],
                   'es_b3': base['typo']['es_b3'], 'ft_jamo': res_t}
        legacy_methods = {'like': base['legacy']['like'], 'fulltext': base['legacy']['fulltext'],
                          'es_b3': base['legacy']['es_b3'], 'ft_jamo': res_l}
        json.dump({'typo': res_t, 'legacy': res_l}, open(os.path.join(RAW, f'ft_jamo-{label}.json'), 'w', encoding='utf-8'), ensure_ascii=False)
        for m, res in methods.items():
            for dist in (1, 2, 3):
                idxs = [i for i, x in enumerate(typo) if x['jamoDistance'] == dist]
                l5 = sum(sc.loose_hit(res[i], typo[i]['dish']) for i in idxs) / len(idxs)
                summary.setdefault((m, dist), []).append(l5)
            # 자모만 고친 오타(음절 누락 제외)
            for dist in (1, 2, 3):
                idxs = [i for i, x in enumerate(typo) if x['jamoDistance'] == dist and 'syllable_drop' not in x['ops']]
                if idxs:
                    l5 = sum(sc.loose_hit(res[i], typo[i]['dish']) for i in idxs) / len(idxs)
                    summary.setdefault((m, f'{dist}-jamo-only'), []).append(l5)
        normal = [i for i, q in enumerate(legacy) if not q.get('isTypo') and not q.get('isBoundary')]
        typo20 = [i for i, q in enumerate(legacy) if q.get('isTypo') and not q.get('isBoundary')]
        hold = [i for i, q in enumerate(legacy) if q.get('isHoldout')]
        for m, res in legacy_methods.items():
            def p5(i):
                exp = legacy[i].get('expected') or []
                return sum(1 for _, _, t in res[i][:5] if any(e in (t or '') for e in exp)) / 5

            def top1(i):
                exp = legacy[i].get('expected') or []
                return bool(res[i]) and any(e in (res[i][0][2] or '') for e in exp)
            summary.setdefault((m, 'normal_p5'), []).append(sum(p5(i) for i in normal) / len(normal))
            summary.setdefault((m, 'typo_top1'), []).append(sum(top1(i) for i in typo20))
            summary.setdefault((m, 'holdout_top1'), []).append(sum(top1(i) for i in hold))
    names = {'like': 'MySQL LIKE', 'fulltext': 'FULLTEXT(제목 ngram)', 'ft_jamo': '**FULLTEXT(자모 컬럼 ngram)**', 'es_b3': 'ES+Nori+Jamo'}
    print('\n| 방식 | 자모 1곳(느슨@5) | 2곳 | 3곳 | 정상 P@5 | 오타 1위 | 처음 보는 오타 1위 |')
    print('|---|---|---|---|---|---|---|')
    f = lambda v: f'{min(v):.1%}~{max(v):.1%}' if abs(max(v) - min(v)) > 1e-9 else f'{v[0]:.1%}'
    for m in ('like', 'fulltext', 'ft_jamo', 'es_b3'):
        print(f"| {names[m]} | {f(summary[(m, 1)])} | {f(summary[(m, 2)])} | {f(summary[(m, 3)])} | {summary[(m, 'normal_p5')][0]:.1%} | "
              f"{int(summary[(m, 'typo_top1')][0])}/20 | {int(summary[(m, 'holdout_top1')][0])}/12 |")
    print('\n자모만 고친 오타(음절 누락 제외, 느슨@5):')
    print('| 방식 | 1곳 | 2곳 | 3곳 |')
    print('|---|---|---|---|')
    for m in ('like', 'fulltext', 'ft_jamo', 'es_b3'):
        print(f"| {names[m]} | " + ' | '.join(f(summary[(m, f'{d}-jamo-only')]) for d in (1, 2, 3)) + ' |')
    print('\nFULLTEXT(자모) 질의 600건 한 세션 평균 소요(ms/질의, 클라이언트 오버헤드 포함):', {k: round(v, 2) for k, v in timing.items()})
    for (m, k), v in summary.items():
        out_rows.append(['exp7', f'jamo_ft_{k}', m, 'seeds42-7-2026', 'value_median', round(sorted(v)[len(v) // 2], 4), f'min={min(v):.4f};max={max(v):.4f}'])
    with open(CSV_PATH, 'a', newline='', encoding='utf-8') as fh:
        csv.writer(fh).writerows(out_rows)
    # 2) 정리: 실험용 컬럼·인덱스 삭제(스키마 원복)
    mysql('alter table recipe drop index ft_title_jamo')
    mysql('alter table recipe drop column title_jamo')
    print('cleaned up title_jamo column and index')


if __name__ == '__main__':
    main()

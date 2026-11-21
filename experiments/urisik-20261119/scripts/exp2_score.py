"""실험 2 채점: eval-*.json → 표(마크다운) + results.csv 행.

정답 기준
  - 느슨(loose, 대표 지표): 상위 결과 제목에 원래 음식명(dish)이 포함됨
  - 엄격(strict, 참고)    : 원래 레시피 ID가 상위 5개에 있음. 같은 음식명 레시피가 여러 개라 과소평가된다
  - top1        : 1위 결과 기준 (엄격/느슨)
기존 질의 세트(k6/data/search-queries.json)는 기존 스크립트와 같은 정의로 계산
  - P@5 = 상위 5개 중 제목이 expected 중 하나를 포함하는 개수 / 5
  - 오타 1위 정답 = 1위 제목이 expected 중 하나를 포함

사용: python exp2_score.py eval-seed42_5k.json [eval-...json ...]
"""
import csv
import json
import os
import sys

sys.stdout.reconfigure(encoding='utf-8')
HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, '..', 'raw', 'exp2')
CSV_PATH = os.path.join(HERE, '..', 'results.csv')
sys.path.insert(0, HERE)
from exp2_typos import decompose  # noqa: E402


def strict_hit(res, rid, k=5):
    return any((t or '').lower() == 'recipe' and r == rid for t, r, _ in res[:k])


def loose_hit(res, dish, k=5):
    return any(dish in (title or '') for _, _, title in res[:k])


def classify_failure(q):
    reasons = []
    if q['jamoDistance'] >= 3:
        reasons.append('자모 3곳 이상')
    if 'syllable_drop' in q['ops']:
        reasons.append('음절 누락 포함')
    # fuzziness AUTO: 토큰 길이 3~5 → 1, 6+ → 2. 음식명 토큰의 자모 길이가 짧으면 허용치가 작다
    dish_len = len(decompose(q['dish']))
    if dish_len <= 5:
        reasons.append('짧은 음식명(자모 5 이하, 허용 편집 1)')
    return reasons or ['기타']


def score(path, rows_out):
    d = json.load(open(path, encoding='utf-8'))
    label = d['label']
    typo_q = json.load(open(os.path.join(RAW, f"typo-queries-{label.split('_')[0]}.json"), encoding='utf-8'))['queries']
    legacy_q = json.load(open(os.path.join(HERE, '..', '..', '..', 'k6', 'data', 'search-queries.json'), encoding='utf-8'))
    methods = list(d['typo'].keys())
    print(f'\n## {label}\n')
    print('| 방식 | 거리 | n | 엄격@5 | 느슨@5 | 엄격 1위 | 느슨 1위 |')
    print('|---|---|---|---|---|---|---|')
    summary = {}
    for m in methods:
        for dist in (1, 2, 3):
            idx = [i for i, q in enumerate(typo_q) if q['jamoDistance'] == dist]
            n = len(idx)
            s5 = sum(strict_hit(d['typo'][m][i], typo_q[i]['recipeId']) for i in idx)
            l5 = sum(loose_hit(d['typo'][m][i], typo_q[i]['dish']) for i in idx)
            s1 = sum(strict_hit(d['typo'][m][i], typo_q[i]['recipeId'], 1) for i in idx)
            l1 = sum(loose_hit(d['typo'][m][i], typo_q[i]['dish'], 1) for i in idx)
            summary[(m, dist)] = (n, s5, l5, s1, l1)
            print(f'| {m} | {dist} | {n} | {s5/n:.1%} | {l5/n:.1%} | {s1/n:.1%} | {l1/n:.1%} |')
            for k, v in (('strict_at5', s5 / n), ('loose_at5', l5 / n), ('strict_top1', s1 / n), ('loose_top1', l1 / n)):
                rows_out.append(['exp2', f'typo_d{dist}', m, label, k, round(v, 4), f'n={n}'])

    # 기존 질의 세트
    normal = [i for i, q in enumerate(legacy_q) if not q.get('isTypo') and not q.get('isBoundary')]
    typo20 = [i for i, q in enumerate(legacy_q) if q.get('isTypo') and not q.get('isBoundary')]
    holdout = [i for i, q in enumerate(legacy_q) if q.get('isHoldout')]
    boundary = [i for i, q in enumerate(legacy_q) if q.get('isBoundary')]
    print(f'\n기존 질의 세트: 정상 {len(normal)}, 오타 {len(typo20)} (그중 holdout {len(holdout)}), 경계 {len(boundary)}\n')
    print('| 방식 | 정상 P@5 | 오타 1위 정답 | holdout 1위 | 경계 1위 |')
    print('|---|---|---|---|---|')
    for m in methods:
        res = d['legacy'][m]
        def p5(i):
            exp = legacy_q[i].get('expected') or []
            return sum(1 for _, _, t in res[i][:5] if any(e in (t or '') for e in exp)) / 5
        def top1(i):
            exp = legacy_q[i].get('expected') or []
            return bool(res[i]) and any(e in (res[i][0][2] or '') for e in exp)
        np5 = sum(p5(i) for i in normal) / len(normal)
        t1 = sum(top1(i) for i in typo20)
        h1 = sum(top1(i) for i in holdout)
        b1 = sum(top1(i) for i in boundary)
        print(f'| {m} | {np5:.1%} | {t1}/{len(typo20)} | {h1}/{len(holdout)} | {b1}/{len(boundary)} |')
        rows_out += [['exp2', 'legacy', m, label, 'normal_p5', round(np5, 4), f'n={len(normal)}'],
                     ['exp2', 'legacy', m, label, 'typo_top1', t1, f'n={len(typo20)}'],
                     ['exp2', 'legacy', m, label, 'holdout_top1', h1, f'n={len(holdout)}'],
                     ['exp2', 'legacy', m, label, 'boundary_top1', b1, f'n={len(boundary)}']]

    # 실패 질의 (앱 기본값 es_b3, 거리 1~2)
    fails = []
    for i, q in enumerate(typo_q):
        if q['jamoDistance'] <= 2 and not loose_hit(d['typo']['es_b3'][i], q['dish']):
            top = d['typo']['es_b3'][i][0][2] if d['typo']['es_b3'][i] else '(결과 없음)'
            fails.append({**q, 'top1': top, 'reasons': classify_failure(q)})
    json.dump(fails, open(os.path.join(RAW, f'failures-{label}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    from collections import Counter
    c = Counter(r for f in fails for r in f['reasons'])
    print(f'\nes_b3 거리 1~2 느슨 기준 실패 {len(fails)}건, 원인 분류(중복 가능): {dict(c)}')
    return summary


def main():
    rows = []
    for name in sys.argv[1:]:
        score(os.path.join(RAW, name), rows)
    with open(CSV_PATH, 'a', newline='', encoding='utf-8') as f:
        csv.writer(f).writerows(rows)


if __name__ == '__main__':
    main()

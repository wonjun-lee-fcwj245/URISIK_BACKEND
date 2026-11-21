"""평균 별점 누적 오차 검증. 사용: python exp_scoresum.py --variant v4|s
레시피 20개 × 3세트(서로 다른 ID 범위)를 0으로 초기화하고 2,000건 리뷰를 동시에 쓴 뒤 저장된 값을 실제 집계와 비교한다."""
import argparse, csv, json, os, subprocess, sys
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
RAW = os.path.join(ROOT, 'raw', 'scoresum')
CSV_PATH = os.path.join(ROOT, 'results.csv')

def mysql(sql):
    r = subprocess.run(['docker', 'exec', '-i', 'urisik-mysql', 'sh', '-c', 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B urisik'],
                       input=sql.encode('utf-8'), capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode('utf-8', 'replace'))
    return [l.split('\t') for l in r.stdout.decode('utf-8').splitlines() if l]

def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--variant', required=True); a = ap.parse_args()
    os.makedirs(RAW, exist_ok=True)
    has_sum = a.variant in ('s', 'fix19')
    start = {'v4': 4001, 's': 4201, 'fix19': 4401}[a.variant]
    rows_out = []
    for n in range(3):
        base = start + 20 * n
        ids = ','.join(str(base + k) for k in range(20))
        mysql(f"delete from recipe_review where recipe_id in ({ids}); update recipe set review_count=0, avg_score=0" + (", score_sum=0" if has_sum else "") + f" where id in ({ids});")
        env = dict(os.environ, BASE=str(base), NREC='20')
        r = subprocess.run(['k6', 'run', '--no-color', '-q', os.path.join(HERE, 'exp-scoresum.js')], env=env, capture_output=True, text=True, encoding='utf-8', errors='replace')
        open(os.path.join(RAW, f'{a.variant}_set{n+1}.log'), 'w', encoding='utf-8').write(r.stdout + r.stderr)
        cols = "t.id, t.review_count, count(c.id), t.avg_score, round(avg(c.score),1), avg(c.score)" + (", t.score_sum, sum(c.score)" if has_sum else "")
        res = mysql(f"select {cols} from recipe t join recipe_review c on c.recipe_id=t.id where t.id in ({ids}) group by t.id, t.review_count, t.avg_score" + (", t.score_sum" if has_sum else "") + " order by t.id")
        open(os.path.join(RAW, f'{a.variant}_set{n+1}.tsv'), 'w', encoding='utf-8').write('\n'.join('\t'.join(x) for x in res))
        cnt_mis = sum(1 for x in res if int(x[1]) != int(x[2]))
        avg_mis = sum(1 for x in res if abs(float(x[3]) - float(x[4])) > 1e-9)
        max_diff = max(abs(float(x[3]) - float(x[5])) for x in res)
        sum_mis = sum(1 for x in res if has_sum and int(x[6]) != int(x[7]))
        total = sum(int(x[2]) for x in res)
        d = dict(recipes=len(res), reviews=total, count_mismatch=cnt_mis, avg_mismatch_rounded=avg_mis, max_abs_diff_vs_exact=round(max_diff, 4), score_sum_mismatch=sum_mis if has_sum else '')
        print(a.variant, 'set', n + 1, d)
        rows_out += [['scoresum', f'20recipes_2000reviews', a.variant, n + 1, k, v, ''] for k, v in d.items()]
    new = not os.path.exists(CSV_PATH)
    with open(CSV_PATH, 'a', newline='', encoding='utf-8') as f:
        csv.writer(f).writerows(rows_out)

if __name__ == '__main__':
    main()

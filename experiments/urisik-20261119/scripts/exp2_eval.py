"""실험 2: 검색 방식별 정답률 평가 (검색 계층 직접 질의).

앱의 검색 API는 내부 검색 뒤 외부 API(식품안전나라) 호출·변형 레시피 병합을 함께 하므로,
정답률은 앱과 같은 쿼리를 DB/ES에 직접 보내 측정한다. 쿼리는 앱 코드와 같은 형태로 재현했다.
  - like     : RecipeRepository.findByTitleContainingIgnoreCase  → UPPER(title) LIKE UPPER('%kw%') LIMIT 5
  - fulltext : RecipeRepository.findByTitleFulltext              → MATCH(title) AGAINST(kw IN BOOLEAN MODE) LIMIT 5
               (앱처럼 Boolean 연산자 제거, 1글자는 LIKE fallback)
  - es_b0    : ES+Nori 단독 (multi_match title^2, ingredientsRaw)
  - es_bN    : ES+Nori+Jamo (위 + match titleJamo fuzziness AUTO boost N). 앱 기본값은 N=3
앱 코드의 boost는 3.0 고정이므로 boost 민감도는 ES 직접 질의로만 측정할 수 있다.

사용: python exp2_eval.py --typos ../raw/exp2/typo-queries-seed42.json --label seed42_5k
"""
import argparse
import json
import os
import re
import subprocess
import sys
import urllib.request

sys.stdout.reconfigure(encoding='utf-8')
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
sys.path.insert(0, HERE)
from exp2_typos import decompose  # noqa: E402

BOOST = [0, 1, 2, 3, 5, 8]
BOOLEAN_OPS = re.compile(r'[+\-<>()~*"@]')
ES = 'http://localhost:9200'
TOOK = {}  # boost -> ES 엔진 처리 시간(took, ms) 목록. msearch 안 개별 검색의 took


def es_body(kw, boost, size=5):
    should = [{'multi_match': {'query': kw, 'fields': ['title^2', 'ingredientsRaw']}}]
    if boost > 0:
        should.append({'match': {'titleJamo': {'query': decompose(kw), 'fuzziness': 'AUTO', 'boost': float(boost)}}})
    return {'size': size, 'query': {'bool': {'should': should}}, '_source': ['recipeId', 'title', 'type']}


def es_search_many(keywords, boost):
    out = []
    for i in range(0, len(keywords), 200):
        chunk = keywords[i:i + 200]
        lines = []
        for kw in chunk:
            lines.append(json.dumps({'index': 'recipes'}))
            lines.append(json.dumps(es_body(kw, boost), ensure_ascii=False))
        data = ('\n'.join(lines) + '\n').encode('utf-8')
        req = urllib.request.Request(f'{ES}/_msearch', data=data, headers={'Content-Type': 'application/x-ndjson'})
        resp = json.load(urllib.request.urlopen(req, timeout=120))
        for r in resp['responses']:
            hits = r.get('hits', {}).get('hits', [])
            out.append([(h['_source'].get('type'), h['_source'].get('recipeId'), h['_source'].get('title')) for h in hits])
            TOOK.setdefault(boost, []).append(r.get('took'))
    return out


def mysql_many(keywords, method):
    stmts = []
    for i, kw in enumerate(keywords):
        k = kw.replace('\\', '\\\\').replace("'", "''")
        stmts.append(f"select '###{i}';")
        if method == 'like':
            stmts.append(f"select id, title from recipe where upper(title) like upper('%{k}%') limit 5;")
        else:
            s = BOOLEAN_OPS.sub('', kw).replace("'", "''")
            if not s.strip():
                continue
            if len(s) == 1:
                stmts.append(f"select id, title from recipe where upper(title) like upper('%{s}%') limit 5;")
            else:
                stmts.append(f"select id, title from recipe where match(title) against('{s}' in boolean mode) limit 5;")
    r = subprocess.run(['docker', 'exec', '-i', 'urisik-mysql', 'sh', '-c',
                        'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B urisik'],
                       input='\n'.join(stmts).encode('utf-8'), capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode('utf-8', 'replace'))
    res = [[] for _ in keywords]
    cur = None
    for line in r.stdout.decode('utf-8').splitlines():
        if line.startswith('###'):
            cur = int(line[3:])
            continue
        rid, title = line.split('\t', 1)
        res[cur].append(('recipe', int(rid), title))
    return res


def run_method(method, keywords):
    if method in ('like', 'fulltext'):
        return mysql_many(keywords, method)
    return es_search_many(keywords, int(method[4:]))


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--typos', required=True)
    p.add_argument('--label', required=True)
    p.add_argument('--queries', default=os.path.join(REPO, 'k6', 'data', 'search-queries.json'))
    a = p.parse_args()
    typo = json.load(open(a.typos, encoding='utf-8'))['queries']
    legacy = json.load(open(a.queries, encoding='utf-8'))
    methods = ['like', 'fulltext'] + [f'es_b{b}' for b in BOOST]

    results = {'label': a.label, 'typo': {}, 'legacy': {}}
    tkw = [q['typo'] for q in typo]
    lkw = [q['keyword'] for q in legacy]
    for m in methods:
        results['typo'][m] = run_method(m, tkw)
        results['legacy'][m] = run_method(m, lkw)
        print('done', m, file=sys.stderr)
    results['es_took_ms'] = {str(k): v for k, v in TOOK.items()}
    out = os.path.join(HERE, '..', 'raw', 'exp2', f'eval-{a.label}.json')
    json.dump(results, open(out, 'w', encoding='utf-8'), ensure_ascii=False)
    print('saved', out)


if __name__ == '__main__':
    main()

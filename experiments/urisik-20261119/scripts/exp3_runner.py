"""실험 3 실행기: 추천 API 캐시.

  python exp3_runner.py prep                  # 사용자 101~1000 추가, 앱(v4, 기본 compose 설정) 기동
  python exp3_runner.py latency --reps 5      # cold/warm × VU 1/50, 회차마다 조건 순서 회전
  python exp3_runner.py invalidation --trials 50
  python exp3_runner.py redis-down

적중률은 Micrometer cache.gets{cache=recommend*, result=hit|miss}의 실행 전후 차이로 계산한다
(Redis INFO는 모든 캐시를 합친 값이라 쓰지 않는다).
"""
import argparse
import csv
import json
import os
import statistics as st
import subprocess
import threading
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
REPO = os.path.abspath(os.path.join(ROOT, '..', '..'))
RAW = os.path.join(ROOT, 'raw', 'exp3')
CSV_PATH = os.path.join(ROOT, 'results.csv')
BASE = 'http://localhost:8080'
RECO_CACHES = ['recommendSafe', 'recommendHighScore', 'recommendSafeHighScore', 'recommendWish']
ZIPF_S = '1.0'


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace', **kw)


def mysql(sql):
    r = subprocess.run(['docker', 'exec', '-i', 'urisik-mysql', 'sh', '-c',
                        'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B urisik'],
                       input=sql.encode('utf-8'), capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode('utf-8', 'replace'))
    return r.stdout.decode('utf-8')


def http_json(method, path, token=None, body=None, timeout=10):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if token:
        req.add_header('Authorization', f'Bearer {token}')
    if data:
        req.add_header('Content-Type', 'application/json')
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, json.load(r)


def token(member_id):
    return http_json('POST', f'/api/auth/test-token?memberId={member_id}')[1]['result']['accessToken']


def wait_ready(timeout=300):
    start = time.time()
    while time.time() - start < timeout:
        try:
            token(1)
            return
        except Exception:
            time.sleep(3)
    raise RuntimeError('app not ready')


def cache_counts():
    out = {}
    for c in RECO_CACHES:
        for res in ('hit', 'miss'):
            try:
                _, d = http_json('GET', f'/actuator/metrics/cache.gets?tag=cache:{c}&tag=result:{res}')
                out[(c, res)] = d['measurements'][0]['value']
            except Exception:
                out[(c, res)] = 0.0
    return out


def append(rows):
    new = not os.path.exists(CSV_PATH)
    with open(CSV_PATH, 'a', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        if new:
            w.writerow(['experiment', 'scenario', 'variant', 'run', 'metric', 'value', 'note'])
        w.writerows(rows)


def prep():
    mysql(open(os.path.join(HERE, 'prep-exp3.sql'), encoding='utf-8').read())
    env = dict(os.environ, APP_VARIANT='v4')
    sh(['docker', 'compose', '-f', os.path.join(REPO, 'docker-compose.local.yml'), '-f', os.path.join(HERE, 'compose.variant.yml'),
        'up', '-d', '--no-build', '--pull', 'never', '--force-recreate', 'app'], env=env, cwd=REPO)
    wait_ready()
    time.sleep(10)
    print('members', mysql('select count(*) from member').strip())


def k6(mode, vus, label):
    jout = os.path.join(RAW, f'{label}.json')
    r = sh(['k6', 'run', '--no-color', '--summary-export', jout, os.path.join(HERE, 'exp3-cache.js')],
           env=dict(os.environ, MODE=mode, VUS=str(vus), ZIPF_S=ZIPF_S))
    open(os.path.join(RAW, f'{label}.log'), 'w', encoding='utf-8').write(r.stdout + '\n' + r.stderr)
    return json.load(open(jout, encoding='utf-8'))['metrics']


def latency(reps):
    conds = [('cold', 1), ('cold', 50), ('warm', 1), ('warm', 50)]
    for n in range(1, reps + 1):
        order = conds[(n - 1) % 4:] + conds[:(n - 1) % 4]
        for mode, vus in order:
            sh(['docker', 'exec', 'urisik-redis', 'redis-cli', 'FLUSHALL'])
            if mode == 'warm':
                k6('prefill', 10, f'prefill_run{n}_vu{vus}')
            before = cache_counts()
            label = f'{mode}_vu{vus}_run{n}'
            m = k6(mode, vus, label)
            time.sleep(1)
            after = cache_counts()
            hit = sum(after[(c, 'hit')] - before[(c, 'hit')] for c in RECO_CACHES)
            miss = sum(after[(c, 'miss')] - before[(c, 'miss')] for c in RECO_CACHES)
            d = m['http_req_duration{name:reco}']
            vals = {'avg_ms': d['avg'], 'p50_ms': d['med'], 'p95_ms': d['p(95)'], 'p99_ms': d['p(99)'],
                    'fail_rate': m['http_req_failed{name:reco}']['value'],
                    'cache_hit': hit, 'cache_miss': miss, 'hit_ratio': round(hit / (hit + miss), 4) if hit + miss else '',
                    'miss_ratio': round(miss / (hit + miss), 4) if hit + miss else ''}
            for ep in ('safe-recipes', 'high-score', 'safe-high-score', 'wish'):
                vals[f'p95_ms_{ep}'] = m.get(f'http_req_duration{{ep:{ep}}}', {}).get('p(95)')
            append([['exp3', f'{mode}_vu{vus}', 'v4', n, k, v, f'N=1000;zipf_s={ZIPF_S};warmup={"yes" if n == 1 else "no"}']
                    for k, v in vals.items()])
            print(label, json.dumps(vals))


def redis_stats():
    r = sh(['docker', 'exec', 'urisik-redis', 'redis-cli', 'INFO', 'stats']).stdout
    d = dict(l.strip().split(':', 1) for l in r.splitlines() if ':' in l)
    return int(d['keyspace_hits']), int(d['keyspace_misses'])


def hitrate():
    """추천 캐시 적중률 (앱에 Redis 캐시 통계가 꺼져 있어 Micrometer cache.gets에 값이 없다).
    조건마다: FLUSHALL → (warm이면 prefill) → CONFIG RESETSTAT → 추천 API만 호출 → INFO stats의 keyspace_hits/misses.
    이 값은 추천 캐시 조회뿐 아니라 그 안에서 호출되는 familyAllergens 캐시 조회도 포함한다(같은 Redis).
    """
    for n in (1, 2, 3):
        for mode, vus in (('cold', 1), ('cold', 50), ('warm', 1), ('warm', 50)):
            sh(['docker', 'exec', 'urisik-redis', 'redis-cli', 'FLUSHALL'])
            if mode == 'warm':
                k6('prefill', 10, f'hitrate_prefill_{mode}_vu{vus}_run{n}')
            sh(['docker', 'exec', 'urisik-redis', 'redis-cli', 'CONFIG', 'RESETSTAT'])
            k6(mode, vus, f'hitrate_{mode}_vu{vus}_run{n}')
            time.sleep(1)
            hit, miss = redis_stats()
            tot = hit + miss
            vals = {'redis_hits': hit, 'redis_misses': miss,
                    'hit_ratio': round(hit / tot, 4) if tot else '', 'miss_ratio': round(miss / tot, 4) if tot else ''}
            append([['exp3', f'hitrate_{mode}_vu{vus}', 'v4', n, k, v, 'redis INFO stats after RESETSTAT; includes familyAllergens cache lookups']
                    for k, v in vals.items()])
            print(f'hitrate_{mode}_vu{vus}_run{n}', json.dumps(vals))


def timeseries(rate=30, duration=420, window=10):
    """빈 캐시 + Zipf 트래픽: 10초 창마다 요청 단위 적중률을 계산한다.
    요청 1건당 추천 캐시 조회 1회, 추천 캐시가 미적중이면 가족 알레르기 캐시 조회가 1회 더 생긴다.
    따라서 창 안의 Redis 조회 수 L = R + (추천 캐시 미적중 수) 이므로 추천 캐시 미적중 = L - R, R = rate * window.
    """
    sh(['docker', 'exec', 'urisik-redis', 'redis-cli', 'FLUSHALL'])
    sh(['docker', 'exec', 'urisik-redis', 'redis-cli', 'CONFIG', 'RESETSTAT'])
    jout = os.path.join(RAW, 'timeseries.json')
    env = dict(os.environ, RATE=str(rate), DURATION_S=str(duration), USERS='1000', ZIPF_S='1.0')
    proc = subprocess.Popen(['k6', 'run', '--no-color', '-q', '--summary-export', jout,
                             os.path.join(HERE, 'exp3-timeseries.js')], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    # setup(토큰 1,000개 발급)이 끝나 요청이 시작될 때까지 기다린다: Redis 조회가 늘기 시작하는 시점
    t_wait = time.time()
    while True:
        h, m = redis_stats()
        if h + m > 0 or time.time() - t_wait > 300:
            break
        time.sleep(1)
    prev_l = h + m
    t0 = time.time()
    rows = []
    while proc.poll() is None:
        time.sleep(window)
        h, m = redis_stats()
        l = h + m
        d_l = l - prev_l
        prev_l = l
        elapsed = round(time.time() - t0)
        r = rate * window
        miss_req = max(0, d_l - r)
        rows.append({'t_s': elapsed, 'redis_lookups': d_l, 'requests_est': r, 'reco_miss_est': miss_req,
                     'hit_ratio_est': round(1 - miss_req / r, 4) if r else None})
        print(json.dumps(rows[-1]))
    out = proc.communicate()[0] or ''
    open(os.path.join(RAW, 'timeseries.log'), 'w', encoding='utf-8').write(out)
    json.dump(rows, open(os.path.join(RAW, 'timeseries-windows.json'), 'w'), indent=1)
    m = json.load(open(jout, encoding='utf-8'))['metrics']
    d = m['http_req_duration{name:reco}'] if 'http_req_duration{name:reco}' in m else m['http_req_duration']
    reqs = m['http_reqs']['count']
    append([['exp3', 'timeseries', 'v4', 1, 'window_hit_ratio_t%03d' % r['t_s'], r['hit_ratio_est'], f"rate={rate}rps;window={window}s;zipf_s=1.0;users=1000"] for r in rows]
           + [['exp3', 'timeseries', 'v4', 1, 'total_requests', reqs, ''], ['exp3', 'timeseries', 'v4', 1, 'p95_ms', d['p(95)'], '']])
    print('total requests (setup 포함)', reqs)


def invalidation(trials):
    """찜 추가 → (afterCommit) Kafka cache-evict → 추천 캐시 삭제 → 새 값이 보일 때까지의 시간.

    매 시도: 사용자 U의 /wish 추천 캐시를 채운다 → SQL로 레시피 X의 wish_count를 현재 최댓값으로 맞춘다
    (SQL 변경은 evict를 부르지 않으므로 캐시는 계속 옛 값) → 캐시가 옛 값을 주는지 확인 →
    U가 X를 찜(API, wish_count+1로 X가 단독 1위) → 50ms 간격으로 /wish를 조회해 X가 처음 보일 때까지 시간 측정.
    동시에 다른 스레드가 10ms 간격으로 같은 캐시를 읽어, evict가 커밋 전에 일어나면 옛 값이 다시 캐시되는 상황을 유도한다.
    10초 안에 새 값이 보이지 않으면 '지속 stale'로 기록한다.
    """
    results = []
    used = set(int(x) for x in mysql('select recipe_id from personal_wish_list').split())
    candidates = [int(x) for x in mysql('select id from recipe where id between 3000 and 4999 order by id').split() if int(x) not in used]
    for t in range(trials):
        u = 1 + (t % 100)
        tok = token(u)
        x = candidates[t]
        http_json('GET', '/api/recommendations/home/wish', tok)          # 캐시 채우기
        top = int(mysql('select max(wish_count) from recipe').strip())
        mysql(f'update recipe set wish_count={top} where id={x}')
        _, body = http_json('GET', '/api/recommendations/home/wish', tok)
        ids = [r['id'] for r in body['result']['recipes']]
        cached_old = str(x) not in ids
        stop = threading.Event()
        def hammer():
            while not stop.is_set():
                try:
                    http_json('GET', '/api/recommendations/home/wish', tok, timeout=5)
                except Exception:
                    pass
                time.sleep(0.01)
        th = threading.Thread(target=hammer, daemon=True)
        th.start()
        t0 = time.time()
        st_, _ = http_json('POST', f'/api/family-rooms/{u}/profile-wishes', tok, {'recipeId': [x], 'transformedRecipeId': []})
        t_resp = time.time()
        stale_reads, fresh_at = 0, None
        while time.time() - t_resp < 10:
            _, body = http_json('GET', '/api/recommendations/home/wish', tok)
            if str(x) in [r['id'] for r in body['result']['recipes']]:
                fresh_at = time.time()
                break
            stale_reads += 1
            time.sleep(0.05)
        stop.set()
        th.join()
        rec = {'trial': t + 1, 'user': u, 'recipe': x, 'cached_old_before_write': cached_old, 'write_status': st_,
               'write_ms': round((t_resp - t0) * 1000, 1), 'stale_reads': stale_reads,
               'fresh_after_ms': round((fresh_at - t_resp) * 1000, 1) if fresh_at else None}
        results.append(rec)
        print(json.dumps(rec))
    json.dump(results, open(os.path.join(RAW, 'invalidation.json'), 'w'), indent=1)
    ok = [r['fresh_after_ms'] for r in results if r['fresh_after_ms'] is not None]
    def p(v, q):
        v = sorted(v); k = (len(v) - 1) * q; f = int(k); c = min(f + 1, len(v) - 1)
        return v[f] + (v[c] - v[f]) * (k - f)
    rows = [['exp3', 'invalidation', 'v4', 'all', k, v, f'trials={trials};poll=50ms;hammer=10ms'] for k, v in {
        'trials': len(results), 'precondition_cached_old': sum(r['cached_old_before_write'] for r in results),
        'persistent_stale_10s': sum(1 for r in results if r['fresh_after_ms'] is None),
        'fresh_p50_ms': p(ok, 0.5) if ok else '', 'fresh_p95_ms': p(ok, 0.95) if ok else '',
        'fresh_max_ms': max(ok) if ok else '', 'stale_reads_total': sum(r['stale_reads'] for r in results),
    }.items()]
    append(rows)
    print(rows)


def redis_down():
    tok = token(1)
    sh(['docker', 'exec', 'urisik-redis', 'redis-cli', 'FLUSHALL'])
    sh(['docker', 'stop', 'urisik-redis'])
    time.sleep(3)
    res = []
    for i in range(40):
        ep = ['safe-recipes', 'high-score', 'safe-high-score', 'wish'][i % 4]
        t0 = time.time()
        try:
            req = urllib.request.Request(f'{BASE}/api/recommendations/home/{ep}')
            req.add_header('Authorization', f'Bearer {tok}')
            with urllib.request.urlopen(req, timeout=30) as r:
                code = r.status
        except urllib.error.HTTPError as e:
            code = e.code
        except Exception as e:
            code = f'ERR:{type(e).__name__}'
        res.append({'ep': ep, 'status': code, 'ms': round((time.time() - t0) * 1000, 1)})
    sh(['docker', 'start', 'urisik-redis'])
    json.dump(res, open(os.path.join(RAW, 'redis-down.json'), 'w'), indent=1)
    codes = {}
    for r in res:
        codes[str(r['status'])] = codes.get(str(r['status']), 0) + 1
    ms = [r['ms'] for r in res]
    append([['exp3', 'redis_down', 'v4', 1, 'status_counts', json.dumps(codes), 'n=40'],
            ['exp3', 'redis_down', 'v4', 1, 'p50_ms', st.median(ms), 'n=40'],
            ['exp3', 'redis_down', 'v4', 1, 'max_ms', max(ms), 'n=40']])
    print(codes, st.median(ms), max(ms))
    wait_ready()


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest='cmd')
    sub.add_parser('prep')
    l = sub.add_parser('latency')
    l.add_argument('--reps', type=int, default=5)
    i = sub.add_parser('invalidation')
    i.add_argument('--trials', type=int, default=50)
    sub.add_parser('redis-down')
    sub.add_parser('hitrate')
    sub.add_parser('timeseries')
    a = p.parse_args()
    os.makedirs(RAW, exist_ok=True)
    {'prep': prep, 'redis-down': redis_down}.get(a.cmd, lambda: None)() if a.cmd in ('prep', 'redis-down') else None
    if a.cmd == 'latency':
        latency(a.reps)
    elif a.cmd == 'invalidation':
        invalidation(a.trials)
    elif a.cmd == 'hitrate':
        hitrate()
    elif a.cmd == 'timeseries':
        timeseries()


if __name__ == '__main__':
    main()

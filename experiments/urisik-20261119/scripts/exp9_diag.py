"""실험 9: 커넥션을 누가 쥐는지 관찰한다(앱 코드는 바꾸지 않는다).
  python exp9_diag.py sample     # 캐시 미적중 검색 100 RPS 60초 동안 3초마다: Hikari 게이지, Tomcat·executor 지표, MySQL 커넥션 상태, 컨테이너 CPU
  python exp9_diag.py leak       # Hikari 누수 탐지(100ms)로 오래 빌린 커넥션의 획득 위치(스택)를 로그로 모아 집계
이미지는 APP_VARIANT(기본 m = main 248edca), 외부 API stub 20ms, ES 검색.
"""
import collections
import json
import os
import re
import statistics as st
import subprocess
import sys
import threading
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
REPO = os.path.abspath(os.path.join(ROOT, '..', '..'))
RAW = os.path.join(ROOT, 'raw', 'exp9')
sys.path.insert(0, HERE)
import exp5_connhold as e5  # noqa: E402
import exp8_search_tx as e8  # noqa: E402
e5.RAW = RAW

VARIANT = os.environ.get('APP_VARIANT', 'm')


def gauge(name):
    try:
        with urllib.request.urlopen(f'http://localhost:8080/actuator/metrics/{name}', timeout=5) as r:
            d = json.load(r)
        m = {x['statistic']: x['value'] for x in d['measurements']}
        return m.get('VALUE', m.get('COUNT'))
    except Exception:
        return None


def mysql_rows(sql):
    r = subprocess.run(['docker', 'exec', '-i', 'urisik-mysql', 'sh', '-c',
                        'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B'],
                       input=sql.encode('utf-8'), capture_output=True)
    return [l.split('\t') for l in r.stdout.decode('utf-8', 'replace').splitlines() if l]


def container_cpu():
    r = subprocess.run(['docker', 'stats', '--no-stream', '--format', '{{.Name}} {{.CPUPerc}}',
                        'urisik-app', 'urisik-mysql', 'urisik-elasticsearch'], capture_output=True, text=True)
    out = {}
    for l in r.stdout.splitlines():
        n, c = l.split()
        out[n] = float(c.strip('%'))
    return out


def start_load():
    env = dict(os.environ, API='search', RATE='100', DURATION_S='60', USERS='100', KW_MODE='unique')
    return subprocess.Popen(['k6', 'run', '--no-color', '-q', os.path.join(HERE, 'exp5-connhold.js')], env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)


def sample_phase():
    os.makedirs(RAW, exist_ok=True)
    e8.switch(VARIANT)
    e5.restart_stub(20)
    proc = start_load()
    time.sleep(12)  # 램프업 후 정상 구간만 본다
    rows = []
    t_end = time.time() + 40
    while time.time() < t_end:
        s = {'t': round(time.time()), 'hikari_active': gauge('hikaricp.connections.active'),
             'hikari_pending': gauge('hikaricp.connections.pending'), 'hikari_idle': gauge('hikaricp.connections.idle'),
             'tomcat_busy': gauge('tomcat.threads.busy'), 'tomcat_current': gauge('tomcat.threads.current'),
             'executor_active': gauge('executor.active'), 'executor_queued': gauge('executor.queued'),
             'executor_pool': gauge('executor.pool.size')}
        pl = mysql_rows("select command, count(*), round(avg(time),1), max(time) from information_schema.processlist where db='urisik' group by command;")
        s['mysql'] = {r[0]: {'n': int(r[1]), 'avg_time_s': float(r[2]), 'max_time_s': int(r[3])} for r in pl}
        qs = mysql_rows("select left(replace(info,'\\n',' '),90), count(*) from information_schema.processlist where db='urisik' and command='Query' and info is not null group by 1 order by 2 desc limit 3;")
        s['top_queries'] = [[q[0], int(q[1])] for q in qs]
        s['cpu'] = container_cpu()
        rows.append(s)
        print(json.dumps(s, ensure_ascii=False), flush=True)
    out = proc.communicate()[0]
    open(os.path.join(RAW, 'sample-k6.log'), 'w', encoding='utf-8').write(out or '')
    json.dump(rows, open(os.path.join(RAW, f'samples-{VARIANT}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    def med(f):
        v = [f(x) for x in rows if f(x) is not None]
        return round(st.median(v), 1) if v else None
    summary = {
        'samples': len(rows),
        'hikari_active_median': med(lambda x: x['hikari_active']),
        'hikari_pending_median': med(lambda x: x['hikari_pending']),
        'tomcat_busy_median': med(lambda x: x['tomcat_busy']),
        'executor_active_median': med(lambda x: x['executor_active']),
        'executor_queued_median': med(lambda x: x['executor_queued']),
        'mysql_sleep_median': med(lambda x: x['mysql'].get('Sleep', {}).get('n', 0)),
        'mysql_query_median': med(lambda x: x['mysql'].get('Query', {}).get('n', 0)),
        'app_cpu_pct_median': med(lambda x: x['cpu'].get('urisik-app')),
        'mysql_cpu_pct_median': med(lambda x: x['cpu'].get('urisik-mysql')),
        'es_cpu_pct_median': med(lambda x: x['cpu'].get('urisik-elasticsearch')),
    }
    print('SUMMARY', json.dumps(summary), flush=True)
    rows_csv = [['exp9', 'sample_unique_100rps', VARIANT, 1, k, v, 'search unique kw 100rps; 3s sampling over 40s'] for k, v in summary.items()]
    e5.append(rows_csv)


def leak_phase():
    os.makedirs(RAW, exist_ok=True)
    files = ['-f', os.path.join(REPO, 'docker-compose.local.yml'), '-f', os.path.join(HERE, 'compose.variant.yml'),
             '-f', os.path.join(HERE, 'exp2', 'engine.yml'), '-f', os.path.join(HERE, 'exp2', 'stub.yml'),
             '-f', os.path.join(HERE, 'exp9', 'leak.yml')]
    env = dict(os.environ, APP_VARIANT=VARIANT, SEARCH_ENGINE='elasticsearch')
    r = subprocess.run(['docker', 'compose'] + files + ['up', '-d', '--no-build', '--pull', 'never', '--force-recreate', 'app'],
                       env=env, cwd=REPO, capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(r.stdout + r.stderr)
    t = time.time()
    while time.time() - t < 400:
        if 'Started UrisikBackendApplication' in subprocess.run(['docker', 'logs', 'urisik-app'], capture_output=True, text=True, errors='replace').stdout:
            break
        time.sleep(3)
    time.sleep(8)
    e5.restart_stub(20)
    t0 = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
    proc = start_load()
    out = proc.communicate()[0]
    open(os.path.join(RAW, 'leak-k6.log'), 'w', encoding='utf-8').write(out or '')
    logs = subprocess.run(['docker', 'logs', '--since', t0, 'urisik-app'], capture_output=True, text=True, errors='replace')
    text = logs.stdout + logs.stderr
    open(os.path.join(RAW, 'leak-app.log'), 'w', encoding='utf-8').write(text)
    blocks = re.split(r'(?=Connection leak detection triggered)', text)
    top = collections.Counter()
    deep = collections.Counter()
    n = 0
    for b in blocks:
        if not b.startswith('Connection leak detection triggered'):
            continue
        n += 1
        frames = re.findall(r'\n\s+at ([\w.$]+)\(', b)
        app = [f for f in frames if f.startswith('com.urisik')]
        top[app[0] if app else '(앱 프레임 없음)'] += 1
        deep[' > '.join([f.split('.')[-2] + '.' + f.split('.')[-1] for f in app[:3]])] += 1
    print('leak events', n)
    for k, v in top.most_common(8):
        print(v, k)
    json.dump({'events': n, 'top_frame': top.most_common(10), 'top_chain': deep.most_common(10)},
              open(os.path.join(RAW, 'leak-summary.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    e5.append([['exp9', 'leak_unique_100rps', VARIANT, 1, 'leak_events', n, 'threshold=100ms']])
    print('LEAK_DONE', flush=True)


if __name__ == '__main__':
    mode = sys.argv[1] if len(sys.argv) > 1 else 'sample'
    sample_phase() if mode == 'sample' else leak_phase()
    print('EXP9_DONE', flush=True)

"""실험 17: 부하 중 ES 서킷이 열리는 이유(ES 호출이 순간 500ms를 넘는 원인) 진단.
ES 우회가 관찰된 구성(전체 튜닝 + 수정 전, 도커 네트워크 stub)으로 실험 4 사다리를 한 번 돌리면서
ES 노드 지표를 2초마다 컨테이너 안에서 읽는다(ES 포트는 호스트에 열려 있지 않다).
  python exp17_es_diag.py
수집: 검색 스레드 풀(active, queue, rejected, completed), JVM GC(young/old 횟수·시간), 힙 사용률,
검색 질의 수·질의 시간 합계, 프로세스 CPU. 앱 쪽 ES 서킷 차단 수는 실험 4 수집기(es_cb_not_permitted)에 있다.
"""
import csv
import json
import os
import subprocess
import sys
import threading
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
RUN = os.environ.get('EXP17_RUN', '1')
LABEL = 'exp17_tuned_m_on'
RAW = os.path.join(ROOT, 'raw', LABEL)
FILTER = ('nodes.*.thread_pool.search,nodes.*.jvm.gc.collectors,nodes.*.jvm.mem.heap_used_percent,'
          'nodes.*.indices.search.query_total,nodes.*.indices.search.query_time_in_millis,nodes.*.process.cpu.percent')


def es_stats():
    r = subprocess.run(['docker', 'exec', 'urisik-elasticsearch', 'sh', '-c',
                        f'curl -s -m 3 "localhost:9200/_nodes/stats/thread_pool,jvm,indices,process?filter_path={FILTER}"'],
                       capture_output=True, text=True, errors='replace')
    if r.returncode != 0 or not r.stdout.strip():
        return None
    try:
        nodes = json.loads(r.stdout)['nodes']
    except (ValueError, KeyError):
        return None
    n = next(iter(nodes.values()))
    tp = n['thread_pool']['search']
    gc = n['jvm']['gc']['collectors']
    return {
        'search_active': tp.get('active'), 'search_queue': tp.get('queue'), 'search_rejected': tp.get('rejected'),
        'search_completed': tp.get('completed'),
        'gc_young_count': gc.get('young', {}).get('collection_count'), 'gc_young_ms': gc.get('young', {}).get('collection_time_in_millis'),
        'gc_old_count': gc.get('old', {}).get('collection_count'), 'gc_old_ms': gc.get('old', {}).get('collection_time_in_millis'),
        'heap_used_pct': n['jvm']['mem'].get('heap_used_percent'),
        'query_total': n['indices']['search'].get('query_total'), 'query_time_ms': n['indices']['search'].get('query_time_in_millis'),
        'es_cpu_pct': n['process']['cpu'].get('percent'),
    }


def app_es_timer():
    """앱이 재는 ES 호출 시간(클라이언트 쪽, search.es.query 타이머)의 누적 횟수·합계·최대."""
    try:
        with urllib.request.urlopen('http://localhost:8080/actuator/metrics/search.es.query', timeout=3) as r:
            m = {x['statistic']: x['value'] for x in json.load(r)['measurements']}
        return m.get('COUNT'), m.get('TOTAL_TIME'), m.get('MAX')
    except Exception:
        return None, None, None


def sampler(stop, path):
    cols = None
    with open(path, 'w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        while not stop.is_set():
            t = time.time()
            s = es_stats()
            if s is not None:
                s['app_es_count'], s['app_es_total_s'], s['app_es_max_s'] = app_es_timer()
                if cols is None:
                    cols = ['ts'] + list(s)
                    w.writerow(cols)
                w.writerow([time.strftime('%Y-%m-%dT%H:%M:%S', time.localtime(t))] + [s[c] for c in cols[1:]])
                f.flush()
            stop.wait(max(0.0, 2.0 - (time.time() - t)))


def main():
    os.makedirs(RAW, exist_ok=True)
    stop = threading.Event()
    th = threading.Thread(target=sampler, args=(stop, os.path.join(RAW, f'es-stats-run{RUN}.csv')), daemon=True)
    th.start()
    env = dict(os.environ, EXP4_STUB='net', EXP4_RATES='300,350,400,450,500,550,600', APP_VARIANT='m',
               EXP4_RAW=LABEL, EXP4_LABEL=LABEL, PYTHONUNBUFFERED='1')
    env.pop('EXP4_OSIV_OFF', None)
    r = subprocess.run([sys.executable, os.path.join(HERE, 'exp4b_runner.py'), 'arrival', '--cfg', 'tuned', '--run-no', RUN], env=env)
    stop.set()
    th.join(timeout=10)
    if r.returncode != 0:
        raise SystemExit(f'실험 4 실행 실패(returncode={r.returncode})')
    a = subprocess.run([sys.executable, os.path.join(HERE, 'exp4c_analyze.py'), 'tuned', RUN], env=env)
    if a.returncode != 0:
        raise SystemExit('단계 집계 실패')
    print('EXP17_DONE', flush=True)


if __name__ == '__main__':
    main()

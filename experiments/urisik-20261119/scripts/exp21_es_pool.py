"""실험 21: ES 서킷 우회의 원인이 ES 클라이언트 연결 풀(호스트당 10, 전체 30)인지 확인.

  python exp21_es_pool.py      # 4라운드(1회차 워밍업) × 2조건, 라운드마다 순서를 바꾼다

조건: 전체 튜닝(Hikari 50, Tomcat 500) + 수정 전 코드 + OSIV 켬, 도커 네트워크 stub(20ms), 원래 사다리(300→600).
이미지 urisik-app:mp = urisik-app:m(main 248edca) + EsClientPoolConfig(환경 변수로 연결 풀 크기 변경, 없으면 기본값)
  pool_default = 환경 변수 없음(라이브러리 기본: 호스트당 10, 전체 30)
  pool_50      = ES_MAX_CONN_PER_ROUTE=50, ES_MAX_CONN_TOTAL=100 (scripts/exp21/es-pool-50.yml)
수집(raw/exp21_<조건>/es-stats-run{N}.csv): 실험 17의 ES 노드 지표·앱 ES 호출 시간 + 앱→ES(9200) ESTABLISHED TCP 연결 수.
ES 우회 비율은 실험 4 수집기·분석기(exp4c_analyze)의 es_circuit_blocked_share_of_search.
"""
import csv
import os
import subprocess
import sys
import threading
import time

import exp17_es_diag as e17

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))


def es_conns():
    """앱 컨테이너에서 ES(포트 9200 = 0x23F0)로 ESTABLISHED(01) 상태인 TCP 연결 수."""
    r = subprocess.run(['docker', 'exec', 'urisik-app', 'sh', '-c', 'cat /proc/net/tcp /proc/net/tcp6 2>/dev/null'],
                       capture_output=True, text=True, errors='replace')
    n = 0
    for line in r.stdout.splitlines()[1:]:
        f = line.split()
        if len(f) > 3 and f[2].endswith(':23F0') and f[3] == '01':
            n += 1
    return n if r.returncode == 0 else ''


def sampler(stop, path):
    cols = None
    with open(path, 'w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        while not stop.is_set():
            t = time.time()
            s = e17.es_stats() or {}
            s['app_es_count'], s['app_es_total_s'], s['app_es_max_s'], s['app_es_error'] = e17.app_es_timer()
            s['app_es_tcp_established'] = es_conns()
            if cols is None and s.get('search_active') is not None:
                cols = ['ts'] + list(s)
                w.writerow(cols)
            if cols is not None:
                w.writerow([time.strftime('%Y-%m-%dT%H:%M:%S', time.localtime(t))] + [s.get(c, '') for c in cols[1:]])
                f.flush()
            stop.wait(max(0.0, 2.0 - (time.time() - t)))


def run_arm(arm, n):
    label = f'exp21_{arm}'
    raw = os.path.join(ROOT, 'raw', label)
    os.makedirs(raw, exist_ok=True)
    env = dict(os.environ, EXP4_STUB='net', EXP4_RATES='300,350,400,450,500,550,600', APP_VARIANT='mp',
               EXP4_RAW=label, EXP4_LABEL=label, PYTHONUNBUFFERED='1',
               EXP4_EXTRA='exp21/es-pool-50.yml' if arm == 'pool_50' else '')
    env.pop('EXP4_OSIV_OFF', None)
    stop = threading.Event()
    th = threading.Thread(target=sampler, args=(stop, os.path.join(raw, f'es-stats-run{n}.csv')), daemon=True)
    th.start()
    r = subprocess.run([sys.executable, os.path.join(HERE, 'exp4b_runner.py'), 'arrival', '--cfg', 'tuned', '--run-no', str(n)], env=env)
    stop.set()
    th.join(timeout=10)
    if r.returncode != 0:
        raise SystemExit(f'실험 4 실행 실패({arm} {n}, returncode={r.returncode})')
    a = subprocess.run([sys.executable, os.path.join(HERE, 'exp4c_analyze.py'), 'tuned', str(n)], env=env)
    if a.returncode != 0:
        raise SystemExit('단계 집계 실패')
    print('EXP21_RUN_DONE', arm, n, flush=True)


def main():
    import exp16_netstub_compare as m
    m.restart_stub_container(20)
    subprocess.run([sys.executable, os.path.join(HERE, 'exp4', 'prep_mealplan.py')], check=True)
    for n in (1, 2, 3, 4):
        for arm in (['pool_default', 'pool_50'] if n % 2 else ['pool_50', 'pool_default']):
            run_arm(arm, n)
    print('EXP21_DONE', flush=True)


if __name__ == '__main__':
    main()

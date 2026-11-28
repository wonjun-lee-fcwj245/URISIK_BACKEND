"""실험 23: ES 서킷 설정을 덜 민감하게 바꾸면 콜드 스타트 구간의 ES 우회가 줄어드는지 확인(실험 21과 같은 틀).

  EXP23_IMAGE=main45 python exp23_es_cb.py   # 4라운드(1회차 워밍업) × 2조건, 라운드마다 순서를 바꾼다

조건: 전체 튜닝(Hikari 50, Tomcat 500) + OSIV 켬, 도커 네트워크 stub(20ms), 원래 사다리(300→600). 이미지는 최신 main(#45 포함).
  cb_current = docker-compose.local.yml의 ES 서킷 설정(창 10건, 최소 5건, 느린 호출 500ms, 비율 80%)
  cb_relaxed = 창 50건, 최소 20건, 느린 호출 1초, 비율 80% (scripts/exp23/es-cb-relaxed.yml)
배포용 docker-compose.yml에는 Resilience4j 설정이 없어 배포는 라이브러리 기본값(창 100건, 느린 호출 60초)일 가능성이 크다.
수집은 실험 21과 같다(ES 노드 지표, 앱 ES 호출 시간, 앱→ES TCP 연결 수). ES 우회 비율은 exp4c_analyze의 es_circuit_blocked_share_of_search.
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
    label = f'exp23_{arm}'
    raw = os.path.join(ROOT, 'raw', label)
    os.makedirs(raw, exist_ok=True)
    env = dict(os.environ, EXP4_STUB='net', EXP4_RATES='300,350,400,450,500,550,600', APP_VARIANT=os.environ.get('EXP23_IMAGE', 'main45'),
               EXP4_RAW=label, EXP4_LABEL=label, PYTHONUNBUFFERED='1',
               EXP4_EXTRA='exp23/es-cb-relaxed.yml' if arm == 'cb_relaxed' else '')
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
    print('EXP23_RUN_DONE', arm, n, flush=True)


def main():
    import exp16_netstub_compare as m
    m.restart_stub_container(20)
    subprocess.run([sys.executable, os.path.join(HERE, 'exp4', 'prep_mealplan.py')], check=True)
    only = [x.split(':') for x in os.environ.get('EXP23_ONLY', '').split(',') if x]   # 예: cb_relaxed:3,cb_relaxed:4 (중단된 회차만 이어서)
    if only:
        for arm, n in only:
            run_arm(arm, int(n))
    else:
        for n in (1, 2, 3, 4):
            for arm in (['cb_current', 'cb_relaxed'] if n % 2 else ['cb_relaxed', 'cb_current']):
                run_arm(arm, n)
    print('EXP23_DONE', flush=True)


if __name__ == '__main__':
    main()

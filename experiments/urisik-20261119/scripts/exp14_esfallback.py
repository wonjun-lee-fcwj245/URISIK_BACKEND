"""실험 14: 핵심 비교(수정 전+OSIV 켬 vs 수정 후+OSIV 끔, 캐시 미적중 검색 100 RPS)를 다시 재면서
회차마다 (1) ES 서킷이 열려 MySQL로 우회한 요청 수, (2) ES 호출이 처음 실패한 원인을 기록한다.
  python exp14_esfallback.py       # 3라운드(1회차 워밍업)
"""
import json
import os
import subprocess
import sys
import threading
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import exp10_osiv as x10  # noqa: E402
import exp8_search_tx as e8  # noqa: E402
import exp5_connhold as e5  # noqa: E402

RAW = os.path.join(os.path.dirname(HERE), 'raw', 'exp14')


def poll_cb_errors(stop, sink):
    """부하 중 1초마다 ES 서킷브레이커의 ERROR 이벤트를 모아 서로 다른 오류 메시지를 기록한다."""
    seen = set()
    while not stop.is_set():
        try:
            with urllib.request.urlopen('http://localhost:8080/actuator/circuitbreakerevents/elasticsearchapi/error', timeout=3) as r:
                for ev in json.load(r).get('circuitBreakerEvents', []):
                    key = (ev.get('errorMessage') or '')[:160]
                    if key not in seen:
                        seen.add(key)
                        sink.append({'time': ev.get('creationTime'), 'error': key, 'durationMs': ev.get('duration')})
        except Exception:
            pass
        stop.wait(1.0)


def one_run(tag, variant, run_no):
    if tag == 'm_on':
        e8.switch(variant)
    else:
        x10.start_app(variant)
    e5.restart_stub(20)
    t0 = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
    stop, sink = threading.Event(), []
    th = threading.Thread(target=poll_cb_errors, args=(stop, sink), daemon=True)
    th.start()
    vals = e5.run_cond(f'{tag}_search_unique_100rps', 'search', 100, run_no, 'unique')
    stop.set()
    th.join(timeout=3)
    logs = subprocess.run(['docker', 'logs', '--since', t0, 'urisik-app'], capture_output=True, text=True, errors='replace')
    text = logs.stdout + logs.stderr
    fallback = text.count('ES 검색 실패, MySQL fallback')
    reqs = vals['requests']
    vals['es_fallback_requests'] = fallback
    vals['es_fallback_share'] = round(fallback / max(reqs, 1), 4)
    e5.append([['exp14', 'search_unique_100rps', tag, run_no, k, v, f'esfallback;rate=100rps;kw=unique;warmup={"yes" if run_no == 1 else "no"}']
               for k, v in vals.items()])
    json.dump(sink, open(os.path.join(RAW, f'{tag}_run{run_no}_cb_errors.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print(tag, 'run', run_no, json.dumps(vals), flush=True)
    print('   ES first errors:', json.dumps(sink[:3], ensure_ascii=False), flush=True)


def main():
    os.makedirs(RAW, exist_ok=True)
    e5.RAW = RAW
    for run_no in (1, 2, 3):
        for tag, variant in ((('m_on', 'm'), ('sf_off', 'sf')) if run_no % 2 else (('sf_off', 'sf'), ('m_on', 'm'))):
            one_run(tag, variant, run_no)
    print('EXP14_DONE', flush=True)


if __name__ == '__main__':
    main()

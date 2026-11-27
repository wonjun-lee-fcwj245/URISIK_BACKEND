"""실험 20: 실험 4의 정정 근거 확인. 같은 세션에서 호스트 stub과 도커 네트워크 stub을 번갈아 재고,
외부 API(foodSafetyApi) 호출·재시도 수를 2초마다 기록한다.

  python exp20_stub_ab.py        # 4라운드(1회차 워밍업) × 2조건, 라운드마다 순서를 바꾼다

조건: 기본 설정(Hikari 10, Tomcat 200) + main + OSIV 켬, 원래 사다리(300→600 RPS, 50 단위, 단계당 60초).
  host = EXP4_STUB=1 (extra_hosts host-gateway → 호스트 PC의 foodsafety_stub.py, 20ms)
  net  = EXP4_STUB=net (도커 네트워크 안 urisik-stubnet, 20ms)
수집(raw/exp20_<조건>/ext-calls-run{N}.csv): resilience4j.retry.calls{name=foodSafetyApi}의 kind별 누계
(코드의 인스턴스 이름은 foodSafetyApi다. 환경 변수 설정은 소문자 foodsafetyapi라는 별도 인스턴스로 묶인다.
 재시도는 캐시 바깥에 있어 캐시 적중도 successful_without_retry로 센다. 실제 HTTP 실패는 failed_* 와 *_with_retry로 본다),
외부 검색 캐시 키 수(Redis externalRecipeSearch*),
resilience4j.circuitbreaker.calls{name=foodSafetyApi}의 kind별 누계, not.permitted 누계. 앱은 회차마다 재시작되므로 누계는 회차 안에서만 의미가 있다.
"""
import csv
import json
import os
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
RETRY_KINDS = ['successful_without_retry', 'successful_with_retry', 'failed_with_retry', 'failed_without_retry']
CB_KINDS = ['successful', 'failed', 'ignored']


def metric(name, tags):
    q = '&'.join('tag=' + urllib.parse.quote(f'{k}:{v}') for k, v in tags.items())
    try:
        with urllib.request.urlopen(f'http://localhost:8080/actuator/metrics/{name}?{q}', timeout=3) as r:
            return sum(m['value'] for m in json.load(r)['measurements'] if m['statistic'] in ('COUNT', 'VALUE'))
    except urllib.error.HTTPError as e:
        return 0 if e.code == 404 else f'HTTP{e.code}'   # 404 = 아직 이 kind가 한 번도 없음
    except Exception as e:
        return f'ERR:{type(e).__name__}'


def redis_keys():
    """외부 API 검색 결과 캐시 키 수. 검색어 7개가 모두 캐시되면 7이고, 호출이 실패하면 캐시되지 않아 다시 호출된다."""
    r = subprocess.run(['docker', 'exec', 'urisik-redis', 'redis-cli', '--scan', '--pattern', 'externalRecipeSearch*'],
                       capture_output=True, text=True, errors='replace')
    return len([l for l in r.stdout.splitlines() if l.strip()]) if r.returncode == 0 else 'ERR'


def sampler(stop, path):
    cols = ['ts'] + [f'retry_{k}' for k in RETRY_KINDS] + [f'cb_{k}' for k in CB_KINDS] + ['cb_not_permitted', 'external_cache_keys']
    with open(path, 'w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(cols)
        while not stop.is_set():
            t = time.time()
            row = [time.strftime('%Y-%m-%dT%H:%M:%S', time.localtime(t))]
            row += [metric('resilience4j.retry.calls', {'name': 'foodSafetyApi', 'kind': k}) for k in RETRY_KINDS]
            row += [metric('resilience4j.circuitbreaker.calls', {'name': 'foodSafetyApi', 'kind': k}) for k in CB_KINDS]
            row += [metric('resilience4j.circuitbreaker.not.permitted.calls', {'name': 'foodSafetyApi'})]
            row += [redis_keys()]
            w.writerow(row)
            f.flush()
            stop.wait(max(0.0, 2.0 - (time.time() - t)))


def run_arm(arm, n):
    label = f'exp20_{arm}'
    raw = os.path.join(ROOT, 'raw', label)
    os.makedirs(raw, exist_ok=True)
    env = dict(os.environ, EXP4_STUB='1' if arm == 'host' else 'net', EXP4_RATES='300,350,400,450,500,550,600',
               APP_VARIANT='m', EXP4_RAW=label, EXP4_LABEL=label, PYTHONUNBUFFERED='1')
    env.pop('EXP4_OSIV_OFF', None)
    stop = threading.Event()
    th = threading.Thread(target=sampler, args=(stop, os.path.join(raw, f'ext-calls-run{n}.csv')), daemon=True)
    th.start()
    r = subprocess.run([sys.executable, os.path.join(HERE, 'exp4b_runner.py'), 'arrival', '--cfg', 'default', '--run-no', str(n)], env=env)
    stop.set()
    th.join(timeout=10)
    if r.returncode != 0:
        raise SystemExit(f'실험 4 실행 실패({arm} {n}, returncode={r.returncode})')
    a = subprocess.run([sys.executable, os.path.join(HERE, 'exp4c_analyze.py'), 'default', str(n)], env=env)
    if a.returncode != 0:
        raise SystemExit('단계 집계 실패')
    print('EXP20_RUN_DONE', arm, n, flush=True)


def main():
    import exp16_netstub_compare as m
    m.restart_stub_container(20)
    host = subprocess.Popen([sys.executable, os.path.join(HERE, 'exp2', 'foodsafety_stub.py'), '--port', '80', '--delay-ms', '20'],
                            stdout=open(os.path.join(ROOT, 'raw', 'exp20-hoststub.log'), 'w'), stderr=subprocess.STDOUT)
    try:
        time.sleep(2)
        if host.poll() is not None:
            raise SystemExit('호스트 stub이 뜨지 않음(raw/exp20-hoststub.log)')
        subprocess.run([sys.executable, os.path.join(HERE, 'exp4', 'prep_mealplan.py')], check=True)
        for n in (1, 2, 3, 4):
            order = ['host', 'net'] if n % 2 else ['net', 'host']
            for arm in order:
                run_arm(arm, n)
    finally:
        host.terminate()
    print('EXP20_DONE', flush=True)


if __name__ == '__main__':
    main()

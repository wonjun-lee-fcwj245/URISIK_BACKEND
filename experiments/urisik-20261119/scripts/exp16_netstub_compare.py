"""실험 16: 외부 API stub을 도커 네트워크 안(자바 stub 컨테이너 urisik-stubnet)에 두고 핵심 비교를 다시 잰다.
호스트 경로(host-gateway)의 `Connect timed out`+재시도가 포화에 섞였는지 가리기 위해서다.
조건: 캐시 미적중(검색어 유일) 검색 100 RPS, 수정 전+OSIV 켬(m_on) vs 수정 후+OSIV 끔(sf_off), 3라운드(1회차 워밍업), 번갈아 실행.
회차마다 ES 우회 요청 수와 외부 API 재시도 횟수도 기록한다.
  python exp16_netstub_compare.py       (urisik-stubnet 컨테이너가 떠 있어야 한다: exp15b_netstub.py가 기동)
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import exp5_connhold as e5  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, '..'))
REPO = os.path.abspath(os.path.join(ROOT, '..', '..'))
DELAY = os.environ.get('STUB_DELAY')          # 지정하면 stub 컨테이너를 그 지연(ms)으로 다시 띄운다
LABEL = os.environ.get('EXP_LABEL', 'exp16')
RAW = os.path.join(ROOT, 'raw', LABEL)


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace', **kw)


def start_app(variant, osiv_off):
    files = ['-f', os.path.join(REPO, 'docker-compose.local.yml'), '-f', os.path.join(HERE, 'compose.variant.yml'),
             '-f', os.path.join(HERE, 'exp2', 'engine.yml')]   # stub.yml(extra_hosts) 없음: DNS 별칭으로 stub 컨테이너에 연결
    if osiv_off:
        files += ['-f', os.path.join(HERE, 'exp10', 'osiv-off.yml')]
    env = dict(os.environ, APP_VARIANT=variant, SEARCH_ENGINE='elasticsearch')
    r = sh(['docker', 'compose'] + files + ['up', '-d', '--no-build', '--pull', 'never', '--force-recreate', 'app'], env=env, cwd=REPO)
    if r.returncode != 0:
        raise SystemExit(r.stdout + r.stderr)
    t = time.time()
    while time.time() - t < 400:
        if 'Started UrisikBackendApplication' in sh(['docker', 'logs', 'urisik-app']).stdout:
            break
        time.sleep(3)
    time.sleep(8)


def retry_count(kind):
    with urllib.request.urlopen(f'http://localhost:8080/actuator/metrics/resilience4j.retry.calls?tag=kind:{kind}', timeout=5) as x:
        return json.load(x)['measurements'][0]['value']


def one_run(tag, variant, osiv_off, run_no):
    start_app(variant, osiv_off)
    t0 = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
    vals = e5.run_cond(f'{tag}_search_unique_100rps', 'search', 100, run_no, 'unique')
    text = sh(['docker', 'logs', '--since', t0, 'urisik-app'])
    fallback = (text.stdout + text.stderr).count('ES 검색 실패, MySQL fallback')
    vals['es_fallback_requests'] = fallback
    vals['es_fallback_share'] = round(fallback / max(vals['requests'], 1), 4)
    vals['external_retry_success'] = retry_count('successful_with_retry')
    vals['external_retry_failed'] = retry_count('failed_with_retry')
    e5.append([[LABEL, 'search_unique_100rps', tag, run_no, k, v, f'netstub;stub_delay={DELAY or 20}ms;rate=100rps;kw=unique;warmup={"yes" if run_no == 1 else "no"}']
               for k, v in vals.items()])
    print(tag, 'run', run_no, json.dumps(vals), flush=True)


def restart_stub_container(delay_ms):
    sh(['docker', 'rm', '-f', 'urisik-stubnet'])
    env = dict(os.environ, MSYS_NO_PATHCONV='1')
    r = sh(['docker', 'run', '-d', '--name', 'urisik-stubnet', '--network', 'urisik_backend_default',
            '--network-alias', 'openapi.foodsafetykorea.go.kr', '-v', os.path.join(HERE, 'exp15').replace(chr(92), '/') + ':/stub',
            'gradle:8.5-jdk17', 'java', '/stub/Stub.java', '80', str(delay_ms)], env=env)
    if r.returncode != 0:
        raise SystemExit(r.stdout + r.stderr)
    time.sleep(12)


def main():
    os.makedirs(RAW, exist_ok=True)
    e5.RAW = RAW
    if DELAY:
        restart_stub_container(DELAY)
    for run_no in (1, 2, 3):
        order = (('m_on', 'm', False), ('sf_off', 'sf', True)) if run_no % 2 else (('sf_off', 'sf', True), ('m_on', 'm', False))
        for tag, variant, off in order:
            one_run(tag, variant, off, run_no)
    print('EXP16_DONE', flush=True)


if __name__ == '__main__':
    main()

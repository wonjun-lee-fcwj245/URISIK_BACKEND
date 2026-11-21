"""실험 10: OSIV(spring.jpa.open-in-view)를 끄면 검색의 커넥션 점유가 달라지는가.
앱 코드는 바꾸지 않고 환경 변수 SPRING_JPA_OPEN_IN_VIEW=false만 준다(scripts/exp10/osiv-off.yml).
  python exp10_osiv.py            # m(수정 전)·sf(트랜잭션 범위 축소) × OSIV 끔, 캐시 미적중 검색 100 RPS, 3라운드(1회차 워밍업)
비교 대상(OSIV 켬)은 exp8의 같은 조건 결과를 쓴다.
"""
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
REPO = os.path.abspath(os.path.join(ROOT, '..', '..'))
RAW = os.path.join(ROOT, 'raw', 'exp10')
sys.path.insert(0, HERE)
import exp5_connhold as e5  # noqa: E402
e5.RAW = RAW


def start_app(variant):
    files = ['-f', os.path.join(REPO, 'docker-compose.local.yml'), '-f', os.path.join(HERE, 'compose.variant.yml'),
             '-f', os.path.join(HERE, 'exp2', 'engine.yml'), '-f', os.path.join(HERE, 'exp2', 'stub.yml'),
             '-f', os.path.join(HERE, 'exp10', 'osiv-off.yml')]
    env = dict(os.environ, APP_VARIANT=variant, SEARCH_ENGINE='elasticsearch')
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
    logs = subprocess.run(['docker', 'logs', 'urisik-app'], capture_output=True, text=True, errors='replace')
    osiv_warn = 'open-in-view' in (logs.stdout + logs.stderr)
    return osiv_warn


def main():
    os.makedirs(RAW, exist_ok=True)
    checks = {}
    for run_no in (1, 2, 3):
        for variant in (('m', 'sf') if run_no % 2 == 1 else ('sf', 'm')):
            warn = start_app(variant)
            checks[f'{variant}_run{run_no}'] = {'osiv_warning_in_log': warn}
            e5.restart_stub(20)
            vals = e5.run_cond(f'{variant}_osiv_off_search_unique_100rps', 'search', 100, run_no, 'unique')
            e5.append([['exp10', 'search_unique_100rps_osiv_off', variant, run_no, k, v,
                        f'api=search;rate=100rps;60s;kw=unique;osiv=off;warmup={"yes" if run_no == 1 else "no"}'] for k, v in vals.items()])
            print(variant, 'osiv_off run', run_no, json.dumps(vals), flush=True)
    json.dump(checks, open(os.path.join(RAW, 'osiv-log-check.json'), 'w'), indent=1)
    print('OSIV warning still logged (should be False when off):', checks, flush=True)
    print('EXP10_DONE', flush=True)


if __name__ == '__main__':
    main()

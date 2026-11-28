"""실험 25: Redis 타임아웃 200ms 대 500ms 부하 중 Redis 중단(실험 6의 재측정, 같은 세션 번갈아).

  python exp25_redis_timeout_ab.py --rounds 3

앱 이미지 redisfix2(캐시 래퍼 포함, 실험 6의 2차 수정)를 쓰고 외부 API 대역은 도커 네트워크 안 stub(20ms)이다.
라운드마다 두 조건 순서를 바꾸고, 라운드 사이에 앱·Redis를 새로 만든다. 조건 이름: t200, t500.
"""
import argparse
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def main(rounds):
    import exp16_netstub_compare as m
    m.restart_stub_container(20)
    for n in range(1, rounds + 1):
        for arm in (['t200', 't500'] if n % 2 else ['t500', 't200']):
            env = dict(os.environ, EXP6_IMAGE='redisfix2', EXP6_STUB='net', EXP6_RAW='exp25_redis_timeout_ab',
                       EXP6_EXPERIMENT='exp25_redis_timeout_ab', PYTHONUNBUFFERED='1',
                       EXP6_OVERLAY='exp6/redis-timeout-200.yml' if arm == 't200' else 'exp6/redis-timeout-500.yml')
            r = subprocess.run([sys.executable, os.path.join(HERE, 'exp6_redis.py'), '--variant', arm, '--run-no', str(n)], env=env)
            if r.returncode != 0:
                raise SystemExit(f'exp6 실행 실패({arm} {n}, returncode={r.returncode})')
            print('EXP25_RUN_DONE', arm, n, flush=True)
    print('EXP25_DONE', flush=True)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--rounds', type=int, default=3)
    main(ap.parse_args().rounds)

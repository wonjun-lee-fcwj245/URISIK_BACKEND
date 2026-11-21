"""실험 9 보조: 스레드 덤프로 "트랜잭션 안에 있는 스레드가 무엇을 기다리는가"를 분류한다(앱 코드 변경 없음).
  APP_VARIANT=m  python exp9_dump.py     # 수정 전
  APP_VARIANT=sf python exp9_dump.py     # 수정 후
캐시 미적중 검색 100 RPS 부하 중 7초 간격으로 4번 `kill -3`(JVM이 스레드 덤프를 stdout으로 출력)을 보내고 docker logs에서 파싱한다.
"""
import collections
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import exp9_diag as d  # noqa: E402

VARIANT = d.VARIANT
RAW = d.RAW
FRAME = re.compile(r'\n\s+at ([\w.$<>]+)\(')
THREAD = re.compile(r'^\s*"(http-nio-8080-exec-\d+)"')


def classify(frames):
    """스택(맨 위 프레임부터)에서 무엇을 기다리는지 분류한다."""
    for f in frames:
        if 'HikariPool.getConnection' in f or 'ConcurrentBag.borrow' in f:
            return 'DB 커넥션 대기(풀)'
        if 'RestTemplate' in f or 'FoodSafetyRecipeClientImpl' in f:
            return '외부 API 호출'
        if 'co.elastic' in f or 'org.elasticsearch' in f or 'RecipeElasticSearchService' in f:
            return 'ES 호출'
        if 'io.lettuce' in f or 'org.springframework.data.redis' in f:
            return 'Redis 호출'
        if 'com.mysql' in f or 'org.hibernate.engine.jdbc' in f:
            return 'MySQL 통신'
    return '기타'


def main():
    os.makedirs(RAW, exist_ok=True)
    d.e8.switch(VARIANT)
    d.e5.restart_stub(20)
    t0 = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
    proc = d.start_load()
    time.sleep(18)
    for _ in range(4):
        subprocess.run(['docker', 'exec', 'urisik-app', 'sh', '-c', 'kill -3 1'], capture_output=True)
        time.sleep(7)
    proc.communicate()
    logs = subprocess.run(['docker', 'logs', '--since', t0, 'urisik-app'], capture_output=True, text=True, errors='replace')
    text = logs.stdout + logs.stderr
    open(os.path.join(RAW, f'threaddumps-{VARIANT}.log'), 'w', encoding='utf-8').write(text)
    dumps = text.split('Full thread dump')[1:]
    cnt = collections.Counter()
    nthreads = 0
    for dump in dumps:
        for block in dump.split('\n\n'):
            if not THREAD.match(block.lstrip('\n')):
                continue
            frames = FRAME.findall('\n' + block)
            in_tx = any('TransactionInterceptor.invoke' in f or 'TransactionTemplate.execute' in f for f in frames)
            cnt[(in_tx, classify(frames))] += 1
            nthreads += 1
    print(VARIANT, 'dumps', len(dumps), 'exec thread samples', nthreads, flush=True)
    rows = []
    for (in_tx, cat), n in sorted(cnt.items(), key=lambda x: -x[1]):
        print(f'  트랜잭션 안={in_tx} | {cat}: {n} ({n / max(nthreads, 1):.0%})', flush=True)
        rows.append(['exp9', f'threaddump_tx{int(in_tx)}_{cat}', VARIANT, 1, 'thread_share',
                     round(n / max(nthreads, 1), 4), f'dumps={len(dumps)};samples={nthreads}'])
    json.dump({'dumps': len(dumps), 'samples': nthreads, 'counts': {f'{k[0]}|{k[1]}': v for k, v in cnt.items()}},
              open(os.path.join(RAW, f'threaddump-summary-{VARIANT}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    d.e5.append(rows)
    print('DUMP_DONE', flush=True)


if __name__ == '__main__':
    main()

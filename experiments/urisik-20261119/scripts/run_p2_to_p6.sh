#!/usr/bin/env bash
# 우선순위 2~6을 차례로 실행 (측정 사이에 CPU가 겹치지 않게 순차)
#  2) 락 순서만 바꾼 재비교  3) 재시도 백오프·트랜잭션 상태  4) 캐시 적중률 시간 추이  5) 외부 API 모킹 후 검색 지연  6) 점수 합계 검증
set -euo pipefail
cd "$(dirname "$0")"
PYEOF_UNUSED=1
PYTHONUNBUFFERED=1 bash exp1_lock.sh     > ../raw/p2-lock-run.log 2>&1
PYTHONUNBUFFERED=1 bash exp1_retry2.sh   > ../raw/p3-retry2-run.log 2>&1
{
  python exp3_runner.py prep
  python exp3_runner.py timeseries
} > ../raw/p4-timeseries-run.log 2>&1
(python exp2/foodsafety_stub.py --port 80 --delay-ms 20 > /dev/null 2>&1 &) ; sleep 2
PYTHONUNBUFFERED=1 bash exp2_stub.sh     > ../raw/p5-stub-run.log 2>&1
{
  APP_VARIANT=v4 docker compose -f ../../../docker-compose.local.yml -f compose.variant.yml up -d --no-build --pull never --force-recreate app
  python exp1_runner.py switch v4
  python exp_scoresum.py --variant v4
  python exp1_runner.py switch s
  python exp_scoresum.py --variant s
} > ../raw/p6-scoresum-run.log 2>&1
echo P2_TO_P6_DONE

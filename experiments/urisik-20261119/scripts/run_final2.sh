#!/usr/bin/env bash
# 메모리 부족 중단 후 이어하기: 5(검색 지연 남은 4회차) → 6(점수 합계) → 7(처리량 재설계)
set -euo pipefail
cd "$(dirname "$0")"
R="python exp2_runner.py"
export EXP2_STUB=1
ENGINES=(mysql fulltext elasticsearch)
N=4
for i in 0 1 2; do
  E=${ENGINES[$(( (i + N) % 3 ))]}
  $R switch --engine $E
  $R latency --engine $E --vus 10 --run-no $N --dataset 5korig-stub
  $R latency --engine $E --vus 50 --run-no $N --dataset 5korig-stub
done
unset EXP2_STUB
echo STUB_DONE
APP_VARIANT=v4 docker compose -f ../../../docker-compose.local.yml -f compose.variant.yml up -d --no-build --pull never --force-recreate app
python exp1_runner.py switch v4
python exp_scoresum.py --variant v4
python exp1_runner.py switch s
python exp_scoresum.py --variant s
echo SCORESUM_DONE
bash exp4b_all.sh

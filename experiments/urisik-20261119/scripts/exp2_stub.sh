#!/usr/bin/env bash
# 외부 API를 stub(20ms 고정 지연)으로 바꾼 뒤 검색 지연 재측정: 5,000건, 엔진 3종 × VU 10/50, 4라운드(1회차 워밍업), 엔진 순서 회전
set -euo pipefail
cd "$(dirname "$0")"
R="python exp2_runner.py"
ENGINES=(mysql fulltext elasticsearch)
export EXP2_STUB=1
for N in 1 2 3 4; do
  for i in 0 1 2; do
    E=${ENGINES[$(( (i + N) % 3 ))]}
    $R switch --engine $E
    $R latency --engine $E --vus 10 --run-no $N --dataset 5korig-stub
    $R latency --engine $E --vus 50 --run-no $N --dataset 5korig-stub
  done
done
echo STUB_DONE

#!/usr/bin/env bash
# (도커 네트워크 안 stub 컨테이너, 호스트 경로 영향 제거) 외부 API를 stub(20ms 고정 지연)으로 바꾼 뒤 검색 지연 재측정: 5,000건, 엔진 3종 × VU 10/50, 4라운드(1회차 워밍업), 엔진 순서 회전
set -euo pipefail
cd "$(dirname "$0")"
R="python exp2_runner.py"
ENGINES=(mysql fulltext elasticsearch)
export EXP2_STUB=net EXP2_LABEL=exp2_netstub EXP2_RAW=exp2_netstub
python -c "import exp16_netstub_compare as m; m.restart_stub_container(20)"
for N in 1 2 3 4; do
  for i in 0 1 2; do
    E=${ENGINES[$(( (i + N) % 3 ))]}
    $R switch --engine $E
    $R latency --engine $E --vus 10 --run-no $N --dataset 5korig-netstub
    $R latency --engine $E --vus 50 --run-no $N --dataset 5korig-netstub
  done
done
echo NETSTUB_DONE

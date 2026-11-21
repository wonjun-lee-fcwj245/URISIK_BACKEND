#!/usr/bin/env bash
# 재시도 방식 비교(새 구현, 옛 58/100 재현이 아님): 같은 레시피에 100명 동시 리뷰, 이미지 r(0e223cf + 재시도 모드)
#  - inside3 : 같은 트랜잭션 안에서 최대 3회 재시도
#  - outside3: 트랜잭션 밖(새 트랜잭션)에서 최대 3회 재시도
#  - outside10: 트랜잭션 밖에서 최대 10회 재시도
# 5라운드, 라운드마다 순서를 회전. 1회차는 워밍업으로 집계 제외.
set -euo pipefail
cd "$(dirname "$0")"
R="python exp1_runner.py"
CFGS=("inside 3" "outside 3" "outside 10")
for N in 1 2 3 4 5; do
  for i in 0 1 2; do
    set -- ${CFGS[$(( (i + N - 1) % 3 ))]}
    MODE=$1; MAX=$2
    REVIEW_RETRY_MODE=$MODE REVIEW_RETRY_MAX=$MAX $R switch r
    $R warm
    $R run --scenario "RA_${MODE}${MAX}" --variant r --kind review --vus 100 --run-no "$N"
  done
done
echo RETRY_DONE

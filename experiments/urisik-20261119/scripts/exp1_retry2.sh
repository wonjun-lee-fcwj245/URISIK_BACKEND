#!/usr/bin/env bash
# 재시도 재측정 (r2 이미지): 지수 백오프(50,100,200,400,800,1600ms 상한 + 지터) 비교, 그리고 같은 트랜잭션 안 재시도의 트랜잭션 상태 로그
#  RB_outside3exp / RB_outside10exp : 5라운드(1회차 워밍업)
#  RB_inside3fixed : 전체 앱 로그 저장(EXP_SAVE_FULL_LOG)으로 3회
set -euo pipefail
cd "$(dirname "$0")"
R="python exp1_runner.py"
CFGS=("outside 3" "outside 10")
for N in 1 2 3 4 5; do
  for i in 0 1; do
    set -- ${CFGS[$(( (i + N - 1) % 2 ))]}
    MODE=$1; MAX=$2
    REVIEW_RETRY_MODE=$MODE REVIEW_RETRY_MAX=$MAX REVIEW_RETRY_BACKOFF=exp $R switch r2
    $R warm
    $R run --scenario "RB_${MODE}${MAX}exp" --variant r2 --kind review --vus 100 --run-no "$N"
  done
done
for N in 1 2 3; do
  REVIEW_RETRY_MODE=inside REVIEW_RETRY_MAX=3 REVIEW_RETRY_BACKOFF=fixed $R switch r2
  $R warm
  EXP_SAVE_FULL_LOG=1 $R run --scenario "RB_inside3fixed" --variant r2 --kind review --vus 100 --run-no "$N"
done
echo RETRY2_DONE

#!/usr/bin/env bash
# exp2_resume.sh가 ENGINES 선언 누락으로 50k 지연 측정 직전에 멈춰서, 남은 부분만 실행
#  - 50,000건(시드 42, 이미 적재됨) 지연: 엔진 3종 × VU 10/50 × 3회, 라운드마다 엔진 순서 회전
#  - seed-5k.sql로 복원 (실험 4·3 기준 데이터)
set -euo pipefail
cd "$(dirname "$0")"
R="python exp2_runner.py"
ENGINES=(mysql fulltext elasticsearch)
for N in 1 2 3; do
  for i in 0 1 2; do
    E=${ENGINES[$(( (i + N) % 3 ))]}
    $R switch --engine $E
    $R latency --engine $E --vus 10 --run-no $N --dataset 50ks42
    $R latency --engine $E --vus 50 --run-no $N --dataset 50ks42
  done
done
$R reseed --sql ../../../db/init/seed-5k.sql
# 기존 스크립트 대조 재측정 (5,000건 복원 상태, 1차 실행은 무효 처리됨)
$R switch --engine mysql;         $R legacy --engine mysql --dataset 5korig
$R switch --engine elasticsearch; $R legacy --engine elasticsearch --dataset 5korig
echo EXP2_DONE

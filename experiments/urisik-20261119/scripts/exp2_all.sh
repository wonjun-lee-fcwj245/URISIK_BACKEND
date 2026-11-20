#!/usr/bin/env bash
# 실험 2 전체 순서
#  1) 기존 데이터(seed-5k.sql, #16 벤치마크와 같은 데이터)에서: 정답률(오타 시드 3종), 지연(엔진 3종 × VU 10/50 × 5회, 엔진 순서를 라운드마다 회전), 기존 스크립트 대조
#  2) 시드 고정 재생성 데이터 3종(5k) + 50k에서: 정답률, 50k는 지연도 3회
#  3) 실험 4를 위해 seed-5k.sql로 복원
set -euo pipefail
cd "$(dirname "$0")"
R="python exp2_runner.py"
RAWD=../raw/exp2
mkdir -p $RAWD

# 1) 기존 데이터
$R switch --engine elasticsearch
for S in 42 7 2026; do python exp2_eval.py --typos $RAWD/typo-queries-seed$S.json --label seed${S}_5korig; done
ENGINES=(mysql fulltext elasticsearch)
for N in 1 2 3 4 5; do
  for i in 0 1 2; do
    E=${ENGINES[$(( (i + N) % 3 ))]}
    $R switch --engine $E
    $R latency --engine $E --vus 10 --run-no $N --dataset 5korig
    $R latency --engine $E --vus 50 --run-no $N --dataset 5korig
  done
done
$R switch --engine mysql;         $R legacy --engine mysql --dataset 5korig
$R switch --engine elasticsearch; $R legacy --engine elasticsearch --dataset 5korig

# 2) 시드 고정 재생성 데이터
for S in 42 7 2026; do
  SEED=$S node -r ./exp2/seeded-random.cjs ../../../db/init/generate-seed.js 5000 > $RAWD/seed-5k-s$S.sql
  $R reseed --sql $RAWD/seed-5k-s$S.sql
  python exp2_eval.py --typos $RAWD/typo-queries-seed42.json --label seed42_5ks$S
done
SEED=42 node -r ./exp2/seeded-random.cjs ../../../db/init/generate-seed.js 50000 > $RAWD/seed-50k-s42.sql
$R reseed --sql $RAWD/seed-50k-s42.sql
python exp2_eval.py --typos $RAWD/typo-queries-seed42.json --label seed42_50ks42
for N in 1 2 3; do
  for i in 0 1 2; do
    E=${ENGINES[$(( (i + N) % 3 ))]}
    $R switch --engine $E
    $R latency --engine $E --vus 10 --run-no $N --dataset 50ks42
    $R latency --engine $E --vus 50 --run-no $N --dataset 50ks42
  done
done

# 3) 복원
$R reseed --sql ../../../db/init/seed-5k.sql
echo EXP2_DONE

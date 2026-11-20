#!/usr/bin/env bash
# 실험 4 재설계: 설정 3종(default, hikari, tuned) × 4회(1회차 워밍업 제외 → 3회 집계), 라운드마다 순서 회전
set -euo pipefail
cd "$(dirname "$0")"
export EXP4_STUB=1
python exp4/prep_mealplan.py
CFGS=(default hikari tuned)
for N in 1 2 3 4; do
  for i in 0 1 2; do
    C=${CFGS[$(( (i + N - 1) % 3 ))]}
    APP_VARIANT=v4 python exp4b_runner.py arrival --cfg "$C" --run-no "$N"
  done
done
cd ../../..
docker compose -f docker-compose.local.yml up -d --no-build --pull never --force-recreate mysql redis elasticsearch kafka zookeeper app
echo EXP4B_DONE

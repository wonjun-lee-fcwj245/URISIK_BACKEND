#!/usr/bin/env bash
# 실험 1 본 측정: 라운드마다 변형을 번갈아 실행해 발열·백그라운드 요인을 양쪽에 고르게 분산
# 홀수 라운드 v0 → v3 → v1, 짝수 라운드 v1 → v3 → v0
# 각 라운드에서 변형 교체 → 예열(집계 제외) → 시나리오별 1회
set -euo pipefail
cd "$(dirname "$0")"
R="python exp1_runner.py"
scenarios() {
  local V=$1 N=$2
  if [ "$V" = "v1" ]; then
    $R run --scenario A --variant v1 --kind review --vus 100 --run-no "$N"
    $R run --scenario D --variant v1 --kind wish   --vus 100 --run-no "$N"
    $R run --scenario E --variant v1 --kind wish   --vus 50  --run-no "$N"
    return
  fi
  $R run --scenario A --variant "$V" --kind review  --vus 100 --run-no "$N"
  $R run --scenario B --variant "$V" --kind review  --vus 100 --mode distinct --run-no "$N"
  for U in 10 25 50 200; do
    $R run --scenario C --variant "$V" --kind review --vus "$U" --run-no "$N"
  done
  $R run --scenario D --variant "$V" --kind treview --vus 100 --run-no "$N"
  $R run --scenario D --variant "$V" --kind wish    --vus 100 --run-no "$N"
  $R run --scenario D --variant "$V" --kind twish   --vus 100 --run-no "$N"
  $R run --scenario E --variant "$V" --kind wish    --vus 50  --run-no "$N"
}
for N in 1 2 3 4 5; do
  if [ $((N % 2)) -eq 1 ]; then ORDER="v0 v3 v1"; else ORDER="v1 v3 v0"; fi
  for V in $ORDER; do
    $R switch "$V"
    $R warm
    scenarios "$V" "$N"
  done
done

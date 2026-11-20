#!/usr/bin/env bash
# 실험 1 전체 실행 순서. 변형별로 앱 이미지를 교체한 뒤 시나리오를 5회씩 실행한다.
# 실행: bash exp1_all.sh [variant ...]   (기본: v3 v0 v1)
set -euo pipefail
cd "$(dirname "$0")"
R="python exp1_runner.py"
VARIANTS=("$@"); [ ${#VARIANTS[@]} -eq 0 ] && VARIANTS=(v3 v0 v1)
for V in "${VARIANTS[@]}"; do
  $R switch "$V"
  if [ "$V" = "v1" ]; then
    # v1(FOR UPDATE)은 기존 주장 대조용: 리뷰 A, 찜 원본 D, 찜 VU50 E만
    $R run --scenario A --variant v1 --kind review --vus 100
    $R run --scenario D --variant v1 --kind wish   --vus 100
    $R run --scenario E --variant v1 --kind wish   --vus 50
    continue
  fi
  $R run --scenario A --variant "$V" --kind review  --vus 100
  $R run --scenario B --variant "$V" --kind review  --vus 100 --mode distinct
  for N in 10 25 50 200; do
    $R run --scenario C --variant "$V" --kind review --vus "$N"
  done
  $R run --scenario D --variant "$V" --kind treview --vus 100
  $R run --scenario D --variant "$V" --kind wish    --vus 100
  $R run --scenario D --variant "$V" --kind twish   --vus 100
  $R run --scenario E --variant "$V" --kind wish    --vus 50
done

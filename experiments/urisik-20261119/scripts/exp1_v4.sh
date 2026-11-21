#!/usr/bin/env bash
# 평균 별점 버그 수정(v4, 0914ab8) 검증: A·D(+B: 레시피당 리뷰 1건이라 평균 오류가 가장 선명) 5회
set -euo pipefail
cd "$(dirname "$0")"
R="python exp1_runner.py"
$R switch v4
for N in 1 2 3 4 5; do
  $R warm
  $R run --scenario A --variant v4 --kind review  --vus 100 --run-no "$N"
  $R run --scenario B --variant v4 --kind review  --vus 100 --mode distinct --run-no "$N"
  $R run --scenario D --variant v4 --kind treview --vus 100 --run-no "$N"
  $R run --scenario D --variant v4 --kind wish    --vus 100 --run-no "$N"
  $R run --scenario D --variant v4 --kind twish   --vus 100 --run-no "$N"
done

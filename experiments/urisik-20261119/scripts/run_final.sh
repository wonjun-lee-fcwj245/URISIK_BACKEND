#!/usr/bin/env bash
# 추천 조합 + 원래 조건 재현: 실험 3 남은 부분 → 재시도 비교 → CPU 제한 없는 처리량 대조
set -euo pipefail
cd "$(dirname "$0")"
until docker image inspect urisik-app:r >/dev/null 2>&1; do sleep 10; done   # r 이미지 빌드 완료 대기
PYTHONUNBUFFERED=1 bash exp3_rest.sh   > ../raw/exp3-rest-run.log 2>&1
PYTHONUNBUFFERED=1 bash exp1_retry.sh  > ../raw/exp1-retry-run.log 2>&1
PYTHONUNBUFFERED=1 bash exp_nocpu.sh   > ../raw/exp-nocpu-run.log 2>&1

#!/usr/bin/env bash
# 실험 3 (실험 4 완료 후 원래 compose 설정 상태에서 실행)
set -euo pipefail
cd "$(dirname "$0")"
R="python exp3_runner.py"
$R prep
$R latency --reps 5
$R invalidation --trials 50
$R redis-down
echo EXP3_DONE

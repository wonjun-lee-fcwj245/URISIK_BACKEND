#!/usr/bin/env bash
# 실험 2(남은 부분) → 실험 4 → 실험 3
set -euo pipefail
cd "$(dirname "$0")"
PYTHONUNBUFFERED=1 bash exp2_finish.sh > ../raw/exp2-run-finish.log 2>&1
PYTHONUNBUFFERED=1 bash exp4_all.sh    > ../raw/exp4-run.log 2>&1
PYTHONUNBUFFERED=1 bash exp3_all.sh    > ../raw/exp3-run.log 2>&1

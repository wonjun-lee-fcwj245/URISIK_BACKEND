#!/usr/bin/env bash
# 실험 2 → 실험 4 연속 실행
set -euo pipefail
cd "$(dirname "$0")"
PYTHONUNBUFFERED=1 bash exp2_all.sh > ../raw/exp2-run.log 2>&1
PYTHONUNBUFFERED=1 bash exp4_all.sh > ../raw/exp4-run.log 2>&1

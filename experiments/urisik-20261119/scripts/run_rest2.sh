#!/usr/bin/env bash
# 메모리 부족으로 중단된 뒤 이어하기: 실험 2(나머지) → 실험 4 → 실험 3
set -euo pipefail
cd "$(dirname "$0")"
PYTHONUNBUFFERED=1 bash exp2_resume.sh > ../raw/exp2-run-resume.log 2>&1
PYTHONUNBUFFERED=1 bash exp4_all.sh    > ../raw/exp4-run.log 2>&1
PYTHONUNBUFFERED=1 bash exp3_all.sh    > ../raw/exp3-run.log 2>&1

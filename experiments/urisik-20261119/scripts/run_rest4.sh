#!/usr/bin/env bash
# 실험 4(남은 부분) → 실험 3
set -euo pipefail
cd "$(dirname "$0")"
PYTHONUNBUFFERED=1 bash exp4_resume.sh > ../raw/exp4-run-resume.log 2>&1
PYTHONUNBUFFERED=1 bash exp3_all.sh    > ../raw/exp3-run.log 2>&1

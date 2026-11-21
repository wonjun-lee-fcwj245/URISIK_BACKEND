#!/usr/bin/env bash
# 1) 커넥션 점유 시간: v4 + ES 검색 + 외부 API stub
set -euo pipefail
cd "$(dirname "$0")"
export EXP2_STUB=1
python exp2_runner.py switch --engine elasticsearch
python exp5_connhold.py
echo STEP1_DONE

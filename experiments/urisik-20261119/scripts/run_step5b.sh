#!/usr/bin/env bash
# 실험 5 추가(리뷰 반영): 검색어 반복·저부하 조건. v4 + ES + 외부 API stub 20ms
set -euo pipefail
cd "$(dirname "$0")"
export EXP2_STUB=1
python exp2_runner.py switch --engine elasticsearch
python exp5_connhold.py extra
echo STEP5B_DONE

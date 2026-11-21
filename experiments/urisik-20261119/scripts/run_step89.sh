#!/usr/bin/env bash
# #24 재측정: 검색어를 실행마다 유일하게 바꾼 뒤 (1) 수정 전/후 비교 exp8 (2) 커넥션 점유 진단 exp9
set -uo pipefail
cd "$(dirname "$0")"
export EXP2_STUB=1 PYTHONUNBUFFERED=1
python exp8_search_tx.py
python exp9_diag.py sample
python exp9_diag.py leak
echo CHAIN89_DONE

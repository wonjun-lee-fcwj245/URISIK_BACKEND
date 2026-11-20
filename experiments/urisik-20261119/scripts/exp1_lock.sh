#!/usr/bin/env bash
# 락 순서만 바꾼 재비교 (v4 코드 위에 LOCK_MODE만 다르다: lock-modes-on-0914ab8.patch)
#  update_first: 카운터 UPDATE → INSERT (현재)
#  for_update  : SELECT FOR UPDATE → INSERT → UPDATE
#  insert_first: INSERT → UPDATE (수정 전 순서)
# 시나리오: A 같은 레시피 100명 리뷰, B 서로 다른 레시피 100개 리뷰, D 찜 100명(원본), E 찜 50명
# 5라운드, 라운드마다 순서 회전, 1회차는 워밍업
set -euo pipefail
cd "$(dirname "$0")"
R="python exp1_runner.py"
MODES=(update_first for_update insert_first)
for N in 1 2 3 4 5; do
  for i in 0 1 2; do
    M=${MODES[$(( (i + N - 1) % 3 ))]}
    LOCK_MODE=$M $R switch L
    $R warm
    $R run --scenario "LA_$M" --variant L --kind review --vus 100 --run-no "$N"
    $R run --scenario "LB_$M" --variant L --kind review --vus 100 --mode distinct --run-no "$N"
    $R run --scenario "LD_$M" --variant L --kind wish   --vus 100 --run-no "$N"
    $R run --scenario "LE_$M" --variant L --kind wish   --vus 50  --run-no "$N"
  done
done
echo LOCK_DONE

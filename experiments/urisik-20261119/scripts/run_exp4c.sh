#!/usr/bin/env bash
# 실험 4c: 실험 4 재설계를 550·600 RPS 부근(500,550,600)만 다시 돌려 Hikari 점유 시간과 ES 서킷 우회를 함께 기록한다.
# 설정 default·tuned × 3회(1회차 워밍업), 라운드마다 순서 교대. 외부 API는 실험 4와 같은 호스트 stub(검색어 7개 반복이라 외부 호출 거의 없음).
set -uo pipefail
cd "$(dirname "$0")"
export EXP4_STUB=1 EXP4_RATES=500,550,600 PYTHONUNBUFFERED=1 APP_VARIANT=v4
python exp4/prep_mealplan.py
for N in 1 2 3; do
  if [ $((N % 2)) -eq 1 ]; then ORDER="default tuned"; else ORDER="tuned default"; fi
  for C in $ORDER; do
    python exp4b_runner.py arrival --cfg "$C" --run-no "$N"
    python exp4c_analyze.py "$C" "$N"
  done
done
echo EXP4C_DONE

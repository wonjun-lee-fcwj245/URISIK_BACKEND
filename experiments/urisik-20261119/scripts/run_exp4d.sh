#!/usr/bin/env bash
# 실험 4d: 원래 실험 4 사다리(300→600, 7단계)로 기본 설정(Hikari 10)에서 수정 전(main + OSIV 켬)과 수정 후(sf + OSIV 끔)를 번갈아 3회씩(1회차 워밍업) 돌린다.
# (4c는 400부터 시작해 앱이 식지 않은 채 연쇄 붕괴해 무효였다. 이번에는 원래 프로토콜을 그대로 쓴다.)
# 결과는 raw/exp4c_<arm>/, results.csv의 exp4c_<arm>에 기록해 exp4b 원본과 섞이지 않게 한다.
set -uo pipefail
cd "$(dirname "$0")"
export EXP4_STUB=1 EXP4_RATES=300,350,400,450,500,550,600 PYTHONUNBUFFERED=1
python exp4/prep_mealplan.py
run_arm() {   # $1=arm(m_on|sf_off) $2=run_no
  local arm=$1 n=$2
  if [ "$arm" = "m_on" ]; then
    APP_VARIANT=m EXP4_RAW=exp4d_m_on EXP4_LABEL=exp4d_m_on python exp4b_runner.py arrival --cfg default --run-no "$n"
    APP_VARIANT=m EXP4_RAW=exp4d_m_on EXP4_LABEL=exp4d_m_on python exp4c_analyze.py default "$n"
  else
    APP_VARIANT=sf EXP4_OSIV_OFF=1 EXP4_RAW=exp4d_sf_off EXP4_LABEL=exp4d_sf_off python exp4b_runner.py arrival --cfg default --run-no "$n"
    APP_VARIANT=sf EXP4_OSIV_OFF=1 EXP4_RAW=exp4d_sf_off EXP4_LABEL=exp4d_sf_off python exp4c_analyze.py default "$n"
  fi
}
for N in 1 2 3; do
  if [ $((N % 2)) -eq 1 ]; then ORDER="m_on sf_off"; else ORDER="sf_off m_on"; fi
  for ARM in $ORDER; do run_arm "$ARM" "$N"; done
done
echo EXP4C_DONE

#!/usr/bin/env bash
# 실험 4c (재설계): 기본 설정(Hikari 10, Tomcat 200)에서 수정 전(main + OSIV 켬)과 수정 후(sf + OSIV 끔)를
# 원래 실험 4처럼 사다리(400→600, 50 단위)로 번갈아 3회씩(1회차 워밍업) 돌려 단계별 Hikari 점유 시간·풀 대기·ES 서킷 우회를 기록한다.
# 결과는 raw/exp4c_<arm>/, results.csv의 exp4c_<arm>에 기록해 exp4b 원본과 섞이지 않게 한다.
set -uo pipefail
cd "$(dirname "$0")"
export EXP4_STUB=1 EXP4_RATES=400,450,500,550,600 PYTHONUNBUFFERED=1
python exp4/prep_mealplan.py
run_arm() {   # $1=arm(m_on|sf_off) $2=run_no
  local arm=$1 n=$2
  if [ "$arm" = "m_on" ]; then
    APP_VARIANT=m EXP4_RAW=exp4c_m_on EXP4_LABEL=exp4c_m_on python exp4b_runner.py arrival --cfg default --run-no "$n"
    APP_VARIANT=m EXP4_RAW=exp4c_m_on EXP4_LABEL=exp4c_m_on python exp4c_analyze.py default "$n"
  else
    APP_VARIANT=sf EXP4_OSIV_OFF=1 EXP4_RAW=exp4c_sf_off EXP4_LABEL=exp4c_sf_off python exp4b_runner.py arrival --cfg default --run-no "$n"
    APP_VARIANT=sf EXP4_OSIV_OFF=1 EXP4_RAW=exp4c_sf_off EXP4_LABEL=exp4c_sf_off python exp4c_analyze.py default "$n"
  fi
}
for N in 1 2 3; do
  if [ $((N % 2)) -eq 1 ]; then ORDER="m_on sf_off"; else ORDER="sf_off m_on"; fi
  for ARM in $ORDER; do run_arm "$ARM" "$N"; done
done
echo EXP4C_DONE

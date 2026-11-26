#!/usr/bin/env bash
# 실험 4e 보충: 보완안(sfb_off) 3회차가 측정 중 절전으로 무효가 되어, 같은 세션에서 보완안과 대조군(m_on)을 4회차로 다시 돈다.
set -euo pipefail
cd "$(dirname "$0")"
export EXP4_STUB=net EXP4_RATES=300,350,400,450,500,550,600 PYTHONUNBUFFERED=1
python -c "import exp16_netstub_compare as m; m.restart_stub_container(20)"
python exp4/prep_mealplan.py
for arm in default_sfb_off default_m_on; do
  case "$arm" in
    default_m_on)    image=m;   off="" ;;
    default_sfb_off) image=sfb; off=1 ;;
  esac
  label=exp4e_$arm
  APP_VARIANT=$image EXP4_OSIV_OFF=$off EXP4_RAW=$label EXP4_LABEL=$label python exp4b_runner.py arrival --cfg default --run-no 4
  APP_VARIANT=$image EXP4_OSIV_OFF=$off EXP4_RAW=$label EXP4_LABEL=$label python exp4c_analyze.py default 4
done
echo EXP4E_RERUN_DONE

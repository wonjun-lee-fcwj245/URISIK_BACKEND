#!/usr/bin/env bash
# 실험 4e: 도커 네트워크 안 stub(urisik-stubnet, 20ms)에서 원래 사다리(300→600)를 돌린다(호스트 stub 경로의 영향 제거).
# 구성 4개 × 3회(1회차 워밍업), 회차마다 순서를 돌린다:
#   default_m_on  = 수정 전(main) + OSIV 켬 (실험 4 기본 설정의 재측정)
#   default_sf_off = 트랜잭션 범위 축소(#26 원안) + OSIV 끔
#   default_sfb_off = 위 + ES 동시 호출 제한(보완안) + OSIV 끔
#   tuned_m_on    = 수정 전 + 전체 튜닝(Hikari 50, Tomcat 500) (실험 4 headline 구성의 재측정)
set -euo pipefail   # 한 단계라도 실패하면 체인을 중단하고 실패 코드를 돌려준다
cd "$(dirname "$0")"
export EXP4_STUB=net EXP4_RATES=300,350,400,450,500,550,600 PYTHONUNBUFFERED=1
python -c "import exp16_netstub_compare as m; m.restart_stub_container(20)"
python exp4/prep_mealplan.py
run_arm() {   # $1=arm $2=run_no
  local arm=$1 n=$2 cfg image off
  case "$arm" in
    default_m_on)    cfg=default; image=m;   off="" ;;
    default_sf_off)  cfg=default; image=sf;  off=1 ;;
    default_sfb_off) cfg=default; image=sfb; off=1 ;;
    tuned_m_on)      cfg=tuned;   image=m;   off="" ;;
  esac
  local label=exp4e_$arm
  APP_VARIANT=$image EXP4_OSIV_OFF=$off EXP4_RAW=$label EXP4_LABEL=$label python exp4b_runner.py arrival --cfg "$cfg" --run-no "$n"
  APP_VARIANT=$image EXP4_OSIV_OFF=$off EXP4_RAW=$label EXP4_LABEL=$label python exp4c_analyze.py "$cfg" "$n"
}
ARMS=(default_m_on default_sf_off default_sfb_off tuned_m_on)
for N in 1 2 3; do
  for i in 0 1 2 3; do
    run_arm "${ARMS[$(( (i + N - 1) % 4 ))]}" "$N"
  done
done
echo EXP4E_DONE

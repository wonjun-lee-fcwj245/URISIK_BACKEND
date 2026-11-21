#!/usr/bin/env bash
# 실험 4 전체 순서 (CPU 제한 적용, 이미지 v4)
#  1) 요청률 단계 부하: 설정 5종 × 3회, 라운드마다 설정 순서를 회전
#  2) 기존 overall-load-test.js(VU 방식) 대조: 기본값, 전체 튜닝 각 1회
#  3) 원래 compose 상태로 복원 (CPU 제한·설정 override 해제)
set -euo pipefail
cd "$(dirname "$0")"
R="python exp4_runner.py"
CFGS=(default hikari maxconn tomcat tuned)
for N in 1 2 3; do
  for i in 0 1 2 3 4; do
    C=${CFGS[$(( (i + N - 1) % 5 ))]}
    $R arrival --cfg "$C" --run-no "$N"
  done
done
$R legacy --cfg default
$R legacy --cfg tuned
cd ../../..
docker compose -f docker-compose.local.yml up -d --no-build --pull never --force-recreate mysql redis elasticsearch kafka zookeeper app
echo EXP4_DONE

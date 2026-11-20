#!/usr/bin/env bash
# 중단된 실험 4 이어하기: 아직 유효 결과가 없는 회차만 실행
#  - 3회차: tomcat, tuned, default, hikari   (maxconn 3회차는 이미 유효)
#  - maxconn 1회차 재측정 (절전으로 무효 처리됨)
#  - 기존 overall-load-test.js(VU 방식) 대조: 기본값, 전체 튜닝 각 1회
#  - 원래 compose 설정으로 복원
set -euo pipefail
cd "$(dirname "$0")"
R="python exp4_runner.py"
$R arrival --cfg tomcat  --run-no 3
$R arrival --cfg tuned   --run-no 3
$R arrival --cfg default --run-no 3
$R arrival --cfg hikari  --run-no 3
$R arrival --cfg maxconn --run-no 1
$R legacy --cfg default
$R legacy --cfg tuned
cd ../../..
docker compose -f docker-compose.local.yml up -d --no-build --pull never --force-recreate mysql redis elasticsearch kafka zookeeper app
echo EXP4_DONE

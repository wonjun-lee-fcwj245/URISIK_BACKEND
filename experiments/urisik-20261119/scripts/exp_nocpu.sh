#!/usr/bin/env bash
# 733→1,012 RPS 원래 조건 재현: 컨테이너 CPU 제한 없이 기존 overall-load-test.js(VU 방식) 실행
# 기본값/전체 튜닝을 번갈아 2회씩 (default, tuned, tuned, default)
set -euo pipefail
cd "$(dirname "$0")"
R="python exp4_runner.py"
$R legacy --cfg default --nocpu --run-no 1
$R legacy --cfg tuned   --nocpu --run-no 1
$R legacy --cfg tuned   --nocpu --run-no 2
$R legacy --cfg default --nocpu --run-no 2
cd ../../..
docker compose -f docker-compose.local.yml up -d --no-build --pull never --force-recreate mysql redis elasticsearch kafka zookeeper app
echo NOCPU_DONE

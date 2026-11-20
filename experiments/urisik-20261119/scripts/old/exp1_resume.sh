#!/usr/bin/env bash
# 1차 실행이 v3 D-twish에서 SQL 인용 버그로 중단되어, 남은 부분부터 이어서 실행
set -euo pipefail
cd "$(dirname "$0")"
R="python exp1_runner.py"
$R run --scenario D --variant v3 --kind twish --vus 100
$R run --scenario E --variant v3 --kind wish  --vus 50
bash exp1_all.sh v0 v1

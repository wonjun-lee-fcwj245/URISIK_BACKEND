#!/usr/bin/env bash
# 실험 3 남은 부분: Redis 중단 → 적중률 (앱은 v4, 원래 compose의 CPU 제한 없는 설정)
set -euo pipefail
cd "$(dirname "$0")"
R="python exp3_runner.py"
$R prep                # v4 앱 재기동, 사용자 101~1000 확인
$R redis-down
$R hitrate
echo EXP3_REST_DONE

#!/usr/bin/env bash
# 스레드 덤프 분류: 수정 전 m, 수정 후 sf
set -uo pipefail
cd "$(dirname "$0")"
export EXP2_STUB=1 PYTHONUNBUFFERED=1
APP_VARIANT=m python exp9_dump.py
APP_VARIANT=sf python exp9_dump.py
echo DUMPCHAIN_DONE

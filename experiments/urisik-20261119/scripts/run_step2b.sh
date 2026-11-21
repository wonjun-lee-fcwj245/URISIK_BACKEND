#!/usr/bin/env bash
# 2차 수정(FailureTolerantCache) 검증: 단위 테스트 → 이미지 빌드(redisfix2) → 같은 장애 주입 3회
set -uo pipefail
cd "$(dirname "$0")"
RAWD=../raw
WT="../../../../urisik-wt-redis"
BROKEN="$WT/src/test/java/com/urisik/backend/allergy/RecipeAllergyControllerTest.java"
mv "$BROKEN" "$WT/../urisik-wt-redis-rac.bak"
(cd "$WT" && MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/workspace" -w /workspace gradle:8.5-jdk17 gradle --no-daemon test --tests "com.urisik.backend.global.config.FailureTolerantCacheTest" > ../URISIK_BACKEND/experiments/urisik-20261119/raw/redisfix2-test.log 2>&1; echo "exit $?" >> ../URISIK_BACKEND/experiments/urisik-20261119/raw/redisfix2-test.log)
mv "$WT/../urisik-wt-redis-rac.bak" "$BROKEN"
tail -4 $RAWD/redisfix2-test.log
docker build -q -t urisik-app:redisfix2 "$WT" > $RAWD/build-redisfix2.log 2>&1
tail -1 $RAWD/build-redisfix2.log
export EXP2_STUB=1
python -c "import exp5_connhold as e; e.restart_stub(20)"
for N in 1 2 3; do
  python exp6_redis.py --variant redisfix2 --run-no $N
done
echo STEP2B_DONE

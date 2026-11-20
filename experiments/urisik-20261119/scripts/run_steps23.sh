#!/usr/bin/env bash
# 1단계(커넥션 점유 시간)가 끝나면 2단계(Redis 장애 수정 실험)와 3단계(자모 + FULLTEXT)를 이어서 실행한다.
set -uo pipefail
cd "$(dirname "$0")"
RAWD=../raw
until grep -q STEP1_DONE $RAWD/step1-connhold.log 2>/dev/null; do sleep 20; done
echo "step1 done $(date +%H:%M)"

# ---- 2단계: 코드 수정 검증과 이미지 빌드 ----
WT="../../../../urisik-wt-redis"
BROKEN="$WT/src/test/java/com/urisik/backend/allergy/RecipeAllergyControllerTest.java"
mv "$BROKEN" "$WT/../urisik-wt-redis-rac.bak"
(cd "$WT" && MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/workspace" -w /workspace gradle:8.5-jdk17 gradle --no-daemon test --tests "com.urisik.backend.global.config.RedisCacheErrorHandlerTest" > ../URISIK_BACKEND/experiments/urisik-20261119/raw/redisfix-test.log 2>&1; echo "exit $?" >> ../URISIK_BACKEND/experiments/urisik-20261119/raw/redisfix-test.log)
mv "$WT/../urisik-wt-redis-rac.bak" "$BROKEN"
tail -3 $RAWD/redisfix-test.log
docker build -q -t urisik-app:redisfix "$WT" > $RAWD/build-redisfix.log 2>&1
tail -1 $RAWD/build-redisfix.log

export EXP2_STUB=1
python -c "import exp5_connhold as e; e.restart_stub(20)"   # 1단계가 stub 지연을 300ms로 바꿔 둠
python exp6_baseline.py
for N in 1 2 3; do
  python exp6_redis.py --variant v3 --run-no $N
  python exp6_redis.py --variant redisfix --run-no $N
done
echo STEP2_DONE

# ---- 3단계: 자모 + FULLTEXT ----
unset EXP2_STUB
python exp7_jamo_fulltext.py
echo STEP3_DONE

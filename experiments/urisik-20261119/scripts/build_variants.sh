#!/usr/bin/env bash
# 추가 변형 이미지 빌드 (측정과 CPU가 겹치지 않도록 측정 전에 한꺼번에)
cd "$(dirname "$0")/../../.."
for pair in "L:../urisik-wt-lock" "r2:../urisik-wt-retry" "s:../urisik-wt-score"; do
  tag=${pair%%:*}; dir=${pair#*:}
  docker build -q -t urisik-app:$tag $dir > experiments/urisik-20261119/raw/build-$tag.log 2>&1
  echo "$tag exit $?" >> experiments/urisik-20261119/raw/build-$tag.log
done
echo BUILDS_DONE > experiments/urisik-20261119/raw/build-variants.done

#!/usr/bin/env bash
# 절전(15:03~19:13)을 가로지른 무효 회차를 체인이 끝난 뒤 다시 측정한다.
#  1) 체인(EXP4B_DONE)이 끝나기를 기다린다  2) 무효 회차 결과를 results.csv·raw에서 분리한다  3) 같은 회차 번호로 재측정  4) 원래 compose로 복원
set -euo pipefail
cd "$(dirname "$0")"
until grep -q EXP4B_DONE ../raw/final2-run.log 2>/dev/null; do sleep 30; done
python - <<'PY'
import csv, os, shutil
root = os.path.abspath('..')
rows = list(csv.reader(open(os.path.join(root, 'results.csv'), encoding='utf-8')))
bad = [r for r in rows[1:] if r[0] == 'exp4b' and r[2] == 'default' and r[3] == '3']
keep = [rows[0]] + [r for r in rows[1:] if r not in bad]
csv.writer(open(os.path.join(root, 'results.csv'), 'w', newline='', encoding='utf-8')).writerows(keep)
d = os.path.join(root, 'raw', 'exp4b', 'invalid-system-sleep')
os.makedirs(d, exist_ok=True)
for f in os.listdir(os.path.join(root, 'raw', 'exp4b')):
    if f.startswith('arrival_default_run3.'):
        shutil.move(os.path.join(root, 'raw', 'exp4b', f), os.path.join(d, f))
open(os.path.join(d, 'README.md'), 'w', encoding='utf-8').write('default 3회차는 15:03~19:13 시스템 절전을 가로질러 실행돼 무효. 재측정함.\n')
print('moved invalid rows:', len(bad))
PY
export EXP4_STUB=1
APP_VARIANT=v4 python exp4b_runner.py arrival --cfg default --run-no 3
cd ../../..
docker compose -f docker-compose.local.yml up -d --no-build --pull never --force-recreate mysql redis elasticsearch kafka zookeeper app
echo RERUN_DONE

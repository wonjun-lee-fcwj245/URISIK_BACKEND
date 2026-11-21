"""실험 4 보조: 가족방 1~100에 이번 주(KST 월요일 기준) CONFIRMED 식단을 넣어 /meal-plans/today가 404 대신 200을 돌려주게 한다.
앱은 LocalDate.now(KST)의 주 시작(월요일)으로 조회하므로 같은 계산을 쓴다. 여러 번 실행해도 안전(INSERT IGNORE)."""
import datetime as dt, subprocess, sys

kst = dt.timezone(dt.timedelta(hours=9))
today = dt.datetime.now(kst).date()
monday = today - dt.timedelta(days=today.weekday())
days = ['monday','tuesday','wednesday','thursday','friday','saturday','sunday']
cols = ['status','week_start_date','family_room_id','create_at','updated_at'] + [f'{d}_{m}' for d in days for m in ('lunch','dinner')] + [f'{d}_{m}_type' for d in days for m in ('lunch','dinner')]
vals = []
for fr in range(1, 101):
    row = ["'CONFIRMED'", f"'{monday}'", str(fr), 'NOW(6)', 'NOW(6)']
    row += [str(100 + fr)] * 14 + ["'RECIPE'"] * 14
    vals.append('(' + ','.join(row) + ')')
sql = f"INSERT IGNORE INTO meal_plan ({','.join(cols)}) VALUES " + ','.join(vals) + ';'
r = subprocess.run(['docker', 'exec', '-i', 'urisik-mysql', 'sh', '-c', 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" urisik'],
                   input=sql.encode('utf-8'), capture_output=True)
print('week_start', monday, 'return', r.returncode, r.stderr.decode('utf-8', 'replace')[-200:])

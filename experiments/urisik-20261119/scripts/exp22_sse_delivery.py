"""실험 22: 실시간 알림(SSE)이 실제로 전달되는지와 걸리는 시간.

  EXP22_LABEL=exp22_sse_fix35 python exp22_sse_delivery.py --trials 5           # Redis 정상
  EXP22_LABEL=... python exp22_sse_delivery.py --trials 3 --redis-down          # Redis 중단 상태에서

알림 동의 회원으로 /api/notifications/subscribe를 열어 둔 채 식단 확정 이벤트(Kafka meal-plan-confirmed)를 넣고,
SSE로 notification 이벤트가 오기까지의 시간과 알림 행이 생기기까지의 시간을 같은 기준(발행 직전)으로 잰다.
"""
import argparse
import json
import os
import subprocess
import threading
import time
import urllib.request

import exp19_redis_notification as e19
from exp3_runner import mysql, token, append, ROOT, BASE
from exp19_redis_notification import already_recorded

LABEL = os.environ.get('EXP22_LABEL', 'exp22_sse')
RAW = os.path.join(ROOT, 'raw', LABEL)


def listen(tok, events, stop, ready):
    req = urllib.request.Request(BASE + '/api/notifications/subscribe')
    req.add_header('Authorization', f'Bearer {tok}')
    try:
        with urllib.request.urlopen(req, timeout=200) as r:
            ready.set()
            while not stop.is_set():
                line = r.readline().decode('utf-8', 'replace').strip()
                if line:
                    events.append((time.time(), line[:120]))
    except Exception as ex:
        events.append((time.time(), f'END {type(ex).__name__}'))
        ready.set()


def trial(member, room, tok, wait_s):
    events, stop, ready = [], threading.Event(), threading.Event()
    th = threading.Thread(target=listen, args=(tok, events, stop, ready), daemon=True)
    th.start()
    ready.wait(10)
    time.sleep(1)
    base = e19.notif_count(member)
    t0 = time.time()
    ok, err = e19.produce(room, 5)
    sse_at = row_at = None
    while time.time() - t0 < wait_s and (sse_at is None or row_at is None):
        if sse_at is None:
            hit = [t for t, l in events if l.startswith('event:notification')]
            if hit:
                sse_at = round(hit[0] - t0, 2)
        if row_at is None and e19.notif_count(member) >= base + 1:
            row_at = round(time.time() - t0, 2)
        time.sleep(0.2)
    stop.set()
    return {'produce_ok': ok, 'produce_error': err, 'sse_notification_s': sse_at, 'row_s': row_at,
            'sse_lines': [l for _, l in events][:6]}


def main(trials, redis_down, wait_s):
    os.makedirs(RAW, exist_ok=True)
    member, room = map(int, mysql("select m.id, p.family_room from member m join family_member_profile p on p.member=m.id where m.alarm_policy='ALARM_AGREED' limit 1").split())
    tok = token(member)
    phase = 'redis_down' if redis_down else 'redis_up'
    if already_recorded(LABEL, phase=phase):   # 재실행으로 CSV 행이 겹치고 JSON만 덮이는 것을 막는다
        raise SystemExit(f'{LABEL}의 {phase}는 이미 기록돼 있다. EXP22_LABEL을 바꿔 실행하라.')
    out = []
    try:
        if redis_down:
            subprocess.run(['docker', 'stop', 'urisik-redis'], capture_output=True)
            running = subprocess.run(['docker', 'inspect', '-f', '{{.State.Running}}', 'urisik-redis'], capture_output=True, text=True).stdout.strip()
            if running != 'false':
                raise SystemExit('Redis 중단 확인 실패')
            time.sleep(2)
        for n in range(1, trials + 1):
            r = trial(member, room, tok, wait_s)
            r.update({'trial': n, 'phase': phase})
            out.append(r)
            print(json.dumps(r, ensure_ascii=False), flush=True)
    finally:
        if redis_down:
            subprocess.run(['docker', 'start', 'urisik-redis'], capture_output=True)
    json.dump(out, open(os.path.join(RAW, f'{phase}.json'), 'w', encoding='utf-8'), indent=1, ensure_ascii=False)
    rows = []
    for r in out:
        for k in ('sse_notification_s', 'row_s'):
            rows.append([LABEL, phase, 'main', r['trial'], k, r[k] if r[k] is not None else '', f"produce_ok={int(r['produce_ok'])};wait={wait_s}s"])
    append(rows)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--trials', type=int, default=5)
    ap.add_argument('--redis-down', action='store_true')
    ap.add_argument('--wait', type=float, default=150)
    a = ap.parse_args()
    main(a.trials, a.redis_down, a.wait)

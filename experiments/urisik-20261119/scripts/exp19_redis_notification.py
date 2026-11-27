"""실험 19: Redis 중단 시 알림 기능(조회 API, SSE 구독, Kafka → Redis Pub/Sub 발송 경로).

  python exp19_redis_notification.py

main 이미지, 기본 compose 설정(SPRING_DATA_REDIS_TIMEOUT 없음 = 기본 60초)으로 돈다.
1) Redis 정상: 알림 조회 지연, 식단 확정 이벤트(Kafka meal-plan-confirmed) 2건을 연달아 넣고 알림 행이 생길 때까지 시간
2) Redis 중단(docker stop): 같은 측정 + SSE 구독 응답 + 다른 API(레시피 상세) 응답
알림 행은 Redis 발행 전에 저장되므로, 두 번째 이벤트의 행이 늦게 생기면 첫 번째 이벤트의 Redis 발행이 소비자 스레드를 막은 것이다.
"""
import json
import os
import subprocess
import time
import urllib.request

from exp3_runner import http_json, mysql, token, append, ROOT, BASE

LABEL = os.environ.get('EXP19_LABEL', 'exp19_redis_notification')   # 재측정은 다른 이름으로 기록해 원본과 섞이지 않게 한다
RUN = int(os.environ.get('EXP19_RUN', '1'))
WAIT_S = float(os.environ.get('EXP19_WAIT_S', '25'))                 # 알림 행을 기다리는 한도(초). Redis 중단 시 발행 타임아웃(기본 60초)보다 길게
RAW = os.path.join(ROOT, 'raw', LABEL)
TYPE = 'com.urisik.backend.global.kafka.event.NotificationEvent'


def timed(fn):
    t = time.time()
    try:
        r = fn()
        return round((time.time() - t) * 1000, 1), r
    except Exception as e:
        return round((time.time() - t) * 1000, 1), f'{type(e).__name__}: {str(e)[:80]}'


def produce(room, count):
    """이벤트를 넣고 (성공 여부, stderr 요약)을 돌려준다. 발행 실패와 소비·저장 실패를 구분하기 위해."""
    msg = f'__TypeId__:{TYPE}\t' + json.dumps({'familyRoomId': room, 'mealPlanGenerationCount': count})
    r = subprocess.run(['docker', 'exec', '-i', 'urisik-kafka', 'kafka-console-producer', '--bootstrap-server', 'localhost:9092',
                        '--topic', 'meal-plan-confirmed', '--property', 'parse.headers=true', '--property', 'headers.delimiter=\t'],
                       input=msg + '\n', capture_output=True, text=True, timeout=60)
    err = (r.stderr or '').strip()
    return r.returncode == 0 and 'ERROR' not in err, err[:200]


def notif_count(member):
    return int(mysql(f'select count(*) from notification where member_id={member}').strip())


def wait_rows(member, base, t0, limit):
    """발행 전 공통 기준 시각 t0부터 base+1, base+2 행이 처음 보인 시각(초)을 한 루프에서 기록한다."""
    first = second = None
    while time.time() - t0 < limit and second is None:
        n = notif_count(member)
        now = round(time.time() - t0, 1)
        if first is None and n >= base + 1:
            first = now
        if n >= base + 2:
            second = now
        time.sleep(0.5)
    return first, second


def sse_status(tok):
    req = urllib.request.Request(BASE + '/api/notifications/subscribe')
    req.add_header('Authorization', f'Bearer {tok}')
    with urllib.request.urlopen(req, timeout=5) as r:
        r.read(1)
        return r.status


def phase(name, member, room, tok, out):
    res = {'phase': name}
    res['list_ms'], res['list'] = timed(lambda: http_json('GET', '/api/notifications?size=10', tok, timeout=70)[0])
    res['sse_ms'], res['sse'] = timed(lambda: sse_status(tok))
    res['detail_ms'], res['detail'] = timed(lambda: http_json('GET', '/api/recipes/2364', tok, timeout=70)[0])
    base = notif_count(member)
    t0 = time.time()
    ok5, err5 = produce(room, 5)
    ok6, err6 = produce(room, 6)
    res['produce_s'] = round(time.time() - t0, 1)
    res['produce_ok'] = ok5 and ok6
    if not res['produce_ok']:
        res['produce_error'] = err5 or err6
        res['first_row_s'] = res['second_row_s'] = None
    else:
        res['first_row_s'], res['second_row_s'] = wait_rows(member, base, t0, WAIT_S)
    out.append(res)
    print(json.dumps(res, ensure_ascii=False), flush=True)


def main():
    os.makedirs(RAW, exist_ok=True)
    member, room = map(int, mysql("select m.id, p.family_room from member m join family_member_profile p on p.member=m.id where m.alarm_policy='ALARM_AGREED' limit 1").split())
    tok = token(member)
    out = []
    try:
        phase('redis_up', member, room, tok, out)
        r = subprocess.run(['docker', 'stop', 'urisik-redis'], capture_output=True, text=True)
        running = subprocess.run(['docker', 'inspect', '-f', '{{.State.Running}}', 'urisik-redis'], capture_output=True, text=True).stdout.strip()
        if r.returncode != 0 or running != 'false':      # 중단이 확인되지 않으면 redis_down을 재지 않는다
            out.append({'phase': 'redis_down', 'error': f'redis stop 실패(returncode={r.returncode}, running={running})'})
            print(out[-1], flush=True)
        else:
            time.sleep(2)
            phase('redis_down', member, room, tok, out)
    finally:
        subprocess.run(['docker', 'start', 'urisik-redis'], capture_output=True)
    json.dump(out, open(os.path.join(RAW, f'result_run{RUN}.json' if RUN > 1 or LABEL != 'exp19_redis_notification' else 'result.json'), 'w', encoding='utf-8'), indent=1, ensure_ascii=False)
    rows = []
    for r in out:
        if 'error' in r:
            rows.append([LABEL, r['phase'], 'main', RUN, 'error', r['error'], ''])
            continue
        rows.append([LABEL, r['phase'], 'main', RUN, 'produce_ok', int(r['produce_ok']), r.get('produce_error', '')])
        for k in ('list_ms', 'sse_ms', 'detail_ms', 'first_row_s', 'second_row_s'):
            rows.append([LABEL, r['phase'], 'main', RUN, k, r[k] if r[k] is not None else '',
                         f"redis_timeout=default;result={r[k.replace('_ms', '')] if k.endswith('_ms') else ''}"])
    append(rows)


if __name__ == '__main__':
    main()

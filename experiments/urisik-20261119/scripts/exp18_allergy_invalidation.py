"""실험 18: 알레르기 변경 → 추천 캐시(recommendSafe) 갱신. 찜 무효화 실험(exp3 invalidation)과 같은 방식.

  python exp18_allergy_invalidation.py --trials 50 --hammers 1   # 찜 실험과 같은 조건(동시 읽기 1개, 10ms 간격)
  python exp18_allergy_invalidation.py --trials 50 --hammers 4   # 경합을 키운 조건

알레르기를 바꾸면 Kafka 메시지가 두 개로 따로 나간다(추천 캐시 4종 전체 삭제, familyAllergens 키 삭제).
메시지 키가 없어 파티션 3개에 흩어지므로 처리 순서가 보장되지 않는다. 추천 캐시가 먼저 지워지고
familyAllergens가 아직 옛 값일 때 추천을 다시 계산하면, 옛 알레르기 기준의 추천이 TTL(5분) 동안 남을 수 있다.

매 시도: 사용자 U의 알레르기를 [NONE]으로 맞추고 safe-recipes 캐시를 채운다 → 1위 레시피 X의 재료에 든
알레르기 A를 고른다 → 동시 읽기 스레드를 돌리며 U의 알레르기를 [A]로 바꾼다(API) → 50ms 간격으로 조회해
X가 빠질 때까지 시간을 잰다. 10초 안에 빠지지 않으면 Redis에서 두 캐시 키를 직접 지우고 다시 조회해,
그래도 X가 있으면 '알레르기 선택 오류(무효 시도)', 없으면 '지속 stale(옛 알레르기 기준 추천이 남음)'로 기록한다.
"""
import argparse
import json
import os
import subprocess
import threading
import time

from exp3_runner import http_json, mysql, token, append, ROOT

LABEL = os.environ.get('EXP18_LABEL', 'exp18_allergy')   # 재측정은 다른 이름으로 기록해 원본을 덮어쓰지 않는다
IMAGE = os.environ.get('EXP18_IMAGE', 'main')
RAW = os.path.join(ROOT, 'raw', LABEL)
PATH = '/api/recommendations/home/safe-recipes'
KEYWORDS = [  # Allergen enum의 매칭 단어(src/.../Allergen.java)
    ('EGG', ['달걀', '계란', '난백', '난황']), ('MILK', ['우유', '생크림', '치즈', '버터', '연유', '요거트']),
    ('BUCKWHEAT', ['메밀']), ('PEANUT', ['땅콩', '피넛']), ('SOY', ['대두', '두부', '된장', '간장']),
    ('WHEAT', ['밀가루', '글루텐']), ('MACKEREL', ['고등어']), ('SHRIMP', ['새우', '대하']),
    ('PORK', ['돼지고기', '삼겹살', '목살']), ('PEACH', ['복숭아']), ('TOMATO', ['토마토']), ('WALNUT', ['호두']),
    ('CHICKEN', ['닭고기', '닭가슴살', '닭다리']), ('BEEF', ['쇠고기', '소고기']), ('SQUID', ['오징어']),
    ('ABALONE', ['전복']), ('MUSSEL', ['홍합']),
]


def recipes(tok):
    _, body = http_json('GET', PATH, tok, timeout=5)
    return body['result']['recipes']


def pick_allergen(desc):
    for name, words in KEYWORDS:
        if any(w in desc for w in words):
            return name
    return None


def redis_del(*keys):
    """키를 지우고 성공 여부를 돌려준다(명령 실패를 '알레르기 선택 오류'로 잘못 분류하지 않기 위해)."""
    r = subprocess.run(['docker', 'exec', 'urisik-redis', 'redis-cli', 'DEL', *keys], capture_output=True, text=True)
    return r.returncode == 0 and r.stdout.strip().isdigit()


def redis_exists(*keys):
    r = subprocess.run(['docker', 'exec', 'urisik-redis', 'redis-cli', 'EXISTS', *keys], capture_output=True, text=True)
    return int(r.stdout.strip()) if r.returncode == 0 and r.stdout.strip().isdigit() else None


def wait_evicted(keys, limit=5.0):
    """초기화(알레르기 [NONE]) 삭제 메시지가 실제로 처리돼 두 캐시 키가 사라질 때까지 기다린다."""
    t = time.time()
    while time.time() - t < limit:
        if redis_exists(*keys) == 0:
            return True
        time.sleep(0.05)
    return False


def set_allergy(room, tok, allergens):
    st_, _ = http_json('PATCH', f'/api/family-rooms/{room}/profiles', tok, {'allergy': allergens, 'dietPreferences': ['KOREAN']})
    return st_


def main(trials, hammers):
    os.makedirs(RAW, exist_ok=True)
    rooms = dict(tuple(map(int, l.split())) for l in mysql('select member, family_room from family_member_profile where member between 1 and 100').split('\n') if l.strip())
    users = sorted(rooms)
    results = []
    for t in range(trials):
        u = users[t % len(users)]
        room = rooms[u]
        tok = token(u)
        keys = (f'recommendSafe::{u}', f'familyAllergens::{room}')
        recipes(tok)                                      # 두 키를 채워 둔다(초기화 삭제가 처리됐는지 키가 사라지는 것으로 확인)
        set_allergy(room, tok, ['NONE'])
        if not wait_evicted(keys):                        # 고정 대기 대신 삭제 처리 완료를 확인한다
            print('skip: 초기화 삭제가 5초 안에 처리되지 않음', u, flush=True)
            results.append({'trial': t + 1, 'user': u, 'verdict': 'init_timeout'})
            set_allergy(room, tok, ['NONE'])
            continue
        before = recipes(tok)                             # 캐시 채우기(알레르기 없음 기준)
        x = before[0]
        a = pick_allergen(x['description'])
        if a is None:
            print('skip: 1위 재료에 고를 알레르기가 없음', x['id'])
            continue
        cached = [r['id'] for r in recipes(tok)]
        stop = threading.Event()
        hammer_stale = [0]
        t_resp_box = [None]

        def hammer():
            while not stop.is_set():
                try:
                    ids = [r['id'] for r in recipes(tok)]
                    if t_resp_box[0] is not None and x['id'] in ids:
                        hammer_stale[0] += 1
                except Exception:
                    pass
                time.sleep(0.01)
        ths = [threading.Thread(target=hammer, daemon=True) for _ in range(hammers)]
        for th in ths:
            th.start()
        time.sleep(0.05)
        t0 = time.time()
        st_ = set_allergy(room, tok, [a])
        t_resp = time.time()
        t_resp_box[0] = t_resp
        stale_reads, fresh_at = 0, None
        while time.time() - t_resp < 10:
            if x['id'] not in [r['id'] for r in recipes(tok)]:
                fresh_at = time.time()
                break
            stale_reads += 1
            time.sleep(0.05)
        stop.set()
        for th in ths:
            th.join()
        verdict = 'fresh'
        late_reads = 0
        if fresh_at is not None:
            # 새 값이 보인 뒤에도 옛 알레르기로 계산한 결과가 늦게 다시 캐시될 수 있다(@Cacheable에 sync 없음).
            # 동시 읽기를 멈춘 뒤 0.5초 기다렸다가 1초 동안 다시 읽어 옛 결과가 돌아오는지 본다.
            time.sleep(0.5)
            for _ in range(5):
                if x['id'] in [r['id'] for r in recipes(tok)]:
                    late_reads += 1
                time.sleep(0.2)
        if fresh_at is None or late_reads:
            if not redis_del(*keys):
                verdict = 'measure_error'                 # 캐시 삭제 실패: 판정하지 않는다
            elif x['id'] in [r['id'] for r in recipes(tok)]:
                verdict = 'invalid_allergen_pick'
            else:
                verdict = 'persistent_stale' if fresh_at is None else 'late_stale'
        rec = {'trial': t + 1, 'user': u, 'room': room, 'recipe': x['id'], 'allergen': a, 'hammers': hammers,
               'precondition_cached_x': x['id'] in cached, 'write_status': st_, 'write_ms': round((t_resp - t0) * 1000, 1),
               'stale_reads': stale_reads, 'hammer_stale_reads': hammer_stale[0],
               'fresh_after_ms': round((fresh_at - t_resp) * 1000, 1) if fresh_at else None, 'late_stale_reads': late_reads,
               'verdict': verdict}
        results.append(rec)
        print(json.dumps(rec, ensure_ascii=False), flush=True)
        set_allergy(room, tok, ['NONE'])                  # 원래대로
    json.dump(results, open(os.path.join(RAW, f'invalidation_h{hammers}.json'), 'w', encoding='utf-8'), indent=1, ensure_ascii=False)
    valid = [r for r in results if r['verdict'] not in ('invalid_allergen_pick', 'measure_error', 'init_timeout')]
    ok = sorted(r['fresh_after_ms'] for r in valid if r['fresh_after_ms'] is not None)

    def p(v, q):
        k = (len(v) - 1) * q; f = int(k); c = min(f + 1, len(v) - 1)
        return v[f] + (v[c] - v[f]) * (k - f)
    note = f'trials={trials};poll=50ms;hammer=10ms;hammers={hammers}'
    rows = [[LABEL, 'invalidation', IMAGE, f'h{hammers}', k, v, note] for k, v in {
        'trials': len(results), 'excluded_trials': len(results) - len(valid),
        'late_stale_after_fresh': sum(r['verdict'] == 'late_stale' for r in valid),
        'precondition_cached_x': sum(r['precondition_cached_x'] for r in valid),
        'persistent_stale_10s': sum(r['verdict'] == 'persistent_stale' for r in valid),
        'fresh_p50_ms': p(ok, 0.5) if ok else '', 'fresh_p95_ms': p(ok, 0.95) if ok else '', 'fresh_max_ms': max(ok) if ok else '',
        'stale_reads_total': sum(r['stale_reads'] for r in valid),
        'trials_with_stale_read': sum(r['stale_reads'] > 0 for r in valid),
        'hammer_stale_reads_total': sum(r['hammer_stale_reads'] for r in valid),
    }.items()]
    append(rows)
    for r in rows:
        print(r[4], r[5])


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--trials', type=int, default=50)
    ap.add_argument('--hammers', type=int, default=1)
    a = ap.parse_args()
    main(a.trials, a.hammers)

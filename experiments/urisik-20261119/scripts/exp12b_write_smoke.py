"""실험 12b: OSIV를 끈 앱(SPRING_JPA_OPEN_IN_VIEW=false)에 실행 가능한 쓰기 API를 실제로 호출해 지연 로딩 예외가 나는지 본다.
앱은 exp10_osiv.start_app으로 OSIV를 끄고 띄워 둔 상태여야 한다. 회원 3 / 가족방 3 / 시드 레시피를 쓴다(로컬 DB를 변경한다).
  python exp12b_write_smoke.py
"""
import json
import subprocess
import urllib.error
import urllib.request

BASE = 'http://localhost:8080'


def token(i):
    r = urllib.request.Request(f'{BASE}/api/auth/test-token?memberId={i}', method='POST')
    return json.load(urllib.request.urlopen(r, timeout=10))['result']['accessToken']


def call(headers, method, path, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        r = urllib.request.urlopen(req, timeout=30)
        return r.status, r.read().decode()[:100]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:100]


def main():
    h = {'Authorization': 'Bearer ' + token(3), 'Content-Type': 'application/json'}
    member = {'serviceTermsAgreed': True, 'privacyPolicyAgreed': True, 'familyInfoAgreed': True,
              'aiNoticeAgreed': True, 'marketingOptIn': False, 'alarmPolicy': 'ALARM_AGREED'}
    tests = [
        ('POST', '/api/recipes/10/reviews', {'score': 4, 'isFavorite': True}),
        ('POST', '/api/transformed-recipes/5/reviews', {'score': 3, 'isFavorite': False}),
        ('PATCH', '/api/member/alarm', member),
        ('PATCH', '/api/member/agree', member),
        ('POST', '/api/family-rooms/3/profile-wishes', {'recipeId': [10, 11], 'transformedRecipeId': []}),
        ('DELETE', '/api/family-rooms/3/profile-wishes', {'recipeId': [10], 'transformedRecipeId': []}),
        ('DELETE', '/api/family-rooms/3/family-wishlist/items', {'items': [{'type': 'RECIPE', 'id': 10}]}),
        ('POST', '/api/family-rooms/3/invites', None),
    ]
    for m, p, b in tests:
        print(m, p, call(h, m, p, b))
    logs = subprocess.run(['docker', 'logs', '--since', '3m', 'urisik-app'], capture_output=True, text=True, errors='replace')
    text = logs.stdout + logs.stderr
    lazy = [l for l in text.splitlines() if 'LazyInitializationException' in l or 'could not initialize proxy' in l or 'no Session' in l]
    print('lazy loading errors in app log:', len(lazy))


if __name__ == '__main__':
    main()

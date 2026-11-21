"""실험 12: OSIV를 끄면 다른 API가 깨지는가(지연 로딩 예외) — GET API 전수 확인.
컨트롤러에서 GET 경로를 추출해 OSIV 켬/끔 두 상태에서 같은 요청을 보내 상태 코드와 응답 길이를 비교하고,
OSIV 끈 앱 로그에서 LazyInitializationException 흔적을 찾는다. 앱 코드는 바꾸지 않는다.
  python exp12_osiv_smoke.py          # 이미지 APP_VARIANT(기본 m)
POST·PUT·PATCH·DELETE는 요청 본문이 필요해 이 스크립트 범위 밖이다.
"""
import collections
import glob
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
REPO = os.path.abspath(os.path.join(ROOT, '..', '..'))
RAW = os.path.join(ROOT, 'raw', 'exp12')
sys.path.insert(0, HERE)
import exp10_osiv as x10  # noqa: E402
import exp8_search_tx as e8  # noqa: E402

VARIANT = os.environ.get('APP_VARIANT', 'm')
MAPPING = re.compile(r'@GetMapping(?:\(([^)]*)\))?')


def extract_value(args):
    if not args:
        return ''
    m = re.search(r'(?:value\s*=\s*|path\s*=\s*)?\{?\s*"([^"]*)"', args)
    return m.group(1) if m else ''


def collect_get_paths():
    paths = []
    for f in glob.glob(os.path.join(REPO, 'src', 'main', 'java', '**', '*Controller.java'), recursive=True):
        text = open(f, encoding='utf-8').read()
        cls = re.search(r'@RequestMapping\(([^)]*)\)\s*(?:@\w+(?:\([^)]*\))?\s*)*public class', text, re.S)
        base = extract_value(cls.group(1)) if cls else ''
        for m in MAPPING.finditer(text):
            p = (base.rstrip('/') + '/' + extract_value(m.group(1)).lstrip('/')).rstrip('/') or '/'
            paths.append((os.path.basename(f), p))
    return paths


def fill(path):
    return re.sub(r'\{[^}]+\}', '1', path)


def token(i=1):
    req = urllib.request.Request(f'http://localhost:8080/api/auth/test-token?memberId={i}', method='POST')
    return json.load(urllib.request.urlopen(req, timeout=10))['result']['accessToken']


def call(path, tok):
    req = urllib.request.Request('http://localhost:8080' + path, headers={'Authorization': 'Bearer ' + tok})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            body = r.read()
            return r.status, len(body)
    except urllib.error.HTTPError as e:
        return e.code, len(e.read())
    except Exception as e:  # noqa: BLE001
        return 'ERR', str(type(e).__name__)


def run_mode(mode):
    if mode == 'on':
        e8.switch(VARIANT)
    else:
        x10.start_app(VARIANT)
    t0 = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
    tok = token()
    res = {}
    for _, p in sorted(set(collect_get_paths()), key=lambda x: x[1]):
        if p.endswith('/subscribe'):      # SSE는 응답이 끝나지 않아 제외한다
            res[p] = ('SKIP', 'SSE')
            continue
        q = '?keyword=%EA%B9%80%EC%B9%98&page=0&size=10' if p == '/api/recipes/search' else ''
        res[p] = call(fill(p) + q, tok)
    logs = subprocess.run(['docker', 'logs', '--since', t0, 'urisik-app'], capture_output=True, text=True, errors='replace')
    text = logs.stdout + logs.stderr
    lazy = [l for l in text.splitlines() if 'LazyInitializationException' in l or 'could not initialize proxy' in l or 'no Session' in l]
    return res, lazy


def main():
    os.makedirs(RAW, exist_ok=True)
    out = {}
    for mode in ('on', 'off'):
        res, lazy = run_mode(mode)
        out[mode] = {'results': res, 'lazy_errors': lazy[:20], 'lazy_count': len(lazy)}
        print(mode, 'endpoints', len(res), 'lazy errors in log', len(lazy), flush=True)
    json.dump(out, open(os.path.join(RAW, f'smoke-{VARIANT}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    diffs = [(p, out['on']['results'][p], out['off']['results'][p]) for p in out['on']['results']
             if out['on']['results'][p][0] != out['off']['results'].get(p, [None])[0]]
    print('status differs between OSIV on/off:', len(diffs))
    for p, a, b in diffs:
        print('  ', p, a, '->', b)
    st = collections.Counter(v[0] for v in out['off']['results'].values())
    print('OSIV off status counts:', dict(st))
    for l in out['off']['lazy_errors'][:5]:
        print('  LAZY:', l[:200])
    print('SMOKE_DONE', flush=True)


if __name__ == '__main__':
    main()

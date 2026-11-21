"""OSIV를 끈 상태에서 수정 전(m)·수정 후(sf) 검색 응답이 같은지 질의 8개로 확인한다."""
import json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import exp10_osiv as x10  # noqa: E402
import exp8_search_tx as e8  # noqa: E402
import exp5_connhold as e5  # noqa: E402

h = {}
for v in ('m', 'sf'):
    x10.start_app(v)
    e5.restart_stub(20)
    e5.sh(['docker', 'exec', 'urisik-redis', 'redis-cli', 'FLUSHALL'])   # 앞선 구성이 저장한 캐시를 재사용하지 않게 한다
    h[v] = e8.response_hashes()
same = h['m'] == h['sf']
json.dump({'hashes': h, 'identical': same}, open(os.path.join(x10.RAW, 'response-hashes-osiv-off.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
e5.append([['exp10', 'response_identical_osiv_off', 'm_vs_sf', 1, 'queries_identical', sum(1 for k in h['m'] if h['m'][k] == h['sf'][k]), f'of {len(h["m"])}']])
print('identical with OSIV off:', same, flush=True)

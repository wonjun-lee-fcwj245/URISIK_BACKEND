"""실험 11: 외부 API stub의 연결 대기열을 늘린(request_queue_size=1024) 뒤 핵심 조건을 다시 잰다.
기존 stub은 기본 대기열 5라 연결이 몰릴 때 3초 지연이 섞였을 수 있다. 조건: 캐시 미적중(검색어 유일) 100 RPS·10 RPS.
  python exp11_stubfix.py     # m(OSIV 켬), sf(OSIV 끔) × 3라운드(1회차 워밍업)
"""
import json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import exp10_osiv as x10  # noqa: E402
import exp8_search_tx as e8  # noqa: E402
import exp5_connhold as e5  # noqa: E402
RAW = os.path.join(os.path.dirname(HERE), 'raw', 'exp11')
os.makedirs(RAW, exist_ok=True)
e5.RAW = RAW
CONDS = [('search_unique_100rps', 100), ('search_unique_10rps', 10)]


def main():
    for run_no in (1, 2, 3):
        for tag, variant in ((('m_on', 'm'), ('sf_off', 'sf')) if run_no % 2 else (('sf_off', 'sf'), ('m_on', 'm'))):
            if tag == 'm_on':
                e8.switch(variant)          # OSIV 켬(기본)
            else:
                x10.start_app(variant)      # OSIV 끔
            e5.restart_stub(20)             # 수정된 stub 재기동
            for label, rate in CONDS:
                vals = e5.run_cond(f'{tag}_{label}', 'search', rate, run_no, 'unique')
                e5.append([['exp11', label, tag, run_no, k, v, f'stubfix;rate={rate}rps;kw=unique;warmup={"yes" if run_no == 1 else "no"}'] for k, v in vals.items()])
                print(tag, label, 'run', run_no, json.dumps(vals), flush=True)
    print('EXP11_DONE', flush=True)


if __name__ == '__main__':
    main()

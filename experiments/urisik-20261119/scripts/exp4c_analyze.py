"""실험 4c: 실험 4 재설계 실행(EXP4_RATES로 일부 단계만)의 자원 수집 CSV에서 단계별 Hikari 점유 시간과 ES 우회를 계산한다.
  python exp4c_analyze.py <cfg> <run_no>
단계 i의 시작 = k6 시작 + 5초(setup 추정) + i × 65초(단계 60초 + 간격 5초). 단계 시작+10초 ~ +62초 표본을 쓴다(수집 간격이 docker stats 때문에 약 14초라 표본이 단계당 2~4개).
산출: 평균 사용 커넥션 수(= 점유 시간 합 증가분 / 경과 시간), 건당 점유·대기 시간, 풀 활성·대기, ES 서킷 차단(우회) 요청 수.
리뷰 가설 확인용: 단독 부하에서 잰 API별 점유(검색 18ms, 상세 3ms, 식단 5ms, 추천 0.2ms)와 실험 4 요청 비율로 예측한 평균 커넥션 수와 비교한다."""
import csv
import os
import statistics as st
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
RAW = os.path.join(ROOT, 'raw', os.environ.get('EXP4_RAW', 'exp4b'))
LABEL = os.environ.get('EXP4_LABEL', 'exp4c')
CSV_PATH = os.path.join(ROOT, 'results.csv')
STAGE_S, GAP_S, SETUP_GUESS = 60, 5, 5
PREDICT_MS_PER_REQ = 0.4 * 18 + 0.15 * 3 + 0.15 * 5 + 0.30 * 0.2   # 약 8.5ms (단독 부하 점유 × 실험 4 비율)


def fnum(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def main():
    cfg, run_no = sys.argv[1], int(sys.argv[2])
    rates = [int(x) for x in os.environ.get('EXP4_RATES', '500,550,600').split(',')]
    label = f'arrival_{cfg}_run{run_no}'
    t_k6 = float(open(os.path.join(RAW, label + '.k6start.txt')).read())
    rows = list(csv.DictReader(open(os.path.join(RAW, label + '.resources.csv'), encoding='utf-8')))
    for r in rows:
        r['_t'] = time.mktime(time.strptime(r['ts'], '%Y-%m-%dT%H:%M:%S'))
    out = []
    for i, rate in enumerate(rates):
        start = t_k6 + SETUP_GUESS + i * (STAGE_S + GAP_S)
        need = ('hikari_usage_count', 'hikari_usage_total_s', 'hikari_acquire_count', 'hikari_acquire_total_s')   # 부하 중 수집이 비는 표본은 건너뛴다
        win = [r for r in rows if start + 10 <= r['_t'] <= start + 62 and all(fnum(r.get(k)) is not None for k in need)]
        if len(win) < 2:
            print(cfg, run_no, rate, 'samples', len(win), '(부족)')
            continue
        a, b = win[0], win[-1]
        dt = b['_t'] - a['_t']
        d_use_n = fnum(b['hikari_usage_count']) - fnum(a['hikari_usage_count'])
        d_use_s = fnum(b['hikari_usage_total_s']) - fnum(a['hikari_usage_total_s'])
        d_acq_n = fnum(b['hikari_acquire_count']) - fnum(a['hikari_acquire_count'])
        d_acq_s = fnum(b['hikari_acquire_total_s']) - fnum(a['hikari_acquire_total_s'])
        npc = (fnum(b['es_cb_not_permitted']) or 0) - (fnum(a['es_cb_not_permitted']) or 0)
        act = [fnum(r['hikari_active']) for r in win if fnum(r['hikari_active']) is not None]
        pend = [fnum(r['hikari_pending']) for r in win if fnum(r['hikari_pending']) is not None]
        vals = {
            'window_s': round(dt, 1),
            'borrows_per_s': round(d_use_n / dt, 1),
            'usage_ms_per_borrow': round(d_use_s / d_use_n * 1000, 2) if d_use_n else '',
            'avg_connections_in_use': round(d_use_s / dt, 2),
            'predicted_connections_from_solo_usage': round(rate * PREDICT_MS_PER_REQ / 1000, 2),
            'acquire_ms_per_borrow': round(d_acq_s / d_acq_n * 1000, 2) if d_acq_n else '',
            'hikari_active_median': st.median(act) if act else '',
            'hikari_pending_median': st.median(pend) if pend else '',
            'hikari_pending_max': max(pend) if pend else '',
            'es_circuit_blocked_per_s': round(npc / dt, 1),
            'es_circuit_blocked_share_of_search': round(npc / (rate * 0.4 * dt), 3),
        }
        print(cfg, run_no, f'r{rate}', vals, flush=True)
        out += [[LABEL, f'stage_r{rate}', cfg, run_no, k, v, f'window=start+10s..start+62s;samples~14s apart;predict_ms_per_req={PREDICT_MS_PER_REQ:.1f}'] for k, v in vals.items()]
    new = not os.path.exists(CSV_PATH)
    with open(CSV_PATH, 'a', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        if new:
            w.writerow(['experiment', 'scenario', 'variant', 'run', 'metric', 'value', 'note'])
        w.writerows(out)


if __name__ == '__main__':
    main()

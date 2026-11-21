"""실험 4 회차 유효성 검사: 자원 수집 간격이 벌어졌거나(절전·정지 의심) 앞 단계는 정상인데 뒤 단계가 요청 0건인 회차를 표시한다."""
import csv, glob, json, os, sys
from datetime import datetime
RAW = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'raw', 'exp4')
GAP_S = 120  # 부하 구간에서 docker stats 지연으로 20~65초 간격은 정상적으로 생긴다. 절전은 수십 분 단위
bad = []
for res in sorted(glob.glob(os.path.join(RAW, 'arrival_*_run*.resources.csv'))):
    label = os.path.basename(res).replace('.resources.csv', '')
    ts = [datetime.strptime(r['ts'], '%Y-%m-%dT%H:%M:%S') for r in csv.DictReader(open(res, encoding='utf-8'))]
    gaps = [(b - a).total_seconds() for a, b in zip(ts, ts[1:])]
    mx = max(gaps) if gaps else 0
    j = os.path.join(RAW, label + '.json')
    zero_tail = ''
    if os.path.exists(j):
        m = json.load(open(j, encoding='utf-8'))['metrics']
        rates = sorted(int(k.split('r')[-1].rstrip('}')) for k in m if k.startswith('http_reqs{scenario:r'))
        zeros = [r for r in rates if m[f'http_reqs{{scenario:r{r}}}']['count'] == 0 or
                 m.get(f'status_200{{scenario:r{r}}}', {}).get('count', 0) == 0]
        zero_tail = f'200 응답 0건 단계: {zeros}' if zeros else ''
    flag = 'INVALID?' if mx > GAP_S or zero_tail else 'ok'
    print(f'{flag:9} {label:28} 수집 샘플 {len(ts):3}  최대 간격 {mx:5.0f}s  {zero_tail}')
    if flag != 'ok':
        bad.append(label)
print('의심 회차:', bad or '없음')

// 실험 3: 추천 API 캐시 cold / warm
// env:
//   MODE    = cold | warm | prefill
//   VUS     = 동시 VU (1 또는 50)
//   N       = 총 요청 수 (기본 1000)
//   ZIPF_S  = warm 사용자 분포 Zipf 지수 (기본 1.0), 사용자 1..WARM_USERS
//   WARM_USERS = 100
// cold : 사용자 i(1..N)가 정확히 한 번씩 요청 → 직전 FLUSHALL과 합쳐 전부 처음 보는 키(미적중)
// warm : prefill로 사용자 1..100 × 추천 4종 키를 채운 뒤 Zipf 분포로 요청
// 추천 엔드포인트 4종을 요청 순번으로 돌아가며 호출(tag ep)
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MODE = __ENV.MODE || 'warm';
const N = parseInt(__ENV.N || '1000');
const S = parseFloat(__ENV.ZIPF_S || '1.0');
const WARM_USERS = parseInt(__ENV.WARM_USERS || '100');
const EPS = ['safe-recipes', 'high-score', 'safe-high-score', 'wish'];
const USERS = MODE === 'cold' ? N : WARM_USERS;
const ITER = MODE === 'prefill' ? WARM_USERS * EPS.length : N;

export const options = {
    scenarios: { s: { executor: 'shared-iterations', vus: MODE === 'prefill' ? 10 : parseInt(__ENV.VUS || '1'), iterations: ITER, maxDuration: '20m' } },
    summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    thresholds: Object.fromEntries([['http_req_duration{name:reco}', ['max>=0']], ['http_req_failed{name:reco}', ['rate>=0']]]
        .concat(EPS.map((e) => [`http_req_duration{ep:${e}}`, ['max>=0']]))),
    setupTimeout: '300s',
    discardResponseBodies: true,
};

export function setup() {
    const tokens = [];
    for (let i = 1; i <= USERS; i++) {
        const r = http.post(`${BASE_URL}/api/auth/test-token?memberId=${i}`, null, { responseType: 'text', tags: { name: 'setup' } });
        tokens.push(JSON.parse(r.body).result.accessToken);
    }
    // Zipf CDF
    const w = [];
    let sum = 0;
    for (let k = 1; k <= WARM_USERS; k++) { sum += 1 / Math.pow(k, S); w.push(sum); }
    return { tokens, cdf: w.map((x) => x / sum) };
}

function zipf(cdf) {
    const u = Math.random();
    let lo = 0, hi = cdf.length - 1;
    while (lo < hi) { const m = (lo + hi) >> 1; if (cdf[m] < u) lo = m + 1; else hi = m; }
    return lo;
}

export default function (d) {
    const it = exec.scenario.iterationInTest;
    let u, ep;
    if (MODE === 'cold') { u = it; ep = EPS[it % 4]; }
    else if (MODE === 'prefill') { u = Math.floor(it / 4); ep = EPS[it % 4]; }
    else { u = zipf(d.cdf); ep = EPS[it % 4]; }
    const res = http.get(`${BASE_URL}/api/recommendations/home/${ep}`,
        { headers: { Authorization: `Bearer ${d.tokens[u]}` }, tags: { name: MODE === 'prefill' ? 'prefill' : 'reco', ep } });
    check(res, { 'status 200': (r) => r.status === 200 });
}

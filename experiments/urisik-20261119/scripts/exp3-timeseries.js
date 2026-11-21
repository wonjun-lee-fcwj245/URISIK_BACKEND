// 실험 3 보조: 빈 캐시에서 Zipf 분포 트래픽을 일정 속도로 흘려 적중률이 시간에 따라 어떻게 오르는지 본다
// env: RATE(기본 30 RPS), DURATION_S(기본 420), USERS(기본 1000), ZIPF_S(기본 1.0)
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = parseInt(__ENV.RATE || '30');
const DUR = parseInt(__ENV.DURATION_S || '420');
const USERS = parseInt(__ENV.USERS || '1000');
const S = parseFloat(__ENV.ZIPF_S || '1.0');
const EPS = ['safe-recipes', 'high-score', 'safe-high-score', 'wish'];

export const options = {
    scenarios: { s: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${DUR}s`, preAllocatedVUs: 50, maxVUs: 400 } },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    discardResponseBodies: true,
    setupTimeout: '300s',
};

export function setup() {
    const tokens = [];
    for (let i = 1; i <= USERS; i++) {
        const r = http.post(`${BASE_URL}/api/auth/test-token?memberId=${i}`, null, { responseType: 'text', tags: { name: 'setup' } });
        tokens.push(JSON.parse(r.body).result.accessToken);
    }
    let sum = 0; const cdf = [];
    for (let k = 1; k <= USERS; k++) { sum += 1 / Math.pow(k, S); cdf.push(sum); }
    return { tokens, cdf: cdf.map((x) => x / sum) };
}

function zipf(cdf) {
    const u = Math.random(); let lo = 0, hi = cdf.length - 1;
    while (lo < hi) { const m = (lo + hi) >> 1; if (cdf[m] < u) lo = m + 1; else hi = m; }
    return lo;
}

export default function (d) {
    const u = zipf(d.cdf);
    const ep = EPS[exec.scenario.iterationInTest % 4];
    const res = http.get(`${BASE_URL}/api/recommendations/home/${ep}`, { headers: { Authorization: `Bearer ${d.tokens[u]}` }, tags: { name: 'reco' } });
    check(res, { 'status 200': (r) => r.status === 200 });
}

// 실험 4 재설계: 요청률 단계 부하 (k6를 Docker 네트워크 안에서 실행)
// 이전 설계(exp4-arrival.js)에서 바꾼 것
//  - 단계: 300~600 RPS를 50 단위로(이전 200/400/600은 한 단계가 2배라 SLO 최대 RPS가 출렁였다)
//  - p95: 성공 응답만(expected_response:true). 실패 요청이 0ms로 섞여 p95가 낮아지던 문제를 없앤다
//  - 식단 404 제거: 가족방 1~100에 이번 주 CONFIRMED 식단을 미리 넣었다(prep_mealplan.py). 404는 따로 센다
// env: RATES, STAGE_S(60), GAP_S(5), MAX_VUS(2500), USERS(100), BASE_URL
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://app:8080';
const RATES = (__ENV.RATES || '300,350,400,450,500,550,600').split(',').map(Number);
const STAGE_S = parseInt(__ENV.STAGE_S || '60');
const GAP_S = parseInt(__ENV.GAP_S || '5');
const MAX_VUS = parseInt(__ENV.MAX_VUS || '2500');
const USERS = parseInt(__ENV.USERS || '100');

const s200 = new Counter('status_200');
const s404 = new Counter('status_404');
const s5xx = new Counter('status_5xx');
const sOther = new Counter('status_other'); // 0(연결 거부·타임아웃 등) 포함

const scenarios = {};
const thresholds = {};
RATES.forEach((rate, i) => {
    const name = `r${rate}`;
    scenarios[name] = {
        executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: `${STAGE_S}s`,
        preAllocatedVUs: Math.min(MAX_VUS, Math.max(100, rate)), maxVUs: MAX_VUS,
        startTime: `${i * (STAGE_S + GAP_S)}s`, gracefulStop: '10s',
    };
    thresholds[`http_req_duration{scenario:${name},expected_response:true}`] = ['max>=0'];
    thresholds[`http_req_duration{scenario:${name}}`] = ['max>=0'];
    for (const m of ['http_reqs', 'dropped_iterations', 'status_200', 'status_404', 'status_5xx', 'status_other']) {
        thresholds[`${m}{scenario:${name}}`] = ['count>=0'];
    }
});

export const options = {
    scenarios, thresholds,
    summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    discardResponseBodies: true,
    setupTimeout: '120s',
};

export function setup() {
    const tokens = [];
    for (let i = 1; i <= USERS; i++) {
        const res = http.post(`${BASE_URL}/api/auth/test-token?memberId=${i}`, null, { responseType: 'text' });
        tokens.push(JSON.parse(res.body).result.accessToken);
    }
    return { tokens };
}

const KEYWORDS = ['김치찌개', '된장찌개', '두부', '삼겹살', '비빔밥', '불고기', '잡채'];
const RECO = ['/api/recommendations/home/safe-recipes', '/api/recommendations/home/high-score',
    '/api/recommendations/home/safe-high-score', '/api/recommendations/home/wish'];

export default function (data) {
    const u = Math.floor(Math.random() * USERS);
    const headers = { Authorization: `Bearer ${data.tokens[u]}` };
    const r = Math.random();
    let res, ep;
    if (r < 0.4) {
        ep = 'search';
        const kw = KEYWORDS[Math.floor(Math.random() * KEYWORDS.length)];
        res = http.get(`${BASE_URL}/api/recipes/search?keyword=${encodeURIComponent(kw)}&page=0&size=10`, { headers, tags: { ep } });
    } else if (r < 0.7) {
        ep = 'recommend';
        res = http.get(`${BASE_URL}${RECO[Math.floor(Math.random() * RECO.length)]}`, { headers, tags: { ep } });
    } else if (r < 0.85) {
        ep = 'detail';
        res = http.get(`${BASE_URL}/api/recipes/${Math.floor(Math.random() * 5000) + 1}`, { headers, tags: { ep } });
    } else {
        ep = 'mealplan';
        res = http.get(`${BASE_URL}/api/family-rooms/${u + 1}/meal-plans/today`, { headers, tags: { ep } });
    }
    const tags = { ep };
    if (res.status === 200) s200.add(1, tags);
    else if (res.status === 404) s404.add(1, tags);
    else if (res.status >= 500) s5xx.add(1, tags);
    else sOther.add(1, tags);
    check(res, { 'status 200': (x) => x.status === 200 });
}

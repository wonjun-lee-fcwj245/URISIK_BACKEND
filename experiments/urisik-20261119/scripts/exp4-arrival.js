// 실험 4: 요청률(arrival-rate) 단계 부하
// 단계마다 constant-arrival-rate 시나리오 하나 → 단계별 RPS·p95·에러율·dropped_iterations를 시나리오 태그로 분리
// env:
//   RATES    = 초당 요청 수 단계 (콤마), 기본 100,200,400,600,800,1000,1200,1500
//   STAGE_S  = 단계당 유지 시간(초), 기본 60
//   GAP_S    = 단계 사이 쉬는 시간(초), 기본 5
//   MAX_VUS  = 시나리오당 최대 VU, 기본 2000
//   USERS    = 토큰을 발급할 유저 수, 기본 100 (기존 스크립트는 1명 → 추천이 항상 캐시 적중)
// 요청 구성은 기존 overall-load-test.js와 같다: 검색 40%, 추천 30%, 상세 15%, 식단 15%
// 성공은 HTTP 200만 인정. 404는 따로 센다.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATES = (__ENV.RATES || '100,200,400,600,800,1000,1200,1500').split(',').map(Number);
const STAGE_S = parseInt(__ENV.STAGE_S || '60');
const GAP_S = parseInt(__ENV.GAP_S || '5');
const MAX_VUS = parseInt(__ENV.MAX_VUS || '2000');
const USERS = parseInt(__ENV.USERS || '100');

const s200 = new Counter('status_200');
const s404 = new Counter('status_404');
const s5xx = new Counter('status_5xx');
const sOther = new Counter('status_other'); // 0(타임아웃·연결 실패) 포함

const scenarios = {};
const thresholds = {};
RATES.forEach((rate, i) => {
    const name = `r${rate}`;
    scenarios[name] = {
        executor: 'constant-arrival-rate',
        rate,
        timeUnit: '1s',
        duration: `${STAGE_S}s`,
        preAllocatedVUs: Math.min(MAX_VUS, Math.max(50, rate)),
        maxVUs: MAX_VUS,
        startTime: `${i * (STAGE_S + GAP_S)}s`,
        gracefulStop: '10s',
    };
    // 단계별 서브메트릭을 summary에 노출하기 위한 threshold (판정용 아님)
    for (const m of ['http_req_duration', 'http_reqs', 'dropped_iterations', 'status_200', 'status_404', 'status_5xx', 'status_other']) {
        thresholds[`${m}{scenario:${name}}`] = m === 'http_req_duration' ? ['max>=0'] : ['count>=0'];
    }
});
for (const ep of ['search', 'recommend', 'detail', 'mealplan']) {
    thresholds[`http_req_duration{ep:${ep}}`] = ['max>=0'];
}

export const options = {
    scenarios,
    thresholds,
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
    const memberId = u + 1;
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
        // seed 규칙: memberId = familyRoomId
        res = http.get(`${BASE_URL}/api/family-rooms/${memberId}/meal-plans/today`, { headers, tags: { ep } });
    }
    const tags = { ep };
    if (res.status === 200) s200.add(1, tags);
    else if (res.status === 404) s404.add(1, tags);
    else if (res.status >= 500) s5xx.add(1, tags);
    else sOther.add(1, tags);
    check(res, { 'status 200': (x) => x.status === 200 });
}

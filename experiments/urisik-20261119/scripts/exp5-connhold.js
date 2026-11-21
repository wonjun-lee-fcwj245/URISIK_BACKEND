// 실험 5: API별 커넥션 점유 시간 측정용 단독 부하 (일정 속도)
// env: API=search|recommend|detail|mealplan, RATE(기본 100), DURATION_S(기본 60), USERS(기본 100)
// search는 키워드마다 숫자를 붙여 외부 API 캐시(externalRecipeSearch, 키=키워드)를 항상 미적중시킨다.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API = __ENV.API || 'search';
const RATE = parseInt(__ENV.RATE || '100');
const DUR = parseInt(__ENV.DURATION_S || '60');
const USERS = parseInt(__ENV.USERS || '100');
// 미적중 조건: 실행마다 SALT가 달라 검색어가 이전 실행과도 겹치지 않는다(난수 4자리는 조합이 약 63,000개뿐이라 Redis 캐시에 쌓여 오염됐다)
const SALT = __ENV.SALT || String(Date.now());
const KEYWORDS = ['김치찌개', '된장찌개', '두부', '삼겹살', '비빔밥', '불고기', '잡채'];
const RECO = ['safe-recipes', 'high-score', 'safe-high-score', 'wish'];

export const options = {
    scenarios: { s: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${DUR}s`, preAllocatedVUs: 100, maxVUs: 600 } },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    thresholds: { 'http_req_duration{name:target}': ['max>=0'], 'http_req_failed{name:target}': ['rate>=0'] },
    discardResponseBodies: true,
    setupTimeout: '120s',
};

export function setup() {
    const tokens = [];
    for (let i = 1; i <= USERS; i++) {
        const r = http.post(`${BASE_URL}/api/auth/test-token?memberId=${i}`, null, { responseType: 'text', tags: { name: 'setup' } });
        tokens.push(JSON.parse(r.body).result.accessToken);
    }
    return { tokens };
}

export default function (d) {
    const it = exec.scenario.iterationInTest;
    const u = it % USERS;
    const headers = { Authorization: `Bearer ${d.tokens[u]}` };
    let url;
    if (API === 'search') {
        // KW_MODE=repeat: 실험 4와 같이 7개 검색어를 반복(외부 API 캐시 적중). 기본은 매번 미적중
        const kw = __ENV.KW_MODE === 'repeat' ? KEYWORDS[it % KEYWORDS.length] : `${KEYWORDS[it % KEYWORDS.length]} ${SALT}-${it}`;
        url = `${BASE_URL}/api/recipes/search?keyword=${encodeURIComponent(kw)}&page=0&size=10`;
    } else if (API === 'recommend') {
        url = `${BASE_URL}/api/recommendations/home/${RECO[it % 4]}`;
    } else if (API === 'detail') {
        url = `${BASE_URL}/api/recipes/${1 + Math.floor(Math.random() * 5000)}`;
    } else {
        url = `${BASE_URL}/api/family-rooms/${u + 1}/meal-plans/today`;
    }
    const res = http.get(url, { headers, tags: { name: 'target' } });
    check(res, { 'status 200': (r) => r.status === 200 });
}

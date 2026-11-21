// 실험 6: 부하 중 Redis 중단·복구. API별 오류율·지연을 보고 장애가 다른 API로 번지는지 본다.
// 요청은 검색 40%, 추천 30%, 상세 15%, 식단 15%. 클라이언트 타임아웃 5초(걸리면 status 0).
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = parseInt(__ENV.RATE || '60');
const DUR = parseInt(__ENV.DURATION_S || '100');
const USERS = 100;
const KEYWORDS = ['김치찌개', '된장찌개', '두부', '삼겹살', '비빔밥', '불고기', '잡채'];
const RECO = ['safe-recipes', 'high-score', 'safe-high-score', 'wish'];

export const options = {
    scenarios: { s: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: `${DUR}s`, preAllocatedVUs: 100, maxVUs: 700 } },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
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
    const u = Math.floor(Math.random() * USERS);
    const headers = { Authorization: `Bearer ${d.tokens[u]}` };
    const r = Math.random();
    let name, url;
    if (r < 0.4) {
        name = 'search';
        url = `${BASE_URL}/api/recipes/search?keyword=${encodeURIComponent(KEYWORDS[Math.floor(Math.random() * 7)])}&page=0&size=10`;
    } else if (r < 0.7) {
        name = 'recommend';
        url = `${BASE_URL}/api/recommendations/home/${RECO[Math.floor(Math.random() * 4)]}`;
    } else if (r < 0.85) {
        name = 'detail';
        url = `${BASE_URL}/api/recipes/${1 + Math.floor(Math.random() * 5000)}`;
    } else {
        name = 'mealplan';
        url = `${BASE_URL}/api/family-rooms/${u + 1}/meal-plans/today`;
    }
    const res = http.get(url, { headers, tags: { name }, timeout: '5s' });
    check(res, { 'status 200': (x) => x.status === 200 });
}

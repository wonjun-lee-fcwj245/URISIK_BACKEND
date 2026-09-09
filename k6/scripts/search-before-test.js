import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, getTestToken, authHeaders } from '../helpers/config.js';

const exactSearchDuration = new Trend('exact_search_duration');
const typoSearchDuration = new Trend('typo_search_duration');
const ingredientSearchDuration = new Trend('ingredient_search_duration');

// ES 도입 전(MySQL LIKE fallback) 성능 측정용
// search-test.js와 동일한 stages/thresholds로 공정한 비교

export const options = {
    stages: [
        { duration: '10s', target: 1 },
        { duration: '30s', target: 10 },
        { duration: '1m', target: 30 },
        { duration: '30s', target: 10 },
        { duration: '10s', target: 1 },
    ],
    thresholds: {
        http_req_duration: ['p(95)<3000'],
        http_req_failed: ['rate<0.05'],
    },
};

export function setup() {
    const token = getTestToken(1);
    return { token };
}

export default function (data) {
    const params = authHeaders(data.token);

    // 1. 정상 검색 (MySQL LIKE '%김치찌개%')
    const exactRes = http.get(
        `${BASE_URL}/api/recipes/search?keyword=${encodeURIComponent('김치찌개')}&page=0&size=10`,
        params
    );
    exactSearchDuration.add(exactRes.timings.duration);
    check(exactRes, {
        '[정상검색] status 200': (r) => r.status === 200,
    });

    sleep(0.5);

    // 2. 오타 검색 (MySQL LIKE에는 fuzziness 없음 — 결과 없을 수 있음)
    const typoRes = http.get(
        `${BASE_URL}/api/recipes/search?keyword=${encodeURIComponent('김치찌게')}&page=0&size=10`,
        params
    );
    typoSearchDuration.add(typoRes.timings.duration);
    check(typoRes, {
        '[오타검색] status 200': (r) => r.status === 200,
    });

    sleep(0.5);

    // 3. 재료 기반 검색 (MySQL LIKE '%두부%')
    const ingredientRes = http.get(
        `${BASE_URL}/api/recipes/search?keyword=${encodeURIComponent('두부')}&page=0&size=10`,
        params
    );
    ingredientSearchDuration.add(ingredientRes.timings.duration);
    check(ingredientRes, {
        '[재료검색] status 200': (r) => r.status === 200,
    });

    sleep(0.5);
}

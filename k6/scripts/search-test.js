import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, getTestToken, authHeaders } from '../helpers/config.js';

const exactSearchDuration = new Trend('exact_search_duration');
const typoSearchDuration = new Trend('typo_search_duration');
const ingredientSearchDuration = new Trend('ingredient_search_duration');

export const options = {
    stages: [
        { duration: '10s', target: 1 },
        { duration: '30s', target: 10 },
        { duration: '1m', target: 30 },
        { duration: '30s', target: 10 },
        { duration: '10s', target: 1 },
    ],
    thresholds: {
        http_req_duration: ['p(95)<1000'],
        http_req_failed: ['rate<0.01'],
    },
};

export function setup() {
    const token = getTestToken(1);
    return { token };
}

export default function (data) {
    const params = authHeaders(data.token);

    // 1. 정상 검색
    const exactRes = http.get(`${BASE_URL}/api/recipes/search?keyword=김치찌개&page=0&size=10`, params);
    exactSearchDuration.add(exactRes.timings.duration);
    check(exactRes, {
        '[정상검색] status 200': (r) => r.status === 200,
        '[정상검색] 결과 존재': (r) => {
            const body = JSON.parse(r.body);
            return body.result && body.result.items && body.result.items.length > 0;
        },
    });

    sleep(0.5);

    // 2. 오타 보정 검색 (김치찌게 → 김치찌개)
    const typoRes = http.get(`${BASE_URL}/api/recipes/search?keyword=김치찌게&page=0&size=10`, params);
    typoSearchDuration.add(typoRes.timings.duration);
    check(typoRes, {
        '[오타보정] status 200': (r) => r.status === 200,
        '[오타보정] 결과 존재': (r) => {
            const body = JSON.parse(r.body);
            return body.result && body.result.items && body.result.items.length > 0;
        },
    });

    sleep(0.5);

    // 3. 재료 기반 검색
    const ingredientRes = http.get(`${BASE_URL}/api/recipes/search?keyword=두부&page=0&size=10`, params);
    ingredientSearchDuration.add(ingredientRes.timings.duration);
    check(ingredientRes, {
        '[재료검색] status 200': (r) => r.status === 200,
        '[재료검색] 결과 존재': (r) => {
            const body = JSON.parse(r.body);
            return body.result && body.result.items && body.result.items.length > 0;
        },
    });

    sleep(0.5);
}

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, getTestToken, authHeaders } from '../helpers/config.js';

export const options = {
    stages: [
        // Smoke
        { duration: '30s', target: 2 },
        // Load
        { duration: '1m', target: 10 },
        { duration: '1m', target: 50 },
        { duration: '2m', target: 100 },
        // Stress
        { duration: '1m', target: 150 },
        // Recovery
        { duration: '1m', target: 50 },
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        http_req_duration: ['p(95)<500'],
        http_req_failed: ['rate<0.01'],
    },
};

export function setup() {
    const token = getTestToken(1);
    return { token };
}

const FAMILY_ROOM_ID = __ENV.FAMILY_ROOM_ID || '1';

const searchKeywords = ['김치찌개', '된장찌개', '두부', '삼겹살', '비빔밥', '불고기', '잡채'];

export default function (data) {
    const params = authHeaders(data.token);
    const rand = Math.random();

    if (rand < 0.4) {
        // 검색 (40%)
        const keyword = searchKeywords[Math.floor(Math.random() * searchKeywords.length)];
        const res = http.get(
            `${BASE_URL}/api/recipes/search?keyword=${encodeURIComponent(keyword)}&page=0&size=10`,
            params
        );
        check(res, { '[검색] status 200': (r) => r.status === 200 });

    } else if (rand < 0.7) {
        // 추천 (30%)
        const endpoints = [
            '/api/recommendations/home/safe-recipes',
            '/api/recommendations/home/high-score',
            '/api/recommendations/home/safe-high-score',
            '/api/recommendations/home/wish',
        ];
        const endpoint = endpoints[Math.floor(Math.random() * endpoints.length)];
        const res = http.get(`${BASE_URL}${endpoint}`, params);
        check(res, { '[추천] status 200': (r) => r.status === 200 });

    } else if (rand < 0.85) {
        // 레시피 상세 (15%)
        const recipeId = Math.floor(Math.random() * 10) + 1;
        const res = http.get(`${BASE_URL}/api/recipes/${recipeId}`, params);
        check(res, { '[상세] status 200 or 404': (r) => r.status === 200 || r.status === 404 });

    } else {
        // 식단 조회 (15%)
        const res = http.get(
            `${BASE_URL}/api/family-rooms/${FAMILY_ROOM_ID}/meal-plans/today`,
            params
        );
        check(res, { '[식단] status 200 or 404': (r) => r.status === 200 || r.status === 404 });
    }

    sleep(0.5 + Math.random() * 1);
}

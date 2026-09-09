import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { BASE_URL, getTestToken, authHeaders } from '../helpers/config.js';

const searchDuration = new Trend('search_duration');
const recommendDuration = new Trend('recommend_duration');
const detailDuration = new Trend('detail_duration');
const mealPlanDuration = new Trend('meal_plan_duration');
const errorCount = new Counter('error_count');

export const options = {
    stages: [
        // Warm-up
        { duration: '20s', target: 500 },
        // Step 1
        { duration: '40s', target: 1000 },
        // Step 2
        { duration: '40s', target: 1500 },
        // Step 3
        { duration: '40s', target: 2000 },
        // Step 4: 극한
        { duration: '40s', target: 2500 },
        // Step 5: 한계 돌파
        { duration: '40s', target: 3000 },
        // Recovery
        { duration: '30s', target: 50 },
        { duration: '20s', target: 0 },
    ],
    thresholds: {
        checks: ['rate>0.95'],
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
        searchDuration.add(res.timings.duration);
        if (!check(res, { '[검색] status 200': (r) => r.status === 200 })) {
            errorCount.add(1);
        }

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
        recommendDuration.add(res.timings.duration);
        if (!check(res, { '[추천] status 200': (r) => r.status === 200 })) {
            errorCount.add(1);
        }

    } else if (rand < 0.85) {
        // 레시피 상세 (15%)
        const recipeId = Math.floor(Math.random() * 5000) + 1;
        const res = http.get(`${BASE_URL}/api/recipes/${recipeId}`, params);
        detailDuration.add(res.timings.duration);
        if (!check(res, { '[상세] status 200 or 404': (r) => r.status === 200 || r.status === 404 })) {
            errorCount.add(1);
        }

    } else {
        // 식단 조회 (15%)
        const res = http.get(
            `${BASE_URL}/api/family-rooms/${FAMILY_ROOM_ID}/meal-plans/today`,
            params
        );
        mealPlanDuration.add(res.timings.duration);
        if (!check(res, { '[식단] status 200 or 404': (r) => r.status === 200 || r.status === 404 })) {
            errorCount.add(1);
        }
    }

    // sleep 제거 — 최대 부하
}

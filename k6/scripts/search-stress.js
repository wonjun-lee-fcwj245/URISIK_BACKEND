import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { BASE_URL, getTestToken, authHeaders } from '../helpers/config.js';

/**
 * VU 스케일업 스트레스 테스트
 *
 * 30 → 100 → 200 → 300 VU로 점진 증가하며 p95 > 1s 또는 에러 발생 지점 기록.
 * MySQL(SEARCH_ENGINE=mysql) vs ES(기본) 각각 실행하여 비교.
 *
 * 실행:
 *   k6 run search-stress.js -e SEARCH_MODE=before   (MySQL)
 *   k6 run search-stress.js -e SEARCH_MODE=after    (ES)
 */

const queries = new SharedArray('search-queries', function () {
    return JSON.parse(open('../data/search-queries.json'));
});

const searchDuration = new Trend('stress_search_duration');
const searchErrors = new Counter('stress_search_errors');
const errorRate = new Rate('stress_error_rate');

const MODE = __ENV.SEARCH_MODE || 'after';

export const options = {
    stages: [
        { duration: '30s', target: 30 },
        { duration: '1m', target: 100 },
        { duration: '1m', target: 200 },
        { duration: '1m', target: 300 },
        { duration: '30s', target: 300 },
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        stress_error_rate: ['rate<0.10'],
    },
};

export function setup() {
    const token = getTestToken(1);
    console.log(`[search-stress] SEARCH_MODE=${MODE}`);
    return { token };
}

export default function (data) {
    const params = authHeaders(data.token);

    const q = queries[Math.floor(Math.random() * queries.length)];

    const res = http.get(
        `${BASE_URL}/api/recipes/search?keyword=${encodeURIComponent(q.keyword)}&page=0&size=10`,
        params
    );

    searchDuration.add(res.timings.duration);

    const success = res.status === 200;
    errorRate.add(!success);

    if (!success) {
        searchErrors.add(1);
    }

    check(res, {
        'status 200': (r) => r.status === 200,
    });

    sleep(0.1 + Math.random() * 0.2);
}

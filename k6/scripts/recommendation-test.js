import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { BASE_URL, getTestToken, authHeaders } from '../helpers/config.js';

const cacheMissDuration = new Trend('cache_miss_duration');
const cacheHitDuration = new Trend('cache_hit_duration');
const loadTestDuration = new Trend('load_test_duration');

const ENDPOINTS = [
    '/api/recommendations/home/safe-recipes',
    '/api/recommendations/home/high-score',
    '/api/recommendations/home/safe-high-score',
    '/api/recommendations/home/wish',
];

export const options = {
    scenarios: {
        // 시나리오 1: 캐시 miss/hit 비교 (1 VU, 순차 실행)
        cache_effect: {
            executor: 'per-vu-iterations',
            vus: 1,
            iterations: 1,
            maxDuration: '30s',
            exec: 'cacheMissHitTest',
        },
        // 시나리오 2: 캐시 적용 상태에서 부하 테스트 (시나리오 1 이후 실행)
        load_test: {
            executor: 'ramping-vus',
            startTime: '35s',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 10 },
                { duration: '30s', target: 50 },
                { duration: '1m', target: 50 },
                { duration: '30s', target: 10 },
                { duration: '10s', target: 0 },
            ],
            exec: 'loadTest',
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<1000'],
        http_req_failed: ['rate<0.01'],
        cache_miss_duration: ['avg<2000'],
        cache_hit_duration: ['avg<500'],
    },
};

export function setup() {
    const token = getTestToken(1);
    return { token };
}

// 시나리오 1: 1 VU로 캐시 miss → hit 순차 측정
export function cacheMissHitTest(data) {
    const params = authHeaders(data.token);

    for (const endpoint of ENDPOINTS) {
        // 1회차: 캐시 miss (콜드 스타트)
        const res1 = http.get(`${BASE_URL}${endpoint}`, params);
        cacheMissDuration.add(res1.timings.duration);
        check(res1, {
            [`[캐시 miss] ${endpoint} status 200`]: (r) => r.status === 200,
        });

        sleep(0.3);

        // 2회차: 캐시 hit
        const res2 = http.get(`${BASE_URL}${endpoint}`, params);
        cacheHitDuration.add(res2.timings.duration);
        check(res2, {
            [`[캐시 hit] ${endpoint} status 200`]: (r) => r.status === 200,
        });

        // miss vs hit 응답 시간 출력
        console.log(
            `${endpoint} — miss: ${res1.timings.duration.toFixed(1)}ms, hit: ${res2.timings.duration.toFixed(1)}ms`
        );

        sleep(0.3);
    }
}

// 시나리오 2: 캐시가 warm된 상태에서 다수 VU 부하 테스트
export function loadTest(data) {
    const params = authHeaders(data.token);
    const endpoint = ENDPOINTS[Math.floor(Math.random() * ENDPOINTS.length)];

    const res = http.get(`${BASE_URL}${endpoint}`, params);
    loadTestDuration.add(res.timings.duration);
    check(res, {
        [`[부하] ${endpoint} status 200`]: (r) => r.status === 200,
    });

    sleep(0.5 + Math.random() * 0.5);
}

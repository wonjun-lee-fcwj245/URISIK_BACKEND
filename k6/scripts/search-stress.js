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

// stage 경계 시각(초) — VU 태그 결정용
const STAGE_BOUNDARIES = [30, 90, 150, 210, 240, 270];
const STAGE_LABELS = ['ramp-30', 'ramp-100', 'ramp-200', 'ramp-300', 'hold-300', 'cool-down'];

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

function currentStage() {
    // __ENV.__ITER_TIMESTAMP 없으므로 Date 기반 추정
    const elapsed = (Date.now() - startTime) / 1000;
    for (let i = 0; i < STAGE_BOUNDARIES.length; i++) {
        if (elapsed < STAGE_BOUNDARIES[i]) return STAGE_LABELS[i];
    }
    return STAGE_LABELS[STAGE_LABELS.length - 1];
}

let startTime;

export default function (data) {
    if (!startTime) startTime = Date.now();

    const params = authHeaders(data.token);
    const stage = currentStage();

    const q = queries[Math.floor(Math.random() * queries.length)];

    const res = http.get(
        `${BASE_URL}/api/recipes/search?keyword=${encodeURIComponent(q.keyword)}&page=0&size=10`,
        params
    );

    searchDuration.add(res.timings.duration, { stage });

    const success = res.status === 200;
    errorRate.add(!success, { stage });

    if (!success) {
        searchErrors.add(1, { stage });
    }

    check(res, {
        'status 200': (r) => r.status === 200,
    });

    sleep(0.1 + Math.random() * 0.2);
}

/**
 * 시계열 CSV 출력: --out csv=results.csv 와 병행하거나 단독 사용.
 * 구간별 p95 분석이 가능하도록 stage 태그를 포함한다.
 *
 * 사용법:
 *   k6 run search-stress.js --out csv=stress-results.csv
 */
export function handleSummary(data) {
    const summary = {
        mode: MODE,
        stages: STAGE_LABELS,
        metrics: {
            stress_search_duration: data.metrics.stress_search_duration,
            stress_error_rate: data.metrics.stress_error_rate,
        },
    };
    return {
        stdout: JSON.stringify(summary, null, 2) + '\n',
    };
}

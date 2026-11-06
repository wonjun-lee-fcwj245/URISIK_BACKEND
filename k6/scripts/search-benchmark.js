import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { BASE_URL, getTestToken, authHeaders } from '../helpers/config.js';

/**
 * 통합 검색 벤치마크 스크립트
 *
 * 환경변수:
 *   SEARCH_MODE=before  → MySQL LIKE 측정 (docker-compose에서 SEARCH_ENGINE=mysql 설정 필요)
 *   SEARCH_MODE=after   → ES+Nori 측정 (기본 elasticsearch 설정)
 *
 * 실행:
 *   k6 run search-benchmark.js -e SEARCH_MODE=before
 *   k6 run search-benchmark.js -e SEARCH_MODE=after
 */

const queries = new SharedArray('search-queries', function () {
    return JSON.parse(open('../data/search-queries.json'));
});

const searchDuration = new Trend('search_duration');
const typoSearchDuration = new Trend('typo_search_duration');
const resultCount = new Trend('result_count');
const typoResultCount = new Trend('typo_result_count');
const searchErrors = new Counter('search_errors');

const MODE = __ENV.SEARCH_MODE || 'after';

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
    console.log(`[search-benchmark] SEARCH_MODE=${MODE}`);
    return { token };
}

export default function (data) {
    const params = authHeaders(data.token);

    // 30개 검색어 중 랜덤 선택
    const q = queries[Math.floor(Math.random() * queries.length)];
    const keyword = q.keyword;
    const isTypo = q.isTypo;

    const res = http.get(
        `${BASE_URL}/api/recipes/search?keyword=${encodeURIComponent(keyword)}&page=0&size=10`,
        params
    );

    const duration = res.timings.duration;
    let body = null;
    let items = [];

    if (res.status === 200) {
        try {
            body = JSON.parse(res.body);
            items = (body.result && body.result.items) || [];
        } catch (e) {
            // parse error
        }
    }

    if (isTypo) {
        typoSearchDuration.add(duration);
        typoResultCount.add(items.length);
    } else {
        searchDuration.add(duration);
        resultCount.add(items.length);
    }

    if (res.status !== 200) {
        searchErrors.add(1);
    }

    // 기본 체크
    check(res, {
        'status 200': (r) => r.status === 200,
        '결과 존재': () => q._expectNoConvert || items.length > 0,
    });

    // 오타 보정 상세 체크 (ES 모드에서만 의미 있음)
    if (isTypo && MODE === 'after') {
        check(res, {
            '[오타] 결과 존재': () => items.length > 0,
            '[오타] 1위가 기대 결과 포함': () => {
                if (items.length === 0) return false;
                const firstTitle = items[0].title || '';
                return q.expected.some(exp => firstTitle.includes(exp));
            },
        });
    }

    // 재료 검색 체크 (fixture 기반)
    if (q.isIngredient) {
        check(res, {
            '[재료] 다양한 결과 포함': () => {
                if (items.length < 2) return false;
                const titles = items.map(i => i.title);
                const unique = new Set(titles);
                return unique.size >= 2;
            },
        });
    }

    sleep(0.3 + Math.random() * 0.4);
}

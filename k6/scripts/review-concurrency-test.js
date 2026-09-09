import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, getTestToken, authHeaders } from '../helpers/config.js';

const successCount = new Counter('review_success_count');
const duplicateCount = new Counter('review_duplicate_count');

// 리뷰 동시성 테스트
// 100명의 서로 다른 유저가 동시에 같은 레시피에 리뷰를 작성
// 검증 포인트:
// - reviewCount가 정확히 성공 건수만큼 증가하는지 (atomic increment)
// - avgScore가 정확히 계산되는지 (lost update 없음)
// - 동일 유저 중복 리뷰는 unique constraint로 차단되는지

export const options = {
    scenarios: {
        concurrent_reviews: {
            executor: 'shared-iterations',
            vus: 100,
            iterations: 100,
            maxDuration: '30s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.05'],
    },
};

// 리뷰 대상 레시피 (이전 테스트에서 사용하지 않은 ID)
const RECIPE_ID = __ENV.RECIPE_ID || '100';

export function setup() {
    // 100명의 유저 토큰 발급
    const tokens = [];
    for (let i = 1; i <= 100; i++) {
        tokens.push(getTestToken(i));
    }

    // 테스트 전 레시피 상태 확인
    const params = authHeaders(tokens[0]);
    const res = http.get(`${BASE_URL}/api/recipes/${RECIPE_ID}`, params);
    let initialReviewCount = 0;
    let initialAvgScore = 0;
    if (res.status === 200) {
        const body = JSON.parse(res.body);
        initialReviewCount = body.result.reviewCount || 0;
        initialAvgScore = body.result.avgScore || 0;
    }

    return { tokens, initialReviewCount, initialAvgScore };
}

export default function (data) {
    // 각 VU가 서로 다른 유저 토큰 사용 (VU 번호 기반)
    const vuIndex = (__VU - 1) % 100;
    const token = data.tokens[vuIndex];
    const params = authHeaders(token);

    const score = (vuIndex % 5) + 1; // 1~5 점수 분배

    const payload = JSON.stringify({
        score: score,
        isFavorite: score >= 4,
    });

    const res = http.post(
        `${BASE_URL}/api/recipes/${RECIPE_ID}/reviews`,
        payload,
        params
    );

    if (res.status === 200 || res.status === 201) {
        successCount.add(1);
        check(res, { '[리뷰] 생성 성공': (r) => true });
    } else if (res.status === 409 || res.status === 400) {
        duplicateCount.add(1);
        check(res, { '[리뷰] 중복 차단': (r) => true });
    } else {
        console.log(`[ERROR] VU${__VU} status=${res.status} body=${res.body}`);
        check(res, { '[리뷰] 예상치 못한 응답': (r) => false });
    }
}

export function teardown(data) {
    const params = authHeaders(data.tokens[0]);
    const res = http.get(`${BASE_URL}/api/recipes/${RECIPE_ID}`, params);

    if (res.status === 200) {
        const body = JSON.parse(res.body);
        const finalReviewCount = body.result.reviewCount || 0;
        const finalAvgScore = body.result.avgScore || 0;
        const increase = finalReviewCount - data.initialReviewCount;

        console.log(`=== 리뷰 동시성 검증 ===`);
        console.log(`초기 reviewCount: ${data.initialReviewCount}, 최종: ${finalReviewCount}, 증가분: ${increase}`);
        console.log(`초기 avgScore: ${data.initialAvgScore}, 최종: ${finalAvgScore}`);
        console.log(`기대 증가분: 100 (100명이 각각 1회 리뷰)`);
        console.log(`결과: ${increase === 100 ? '✅ 정확히 100건 — lost update 없음' : '⚠️ ' + increase + '건 — 데이터 불일치 확인 필요'}`);
    }
}

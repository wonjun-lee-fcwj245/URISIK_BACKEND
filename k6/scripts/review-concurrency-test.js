import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, getTestToken, authHeaders } from '../helpers/config.js';

const successCount = new Counter('review_success_count');
const duplicateCount = new Counter('review_duplicate_count');

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
        http_req_failed: ['rate<0.5'],
    },
};

// 테스트에 사용할 레시피 ID (환경변수로 주입 가능)
const RECIPE_ID = __ENV.RECIPE_ID || '1';

export function setup() {
    const token = getTestToken(1);

    // 테스트 전 레시피 상태 확인
    const params = authHeaders(token);
    const res = http.get(`${BASE_URL}/api/recipes/${RECIPE_ID}`, params);
    let initialReviewCount = 0;
    if (res.status === 200) {
        const body = JSON.parse(res.body);
        initialReviewCount = body.result.reviewCount || 0;
    }

    return { token, initialReviewCount };
}

export default function (data) {
    const params = authHeaders(data.token);
    const score = ((__VU % 5) + 1); // 1~5 점수

    const payload = JSON.stringify({
        score: score,
        isFavorite: false,
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
        check(res, { '[리뷰] 중복 차단 확인': (r) => true });
    } else {
        check(res, { '[리뷰] 예상치 못한 응답': (r) => false });
    }
}

export function teardown(data) {
    const params = authHeaders(data.token);
    const res = http.get(`${BASE_URL}/api/recipes/${RECIPE_ID}`, params);

    if (res.status === 200) {
        const body = JSON.parse(res.body);
        const finalReviewCount = body.result.reviewCount || 0;
        console.log(`=== 리뷰 동시성 검증 ===`);
        console.log(`초기 reviewCount: ${data.initialReviewCount}`);
        console.log(`최종 reviewCount: ${finalReviewCount}`);
        console.log(`증가분: ${finalReviewCount - data.initialReviewCount}`);
        console.log(`(unique constraint로 동일 사용자는 1회만 성공해야 함)`);
    }
}

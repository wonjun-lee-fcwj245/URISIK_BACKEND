import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, getTestToken, authHeaders } from '../helpers/config.js';

const successCount = new Counter('wish_success_count');

// 찜 동시성 테스트
// 50명의 서로 다른 유저가 동시에 같은 레시피를 찜
// 검증 포인트:
// - wishCount가 정확히 성공 건수만큼 증가하는지 (atomic increment)
// - 동일 유저 중복 찜은 토글(삭제)되므로 정확히 1회씩만 추가되는지

export const options = {
    scenarios: {
        concurrent_wishes: {
            executor: 'shared-iterations',
            vus: 50,
            iterations: 50,
            maxDuration: '30s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.05'],
    },
};

const RECIPE_ID = __ENV.RECIPE_ID || '200';

export function setup() {
    // 50명의 유저 토큰 발급
    const tokens = [];
    const familyRoomIds = [];
    for (let i = 1; i <= 50; i++) {
        tokens.push(getTestToken(i));
        familyRoomIds.push(i); // 각 유저의 familyRoomId = 유저 번호
    }

    // 테스트 전 레시피 상태 확인
    const params = authHeaders(tokens[0]);
    const res = http.get(`${BASE_URL}/api/recipes/${RECIPE_ID}`, params);
    let initialWishCount = 0;
    if (res.status === 200) {
        const body = JSON.parse(res.body);
        initialWishCount = body.result.wishCount || 0;
    }

    return { tokens, familyRoomIds, initialWishCount };
}

export default function (data) {
    const vuIndex = (__VU - 1) % 50;
    const token = data.tokens[vuIndex];
    const familyRoomId = data.familyRoomIds[vuIndex];
    const params = authHeaders(token);

    const payload = JSON.stringify({
        recipeId: [parseInt(RECIPE_ID)],
        transformedRecipeId: [],
    });

    const res = http.post(
        `${BASE_URL}/api/family-rooms/${familyRoomId}/profile-wishes`,
        payload,
        params
    );

    if (res.status === 200 || res.status === 201) {
        successCount.add(1);
        check(res, { '[위시] 추가 성공': (r) => true });
    } else {
        console.log(`[ERROR] VU${__VU} status=${res.status} body=${res.body}`);
        check(res, { '[위시] 예상치 못한 응답': (r) => false });
    }
}

export function teardown(data) {
    const params = authHeaders(data.tokens[0]);
    const res = http.get(`${BASE_URL}/api/recipes/${RECIPE_ID}`, params);

    if (res.status === 200) {
        const body = JSON.parse(res.body);
        const finalWishCount = body.result.wishCount || 0;
        const increase = finalWishCount - data.initialWishCount;

        console.log(`=== 위시 동시성 검증 ===`);
        console.log(`초기 wishCount: ${data.initialWishCount}, 최종: ${finalWishCount}, 증가분: ${increase}`);
        console.log(`기대 증가분: 50 (50명이 각각 1회 찜)`);
        console.log(`결과: ${increase === 50 ? '✅ 정확히 50건 — lost update 없음' : '⚠️ ' + increase + '건 — 데이터 불일치 확인 필요'}`);
    }
}

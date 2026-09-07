import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, getTestToken, authHeaders } from '../helpers/config.js';

const successCount = new Counter('wish_success_count');
const duplicateCount = new Counter('wish_duplicate_count');

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
        http_req_failed: ['rate<0.5'],
    },
};

const FAMILY_ROOM_ID = __ENV.FAMILY_ROOM_ID || '1';
const RECIPE_ID = __ENV.RECIPE_ID || '1';

export function setup() {
    const token = getTestToken(1);
    return { token };
}

export default function (data) {
    const params = authHeaders(data.token);

    const payload = JSON.stringify({
        recipeId: [parseInt(RECIPE_ID)],
        transformedRecipeId: [],
    });

    const res = http.post(
        `${BASE_URL}/api/family-rooms/${FAMILY_ROOM_ID}/profile-wishes`,
        payload,
        params
    );

    if (res.status === 200 || res.status === 201) {
        successCount.add(1);
        check(res, { '[위시] 추가 성공': (r) => true });
    } else if (res.status === 409 || res.status === 400) {
        duplicateCount.add(1);
        check(res, { '[위시] 중복 차단 확인': (r) => true });
    } else {
        check(res, { '[위시] 예상치 못한 응답': (r) => false });
    }
}

export function teardown(data) {
    const params = authHeaders(data.token);
    const res = http.get(`${BASE_URL}/api/recipes/${RECIPE_ID}`, params);

    if (res.status === 200) {
        const body = JSON.parse(res.body);
        const wishCount = body.result.wishCount || 0;
        console.log(`=== 위시 동시성 검증 ===`);
        console.log(`최종 wishCount: ${wishCount}`);
        console.log(`(unique constraint로 동일 사용자는 1회만 성공해야 함)`);
    }
}

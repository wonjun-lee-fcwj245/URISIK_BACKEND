// 실험 1: 동시 리뷰/찜 정합성·교착 측정
// env:
//   KIND    = review | treview | wish | twish
//   VUS     = 동시 사용자 수 (memberId 1..VUS)
//   IDS     = 대상 ID 목록(콤마). 1개면 모든 VU가 같은 대상, VUS개면 VU마다 다른 대상
//   BASE_URL (기본 http://localhost:8080)
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const KIND = __ENV.KIND || 'review';
const VUS = parseInt(__ENV.VUS || '100');
const IDS = (__ENV.IDS || '1001').split(',').map((s) => parseInt(s));

const ok = new Counter('target_ok');
const dup = new Counter('target_duplicate');
const err5xx = new Counter('target_5xx');
const errOther = new Counter('target_other');

const TAG = { review: 'createReview', treview: 'createTransformedReview', wish: 'addWish', twish: 'addTransformedWish' }[KIND];

export const options = {
    scenarios: {
        burst: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '120s' },
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    // 태그별 서브메트릭을 summary에 노출하기 위한 threshold (판정용 아님)
    thresholds: {
        [`http_req_duration{name:${TAG}}`]: ['max>=0'],
        [`http_req_failed{name:${TAG}}`]: ['rate>=0'],
    },
};

export function setup() {
    const tokens = [];
    for (let i = 1; i <= VUS; i++) {
        const res = http.post(`${BASE_URL}/api/auth/test-token?memberId=${i}`, null, { tags: { name: 'setupToken' } });
        tokens.push(JSON.parse(res.body).result.accessToken);
    }
    return { tokens };
}

export default function (data) {
    const idx = (__VU - 1) % VUS;
    const memberId = idx + 1;
    const target = IDS.length === 1 ? IDS[0] : IDS[idx % IDS.length];
    const params = {
        headers: { Authorization: `Bearer ${data.tokens[idx]}`, 'Content-Type': 'application/json' },
        tags: { name: TAG },
    };
    const score = (idx % 5) + 1;

    let res;
    if (KIND === 'review') {
        res = http.post(`${BASE_URL}/api/recipes/${target}/reviews`, JSON.stringify({ score, isFavorite: score >= 4 }), params);
    } else if (KIND === 'treview') {
        res = http.post(`${BASE_URL}/api/transformed-recipes/${target}/reviews`, JSON.stringify({ score, isFavorite: score >= 4 }), params);
    } else if (KIND === 'wish') {
        // seed 규칙: memberId = familyRoomId
        res = http.post(`${BASE_URL}/api/family-rooms/${memberId}/profile-wishes`, JSON.stringify({ recipeId: [target], transformedRecipeId: [] }), params);
    } else {
        res = http.post(`${BASE_URL}/api/family-rooms/${memberId}/profile-wishes`, JSON.stringify({ recipeId: [], transformedRecipeId: [target] }), params);
    }

    if (res.status === 200 || res.status === 201) ok.add(1);
    else if (res.status === 409 || res.status === 400) dup.add(1);
    else if (res.status >= 500) err5xx.add(1);
    else errOther.add(1);

    check(res, { 'status 200': (r) => r.status === 200 });
    if (res.status !== 200) console.log(`[NON200] VU${__VU} target=${target} status=${res.status} body=${String(res.body).slice(0, 200)}`);
}

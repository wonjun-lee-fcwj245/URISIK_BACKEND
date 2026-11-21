// 평균 별점 누적 오차 검증: 사용자 100명이 레시피 20개에 각자 리뷰 1건씩(총 2,000건)을 동시에 쓴다
// env: BASE (첫 레시피 ID), NREC(기본 20)
// 점수는 (사용자, 레시피)로 정해지는 결정적 값(1~5)이라 재현 가능하다
import http from 'k6/http';
import { check } from 'k6';
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const BASE = parseInt(__ENV.BASE || '4001');
const NREC = parseInt(__ENV.NREC || '20');
const VUS = 100;
export const options = {
    scenarios: { s: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '300s' } },
    setupTimeout: '120s',
    discardResponseBodies: true,
};
export function setup() {
    const tokens = [];
    for (let i = 1; i <= VUS; i++) {
        const r = http.post(`${BASE_URL}/api/auth/test-token?memberId=${i}`, null, { responseType: 'text' });
        tokens.push(JSON.parse(r.body).result.accessToken);
    }
    return { tokens };
}
export default function (d) {
    const u = __VU - 1;
    for (let k = 0; k < NREC; k++) {
        const j = (u + k) % NREC;                       // 사용자마다 시작 레시피를 달리해 20개에 고르게 경합
        const score = ((u * 7 + j * 13 + u * j) % 5) + 1;
        const res = http.post(`${BASE_URL}/api/recipes/${BASE + j}/reviews`, JSON.stringify({ score, isFavorite: score >= 4 }),
            { headers: { Authorization: `Bearer ${d.tokens[u]}`, 'Content-Type': 'application/json' } });
        check(res, { 'status 200': (r) => r.status === 200 });
    }
}

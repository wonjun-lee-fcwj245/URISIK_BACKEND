// 실험 2 지연: 검색 API를 고정 VU로 1분간 호출
// env: VUS(기본 10), DURATION(기본 60s), USERS(기본 100)
// 검색어는 k6/data/search-queries.json(정상·오타·경계 69개)에서 무작위
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const USERS = parseInt(__ENV.USERS || '100');
const queries = new SharedArray('q', () => JSON.parse(open('../../../k6/data/search-queries.json')).map((q) => q.keyword));

export const options = {
    scenarios: { s: { executor: 'constant-vus', vus: parseInt(__ENV.VUS || '10'), duration: __ENV.DURATION || '60s' } },
    summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    thresholds: { 'http_req_duration{name:search}': ['max>=0'], 'http_req_failed{name:search}': ['rate>=0'] },
    discardResponseBodies: true,
};

export function setup() {
    const tokens = [];
    for (let i = 1; i <= USERS; i++) {
        const r = http.post(`${BASE_URL}/api/auth/test-token?memberId=${i}`, null, { responseType: 'text' });
        tokens.push(JSON.parse(r.body).result.accessToken);
    }
    return { tokens };
}

export default function (d) {
    const kw = queries[Math.floor(Math.random() * queries.length)];
    const res = http.get(`${BASE_URL}/api/recipes/search?keyword=${encodeURIComponent(kw)}&page=0&size=10`,
        { headers: { Authorization: `Bearer ${d.tokens[Math.floor(Math.random() * USERS)]}` }, tags: { name: 'search' } });
    check(res, { 'status 200': (r) => r.status === 200 });
}

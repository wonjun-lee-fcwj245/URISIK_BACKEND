// 앱 없이 컨테이너 → 호스트 stub 경로만 잰다(새 연결을 매번 맺음). 10 RPS 60초.
import http from 'k6/http';
export const options = {
    scenarios: { s: { executor: 'constant-arrival-rate', rate: 10, timeUnit: '1s', duration: '60s', preAllocatedVUs: 50, maxVUs: 200 } },
    noConnectionReuse: true,
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};
export default function () {
    http.get('http://openapi.foodsafetykorea.go.kr/api/key/COOKRCP01/json/1/10/RCP_NM=test', { tags: { name: 'stub' } });
}

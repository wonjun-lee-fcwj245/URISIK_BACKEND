import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { BASE_URL, getTestToken, authHeaders } from '../helpers/config.js';

/**
 * 검색 품질 측정 스크립트 (Precision@5, Recall)
 *
 * 30개 검색어에 대해 상위 5개 결과의 Precision과 Recall을 계산한다.
 * 1 VU로 순차 실행하여 정확한 품질 메트릭을 수집한다.
 *
 * 실행:
 *   k6 run search-quality.js
 */

const queries = new SharedArray('search-queries', function () {
    return JSON.parse(open('../data/search-queries.json'));
});

const precisionAt5 = new Trend('precision_at_5');
const recall = new Trend('recall');
const typoPrecision = new Trend('typo_precision_at_5');
const typoRecall = new Trend('typo_recall');
const normalPrecision = new Trend('normal_precision_at_5');
const normalRecall = new Trend('normal_recall');
const holdoutPrecision = new Trend('holdout_precision_at_5');
const holdoutRecall = new Trend('holdout_recall');
const queryCount = new Counter('total_queries');
const typoMatchCount = new Counter('typo_top1_match');
const typoMissCount = new Counter('typo_top1_miss');
const holdoutMatchCount = new Counter('holdout_top1_match');
const holdoutMissCount = new Counter('holdout_top1_miss');
const boundaryPrecision = new Trend('boundary_precision_at_5');
const boundaryMatchCount = new Counter('boundary_top1_match');
const boundaryMissCount = new Counter('boundary_top1_miss');
const noConvertPassCount = new Counter('no_convert_pass');
const noConvertFailCount = new Counter('no_convert_fail');

export const options = {
    vus: 1,
    iterations: queries.length,
    thresholds: {
        precision_at_5: ['avg>0.3'],
        recall: ['avg>0.2'],
    },
};

export function setup() {
    const token = getTestToken(1);
    return { token };
}

export default function (data) {
    const params = authHeaders(data.token);
    const idx = __ITER % queries.length;
    const q = queries[idx];

    const res = http.get(
        `${BASE_URL}/api/recipes/search?keyword=${encodeURIComponent(q.keyword)}&page=0&size=10`,
        params
    );

    queryCount.add(1);

    check(res, {
        'status 200': (r) => r.status === 200,
    });

    if (res.status !== 200) {
        precisionAt5.add(0);
        recall.add(0);
        return;
    }

    let body;
    try {
        body = JSON.parse(res.body);
    } catch (e) {
        precisionAt5.add(0);
        recall.add(0);
        return;
    }

    const items = (body.result && body.result.items) || [];
    const top5 = items.slice(0, 5);
    const expected = q.expected || [];

    // 영문 정상 검색어 테스트: 변환되지 않고 0건이면 pass
    if (q._expectNoConvert) {
        const pass = items.length === 0;
        if (pass) {
            noConvertPassCount.add(1);
        } else {
            noConvertFailCount.add(1);
        }
        console.log(
            `[영문보호] "${q.keyword}" → ${items.length}건 → ${pass ? 'PASS (변환 안 됨)' : 'FAIL (변환됨!)'}`
        );
        sleep(0.5);
        return;
    }

    // Precision@5: 상위 5개 중 정답에 포함된 비율
    let relevantInTop5 = 0;
    for (const item of top5) {
        const title = item.title || '';
        if (expected.some(exp => title.includes(exp))) {
            relevantInTop5++;
        }
    }
    const p5 = top5.length > 0 ? relevantInTop5 / top5.length : 0;

    // Recall: 정답 중 상위 결과에서 찾아낸 비율
    let foundExpected = 0;
    for (const exp of expected) {
        if (items.some(item => (item.title || '').includes(exp))) {
            foundExpected++;
        }
    }
    const rec = expected.length > 0 ? foundExpected / expected.length : 0;

    precisionAt5.add(p5);
    recall.add(rec);

    if (q.isTypo) {
        typoPrecision.add(p5);
        typoRecall.add(rec);

        // 오타 1위 매칭 확인
        if (top5.length > 0 && expected.some(exp => (top5[0].title || '').includes(exp))) {
            typoMatchCount.add(1);
        } else {
            typoMissCount.add(1);
        }

        // boundary 오타 별도 집계
        if (q.isBoundary) {
            boundaryPrecision.add(p5);
            if (top5.length > 0 && expected.some(exp => (top5[0].title || '').includes(exp))) {
                boundaryMatchCount.add(1);
            } else {
                boundaryMissCount.add(1);
            }
        }

        // holdout 오타 별도 집계
        if (q.isHoldout) {
            holdoutPrecision.add(p5);
            holdoutRecall.add(rec);
            if (top5.length > 0 && expected.some(exp => (top5[0].title || '').includes(exp))) {
                holdoutMatchCount.add(1);
            } else {
                holdoutMissCount.add(1);
            }
        }
    } else {
        normalPrecision.add(p5);
        normalRecall.add(rec);
    }

    const tag = q.isBoundary ? '한계테스트' : (q.isHoldout ? '홀드아웃' : (q.isTypo ? '오타' : '정상'));
    console.log(
        `[${tag}] "${q.keyword}" → ` +
        `결과 ${items.length}건, P@5=${(p5 * 100).toFixed(0)}%, ` +
        `Recall@10=${(rec * 100).toFixed(0)}%` +
        (top5.length > 0 ? ` (1위: ${top5[0].title})` : ' (결과 없음)')
    );

    sleep(0.5);
}

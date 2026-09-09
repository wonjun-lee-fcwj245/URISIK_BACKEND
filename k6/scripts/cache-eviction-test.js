import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { BASE_URL, getTestToken, authHeaders } from '../helpers/config.js';

const preEvictDuration = new Trend('pre_evict_duration');
const postEvictDuration = new Trend('post_evict_duration');
const evictionDetected = new Counter('eviction_detected');

// Kafka 이벤트 기반 캐시 무효화 검증
// 리뷰 작성 → Kafka cache-evict 이벤트 → Redis 캐시 삭제 → 다음 요청에서 갱신된 데이터 반환
//
// 흐름:
// 1. 추천 API 호출 → 캐시 miss → DB 조회 후 Redis에 저장
// 2. 추천 API 재호출 → 캐시 hit 확인
// 3. 리뷰 작성 → ReviewService가 Kafka에 cache-evict 이벤트 발행
// 4. Kafka Consumer가 이벤트 수신 → Redis 캐시 삭제
// 5. 추천 API 재호출 → 캐시 miss (eviction 발생) → DB에서 갱신된 데이터 조회

export const options = {
    scenarios: {
        cache_eviction: {
            executor: 'per-vu-iterations',
            vus: 1,
            iterations: 1,
            maxDuration: '60s',
            exec: 'cacheEvictionTest',
        },
    },
    thresholds: {
        checks: ['rate==1.0'],
    },
};

export function setup() {
    const token = getTestToken(1);
    return { token };
}

export function cacheEvictionTest(data) {
    const params = authHeaders(data.token);
    const endpoint = '/api/recommendations/home/high-score';

    // 매 실행마다 다른 레시피에 리뷰를 작성하여 중복 방지
    // (동일 유저+레시피 조합은 unique constraint로 1회만 허용)
    const recipeId = Math.floor(Math.random() * 4998) + 2; // 2~4999

    // ── Step 1: 추천 API 1회차 (캐시 miss → DB 조회 → Redis 저장) ──
    const res1 = http.get(`${BASE_URL}${endpoint}`, params);
    check(res1, {
        '[Step1] 캐시 miss 요청 성공': (r) => r.status === 200,
    });
    console.log(`[Step1] 캐시 miss — ${res1.timings.duration.toFixed(1)}ms`);

    sleep(0.5);

    // ── Step 2: 추천 API 2회차 (캐시 hit 확인) ──
    const res2 = http.get(`${BASE_URL}${endpoint}`, params);
    preEvictDuration.add(res2.timings.duration);
    check(res2, {
        '[Step2] 캐시 hit 요청 성공': (r) => r.status === 200,
        '[Step2] 캐시 hit이 miss보다 빠름': () => res2.timings.duration < res1.timings.duration,
    });
    console.log(`[Step2] 캐시 hit — ${res2.timings.duration.toFixed(1)}ms`);

    sleep(0.5);

    // ── Step 3: 리뷰 작성 (Kafka cache-evict 이벤트 트리거) ──
    // ReviewService → kafkaEventProducer.sendCacheEvict()
    // → Kafka "cache-evict" 토픽 발행
    // → CacheEvictKafkaConsumer 수신 → Redis 캐시 삭제
    const reviewBody = JSON.stringify({
        score: 1,
        isFavorite: false,
    });
    const reviewRes = http.post(
        `${BASE_URL}/api/recipes/${recipeId}/reviews`,
        reviewBody,
        params
    );
    check(reviewRes, {
        '[Step3] 리뷰 작성 성공 (Kafka 이벤트 발행)': (r) => r.status === 200,
    });
    console.log(`[Step3] 리뷰 작성 (recipeId=${recipeId}) — ${reviewRes.timings.duration.toFixed(1)}ms`);

    // Kafka 비동기 이벤트 처리 대기
    sleep(3);

    // ── Step 4: 추천 API 3회차 (캐시 eviction 후 miss 확인) ──
    const res3 = http.get(`${BASE_URL}${endpoint}`, params);
    postEvictDuration.add(res3.timings.duration);
    check(res3, {
        '[Step4] eviction 후 요청 성공': (r) => r.status === 200,
    });

    // eviction 검증: 캐시 hit(Step2)보다 느리면 캐시가 무효화된 것
    const evicted = res3.timings.duration > res2.timings.duration * 1.5;
    if (evicted) {
        evictionDetected.add(1);
    }
    check(null, {
        '[Step4] 캐시 eviction 감지 (miss로 전환)': () => evicted,
    });
    console.log(`[Step4] eviction 후 — ${res3.timings.duration.toFixed(1)}ms (hit 대비 ${(res3.timings.duration / res2.timings.duration).toFixed(1)}배)`);

    sleep(0.5);

    // ── Step 5: 캐시 재생성 확인 (4회차 호출은 다시 캐시 hit) ──
    const res4 = http.get(`${BASE_URL}${endpoint}`, params);
    check(res4, {
        '[Step5] 캐시 재생성 후 hit 성공': (r) => r.status === 200,
        '[Step5] 캐시 재생성 hit이 빠름': () => res4.timings.duration < res3.timings.duration,
    });
    console.log(`[Step5] 캐시 재생성 hit — ${res4.timings.duration.toFixed(1)}ms`);
}

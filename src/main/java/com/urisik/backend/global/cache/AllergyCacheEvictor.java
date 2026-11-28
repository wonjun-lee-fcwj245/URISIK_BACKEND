package com.urisik.backend.global.cache;

import com.urisik.backend.global.kafka.event.CacheEvictEvent;
import com.urisik.backend.global.kafka.producer.KafkaEventProducer;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * 가족 알레르기가 바뀔 때 알레르기 관련 캐시를 지운다(#45).
 *
 * <p>Kafka 비동기 삭제만 쓰면 커밋 뒤 약 0.1초 동안 알레르기 유발 레시피가 섞인 옛 추천이 반환됐다.
 * 그래서 같은 요청 안에서 가족 알레르기 → 추천 순서로 직접 지운다. 캐시 관리자가 트랜잭션 인식형
 * ({@code RedisCacheManager.setTransactionAware(true)})이므로 트랜잭션 안에서 부르면 {@code evict}/{@code clear}는
 * 커밋 직후, 응답을 돌려주기 전에 같은 스레드에서 실행된다(커밋 전에 지우면 다른 요청이 옛 DB 값으로 다시 채울 수 있다).
 * 커밋 콜백 안에서 다시 부르면 데코레이터가 실행되지 않는 콜백을 새로 등록하므로, 반드시 트랜잭션 안에서 호출한다.
 * 캐시는 Redis 하나를 모든 인스턴스가 함께 쓰므로 한 곳에서 지우면 된다.
 *
 * <p>직접 삭제와 동시에 옛 알레르기로 계산 중이던 요청이 결과를 다시 캐시할 수 있으므로,
 * 기존 Kafka 삭제(같은 레코드 키로 순서 보장, #37)도 그대로 보내 한 번 더 정리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AllergyCacheEvictor {

    static final String FAMILY_ALLERGENS = "familyAllergens";
    static final List<String> RECOMMEND_CACHES =
            List.of("recommendSafe", "recommendHighScore", "recommendSafeHighScore", "recommendWish");

    private final CacheManager cacheManager;
    private final KafkaEventProducer kafkaEventProducer;

    public void evictForFamily(Long familyRoomId) {
        String key = String.valueOf(familyRoomId);
        Cache family = cacheManager.getCache(FAMILY_ALLERGENS);
        if (family != null) {
            family.evict(key);              // 트랜잭션 안이면 커밋 직후 실행(등록 순서대로)
        }
        for (String name : RECOMMEND_CACHES) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
        kafkaEventProducer.sendCacheEvictInOrder(key, List.of(
                CacheEvictEvent.withKey(List.of(FAMILY_ALLERGENS), key),
                CacheEvictEvent.allEntries(RECOMMEND_CACHES)
        ));
        log.debug("[Cache] 알레르기 변경: 캐시 직접 삭제 + Kafka 삭제 예약 familyRoom={}", key);
    }
}

package com.urisik.backend.global.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.urisik.backend.global.kafka.producer.KafkaEventProducer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** #45: 알레르기 변경 시 캐시를 커밋 직후(같은 요청 안) 지우고, Kafka 삭제도 보낸다. */
class AllergyCacheEvictorTest {

    private final List<String> names = new ArrayList<>(AllergyCacheEvictor.RECOMMEND_CACHES);

    private SimpleCacheManager transactionAwareManager() {
        names.add(AllergyCacheEvictor.FAMILY_ALLERGENS);
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(names.stream()
                .map(n -> (Cache) new TransactionAwareCacheDecorator(new ConcurrentMapCache(n)))
                .toList());
        manager.afterPropertiesSet();
        return manager;
    }

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void evictsRightAfterCommitNotBefore() {
        SimpleCacheManager manager = transactionAwareManager();
        KafkaEventProducer producer = mock(KafkaEventProducer.class);
        manager.getCache("familyAllergens").put("7", List.of("PORK"));
        manager.getCache("recommendSafe").put(3L, "old");

        TransactionSynchronizationManager.initSynchronization();   // 트랜잭션 진행 중
        new AllergyCacheEvictor(manager, producer).evictForFamily(7L);

        // 커밋 전에는 지우지 않는다(지우면 다른 요청이 옛 DB 값으로 다시 채울 수 있다)
        assertThat(manager.getCache("familyAllergens").get("7")).isNotNull();
        assertThat(manager.getCache("recommendSafe").get(3L)).isNotNull();

        // 커밋 직후 등록된 콜백이 같은 스레드에서 실행되면 지워진다
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(manager.getCache("familyAllergens").get("7")).isNull();
        assertThat(manager.getCache("recommendSafe").get(3L)).isNull();

        verify(producer).sendCacheEvictInOrder(eq("7"), anyList());   // Kafka로 한 번 더 정리
    }

    @Test
    void evictsImmediatelyOutsideTransaction() {
        SimpleCacheManager manager = transactionAwareManager();
        manager.getCache("familyAllergens").put("7", List.of("PORK"));
        new AllergyCacheEvictor(manager, mock(KafkaEventProducer.class)).evictForFamily(7L);
        assertThat(manager.getCache("familyAllergens").get("7")).isNull();
    }
}

package com.urisik.backend.global.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.urisik.backend.global.kafka.event.CacheEvictEvent;
import com.urisik.backend.global.kafka.producer.KafkaEventProducer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.core.KafkaTemplate;

/** #37: 알레르기 변경 시 캐시 삭제를 같은 레코드 키로, 가족 알레르기 → 추천 순서로 발행해야 한다. */
class KafkaEventProducerOrderTest {

    @Test
    @SuppressWarnings("unchecked")
    void evictionsAreSentInOrderWithSameRecordKey() {
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        when(template.send(anyString(), anyString(), any())).thenReturn(new CompletableFuture<>());
        KafkaEventProducer producer = new KafkaEventProducer(template);

        CacheEvictEvent family = CacheEvictEvent.withKey(List.of("familyAllergens"), "7");
        CacheEvictEvent reco = CacheEvictEvent.allEntries(List.of("recommendSafe"));
        producer.sendCacheEvictInOrder("7", List.of(family, reco));   // 트랜잭션 밖이므로 바로 발행

        InOrder order = inOrder(template);
        order.verify(template).send(eq("cache-evict"), eq("7"), same(family));
        order.verify(template).send(eq("cache-evict"), eq("7"), same(reco));
    }
}

package com.urisik.backend.global.kafka.producer;

import com.urisik.backend.global.kafka.event.CacheEvictEvent;
import com.urisik.backend.global.kafka.event.NotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendCacheEvict(List<String> cacheNames) {
        CacheEvictEvent event = CacheEvictEvent.allEntries(cacheNames);
        sendAfterCommit("cache-evict", event,
                () -> log.debug("[Kafka] cache-evict 발행: {}", cacheNames));
    }

    public void sendCacheEvict(List<String> cacheNames, String key) {
        CacheEvictEvent event = CacheEvictEvent.withKey(cacheNames, key);
        sendAfterCommit("cache-evict", event,
                () -> log.debug("[Kafka] cache-evict 발행: {} key={}", cacheNames, key));
    }

    /**
     * 여러 캐시 삭제를 같은 레코드 키로 주어진 순서대로 발행한다(#37).
     * 같은 키는 같은 파티션으로 가고, 프로듀서 멱등성(kafka-clients 기본값)과 소비자 동시성 1로 처리 순서가 유지된다.
     * 예: 알레르기 변경 시 가족 알레르기 캐시를 먼저 지우고 추천 캐시를 지워, 옛 알레르기로 추천이 다시 계산되지 않게 한다.
     */
    public void sendCacheEvictInOrder(String recordKey, List<CacheEvictEvent> events) {
        runAfterCommit(() -> events.forEach(event -> doSend("cache-evict", recordKey, event,
                () -> log.debug("[Kafka] cache-evict 발행(순서 보장): {} key={} recordKey={}",
                        event.getCacheNames(), event.getKey(), recordKey))));
    }

    public void sendNotification(Long familyRoomId, Integer mealPlanGenerationCount) {
        NotificationEvent event = new NotificationEvent(familyRoomId, mealPlanGenerationCount);
        sendAfterCommit("meal-plan-confirmed", event,
                () -> log.debug("[Kafka] meal-plan-confirmed 발행: familyRoomId={}, count={}",
                        familyRoomId, mealPlanGenerationCount));
    }

    private void sendAfterCommit(String topic, Object event, Runnable onSuccess) {
        runAfterCommit(() -> doSend(topic, null, event, onSuccess));
    }

    private void runAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            task.run();
                        }
                    }
            );
        } else {
            task.run();
        }
    }

    private void doSend(String topic, String recordKey, Object event, Runnable onSuccess) {
        try {
            (recordKey == null ? kafkaTemplate.send(topic, event) : kafkaTemplate.send(topic, recordKey, event))
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("[Kafka] {} 비동기 발행 실패", topic, ex);
                        } else {
                            onSuccess.run();
                        }
                    });
        } catch (Exception e) {
            log.error("[Kafka] {} 동기 발행 실패", topic, e);
        }
    }
}

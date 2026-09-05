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

    public void sendNotification(Long familyRoomId, Integer mealPlanGenerationCount) {
        NotificationEvent event = new NotificationEvent(familyRoomId, mealPlanGenerationCount);
        sendAfterCommit("meal-plan-confirmed", event,
                () -> log.debug("[Kafka] meal-plan-confirmed 발행: familyRoomId={}, count={}",
                        familyRoomId, mealPlanGenerationCount));
    }

    private void sendAfterCommit(String topic, Object event, Runnable onSuccess) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            doSend(topic, event, onSuccess);
                        }
                    }
            );
        } else {
            doSend(topic, event, onSuccess);
        }
    }

    private void doSend(String topic, Object event, Runnable onSuccess) {
        try {
            kafkaTemplate.send(topic, event)
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

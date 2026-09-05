package com.urisik.backend.global.kafka.producer;

import com.urisik.backend.global.kafka.event.CacheEvictEvent;
import com.urisik.backend.global.kafka.event.NotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendCacheEvict(List<String> cacheNames) {
        CacheEvictEvent event = CacheEvictEvent.allEntries(cacheNames);
        kafkaTemplate.send("cache-evict", event);
        log.debug("[Kafka] cache-evict 발행: {}", cacheNames);
    }

    public void sendCacheEvict(List<String> cacheNames, String key) {
        CacheEvictEvent event = CacheEvictEvent.withKey(cacheNames, key);
        kafkaTemplate.send("cache-evict", event);
        log.debug("[Kafka] cache-evict 발행: {} key={}", cacheNames, key);
    }

    public void sendNotification(Long familyRoomId, Integer mealPlanGenerationCount) {
        NotificationEvent event = new NotificationEvent(familyRoomId, mealPlanGenerationCount);
        kafkaTemplate.send("meal-plan-confirmed", event);
        log.debug("[Kafka] meal-plan-confirmed 발행: familyRoomId={}, count={}",
                familyRoomId, mealPlanGenerationCount);
    }
}

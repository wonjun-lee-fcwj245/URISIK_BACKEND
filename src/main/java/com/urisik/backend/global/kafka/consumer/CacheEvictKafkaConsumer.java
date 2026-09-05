package com.urisik.backend.global.kafka.consumer;

import com.urisik.backend.global.kafka.event.CacheEvictEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheEvictKafkaConsumer {

    private final CacheManager cacheManager;

    @KafkaListener(
            topics = "cache-evict",
            containerFactory = "cacheEvictListenerFactory"
    )
    public void handleCacheEvict(CacheEvictEvent event) {
        for (String cacheName : event.getCacheNames()) {
            var cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                log.warn("[Kafka][CacheEvict] 캐시 없음: {}", cacheName);
                continue;
            }

            if (event.getKey() != null) {
                cache.evict(event.getKey());
                log.debug("[Kafka][CacheEvict] {} key={} 삭제", cacheName, event.getKey());
            } else {
                cache.clear();
                log.debug("[Kafka][CacheEvict] {} 전체 삭제", cacheName);
            }
        }
    }
}

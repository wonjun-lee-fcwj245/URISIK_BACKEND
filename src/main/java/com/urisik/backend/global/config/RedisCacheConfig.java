package com.urisik.backend.global.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

@Configuration
@EnableCaching
public class RedisCacheConfig {

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        objectMapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .allowIfSubType("com.urisik.backend.")
                        .allowIfSubType("java.")
                        .allowIfSubType("jakarta.")
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL
        );

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> cacheConfigs = Map.of(
                "popularKeywords", defaultConfig.entryTtl(Duration.ofHours(3)),
                "familyAllergens", defaultConfig.entryTtl(Duration.ofMinutes(10)),
                "externalRecipeSearch", defaultConfig.entryTtl(Duration.ofHours(1)),
                "recommendSafe", defaultConfig.entryTtl(Duration.ofMinutes(5)),
                "recommendHighScore", defaultConfig.entryTtl(Duration.ofMinutes(5)),
                "recommendSafeHighScore", defaultConfig.entryTtl(Duration.ofMinutes(5)),
                "recommendWish", defaultConfig.entryTtl(Duration.ofMinutes(5))
        );

        // 캐시 오류가 요청 실패로 번지지 않게, 트랜잭션 데코레이터 안쪽(실제 RedisCache 바로 바깥)에 FailureTolerantCache를 끼운다
        RedisCacheManager manager = new RedisCacheManager(
                RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory),
                defaultConfig.entryTtl(Duration.ofMinutes(10)),
                cacheConfigs) {
            @Override
            protected Cache decorateCache(Cache cache) {
                return super.decorateCache(new FailureTolerantCache(cache));
            }
        };
        manager.setTransactionAware(true);
        return manager;
    }
}

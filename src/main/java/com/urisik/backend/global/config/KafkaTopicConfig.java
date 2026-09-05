package com.urisik.backend.global.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic cacheEvictTopic() {
        return TopicBuilder.name("cache-evict")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic mealPlanConfirmedTopic() {
        return TopicBuilder.name("meal-plan-confirmed")
                .partitions(3)
                .replicas(1)
                .build();
    }
}

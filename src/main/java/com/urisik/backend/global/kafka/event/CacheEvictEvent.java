package com.urisik.backend.global.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CacheEvictEvent {

    private List<String> cacheNames;
    private String key;

    public static CacheEvictEvent allEntries(List<String> cacheNames) {
        return new CacheEvictEvent(cacheNames, null);
    }

    public static CacheEvictEvent withKey(List<String> cacheNames, String key) {
        return new CacheEvictEvent(cacheNames, key);
    }
}

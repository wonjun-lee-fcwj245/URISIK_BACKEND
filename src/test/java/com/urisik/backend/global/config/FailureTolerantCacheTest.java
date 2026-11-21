package com.urisik.backend.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailureTolerantCacheTest {

    /** 모든 연산이 Redis 장애처럼 예외를 던지는 캐시 */
    private static class DownCache extends ConcurrentMapCache {
        DownCache() { super("down"); }
        private RuntimeException down() { return new IllegalStateException("Unable to connect to Redis"); }
        @Override public ValueWrapper get(Object key) { throw down(); }
        @Override public <T> T get(Object key, Class<T> type) { throw down(); }
        @Override public <T> T get(Object key, java.util.concurrent.Callable<T> loader) { throw down(); }
        @Override public void put(Object key, Object value) { throw down(); }
        @Override public ValueWrapper putIfAbsent(Object key, Object value) { throw down(); }
        @Override public void evict(Object key) { throw down(); }
        @Override public boolean evictIfPresent(Object key) { throw down(); }
        @Override public void clear() { throw down(); }
        @Override public boolean invalidate() { throw down(); }
    }

    private final Cache tolerant = new FailureTolerantCache(new DownCache());

    @Test
    @DisplayName("Redis 장애 중에도 조회는 미적중(null), 저장·삭제는 조용히 무시한다")
    void swallowsFailures() {
        assertThat(tolerant.get("k")).isNull();
        assertThat(tolerant.get("k", String.class)).isNull();
        assertThatCode(() -> tolerant.put("k", "v")).doesNotThrowAnyException();
        assertThatCode(() -> tolerant.evict("k")).doesNotThrowAnyException();
        assertThatCode(tolerant::clear).doesNotThrowAnyException();
        assertThat(tolerant.putIfAbsent("k", "v")).isNull();
    }

    @Test
    @DisplayName("값 로더가 있는 조회는 캐시가 죽어 있으면 원본 로더를 직접 호출한다")
    void fallsBackToLoader() {
        AtomicInteger calls = new AtomicInteger();
        String v = tolerant.get("k", () -> { calls.incrementAndGet(); return "from-db"; });
        assertThat(v).isEqualTo("from-db");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("원본 조회 자체가 실패하면 그 예외는 삼키지 않는다")
    void loaderFailureIsNotSwallowed() {
        assertThatThrownBy(() -> tolerant.get("k", () -> { throw new IllegalArgumentException("db error"); }))
                .isInstanceOf(Cache.ValueRetrievalException.class);
    }

    @Test
    @DisplayName("정상 캐시는 그대로 동작한다")
    void healthyCacheWorks() {
        Cache healthy = new FailureTolerantCache(new ConcurrentMapCache("ok"));
        healthy.put("k", "v");
        assertThat(healthy.get("k", String.class)).isEqualTo("v");
        healthy.evict("k");
        assertThat(healthy.get("k")).isNull();
    }
}

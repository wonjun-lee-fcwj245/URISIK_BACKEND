package com.urisik.backend.global.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 캐시(Redis) 오류가 요청 실패나 정지로 번지지 않게 하는 캐시 래퍼.
 * 조회·저장·삭제가 실패하면 예외를 삼키고 캐시 미적중으로 처리해 원본(DB)으로 우회한다.
 *
 * CacheErrorHandler만으로는 부족하다. 캐시 매니저가 transactionAware라서 put/evict가 트랜잭션 커밋 뒤에 실행되는데,
 * 그때 던져진 예외는 CacheErrorHandler를 거치지 않고 커밋 단계에서 요청으로 올라가 500이 된다.
 * 그래서 TransactionAwareCacheDecorator 안쪽(실제 RedisCache를 직접 감싸는 위치)에 둔다.
 * 경고 로그는 장애 중 폭주하지 않게 5초에 한 번만 남긴다.
 */
public final class FailureTolerantCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(FailureTolerantCache.class);
    private static final long LOG_INTERVAL_MS = 5_000;
    private static final AtomicLong LAST_LOGGED_AT = new AtomicLong(0);

    private final Cache delegate;

    public FailureTolerantCache(Cache delegate) {
        this.delegate = delegate;
    }

    private void warn(String op, Object key, RuntimeException e) {
        long now = System.currentTimeMillis();
        long last = LAST_LOGGED_AT.get();
        if (now - last >= LOG_INTERVAL_MS && LAST_LOGGED_AT.compareAndSet(last, now)) {
            log.warn("캐시 오류 무시하고 원본으로 우회: op={}, cache={}, key={}, cause={}", op, delegate.getName(), key, e.toString());
        }
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public Object getNativeCache() {
        return delegate.getNativeCache();
    }

    @Override
    public ValueWrapper get(Object key) {
        try {
            return delegate.get(key);
        } catch (RuntimeException e) {
            warn("get", key, e);
            return null;
        }
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        try {
            return delegate.get(key, type);
        } catch (RuntimeException e) {
            warn("get", key, e);
            return null;
        }
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        try {
            return delegate.get(key, valueLoader);
        } catch (ValueRetrievalException e) {
            throw e;   // 값을 만드는 쪽(원본 조회)의 예외는 그대로 전달한다
        } catch (RuntimeException e) {
            warn("get", key, e);
            try {
                return valueLoader.call();
            } catch (Exception inner) {
                throw new ValueRetrievalException(key, valueLoader, inner);
            }
        }
    }

    @Override
    public void put(Object key, Object value) {
        try {
            delegate.put(key, value);
        } catch (RuntimeException e) {
            warn("put", key, e);
        }
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        try {
            return delegate.putIfAbsent(key, value);
        } catch (RuntimeException e) {
            warn("putIfAbsent", key, e);
            return null;
        }
    }

    @Override
    public void evict(Object key) {
        try {
            delegate.evict(key);
        } catch (RuntimeException e) {
            warn("evict", key, e);
        }
    }

    @Override
    public boolean evictIfPresent(Object key) {
        try {
            return delegate.evictIfPresent(key);
        } catch (RuntimeException e) {
            warn("evictIfPresent", key, e);
            return false;
        }
    }

    @Override
    public void clear() {
        try {
            delegate.clear();
        } catch (RuntimeException e) {
            warn("clear", null, e);
        }
    }

    @Override
    public boolean invalidate() {
        try {
            return delegate.invalidate();
        } catch (RuntimeException e) {
            warn("invalidate", null, e);
            return false;
        }
    }
}

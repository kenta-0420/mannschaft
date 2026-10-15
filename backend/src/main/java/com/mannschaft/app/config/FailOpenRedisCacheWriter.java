package com.mannschaft.app.config;

import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** transaction-aware のコミット後書き込みを fail-open にする Redis writer。 */
final class FailOpenRedisCacheWriter implements RedisCacheWriter {

    private final RedisCacheWriter delegate;
    private final CacheErrorHandler errorHandler;

    FailOpenRedisCacheWriter(RedisCacheWriter delegate, CacheErrorHandler errorHandler) {
        this.delegate = delegate;
        this.errorHandler = errorHandler;
    }

    @Override
    public byte[] get(String name, byte[] key) {
        return delegate.get(name, key);
    }

    @Override
    public CompletableFuture<byte[]> retrieve(String name, byte[] key, Duration ttl) {
        return delegate.retrieve(name, key, ttl);
    }

    @Override
    public boolean supportsAsyncRetrieve() {
        return delegate.supportsAsyncRetrieve();
    }

    @Override
    public void put(String name, byte[] key, byte[] value, Duration ttl) {
        try {
            delegate.put(name, key, value, ttl);
        } catch (RuntimeException ex) {
            errorHandler.handleCachePutError(ex, new ConcurrentMapCache(name), key, value);
        }
    }

    @Override
    public CompletableFuture<Void> store(
            String name, byte[] key, byte[] value, Duration ttl) {
        try {
            return delegate.store(name, key, value, ttl).exceptionally(ex -> {
                RuntimeException runtimeException = ex instanceof RuntimeException runtime
                        ? runtime : new IllegalStateException(ex);
                errorHandler.handleCachePutError(
                        runtimeException, new ConcurrentMapCache(name), key, value);
                return null;
            });
        } catch (RuntimeException ex) {
            errorHandler.handleCachePutError(ex, new ConcurrentMapCache(name), key, value);
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public byte[] putIfAbsent(String name, byte[] key, byte[] value, Duration ttl) {
        return delegate.putIfAbsent(name, key, value, ttl);
    }

    @Override
    public void remove(String name, byte[] key) {
        try {
            delegate.remove(name, key);
        } catch (RuntimeException ex) {
            errorHandler.handleCacheEvictError(ex, new ConcurrentMapCache(name), key);
        }
    }

    @Override
    public void clean(String name, byte[] pattern) {
        try {
            delegate.clean(name, pattern);
        } catch (RuntimeException ex) {
            errorHandler.handleCacheClearError(ex, new ConcurrentMapCache(name));
        }
    }

    @Override
    public void clearStatistics(String name) {
        delegate.clearStatistics(name);
    }

    @Override
    public RedisCacheWriter withStatisticsCollector(CacheStatisticsCollector collector) {
        return new FailOpenRedisCacheWriter(delegate.withStatisticsCollector(collector), errorHandler);
    }

    @Override
    public CacheStatistics getCacheStatistics(String cacheName) {
        return delegate.getCacheStatistics(cacheName);
    }
}

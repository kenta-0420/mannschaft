package com.mannschaft.app.config;

import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** コミット後に遅延実行されるキャッシュ書き込みを fail-open にする。 */
final class FailOpenWriteCache implements Cache {

    private final Cache delegate;
    private final CacheErrorHandler errorHandler;

    FailOpenWriteCache(Cache delegate, CacheErrorHandler errorHandler) {
        this.delegate = delegate;
        this.errorHandler = errorHandler;
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
        return delegate.get(key);
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        return delegate.get(key, type);
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        return delegate.get(key, valueLoader);
    }

    @Override
    public CompletableFuture<?> retrieve(Object key) {
        return delegate.retrieve(key);
    }

    @Override
    public <T> CompletableFuture<T> retrieve(
            Object key, Supplier<CompletableFuture<T>> valueLoader) {
        return delegate.retrieve(key, valueLoader);
    }

    @Override
    public void put(Object key, Object value) {
        try {
            delegate.put(key, value);
        } catch (RuntimeException ex) {
            errorHandler.handleCachePutError(ex, delegate, key, value);
        }
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        try {
            return delegate.putIfAbsent(key, value);
        } catch (RuntimeException ex) {
            errorHandler.handleCachePutError(ex, delegate, key, value);
            return null;
        }
    }

    @Override
    public void evict(Object key) {
        try {
            delegate.evict(key);
        } catch (RuntimeException ex) {
            errorHandler.handleCacheEvictError(ex, delegate, key);
        }
    }

    @Override
    public boolean evictIfPresent(Object key) {
        try {
            return delegate.evictIfPresent(key);
        } catch (RuntimeException ex) {
            errorHandler.handleCacheEvictError(ex, delegate, key);
            return false;
        }
    }

    @Override
    public void clear() {
        try {
            delegate.clear();
        } catch (RuntimeException ex) {
            errorHandler.handleCacheClearError(ex, delegate);
        }
    }

    @Override
    public boolean invalidate() {
        try {
            return delegate.invalidate();
        } catch (RuntimeException ex) {
            errorHandler.handleCacheClearError(ex, delegate);
            return false;
        }
    }
}

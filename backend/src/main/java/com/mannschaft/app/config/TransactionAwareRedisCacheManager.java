package com.mannschaft.app.config;

import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;

import java.util.LinkedHashMap;
import java.util.Map;

/** transaction-aware 装飾後もキャッシュ設定を安全に参照できる RedisCacheManager。 */
final class TransactionAwareRedisCacheManager extends RedisCacheManager {

    private final CacheErrorHandler errorHandler;

    TransactionAwareRedisCacheManager(
            RedisCacheWriter cacheWriter,
            RedisCacheConfiguration defaultCacheConfiguration,
            Map<String, RedisCacheConfiguration> initialCacheConfigurations,
            CacheErrorHandler errorHandler) {
        super(cacheWriter, defaultCacheConfiguration, true, initialCacheConfigurations);
        this.errorHandler = errorHandler;
        setTransactionAware(true);
    }

    @Override
    protected Cache decorateCache(Cache cache) {
        return super.decorateCache(new FailOpenWriteCache(cache, errorHandler));
    }

    /**
     * Spring Data Redis 3.5.10 の実装は装飾済み Cache を RedisCache へ直接 cast するため、
     * transaction-aware 時に ClassCastException となる。保有設定から同じ結果を構築する。
     */
    @Override
    public Map<String, RedisCacheConfiguration> getCacheConfigurations() {
        Map<String, RedisCacheConfiguration> configurations =
                new LinkedHashMap<>(getInitialCacheConfiguration());
        getCacheNames().forEach(name -> configurations.putIfAbsent(
                name, getDefaultCacheConfiguration()));
        return Map.copyOf(configurations);
    }
}

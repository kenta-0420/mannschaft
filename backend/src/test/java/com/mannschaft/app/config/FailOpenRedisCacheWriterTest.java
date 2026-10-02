package com.mannschaft.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.data.redis.cache.RedisCacheWriter;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.argThat;

class FailOpenRedisCacheWriterTest {

    private final RedisCacheWriter delegate = mock(RedisCacheWriter.class);
    private final CacheErrorHandler errorHandler = mock(CacheErrorHandler.class);
    private final FailOpenRedisCacheWriter writer =
            new FailOpenRedisCacheWriter(delegate, errorHandler);

    @Test
    void コミット後put失敗はerrorHandlerへ渡して握り潰す() {
        byte[] key = {1};
        byte[] value = {2};
        RuntimeException failure = new IllegalStateException("valkey unavailable");
        doThrow(failure).when(delegate).put("role-permissions", key, value, Duration.ofMinutes(5));

        writer.put("role-permissions", key, value, Duration.ofMinutes(5));

        verify(errorHandler).handleCachePutError(
                org.mockito.ArgumentMatchers.eq(failure),
                argThat(cache -> "role-permissions".equals(cache.getName())),
                org.mockito.ArgumentMatchers.eq(key), org.mockito.ArgumentMatchers.eq(value));
    }

    @Test
    void コミット後evict失敗はerrorHandlerへ渡して握り潰す() {
        byte[] key = {1};
        RuntimeException failure = new IllegalStateException("valkey unavailable");
        doThrow(failure).when(delegate).remove("role-permissions", key);

        writer.remove("role-permissions", key);

        verify(errorHandler).handleCacheEvictError(
                org.mockito.ArgumentMatchers.eq(failure),
                argThat(cache -> "role-permissions".equals(cache.getName())),
                org.mockito.ArgumentMatchers.eq(key));
    }

    @Test
    void 読み取り失敗はCacheInterceptorで処理するため伝播する() {
        byte[] key = {1};
        RuntimeException failure = new IllegalStateException("valkey unavailable");
        doThrow(failure).when(delegate).get("role-permissions", key);

        assertThatThrownBy(() -> writer.get("role-permissions", key)).isSameAs(failure);
    }
}

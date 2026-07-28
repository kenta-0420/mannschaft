package com.mannschaft.app.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cache.Cache;
import org.springframework.data.redis.connection.RedisConnectionFailureException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LoggingCacheErrorHandler} の単体テスト（Spring コンテキストを起動しない純 UT）。
 *
 * <p>検証の主眼は 2 点:</p>
 * <ol>
 *   <li>4 フック（get / put / evict / clear）すべてが <b>例外を再送出しない</b>（fail-open）</li>
 *   <li>fail-open が {@value LoggingCacheErrorHandler#FAIL_OPEN_METRIC} カウンタで
 *       <b>必ず可視化される</b>（「静かな無効化」にしない）</li>
 * </ol>
 *
 * <p>金型は {@code CacheStatsTest}（Spring 起動を伴わない軽量な構成クラス検証）。</p>
 */
@DisplayName("LoggingCacheErrorHandler（キャッシュ fail-open）")
class LoggingCacheErrorHandlerTest {

    private static final String CACHE_NAME = "role-permissions";

    private SimpleMeterRegistry registry;
    private LoggingCacheErrorHandler handler;
    private Cache cache;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        handler = new LoggingCacheErrorHandler(providerOf(registry));
        cache = mock(Cache.class);
        when(cache.getName()).thenReturn(CACHE_NAME);
    }

    /** Spring の実 {@link ObjectProvider} を得るための最小 BeanFactory（自前実装より実物に近い）。 */
    private static ObjectProvider<MeterRegistry> providerOf(MeterRegistry registry) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        if (registry != null) {
            beanFactory.registerSingleton("meterRegistry", registry);
        }
        return beanFactory.getBeanProvider(MeterRegistry.class);
    }

    private double failOpenCount(String operation) {
        return registry.get(LoggingCacheErrorHandler.FAIL_OPEN_METRIC)
                .tag("operation", operation)
                .tag("cache", CACHE_NAME)
                .counter()
                .count();
    }

    @Test
    @DisplayName("handleCacheGetError_Valkey障害_例外を再送出せずカウンタを加算する")
    void handleCacheGetError_例外を握り潰しカウンタ加算() {
        assertThatCode(() -> handler.handleCacheGetError(
                new RedisConnectionFailureException("connection refused"), cache, "key-1"))
                .doesNotThrowAnyException();

        assertThat(failOpenCount("get")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("handleCachePutError_Valkey障害_例外を再送出せずカウンタを加算する")
    void handleCachePutError_例外を握り潰しカウンタ加算() {
        assertThatCode(() -> handler.handleCachePutError(
                new RedisConnectionFailureException("connection refused"), cache, "key-1", "value"))
                .doesNotThrowAnyException();

        assertThat(failOpenCount("put")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("handleCacheEvictError_Valkey障害_例外を再送出せずカウンタを加算する（権限変更を巻き添えにしない）")
    void handleCacheEvictError_例外を握り潰しカウンタ加算() {
        assertThatCode(() -> handler.handleCacheEvictError(
                new RedisConnectionFailureException("connection refused"), cache, "key-1"))
                .doesNotThrowAnyException();

        assertThat(failOpenCount("evict")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("handleCacheClearError_Valkey障害_例外を再送出せずカウンタを加算する")
    void handleCacheClearError_例外を握り潰しカウンタ加算() {
        assertThatCode(() -> handler.handleCacheClearError(
                new RedisConnectionFailureException("connection refused"), cache))
                .doesNotThrowAnyException();

        assertThat(failOpenCount("clear")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("同一操作を複数回_カウンタは加算され続ける（発生回数が観測できる）")
    void 複数回のfailopen_カウンタが積み上がる() {
        RedisConnectionFailureException ex = new RedisConnectionFailureException("down");
        handler.handleCacheEvictError(ex, cache, "k1");
        handler.handleCacheEvictError(ex, cache, "k2");
        handler.handleCacheEvictError(ex, cache, "k3");

        assertThat(failOpenCount("evict")).isEqualTo(3.0);
    }

    @Test
    @DisplayName("操作種別ごとにタグが分かれる（get の加算が evict に混ざらない）")
    void 操作種別ごとにカウンタが分離される() {
        RedisConnectionFailureException ex = new RedisConnectionFailureException("down");
        handler.handleCacheGetError(ex, cache, "k1");
        handler.handleCacheEvictError(ex, cache, "k1");
        handler.handleCacheEvictError(ex, cache, "k2");

        assertThat(failOpenCount("get")).isEqualTo(1.0);
        assertThat(failOpenCount("evict")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("MeterRegistry不在_カウンタ加算をスキップしても例外を投げない（最小テストコンテキスト対策）")
    void MeterRegistry不在でも例外を投げない() {
        LoggingCacheErrorHandler noMetrics = new LoggingCacheErrorHandler(providerOf(null));
        RedisConnectionFailureException ex = new RedisConnectionFailureException("down");

        assertThatCode(() -> {
            noMetrics.handleCacheGetError(ex, cache, "k");
            noMetrics.handleCachePutError(ex, cache, "k", "v");
            noMetrics.handleCacheEvictError(ex, cache, "k");
            noMetrics.handleCacheClearError(ex, cache);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Cacheがnull_ログ整形でNPEを起こさない（ハンドラ自身が落ちては本末転倒）")
    void Cacheがnullでも例外を投げない() {
        RedisConnectionFailureException ex = new RedisConnectionFailureException("down");

        assertThatCode(() -> {
            handler.handleCacheGetError(ex, null, "k");
            handler.handleCachePutError(ex, null, "k", "v");
            handler.handleCacheEvictError(ex, null, "k");
            handler.handleCacheClearError(ex, null);
        }).doesNotThrowAnyException();

        assertThat(registry.get(LoggingCacheErrorHandler.FAIL_OPEN_METRIC)
                .tag("cache", "unknown")
                .counters())
                .hasSize(4);
    }

    @Test
    @DisplayName("例外メッセージがnull_ログ整形でNPEを起こさない")
    void 例外メッセージnullでも例外を投げない() {
        assertThatCode(() -> handler.handleCacheEvictError(
                new RedisConnectionFailureException((String) null), cache, "k"))
                .doesNotThrowAnyException();
    }
}

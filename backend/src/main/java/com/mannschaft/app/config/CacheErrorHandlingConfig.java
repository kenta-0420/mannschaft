package com.mannschaft.app.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * キャッシュ操作の例外ハンドラ（fail-open）を Spring のキャッシュ AOP に配線する構成クラス。
 *
 * <h3>なぜ {@link CachingConfigurer} なのか（{@code @Bean} 単体では効かない）</h3>
 *
 * <p>Spring Framework の {@code AbstractCachingConfiguration}（{@code @EnableCaching} の実体）は
 * {@link CacheErrorHandler} を <b>{@link CachingConfigurer} からしか受け取らない</b>。
 * 素の {@code @Bean CacheErrorHandler} をコンテキストに置いても {@code CacheInterceptor} には
 * 配線されず（Spring Boot 3.5 の {@code CacheAutoConfiguration} にも単体 Bean を拾う仕組みは無い）、
 * 既定の {@code SimpleCacheErrorHandler}（例外を再送出）が使われ続けてしまう。
 * よって本クラスで {@link CachingConfigurer} を実装する。</p>
 *
 * <p>コンテキスト内の {@link CachingConfigurer} は <b>1 個まで</b>という制約があるため
 * （複数あると {@code AbstractCachingConfiguration} が起動時に例外）、
 * 本クラスをキャッシュ例外ハンドリングの唯一の入口とする。
 * {@code @EnableCaching} 自体は {@link RedisConfig} 側に置いたまま変更していない。</p>
 *
 * <h3>プロファイル条件を付けない理由</h3>
 *
 * <p>ハンドラは {@code CacheInterceptor}（AOP 層）が適用するため <b>キャッシュ媒体に依存しない</b>。
 * 本番の {@code RedisCacheManager} でも、test プロファイルの
 * {@code ConcurrentMapCacheManager}（{@link RedisConfig#testInMemoryCacheManager()}）でも
 * 同一に効く。したがって {@code @Profile} は付けない。</p>
 *
 * <p>握り潰す判断の根拠（マスター御裁可）・安全性の論拠・可視化の担保は
 * {@link LoggingCacheErrorHandler} の Javadoc を参照。</p>
 */
@Configuration
public class CacheErrorHandlingConfig implements CachingConfigurer {

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /**
     * @param meterRegistryProvider fail-open カウンタの記録先。{@link ObjectProvider} で遅延解決するのは
     *                              MeterRegistry を持たない最小テストコンテキストでも本構成が壊れないようにするため
     *                              （{@code ValkeyRateLimiter} と同作法）
     */
    public CacheErrorHandlingConfig(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /**
     * キャッシュ get/put/evict/clear の例外を握り潰して業務処理を続行させるハンドラ。
     *
     * <p>{@code @Bean} も併記しているのはコンテキストから型で参照できるようにするため
     * （番人テストがハンドラの実装型を検証する）。配線自体は
     * {@link CachingConfigurer#errorHandler()} のオーバーライドによって行われる。</p>
     */
    @Bean
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler(meterRegistryProvider);
    }
}

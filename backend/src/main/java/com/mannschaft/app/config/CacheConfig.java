package com.mannschaft.app.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Caffeine ベースのアプリケーションキャッシュ設定。
 *
 * <p>F10.5 Phase 10-α (§5.1.4) で導入。Caffeine の {@code recordStats()} を有効化することで
 * キャッシュヒット率を Micrometer から観測可能にする。具体的なキャッシュ名・TTL は
 * 利用側 ({@code @Cacheable("name")} または個別 Bean) で上書きできるよう、
 * 共通の Caffeine ビルダーをデフォルトとして提供する。</p>
 *
 * <p>主たる業務キャッシュは {@link RedisConfig} の {@code RedisCacheManager} が担う。
 * 本クラスはあくまで Caffeine ベースのプロセス内キャッシュ（必要に応じて利用側で
 * {@code Caffeine.from(caffeineConfig)} のように取得して個別 Cache を組み立てる）の
 * 共通ベース設定として提供する。</p>
 *
 * <p>Spring Boot 3.x の Actuator MeterBinder 自動構成は、CacheManager に登録された
 * Caffeine ベース Cache に対して {@code cache.gets{result=hit/miss}} メトリクスを
 * 自動登録する。recordStats() を有効化しておかないとこのメトリクスは常に 0 のままになる。</p>
 */
@Configuration
public class CacheConfig {

    /**
     * 共通 Caffeine ビルダー設定。
     *
     * <ul>
     *   <li>{@code recordStats()} 有効化 — Micrometer へのヒット率公開に必須</li>
     *   <li>{@code expireAfterWrite(10 minutes)} — 一般的な業務データ向けの控えめなデフォルト</li>
     * </ul>
     *
     * <p>個別キャッシュで TTL を変えたい場合は、利用側で {@code Caffeine.newBuilder()} を呼び直すか
     * 本 Bean を {@code @Autowired} した上で再度設定を上書きする。</p>
     */
    @Bean
    public Caffeine<Object, Object> caffeineConfig() {
        return Caffeine.newBuilder()
                .recordStats()
                .expireAfterWrite(Duration.ofMinutes(10));
    }

    /**
     * {@link io.micrometer.core.annotation.Timed @Timed} アノテーションを有効化する AOP Aspect。
     *
     * <p>Spring Boot Actuator は AOP starter がクラスパスにあれば自動構成する場合があるが、
     * F10.5 Phase 10-α では Repository / Service の重要メソッドに {@code @Timed} を確実に
     * 適用したいため明示的に Bean 登録する。Bean が二重定義されることはない
     * （Spring Boot の auto-config 側は {@code @ConditionalOnMissingBean(TimedAspect.class)}）。</p>
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry meterRegistry) {
        return new TimedAspect(meterRegistry);
    }
}

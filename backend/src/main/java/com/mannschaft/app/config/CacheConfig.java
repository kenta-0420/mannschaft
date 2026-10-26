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
     *
     * <h3>利用ガイド（F10.5 Phase 10-α 検分指摘 ③ 対応）</h3>
     *
     * <p>本 Bean が登録されているコンテキストで {@code spring-boot-starter-cache} の
     * 自動構成 ({@code CacheAutoConfiguration} → {@code CaffeineCacheConfiguration}) が起動すると、
     * Spring が {@code CacheManager} (CaffeineCacheManager) を自動構築する際に
     * 本 Bean ({@code Caffeine<Object, Object>}) を Default Builder として採用する。
     * したがって個別の利用クラスは下記のように {@code @Cacheable} を貼るだけで、
     * 本 Bean の {@code recordStats()} と {@code expireAfterWrite(10 minutes)} が
     * そのまま適用された Caffeine Cache を取得できる:</p>
     *
     * <pre>{@code
     * @Service
     * public class MyService {
     *     @Cacheable("myCacheName")
     *     public Foo lookup(String key) { ... }
     * }
     * }</pre>
     *
     * <p>利用側で個別の TTL や maximumSize を指定したい場合は、
     * {@code @Cacheable} とは別に {@code CaffeineCacheManager} を {@code @Bean} で
     * 自前定義し、{@code .setCaffeine(Caffeine.newBuilder().recordStats()....)} のように
     * 本 Bean をベースに更にビルダーチェインを足す形で上書きする。</p>
     *
     * <p>2026-05-07 時点では業務コードに {@code @Cacheable} は未配置である。
     * 設計書 F10.5 §5.1.4 の方針に従い、Phase 10-β で Redis (Valkey) キャッシュ計測の追加と
     * 合わせて、業務側で実際に {@code @Cacheable} を貼る対象（Todo/Schedule など読み取り頻度の
     * 高いマスタ系クエリ）を確定し、具体的な実装ガイドを別途追補する予定。</p>
     *
     * @see io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
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

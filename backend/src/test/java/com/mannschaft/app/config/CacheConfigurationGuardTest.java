package com.mannschaft.app.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * キャッシュ構成の<b>番人テスト</b>（回帰防止）。
 *
 * <p>守る不変条件は 2 つ:</p>
 * <ol>
 *   <li><b>fail-open ハンドラが常に配線されていること</b> —
 *       {@link CacheErrorHandlingConfig} が {@link CachingConfigurer} を実装し
 *       {@link LoggingCacheErrorHandler} を返す。素の {@code @Bean CacheErrorHandler} に
 *       書き換えられると Spring は拾わず既定の {@code SimpleCacheErrorHandler}（例外再送出）に
 *       戻ってしまうため、実装インターフェースごと固定する</li>
 *   <li><b>TTL 無しのキャッシュが混入しないこと</b> — fail-open の安全性は
 *       「evict を取りこぼしても TTL で自然収束する」ことに依存している。
 *       TTL 無し（{@code Duration.ZERO} = 無期限）のキャッシュが 1 つでも増えると
 *       「古い認可情報が永久に残る」危険が生まれるため、機械的に拒否する</li>
 * </ol>
 *
 * <p>実 Redis には一切接続しない（{@link RedisConnectionFactory} は Mockito モック。
 * {@link RedisCacheManager} の構築とキャッシュ構成の読み出しに接続は不要）。</p>
 */
@DisplayName("キャッシュ構成 番人（fail-open 配線・TTL）")
class CacheConfigurationGuardTest {

    /** 既定 TTL（{@link RedisConfig#redisCacheConfiguration()}）。これを超える TTL は認めない。 */
    private static final Duration MAX_ALLOWED_TTL = Duration.ofMinutes(30);

    /** 認可判断に直接効くキャッシュ。反映遅延を最小化するため 5 分以内を強制する。 */
    private static final Duration MAX_AUTHZ_TTL = Duration.ofMinutes(5);

    private static Duration ttlOf(RedisCacheConfiguration configuration) {
        // getTtl() は 3.2 で deprecated。TtlFunction 経由で実効 TTL を取り出す。
        return configuration.getTtlFunction().getTimeToLive(Object.class, null);
    }

    private static Map<String, RedisCacheConfiguration> cacheConfigurations() {
        RedisCacheManager manager = new RedisConfig().cacheManager(mock(RedisConnectionFactory.class));
        // 初期キャッシュ（withCacheConfiguration で登録したもの）を生成させる
        manager.afterPropertiesSet();
        return manager.getCacheConfigurations();
    }

    @Nested
    @DisplayName("fail-open ハンドラの配線")
    class ErrorHandlerWiring {

        @Test
        @DisplayName("CacheErrorHandlingConfig は CachingConfigurer を実装している（@Bean 単体では Spring が拾わない）")
        void CachingConfigurerを実装している() {
            assertThat(CachingConfigurer.class)
                    .as("CacheErrorHandler は CachingConfigurer 経由でしか CacheInterceptor に配線されない")
                    .isAssignableFrom(CacheErrorHandlingConfig.class);
        }

        @Test
        @DisplayName("errorHandler() は LoggingCacheErrorHandler を返す（既定の SimpleCacheErrorHandler に戻っていない）")
        void errorHandlerはLoggingCacheErrorHandlerを返す() {
            CacheErrorHandler handler = new CacheErrorHandlingConfig(
                    new DefaultListableBeanFactory().getBeanProvider(MeterRegistry.class))
                    .errorHandler();

            assertThat(handler).isInstanceOf(LoggingCacheErrorHandler.class);
        }

        @Test
        @DisplayName("fail-open メトリクス名が変わっていない（ダッシュボード/アラートの契約）")
        void メトリクス名が固定されている() {
            assertThat(LoggingCacheErrorHandler.FAIL_OPEN_METRIC).isEqualTo("mannschaft.cache.failopen");
        }
    }

    @Nested
    @DisplayName("TTL の番人")
    class TtlGuard {

        @Test
        @DisplayName("既定キャッシュ設定に有限の TTL が設定されている（無期限キャッシュを作らない）")
        void 既定TTLが有限である() {
            Duration defaultTtl = ttlOf(new RedisConfig().redisCacheConfiguration());

            // AssertJ の AbstractDurationAssert に isNotZero() は存在しない。
            // 「TTL > 0（＝ Duration.ZERO の無期限ではない）」は isPositive() で表現する。
            assertThat(defaultTtl)
                    .as("既定 TTL が Duration.ZERO（無期限）だと、個別指定の無いキャッシュが永久に腐る")
                    .isPositive()
                    .isEqualTo(MAX_ALLOWED_TTL);
        }

        @Test
        @DisplayName("個別設定された全キャッシュに有限かつ既定30分以下の TTL がある")
        void 全キャッシュのTTLが有限かつ30分以下() {
            Map<String, RedisCacheConfiguration> configurations = cacheConfigurations();

            assertThat(configurations).isNotEmpty();
            configurations.forEach((cacheName, configuration) -> {
                Duration ttl = ttlOf(configuration);
                assertThat(ttl)
                        .as("キャッシュ '%s' の TTL が無期限（Duration.ZERO）になっている。"
                                + "fail-open は TTL による自然収束を前提にしているため無期限は禁止", cacheName)
                        .isPositive();
                assertThat(ttl)
                        .as("キャッシュ '%s' の TTL が既定 30 分を超えている", cacheName)
                        .isLessThanOrEqualTo(MAX_ALLOWED_TTL);
            });
        }

        @Test
        @DisplayName("認可に効くキャッシュ（role-permissions / visibilityTemplate）の TTL は5分以内")
        void 認可系キャッシュのTTLは5分以内() {
            Map<String, RedisCacheConfiguration> configurations = cacheConfigurations();

            assertThat(ttlOf(configurations.get("role-permissions")))
                    .as("ロール権限キャッシュ。降格の反映遅延を最小化する")
                    .isLessThanOrEqualTo(MAX_AUTHZ_TTL);
            assertThat(ttlOf(configurations.get("visibilityTemplate")))
                    .as("可視性テンプレートキャッシュ。閲覧認可の中核であり既定30分は長すぎる")
                    .isLessThanOrEqualTo(MAX_AUTHZ_TTL);
        }

        @Test
        @DisplayName("adNgWords の TTL は5分以内（evict が存在せず TTL が唯一の収束手段のため）")
        void 広告NG辞書キャッシュのTTLは5分以内() {
            Map<String, RedisCacheConfiguration> configurations = cacheConfigurations();

            // issue #2544: ad_ng_words にはアプリ側の書き込み経路が 1 つも無く
            // （更新は Flyway マイグレーション＝デプロイ時のみ）、@CacheEvict を貼るべき
            // ミューテーションメソッドが存在しない。よって反映の収束手段は TTL だけである。
            // 同 issue で自己呼び出しを是正して本キャッシュが初めて実際に効くようになったため、
            // 既定 30 分に戻すと「NG ワードを追加したのに最大 30 分ブロックされない」という
            // 是正前には無かった反映遅延を作り込むことになる。ここで機械的に固定する。
            assertThat(configurations)
                    .as("adNgWords に個別 TTL が登録されていない（既定30分に落ちている）")
                    .containsKey("adNgWords");
            assertThat(ttlOf(configurations.get("adNgWords")))
                    .as("広告 NG 辞書キャッシュ。evict 経路が無いため TTL でしか収束しない")
                    .isLessThanOrEqualTo(MAX_AUTHZ_TTL);
        }
    }
}

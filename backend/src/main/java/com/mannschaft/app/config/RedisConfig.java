package com.mannschaft.app.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Valkey(Redis) キャッシュ設定。
 *
 * <p>デフォルト TTL 30分、JSON シリアライゼーション、Java 8 Date/Time API 対応。
 * キー命名規則: {@code mannschaft:cache:{キー名}}</p>
 *
 * <p>キャッシュ基盤（Valkey）障害時の fail-open は {@link CacheErrorHandlingConfig}
 * （{@link CachingConfigurer#errorHandler()} 経由で {@link LoggingCacheErrorHandler} を登録）が担う。
 * Spring 既定の {@code SimpleCacheErrorHandler} は例外を再送出するため、
 * これが無いと Valkey 断のときに {@code @CacheEvict} を持つミューテーション
 * （権限変更等）が巻き添えで 500 になる。</p>
 */
@Configuration
@EnableCaching
public class RedisConfig {

    /**
     * Redis キャッシュのデフォルト設定。
     *
     * <ul>
     *   <li>TTL: 30分</li>
     *   <li>キープレフィックス: {@code mannschaft:cache:}</li>
     *   <li>null 値はキャッシュしない</li>
     *   <li>値のシリアライズ: GenericJackson2JsonRedisSerializer（JavaTimeModule 対応）</li>
     * </ul>
     */
    @Bean
    public RedisCacheConfiguration redisCacheConfiguration() {
        // JavaTimeModule と ParameterNamesModule を登録した ObjectMapper で日時型・コンストラクタ引数名を正しく処理する
        // ParameterNamesModule がないと @RequiredArgsConstructor の Lombok クラス（ApiResponse 等）を
        // デシリアライズできず、キャッシュ読み込み時に 500 エラーが発生する（-parameters フラグ前提）
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.registerModule(new ParameterNamesModule(JsonCreator.Mode.DEFAULT));
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // デシリアライズ時に型情報が必要（LinkedHashMap キャストエラー防止）
        objectMapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.mannschaft")
                        .allowIfSubType("java.util")
                        .allowIfSubType("java.time")
                        // java.lang.Long / Integer / Boolean / String 等のボックス型が
                        // DefaultTyping.EVERYTHING により ["java.lang.Long", 123] 形式でシリアライズされるため許可。
                        // 許可しない場合 BasicPolymorphicTypeValidator が拒否し InvalidTypeIdException が発生する。
                        .allowIfSubType("java.lang")
                        // F15.4 Phase 3: team-search キャッシュで Page<TeamEntity> をシリアライズするため
                        // org.springframework.data.domain.PageImpl 等を許可
                        .allowIfSubType("org.springframework.data")
                        .build(),
                // NON_FINAL はルートオブジェクト（List<T> 等）に型アノテーションを付けないため、
                // GenericJackson2JsonRedisSerializer のデシリアライズ時に
                // "Unexpected token (START_ARRAY)" が発生する。
                // EVERYTHING に変更することでルートレベルを含む全オブジェクトに型情報が付加される。
                // 注意: 変更後は既存の NON_FINAL 形式キャッシュが読めなくなるため、
                // 本 PR 適用時に Valkey のキャッシュをフラッシュすること（redis-cli FLUSHALL）。
                ObjectMapper.DefaultTyping.EVERYTHING
        );

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))
                .computePrefixWith(cacheName -> "mannschaft:cache:" + cacheName + ":")
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(jsonSerializer))
                .disableCachingNullValues();
    }

    /**
     * キャッシュマネージャー。
     *
     * <p>デフォルト TTL は 30分。ケアリンク判定用キャッシュ（careLinks / careCategory）は
     * 変更頻度が高いため 5分 TTL を設定する。F02.2.1 ダッシュボード可視性キャッシュは
     * 設計書 §5 に従い「閲覧者ロール: 60秒」「ウィジェット可視性: 300秒」を設定する。
     * Phase 4-E: コア読み取りキャッシュ（role-permissions: 5分、team-detail / org-detail: 10分）を追加。
     * F15.4 Phase 3: 組織内チーム（店舗）検索キャッシュ（team-search: 60秒）を追加。</p>
     */
    @Bean
    @Profile("!test")
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // デフォルト設定（30分TTL）
        RedisCacheConfiguration defaultConfig = redisCacheConfiguration();

        // ケアリンク判定用（5分TTL）
        RedisCacheConfiguration careLinksConfig = redisCacheConfiguration()
                .entryTtl(Duration.ofMinutes(5));

        // F02.2.1 ダッシュボード閲覧者ロール（60秒TTL）
        RedisCacheConfiguration dashboardViewerRoleConfig = redisCacheConfiguration()
                .entryTtl(Duration.ofSeconds(60));

        // F02.2.1 ダッシュボードウィジェット可視性マップ（300秒TTL）
        RedisCacheConfiguration dashboardWidgetVisibilityConfig = redisCacheConfiguration()
                .entryTtl(Duration.ofSeconds(300));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration("careLinks", careLinksConfig)
                .withCacheConfiguration("careCategory", careLinksConfig)
                .withCacheConfiguration("dashboard:viewer-role", dashboardViewerRoleConfig)
                .withCacheConfiguration("dashboard:widget-visibility", dashboardWidgetVisibilityConfig)
                .withCacheConfiguration("public-stats", redisCacheConfiguration().entryTtl(Duration.ofMinutes(5)))
                // Phase 4-E: コア読み取りキャッシュ
                .withCacheConfiguration("role-permissions",
                        redisCacheConfiguration().entryTtl(Duration.ofMinutes(5)))
                .withCacheConfiguration("team-detail",
                        redisCacheConfiguration().entryTtl(Duration.ofMinutes(10)))
                .withCacheConfiguration("org-detail",
                        redisCacheConfiguration().entryTtl(Duration.ofMinutes(10)))
                // F15.4 Phase 3: 組織内チーム（店舗）検索結果キャッシュ。
                // TTL は短め（60 秒）— チーム更新時の SCAN+DEL を実装しない代わりに
                // 反映遅延を最大 60 秒で許容する（設計書 §6.5）。
                // 権限スコープ（未ログイン / 組織メンバー）はキーに含めるため別キャッシュとなる。
                .withCacheConfiguration("team-search",
                        redisCacheConfiguration().entryTtl(Duration.ofSeconds(60)))
                // F12.5 障害告知バナー: 公開バナーは @CacheEvict で即時無効化されるが、
                // 取りこぼし対策として短め（1分）の TTL を設定する（設計書 F12.5 §5.4）。
                .withCacheConfiguration("active-incidents",
                        redisCacheConfiguration().entryTtl(Duration.ofMinutes(1)))
                // F20.1 課金・エンタイトルメント: 権利判定キャッシュ（60秒TTL）。
                // 契約/付与/取消時は EntitlementCacheEvictor が発行/取消 feature_key 集合を個別 evict する。
                // 既定30分では取消反映が遅すぎるため短TTL。取りこぼしは60秒で自然収束（設計書 02 §8 / 01 §8）。
                .withCacheConfiguration("entitlement:check",
                        redisCacheConfiguration().entryTtl(Duration.ofSeconds(60)))
                // F20.3 ベータ特典: 付与条件（活動実績）評価キャッシュ（10分TTL）。
                // 活動日数・在籍日数は分刻みで変動しないため、entitlement:check（60秒）より長め。
                // enum キーは name() で String 化する（BetaPerkEligibilityService の @Cacheable キー式）。
                .withCacheConfiguration("betaPerk:eligibility",
                        redisCacheConfiguration().entryTtl(Duration.ofMinutes(10)))
                // F00 可視性テンプレート: コンテンツ可視性の判定に使う「閲覧認可の中核」キャッシュ。
                // VisibilityTemplateEvaluator#getTemplateRules の Javadoc は「TTL=5分」と宣言しているが
                // 個別設定が無く既定 30 分に落ちていた（ドリフト是正）。
                // 認可に効くキャッシュは role-permissions と同水準（5分）まで短縮し、
                // evict 取りこぼし時の窓を最小化する。
                .withCacheConfiguration("visibilityTemplate",
                        redisCacheConfiguration().entryTtl(Duration.ofMinutes(5)))
                .build();
    }

    /**
     * テストプロファイル専用のプロセス内キャッシュマネージャー（Redis 非依存）。
     *
     * <p><b>背景（認可根治戦役 AC-1-1d 500 の真因）</b>: 統合テスト用の
     * {@code application-test.yml} は {@code spring.cache.type: none} を指定しているが、
     * 本クラスが {@code @Bean RedisCacheManager cacheManager} を明示定義しているため、
     * Spring Boot の {@code CacheAutoConfiguration}（{@code @ConditionalOnMissingBean(CacheManager.class)}）が
     * バックオフし、{@code type: none} が無効化されて<b>テストでも Redis-backed キャッシュが有効</b>に
     * なっていた。その結果 {@code RoleService.changeRole} 等の {@code @CacheEvict("role-permissions")} が
     * 実 Redis（{@code localhost:6379}）へ接続し、full-shard（shard3）の並行負荷で Redis 接続が
     * 枯渇・失敗すると {@code RedisConnectionFailureException} を送出 → 正常系ミューテーションだけが
     * 非決定的に 500 になっていた（403/BOLA 系はキャッシュ処理へ到達する前に弾かれるため無傷、
     * 単独 shard は Redis に余裕があり緑）。</p>
     *
     * <p><b>是正</b>: 上の {@code RedisCacheManager} を {@code @Profile("!test")} に限定し、
     * テストプロファイルでは本 {@link ConcurrentMapCacheManager}（プロセス内 Map）を CacheManager として
     * 用いる。これにより {@code @Cacheable}/{@code @CacheEvict} のキャッシュ意味論（put/get/evict）は
     * 従来どおり動作しつつ、テストは Redis 接続に一切依存しなくなり、shard 並行負荷でも安定する。
     * キャッシュ名は未登録でも動的生成されるため、アプリ全キャッシュ名（role-permissions 等）が
     * そのまま機能する。</p>
     *
     * <p><b>本番挙動は不変</b>: 本 Bean は {@code test} プロファイルでのみ有効化され、本番／開発では
     * 従来どおり {@link RedisCacheManager}（Valkey）が使われる。テスト以外に一切影響しない。
     * なお「Redis 断で {@code @CacheEvict} が書き込みを巻き込んで失敗する」本番の耐障害性課題は
     * 別バックログとして切り出され、{@link CacheErrorHandlingConfig}
     * （{@link LoggingCacheErrorHandler}）で解決済みである。</p>
     */
    @Bean
    @Profile("test")
    public CacheManager testInMemoryCacheManager() {
        // 名前を渡さない ConcurrentMapCacheManager は要求されたキャッシュ名を動的生成する
        // （アプリで使う全キャッシュ名を列挙せずに済む）。
        return new ConcurrentMapCacheManager();
    }
}

package com.mannschaft.app.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                .build();
    }
}

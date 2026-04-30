package com.mannschaft.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
        // JavaTimeModule を登録した ObjectMapper で日時型を正しくシリアライズ
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // デシリアライズ時に型情報が必要（LinkedHashMap キャストエラー防止）
        objectMapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.mannschaft")
                        .allowIfSubType("java.util")
                        .allowIfSubType("java.time")
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL
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
     * 設計書 §5 に従い「閲覧者ロール: 60秒」「ウィジェット可視性: 300秒」を設定する。</p>
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
                .build();
    }
}

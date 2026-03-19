package com.mannschaft.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
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
}

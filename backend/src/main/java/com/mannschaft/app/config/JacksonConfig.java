package com.mannschaft.app.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.mannschaft.app.config.jackson.LocalDateTimeTimezoneSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;

/**
 * Jackson ObjectMapper のグローバル設定。
 *
 * <p>カスタムシリアライザ {@link LocalDateTimeTimezoneSerializer} を登録することで、
 * すべての {@link LocalDateTime} フィールドを JSON 出力時にユーザーのタイムゾーンへ
 * 変換した ISO-8601 オフセット付き文字列（例: "2026-05-22T09:15:20+09:00"）で返す。</p>
 *
 * <p>Entity / DTO / Service / Repository は一切変更しない。
 * 変更箇所はこの Jackson 設定層のみ。</p>
 *
 * <p>RedisConfig が内部で使用する {@link ObjectMapper} は {@code new ObjectMapper()} で
 * ローカル生成されており Bean として登録されていないため、本 Bean との衝突はない。</p>
 */
@Configuration
public class JacksonConfig {

    /**
     * プライマリ {@link ObjectMapper} Bean。Spring MVC の HTTP メッセージコンバーターが
     * このインスタンスを使用して JSON シリアライズ / デシリアライズを行う。
     *
     * <ul>
     *   <li>{@link JavaTimeModule} — Java 8 Date/Time API のサポート</li>
     *   <li>{@link LocalDateTimeTimezoneSerializer} — LocalDateTime をユーザー TZ 変換して出力</li>
     *   <li>{@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS} 無効 — 数値ではなく文字列で出力</li>
     * </ul>
     *
     * @param builder Spring Boot が自動設定した {@link Jackson2ObjectMapperBuilder}
     * @return カスタマイズ済み ObjectMapper
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        // LocalDateTime → ユーザー TZ 変換シリアライザを登録
        SimpleModule timezoneModule = new SimpleModule("TimezoneModule");
        timezoneModule.addSerializer(LocalDateTime.class, new LocalDateTimeTimezoneSerializer());

        return builder
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .modules(new JavaTimeModule(), timezoneModule, new ParameterNamesModule(JsonCreator.Mode.DEFAULT))
                .build();
    }
}

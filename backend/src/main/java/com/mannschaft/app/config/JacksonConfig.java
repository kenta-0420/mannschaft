package com.mannschaft.app.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.mannschaft.app.config.jackson.LocalDateTimeTimezoneDeserializer;
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
 * <p>入力側は対になる {@link LocalDateTimeTimezoneDeserializer} が担う（Issue #2508）。
 * <b>シリアライザだけを登録してデシリアライザを登録しないと往復が非対称になり</b>、
 * ユーザー TZ が JST 以外の場合に「オフセット（ユーザー TZ）−（+09:00）」ぶんずれた値が
 * そのまま保持されてしまう。両方向を必ず対で登録すること。</p>
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
     *   <li>{@link LocalDateTimeTimezoneDeserializer} — LocalDateTime をユーザー TZ から
     *       サーバー保持形式（Asia/Tokyo 壁時計）へ変換して受け取る</li>
     *   <li>{@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS} 無効 — 数値ではなく文字列で出力</li>
     * </ul>
     *
     * @param builder Spring Boot が自動設定した {@link Jackson2ObjectMapperBuilder}
     * @return カスタマイズ済み ObjectMapper
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        // LocalDateTime ⇄ ユーザー TZ 変換を登録（出力・入力の両方向。片方だけだと往復で値がずれる）
        SimpleModule timezoneModule = new SimpleModule("TimezoneModule");
        timezoneModule.addSerializer(LocalDateTime.class, new LocalDateTimeTimezoneSerializer());
        timezoneModule.addDeserializer(LocalDateTime.class, new LocalDateTimeTimezoneDeserializer());

        return builder
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .modules(new JavaTimeModule(), timezoneModule, new ParameterNamesModule(JsonCreator.Mode.DEFAULT))
                .build();
    }
}

package com.mannschaft.app.config.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.mannschaft.app.common.timezone.TimezoneContextHolder;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * {@link LocalDateTime} をユーザーのタイムゾーンに変換して ISO-8601 オフセット付き文字列で
 * JSON シリアライズするカスタムシリアライザ。
 *
 * <p>変換フロー:</p>
 * <ol>
 *   <li>{@link TimezoneContextHolder#get()} からリクエスト単位のユーザー ZoneId を取得</li>
 *   <li>LocalDateTime を JVM デフォルト TZ（Asia/Tokyo、{@link com.mannschaft.app.config.TimeZoneConfig} が設定）
 *       として解釈</li>
 *   <li>ユーザーの ZoneId に変換して {@link OffsetDateTime} で出力</li>
 * </ol>
 *
 * <p>出力例:</p>
 * <ul>
 *   <li>Tokyo ユーザー: {@code "2026-05-22T09:15:20+09:00"}</li>
 *   <li>New York ユーザー: {@code "2026-05-21T20:15:20-04:00"}</li>
 *   <li>未認証（UTC）: {@code "2026-05-22T00:15:20Z"}</li>
 * </ul>
 *
 * <p>Entity / DTO / Service は一切変更しない。Jackson 設定層のみの変更。</p>
 */
public class LocalDateTimeTimezoneSerializer extends JsonSerializer<LocalDateTime> {

    /** ISO 8601 オフセット付き形式（例: 2026-05-22T09:15:20+09:00） */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Override
    public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }

        // JVM デフォルト TZ（Asia/Tokyo）として解釈 → ユーザー TZ に変換
        ZoneId serverZone = ZoneId.systemDefault();
        ZoneId userZone = TimezoneContextHolder.get();

        OffsetDateTime result = value
                .atZone(serverZone)
                .withZoneSameInstant(userZone)
                .toOffsetDateTime();

        gen.writeString(result.format(FORMATTER));
    }
}

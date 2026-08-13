package com.mannschaft.app.config.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;

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
 *   <li>LocalDateTime をアプリ層の基準ゾーン（{@link UserZoneLocalDateTimeParser#SERVER_ZONE}、
 *       ＝ Asia/Tokyo）として解釈</li>
 *   <li>ユーザーの ZoneId に変換して {@link OffsetDateTime} で出力</li>
 * </ol>
 *
 * <p>基準ゾーンは対になる {@link LocalDateTimeTimezoneDeserializer#SERVER_ZONE} と同じ
 * {@link UserZoneLocalDateTimeParser#SERVER_ZONE} を参照する（CMP-023 第1ロット。旧実装は
 * {@code ZoneId.systemDefault()} を見ており、デシリアライザ側の定数参照と非対称だった。
 * この一致は {@code JacksonTimeTypeSymmetryGuardTest} の AC-9-3 が機械的に固定している）。</p>
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

        // アプリ層の基準ゾーン（Asia/Tokyo）として解釈 → ユーザー TZ に変換
        ZoneId serverZone = UserZoneLocalDateTimeParser.SERVER_ZONE;
        ZoneId userZone = TimezoneContextHolder.get();

        OffsetDateTime result = value
                .atZone(serverZone)
                .withZoneSameInstant(userZone)
                .toOffsetDateTime();

        gen.writeString(result.format(FORMATTER));
    }
}

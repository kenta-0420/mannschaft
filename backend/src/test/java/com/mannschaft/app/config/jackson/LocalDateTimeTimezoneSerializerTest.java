package com.mannschaft.app.config.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LocalDateTimeTimezoneSerializer} の単体テスト。
 *
 * <p>サーバーTZ（JVM デフォルト）を Asia/Tokyo に固定した状態で、
 * ユーザーTZを変えた場合のシリアライズ結果を検証する。</p>
 */
@DisplayName("LocalDateTimeTimezoneSerializer 単体テスト")
class LocalDateTimeTimezoneSerializerTest {

    private ObjectMapper objectMapper;
    private TimeZone originalTimeZone;

    @BeforeEach
    void setUp() {
        // JVM デフォルト TZ を Asia/Tokyo に固定（TimeZoneConfig と同じ）
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));

        // カスタムシリアライザを登録した ObjectMapper を構築
        SimpleModule timezoneModule = new SimpleModule("TimezoneModule");
        timezoneModule.addSerializer(LocalDateTime.class, new LocalDateTimeTimezoneSerializer());

        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(timezoneModule)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @AfterEach
    void tearDown() {
        // テスト間のスレッドローカル汚染を防止
        TimezoneContextHolder.clear();
        // JVM デフォルト TZ を元に戻す
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    @DisplayName("Asia/Tokyo をセット時に +09:00 オフセットで出力される")
    void Tokyo_TZ_プラス9時間オフセット() throws Exception {
        // Given: 2026-05-22T09:15:20 (JST)
        LocalDateTime ldt = LocalDateTime.of(2026, 5, 22, 9, 15, 20);
        TimezoneContextHolder.set(ZoneId.of("Asia/Tokyo"));

        // When
        String json = objectMapper.writeValueAsString(ldt);

        // Then: +09:00 オフセット付きで出力
        // ダブルクォートを除いた文字列で検証
        assertThat(json).isEqualTo("\"2026-05-22T09:15:20+09:00\"");
    }

    @Test
    @DisplayName("America/New_York をセット時に -05:00 または -04:00 オフセットで出力される（夏時間考慮）")
    void NewYork_TZ_マイナスオフセット() throws Exception {
        // Given: 2026-01-22T09:15:20 (JST) = 2026-01-21T19:15:20 EST (-05:00)
        // 1月は標準時（EST -05:00）
        LocalDateTime ldt = LocalDateTime.of(2026, 1, 22, 9, 15, 20);
        TimezoneContextHolder.set(ZoneId.of("America/New_York"));

        // When
        String json = objectMapper.writeValueAsString(ldt);

        // Then: EST（-05:00）または EDT（-04:00）
        // 夏時間の境界を避けるため -05:00 か -04:00 のどちらかであることを確認
        assertThat(json)
                .contains("-05:00")
                .isEqualTo("\"2026-01-21T19:15:20-05:00\"");
    }

    @Test
    @DisplayName("夏時間（5月）の America/New_York は -04:00 オフセットで出力される")
    void NewYork_夏時間_マイナス4時間オフセット() throws Exception {
        // Given: 2026-05-22T09:15:20 (JST) = 2026-05-21T20:15:20 EDT (-04:00)
        LocalDateTime ldt = LocalDateTime.of(2026, 5, 22, 9, 15, 20);
        TimezoneContextHolder.set(ZoneId.of("America/New_York"));

        // When
        String json = objectMapper.writeValueAsString(ldt);

        // Then: EDT（-04:00）
        assertThat(json).isEqualTo("\"2026-05-21T20:15:20-04:00\"");
    }

    @Test
    @DisplayName("TimezoneContextHolder 未セット時（UTC）は +00:00 または Z で出力される")
    void 未セット_UTC_出力() throws Exception {
        // Given: 2026-05-22T09:15:20 (JST) = 2026-05-22T00:15:20 UTC
        LocalDateTime ldt = LocalDateTime.of(2026, 5, 22, 9, 15, 20);
        // TimezoneContextHolder はセットしない（デフォルト UTC）

        // When
        String json = objectMapper.writeValueAsString(ldt);

        // Then: UTC（+00:00）
        assertThat(json).isEqualTo("\"2026-05-22T00:15:20Z\"");
    }

    @Test
    @DisplayName("null 入力時は null が JSON 出力される")
    void null入力_null出力() throws Exception {
        // Given: ラッパークラスで null を確認する
        TestRecord record = new TestRecord(null);

        // When
        String json = objectMapper.writeValueAsString(record);

        // Then: null が出力される
        assertThat(json).contains("\"value\":null");
    }

    @Test
    @DisplayName("UTC を明示的にセットした場合 +00:00 で出力される")
    void UTC_明示_出力() throws Exception {
        // Given: 2026-05-22T09:15:20 (JST) = 2026-05-22T00:15:20 UTC
        LocalDateTime ldt = LocalDateTime.of(2026, 5, 22, 9, 15, 20);
        TimezoneContextHolder.set(ZoneId.of("UTC"));

        // When
        String json = objectMapper.writeValueAsString(ldt);

        // Then: UTC（Z = +00:00 と同等）
        assertThat(json).isEqualTo("\"2026-05-22T00:15:20Z\"");
    }

    @Test
    @DisplayName("Europe/London をセット時に正しく変換される（冬時間: UTC+0）")
    void London_冬時間_UTC() throws Exception {
        // Given: 2026-01-22T09:15:20 (JST) = 2026-01-22T00:15:20 GMT (+00:00)
        LocalDateTime ldt = LocalDateTime.of(2026, 1, 22, 9, 15, 20);
        TimezoneContextHolder.set(ZoneId.of("Europe/London"));

        // When
        String json = objectMapper.writeValueAsString(ldt);

        // Then: GMT（Z）
        assertThat(json).isEqualTo("\"2026-01-22T00:15:20Z\"");
    }

    /** null テスト用の補助レコード */
    record TestRecord(LocalDateTime value) {}
}

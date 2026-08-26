package com.mannschaft.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JacksonConfig} が構築する {@code @Primary ObjectMapper} の単体テスト（Issue #2508）。
 *
 * <p><b>AC-1</b>: {@link com.mannschaft.app.config.jackson.LocalDateTimeTimezoneDeserializer} が
 * 実際に {@code addDeserializer(LocalDateTime.class, ...)} で登録されていることを、
 * 「登録の有無」ではなく<b>実際の変換結果</b>で固定する。
 * デシリアライザの登録漏れ（これが Issue #2508 の欠陥そのもの）が起きると
 * オフセット付き入力で例外、オフセット無し入力でユーザー TZ 無視となり本テストが落ちる。</p>
 *
 * <p>TEST_CONVENTION §2.3 は「設定クラスの Bean 登録は起動テストでカバー」とするが、
 * 本 Bean は <b>登録内容そのものが不具合の再発点</b>であり、起動テストでは値のズレを検出できないため
 * 例外的に単体テストで固定する。</p>
 */
@DisplayName("JacksonConfig ObjectMapper 単体テスト")
class JacksonConfigTest {

    /** 基準の瞬間をサーバー保持形式（Asia/Tokyo 壁時計）で表した値 */
    private static final LocalDateTime EXPECTED_JST = LocalDateTime.of(2026, 5, 22, 9, 15, 20);

    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");

    private ObjectMapper objectMapper;
    private TimeZone originalTimeZone;

    @BeforeEach
    void setUp() {
        // JVM デフォルト TZ を Asia/Tokyo に固定（TimeZoneConfig と同じ）
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));

        objectMapper = new JacksonConfig().objectMapper(Jackson2ObjectMapperBuilder.json());
    }

    @AfterEach
    void tearDown() {
        TimezoneContextHolder.clear();
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    @DisplayName("LocalDateTime デシリアライザが登録されており、オフセット付き入力を読める")
    void デシリアライザ登録_オフセット付き入力を読める() throws Exception {
        // Given: 自分自身が書き出す形式（オフセット付き）
        // When
        LocalDateTime result = objectMapper.readValue("\"2026-05-22T00:15:20Z\"", LocalDateTime.class);

        // Then: 標準デシリアライザなら例外になる入力が、瞬間を保存して JST 壁時計へ変換される
        assertThat(result).isEqualTo(EXPECTED_JST);
    }

    @Test
    @DisplayName("解決済みユーザー TZ のオフセット無し入力がその TZ の壁時計として解釈される")
    void デシリアライザ登録_解決済みTZで解釈される() throws Exception {
        // Given
        TimezoneContextHolder.setResolved(LOS_ANGELES);

        // When
        LocalDateTime result = objectMapper.readValue("\"2026-05-21T17:15:20\"", LocalDateTime.class);

        // Then
        assertThat(result).isEqualTo(EXPECTED_JST);
    }

    @Test
    @DisplayName("未解決（バッチ・未認証）のオフセット無し入力は Asia/Tokyo 解釈で恒等変換になる")
    void デシリアライザ登録_未解決は恒等変換() throws Exception {
        // Given: TimezoneContextHolder は未セット（get() は UTC / isResolved() は false）
        // When
        LocalDateTime result = objectMapper.readValue("\"2026-05-22T09:15:20\"", LocalDateTime.class);

        // Then: UTC 解釈なら 18:15:20 になってしまう。恒等変換であることを固定する
        assertThat(result).isEqualTo(EXPECTED_JST);
    }

    @Test
    @DisplayName("シリアライザとデシリアライザが対で登録されており往復同一になる（AC-8）")
    void シリアライザとデシリアライザが対で登録されている() throws Exception {
        // Given
        TimezoneContextHolder.setResolved(LOS_ANGELES);

        // When
        String json = objectMapper.writeValueAsString(EXPECTED_JST);
        LocalDateTime readBack = objectMapper.readValue(json, LocalDateTime.class);

        // Then
        assertThat(json).isEqualTo("\"2026-05-21T17:15:20-07:00\"");
        assertThat(readBack).isEqualTo(EXPECTED_JST);
    }
}

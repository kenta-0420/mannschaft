package com.mannschaft.app.config.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LocalDateTimeTimezoneDeserializer} の単体テスト（Issue #2508）。
 *
 * <p>サーバー TZ（JVM デフォルト）を Asia/Tokyo に固定した状態で、
 * 「オフセット付き / オフセット無し × ユーザー TZ 解決済み / 未解決」の全組み合わせを検証する。</p>
 *
 * <p>基準となる瞬間は全テストで {@code 2026-05-22T00:15:20Z}（= JST {@code 2026-05-22T09:15:20}
 * = LA 夏時間 PDT {@code 2026-05-21T17:15:20} = NY 夏時間 EDT {@code 2026-05-21T20:15:20}）に統一している。</p>
 */
@DisplayName("LocalDateTimeTimezoneDeserializer 単体テスト")
class LocalDateTimeTimezoneDeserializerTest {

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

        // 本番と同じく「シリアライザ＋デシリアライザを対で」登録した ObjectMapper を構築
        SimpleModule timezoneModule = new SimpleModule("TimezoneModule");
        timezoneModule.addSerializer(LocalDateTime.class, new LocalDateTimeTimezoneSerializer());
        timezoneModule.addDeserializer(LocalDateTime.class, new LocalDateTimeTimezoneDeserializer());

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

    private LocalDateTime read(String isoText) throws Exception {
        return objectMapper.readValue("\"" + isoText + "\"", LocalDateTime.class);
    }

    // ------------------------------------------------------------------
    // AC-2: オフセット付き入力は「瞬間」を保存して Asia/Tokyo 壁時計へ変換する
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC-2 オフセット付き入力は瞬間を保存して JST 壁時計へ変換される")
    class オフセット付き入力 {

        @Test
        @DisplayName("+09:00 はそのまま同じ壁時計になる（国内ユーザー）")
        void プラス9時間_恒等() throws Exception {
            assertThat(read("2026-05-22T09:15:20+09:00")).isEqualTo(EXPECTED_JST);
        }

        @Test
        @DisplayName("Z（UTC）は +9 時間された JST 壁時計になる")
        void Z_UTC_プラス9時間変換() throws Exception {
            assertThat(read("2026-05-22T00:15:20Z")).isEqualTo(EXPECTED_JST);
        }

        @Test
        @DisplayName("-04:00（NY 夏時間）は +13 時間された JST 壁時計になる")
        void マイナス4時間_変換() throws Exception {
            assertThat(read("2026-05-21T20:15:20-04:00")).isEqualTo(EXPECTED_JST);
        }

        @Test
        @DisplayName("-07:00（LA 夏時間）は +16 時間された JST 壁時計になる")
        void マイナス7時間_変換() throws Exception {
            assertThat(read("2026-05-21T17:15:20-07:00")).isEqualTo(EXPECTED_JST);
        }

        @Test
        @DisplayName("オフセット付き入力はユーザー TZ の解決状況に左右されない（瞬間が明示されているため）")
        void 解決済みユーザーTZに影響されない() throws Exception {
            TimezoneContextHolder.setResolved(LOS_ANGELES);
            assertThat(read("2026-05-22T00:15:20Z")).isEqualTo(EXPECTED_JST);
        }

        @Test
        @DisplayName("+00:00 表記も Z と同じく扱われる")
        void プラス0時間_表記() throws Exception {
            assertThat(read("2026-05-22T00:15:20+00:00")).isEqualTo(EXPECTED_JST);
        }
    }

    // ------------------------------------------------------------------
    // AC-3: オフセット無し ＋ 解決済みユーザー TZ → その TZ の壁時計として解釈
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC-3 オフセット無し＋解決済みユーザー TZ はその TZ の壁時計として解釈される")
    class オフセット無し_解決済み {

        @Test
        @DisplayName("America/Los_Angeles の壁時計が JST 相当へ変換される（夏時間 -07:00）")
        void LA_夏時間_JSTへ変換() throws Exception {
            TimezoneContextHolder.setResolved(LOS_ANGELES);

            assertThat(read("2026-05-21T17:15:20")).isEqualTo(EXPECTED_JST);
        }

        @Test
        @DisplayName("America/New_York の壁時計が JST 相当へ変換される（夏時間 -04:00）")
        void NY_夏時間_JSTへ変換() throws Exception {
            TimezoneContextHolder.setResolved(ZoneId.of("America/New_York"));

            assertThat(read("2026-05-21T20:15:20")).isEqualTo(EXPECTED_JST);
        }

        @Test
        @DisplayName("America/New_York の冬時間（-05:00）でも正しく変換される")
        void NY_冬時間_JSTへ変換() throws Exception {
            TimezoneContextHolder.setResolved(ZoneId.of("America/New_York"));

            // 2026-01-21T19:15:20 EST(-05:00) = 2026-01-22T00:15:20Z = JST 2026-01-22T09:15:20
            assertThat(read("2026-01-21T19:15:20"))
                    .isEqualTo(LocalDateTime.of(2026, 1, 22, 9, 15, 20));
        }

        @Test
        @DisplayName("解決済みでも Asia/Tokyo なら恒等変換（国内ユーザーは既存挙動と完全に同じ）")
        void 解決済みTokyo_恒等変換() throws Exception {
            TimezoneContextHolder.setResolved(ZoneId.of("Asia/Tokyo"));

            assertThat(read("2026-05-22T09:15:20")).isEqualTo(EXPECTED_JST);
        }

        @Test
        @DisplayName("解決済みで UTC の場合は +9 時間される（ユーザーが明示的に UTC を選んだケース）")
        void 解決済みUTC_プラス9時間() throws Exception {
            TimezoneContextHolder.setResolved(ZoneOffset.UTC);

            assertThat(read("2026-05-22T00:15:20")).isEqualTo(EXPECTED_JST);
        }

        @Test
        @DisplayName("秒を省略した ISO 形式（HH:mm）でも解釈できる")
        void 秒省略形式() throws Exception {
            TimezoneContextHolder.setResolved(LOS_ANGELES);

            assertThat(read("2026-05-21T17:15")).isEqualTo(LocalDateTime.of(2026, 5, 22, 9, 15));
        }
    }

    // ------------------------------------------------------------------
    // AC-4: オフセット無し ＋ 未解決 → Asia/Tokyo 解釈（UTC 既定を継承しない）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC-4 オフセット無し＋未解決は Asia/Tokyo 解釈（UTC 既定を継承しない）")
    class オフセット無し_未解決 {

        @Test
        @DisplayName("何もセットされていないバッチスレッドでは恒等変換になる（−9 時間しない）")
        void バッチスレッド_恒等変換() throws Exception {
            // TimezoneContextHolder はセットしない（get() は UTC を返すが isResolved() は false）
            assertThat(TimezoneContextHolder.get()).isEqualTo(ZoneOffset.UTC);
            assertThat(TimezoneContextHolder.isResolved()).isFalse();

            // UTC 壁時計として解釈されると 2026-05-22T18:15:20 になってしまう。それを踏まないことを固定する
            assertThat(read("2026-05-22T09:15:20")).isEqualTo(EXPECTED_JST);
        }

        @Test
        @DisplayName("未認証リクエスト（フィルターが印なしで UTC を積む）でも恒等変換になる")
        void 未認証_UTC印なし_恒等変換() throws Exception {
            // UserTimezoneFilter の未認証経路と同じ状態を作る
            TimezoneContextHolder.set(ZoneOffset.UTC);

            assertThat(read("2026-05-22T09:15:20")).isEqualTo(EXPECTED_JST);
        }

        @Test
        @DisplayName("印なしで LA が積まれていても未解決として Asia/Tokyo 解釈になる（印の有無だけが判断材料）")
        void 印なしのLA_はユーザーTZとして採用しない() throws Exception {
            TimezoneContextHolder.set(LOS_ANGELES);

            assertThat(read("2026-05-22T09:15:20")).isEqualTo(EXPECTED_JST);
        }

        @Test
        @DisplayName("clear() 後（スレッドプール再利用時）も恒等変換に戻る")
        void clear後_恒等変換() throws Exception {
            TimezoneContextHolder.setResolved(LOS_ANGELES);
            TimezoneContextHolder.clear();

            assertThat(read("2026-05-22T09:15:20")).isEqualTo(EXPECTED_JST);
        }
    }

    // ------------------------------------------------------------------
    // AC-5: 解釈不能な入力は例外（→ 400）。握り潰さない
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC-5 解釈不能な入力は InvalidFormatException（→ 400）になり握り潰されない")
    class 解釈不能な入力 {

        @Test
        @DisplayName("日付のみ（時刻なし）は例外")
        void 日付のみ_例外() {
            assertThatThrownBy(() -> read("2026-05-22"))
                    .isInstanceOf(InvalidFormatException.class)
                    .hasMessageContaining("2026-05-22");
        }

        @Test
        @DisplayName("日時として意味を持たない文字列は例外")
        void 非日時文字列_例外() {
            assertThatThrownBy(() -> read("not-a-datetime"))
                    .isInstanceOf(InvalidFormatException.class);
        }

        @Test
        @DisplayName("範囲外の月日時刻は例外（無音で丸めない）")
        void 範囲外の値_例外() {
            assertThatThrownBy(() -> read("2026-13-45T99:99:99"))
                    .isInstanceOf(InvalidFormatException.class);
        }

        @Test
        @DisplayName("解決済みユーザー TZ があっても不正入力は例外（フォールバックしない）")
        void 解決済みでも不正入力は例外() {
            TimezoneContextHolder.setResolved(LOS_ANGELES);

            assertThatThrownBy(() -> read("2026/05/22 09:15:20"))
                    .isInstanceOf(InvalidFormatException.class);
        }

        @Test
        @DisplayName("JSON null は null になる（不正入力ではない）")
        void JSON_null_はnull() throws Exception {
            assertThat(objectMapper.readValue("null", LocalDateTime.class)).isNull();
        }

        @Test
        @DisplayName("空文字は null になる（先行実例 LenientOffsetDateTimeDeserializer と同じ扱い）")
        void 空文字_はnull() throws Exception {
            assertThat(objectMapper.readValue("\"\"", LocalDateTime.class)).isNull();
        }

        @Test
        @DisplayName("前後の空白は無視される")
        void 前後空白_トリムされる() throws Exception {
            assertThat(read("  2026-05-22T09:15:20  ")).isEqualTo(EXPECTED_JST);
        }

        @Test
        @DisplayName("配列形式は無音で null にせず例外にする（日時フィールドが黙って消えるのを防ぐ）")
        void 配列形式_例外() {
            // getValueAsString() は非文字列トークンで null を返すため、
            // ガードが無いと「日時が無音で消える」握り潰しになる
            assertThatThrownBy(() -> objectMapper.readValue("[2026,5,22,9,15,20]", LocalDateTime.class))
                    .isInstanceOf(InvalidFormatException.class);
        }

        @Test
        @DisplayName("数値タイムスタンプも無音で null にせず例外にする")
        void 数値タイムスタンプ_例外() {
            assertThatThrownBy(() -> objectMapper.readValue("1779999320", LocalDateTime.class))
                    .isInstanceOf(InvalidFormatException.class);
        }
    }

    // ------------------------------------------------------------------
    // AC-8: シリアライザ ↔ デシリアライザの往復同一性
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC-8 シリアライザとの往復で元の LocalDateTime に戻る")
    class 往復同一性 {

        @Test
        @DisplayName("LA ユーザーとして書き出し→読み戻しで元の値に一致する")
        void LA_ユーザー_往復同一() throws Exception {
            TimezoneContextHolder.setResolved(LOS_ANGELES);

            String json = objectMapper.writeValueAsString(EXPECTED_JST);
            // 書き出しは LA の壁時計＋オフセットになっている
            assertThat(json).isEqualTo("\"2026-05-21T17:15:20-07:00\"");

            LocalDateTime readBack = objectMapper.readValue(json, LocalDateTime.class);
            assertThat(readBack).isEqualTo(EXPECTED_JST);
        }

        @Test
        @DisplayName("Tokyo ユーザーとして書き出し→読み戻しで元の値に一致する")
        void Tokyo_ユーザー_往復同一() throws Exception {
            TimezoneContextHolder.setResolved(ZoneId.of("Asia/Tokyo"));

            String json = objectMapper.writeValueAsString(EXPECTED_JST);
            assertThat(json).isEqualTo("\"2026-05-22T09:15:20+09:00\"");
            assertThat(objectMapper.readValue(json, LocalDateTime.class)).isEqualTo(EXPECTED_JST);
        }

        @Test
        @DisplayName("未認証（UTC 出力）で書き出した JSON も読み戻せる（payload_json の読み戻し経路）")
        void 未認証_UTC出力_往復同一() throws Exception {
            // 未認証相当（印なし UTC）。シリアライザは Z 付きで書き出す
            TimezoneContextHolder.set(ZoneOffset.UTC);

            String json = objectMapper.writeValueAsString(EXPECTED_JST);
            assertThat(json).isEqualTo("\"2026-05-22T00:15:20Z\"");

            // 読み戻し側はオフセットを見て瞬間を復元するため、TZ の状態に関係なく元の値へ戻る
            assertThat(objectMapper.readValue(json, LocalDateTime.class)).isEqualTo(EXPECTED_JST);
        }

        @Test
        @DisplayName("LA ユーザーが書き出した JSON をバッチ（未解決）が読み戻しても一致する")
        void LA出力をバッチが読み戻しても同一() throws Exception {
            TimezoneContextHolder.setResolved(LOS_ANGELES);
            String json = objectMapper.writeValueAsString(EXPECTED_JST);

            // バッチスレッド相当に切り替える（オフセット付きなので解釈は変わらない）
            TimezoneContextHolder.clear();

            assertThat(objectMapper.readValue(json, LocalDateTime.class)).isEqualTo(EXPECTED_JST);
        }

        @Test
        @DisplayName("DTO のフィールドとしても往復が成立する")
        void DTOフィールド_往復同一() throws Exception {
            TimezoneContextHolder.setResolved(LOS_ANGELES);

            String json = objectMapper.writeValueAsString(new TestRecord(EXPECTED_JST));
            assertThat(json).contains("2026-05-21T17:15:20-07:00");

            assertThat(objectMapper.readValue(json, TestRecord.class).value()).isEqualTo(EXPECTED_JST);
        }
    }

    /** DTO フィールド経由の往復を確認する補助レコード */
    record TestRecord(LocalDateTime value) {}
}

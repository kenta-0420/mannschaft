package com.mannschaft.app.config.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.mannschaft.app.common.timezone.TimezoneContextHolder;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * {@link LocalDateTime} をユーザーのタイムゾーンから<b>サーバー保持形式（Asia/Tokyo の壁時計）</b>へ
 * 変換して受け取るカスタムデシリアライザ（Issue #2508）。
 *
 * <h2>なぜ必要か</h2>
 *
 * <p>{@link com.mannschaft.app.config.JacksonConfig} は {@link LocalDateTimeTimezoneSerializer} を
 * 登録して <b>出力</b>だけをユーザー TZ へ変換していたが、対応する<b>入力</b>側が無く
 * Jackson 標準（{@code ISO_LOCAL_DATE_TIME}）のままだった。結果として:</p>
 *
 * <ul>
 *   <li>送信 — 保持値を JST 壁時計と決め打ちし、ユーザー TZ へ変換してオフセット付きで出力</li>
 *   <li>受信 — 文字列をそのまま壁時計として取り込み、<b>ユーザー TZ を完全に無視</b>。
 *       しかも自分が出力したオフセット付き文字列を読み戻せない（例外）</li>
 * </ul>
 *
 * <p>ズレ幅は（ユーザー TZ のオフセット）−（+09:00）で、日本のユーザーだけ偶然 0 になるため
 * 発覚が遅れていた。本デシリアライザで往復の対称性を回復する。</p>
 *
 * <h2>解釈規則</h2>
 *
 * <table border="1">
 *   <caption>入力形式ごとの解釈</caption>
 *   <tr><th>入力</th><th>解釈</th></tr>
 *   <tr>
 *     <td>オフセット付き（{@code +09:00} / {@code Z} / {@code -04:00}）</td>
 *     <td>そのまま瞬間として解釈し、{@link #SERVER_ZONE} の壁時計へ変換して保持する
 *         （クライアントが瞬間を明示しているので最も信頼できる）</td>
 *   </tr>
 *   <tr>
 *     <td>オフセット無し ＋ ユーザー TZ が<b>明示的に解決済み</b>
 *         （{@link TimezoneContextHolder#isResolved()} が {@code true}）</td>
 *     <td>その TZ の壁時計として解釈し、{@link #SERVER_ZONE} へ変換して保持する</td>
 *   </tr>
 *   <tr>
 *     <td>オフセット無し ＋ <b>未解決</b>（未認証・バッチスレッド・キャッシュ Bean 不在）</td>
 *     <td>{@link #SERVER_ZONE} の壁時計として解釈する（＝恒等変換・旧挙動と完全に一致）</td>
 *   </tr>
 *   <tr>
 *     <td>解釈不能</td>
 *     <td>{@link InvalidFormatException}（→ HTTP 400）。握り潰しも無音フォールバックもしない</td>
 *   </tr>
 * </table>
 *
 * <h2>未解決を Asia/Tokyo として扱う理由（最重要）</h2>
 *
 * <p>{@link TimezoneContextHolder#get()} は未セット時に UTC を返し、
 * {@link com.mannschaft.app.common.timezone.UserTimezoneFilter} も未認証時に UTC を明示セットする。
 * そのため <b>{@code get()} を無条件に信じると、フィルターを通らないバッチスレッドの
 * オフセット無し入力が UTC 壁時計として解釈され、−9 時間ずれた値が保持されてしまう</b>
 * （{@code payload_json} の読み戻し等も同じ {@code @Primary ObjectMapper} を使う）。
 * よって {@link TimezoneContextHolder#isResolved()} が {@code true} のときだけユーザー TZ を採用し、
 * それ以外はサーバー既定 TZ として扱う。</p>
 *
 * <p>{@code users.timezone} は {@code NOT NULL DEFAULT 'Asia/Tokyo'}（{@code V1.001}）であるため、
 * 国内ユーザーは解決済み・未解決のいずれの経路でも <b>恒等変換</b>となり、既存の挙動は変わらない。</p>
 *
 * <p>Entity / DTO / Service は一切変更しない。Jackson 設定層のみの変更。</p>
 */
public class LocalDateTimeTimezoneDeserializer extends JsonDeserializer<LocalDateTime> {

    /**
     * サーバーが {@link LocalDateTime} を保持する基準 TZ。
     * {@link com.mannschaft.app.config.TimeZoneConfig} が JVM デフォルトに設定しているものと同じ値を
     * 明示的に固定する（{@link LocalDateTimeTimezoneSerializer} が {@code ZoneId.systemDefault()} で
     * 見ている値と一致させるための定数）。
     */
    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Tokyo");

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String raw = p.getValueAsString();
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }

        // 1. オフセット付き: 瞬間が確定しているのでサーバー基準 TZ の壁時計へ変換する
        try {
            return OffsetDateTime.parse(text)
                    .atZoneSameInstant(SERVER_ZONE)
                    .toLocalDateTime();
        } catch (DateTimeParseException withOffsetFailed) {
            // オフセット無しの可能性があるので次の解釈へ進む
        }

        // 2. オフセット無し: ユーザー TZ が解決済みならその壁時計、未解決ならサーバー基準 TZ の壁時計
        try {
            LocalDateTime wallClock = LocalDateTime.parse(text);
            ZoneId inputZone = TimezoneContextHolder.isResolved()
                    ? TimezoneContextHolder.get()
                    : SERVER_ZONE;
            if (SERVER_ZONE.equals(inputZone)) {
                // 恒等変換（国内ユーザー・バッチ・未認証）。無駄な TZ 往復を避ける
                return wallClock;
            }
            return wallClock.atZone(inputZone)
                    .withZoneSameInstant(SERVER_ZONE)
                    .toLocalDateTime();
        } catch (DateTimeParseException withoutOffsetFailed) {
            // 3. どちらとしても解釈できない: 握り潰さず 400 として失敗させる
            throw InvalidFormatException.from(p,
                    "日時として解釈できません（ISO-8601 形式で指定してください）: " + text,
                    text, LocalDateTime.class);
        }
    }
}

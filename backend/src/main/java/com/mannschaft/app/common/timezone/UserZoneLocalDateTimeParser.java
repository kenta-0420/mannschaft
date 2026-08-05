package com.mannschaft.app.common.timezone;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * クライアントから受け取った日時文字列を、<b>サーバー保持形式（Asia/Tokyo の壁時計）</b>の
 * {@link LocalDateTime} へ解釈する共通パーサ（Issue #2508）。
 *
 * <h2>なぜ共通化するか</h2>
 *
 * <p>同じ日時をリクエストボディで送るかクエリパラメータで送るかで解釈が変わってはならない。
 * ところがボディは Jackson（{@code LocalDateTimeTimezoneDeserializer}）を通り、
 * クエリパラメータは Spring の {@code ConversionService}
 * （{@code UserZoneLocalDateTimeFormatter}）を通るため、経路が完全に分かれている。
 * 解釈規則をこのクラス 1 箇所に集約し、両経路から呼ぶことで
 * <b>片方だけ直る／片方だけ壊れる</b>事故を構造的に防ぐ。</p>
 *
 * <h2>解釈規則</h2>
 *
 * <table border="1">
 *   <caption>入力形式ごとの解釈</caption>
 *   <tr><th>入力</th><th>解釈</th></tr>
 *   <tr>
 *     <td>オフセット付き（{@code +09:00} / {@code Z} / {@code -07:00}）</td>
 *     <td>瞬間が確定しているので、そのまま {@link #SERVER_ZONE} の壁時計へ変換する</td>
 *   </tr>
 *   <tr>
 *     <td>オフセット無し ＋ ユーザー TZ が<b>明示的に解決済み</b>
 *         （{@link TimezoneContextHolder#isResolved()} が {@code true}）</td>
 *     <td>その TZ の壁時計として解釈し、{@link #SERVER_ZONE} へ変換する</td>
 *   </tr>
 *   <tr>
 *     <td>オフセット無し ＋ <b>未解決</b>（未認証・バッチスレッド）</td>
 *     <td>{@link #SERVER_ZONE} の壁時計として解釈する（＝恒等変換・旧挙動と一致）</td>
 *   </tr>
 *   <tr>
 *     <td>解釈不能</td>
 *     <td>{@link DateTimeParseException}。呼び出し側が各経路の 400 応答へ翻訳する</td>
 *   </tr>
 * </table>
 *
 * <h2>未解決を Asia/Tokyo として扱う理由</h2>
 *
 * <p>{@link TimezoneContextHolder#get()} は未セット時に UTC を返し、
 * {@link UserTimezoneFilter} も未認証時に UTC を明示セットする。そのため
 * {@code get()} を無条件に信じると、フィルターを通らないバッチスレッドのオフセット無し入力が
 * UTC 壁時計として解釈され −9 時間ずれる。よって {@link TimezoneContextHolder#isResolved()} が
 * {@code true} のときだけユーザー TZ を採用する。</p>
 */
public final class UserZoneLocalDateTimeParser {

    /**
     * サーバーが {@link LocalDateTime} を保持する<b>アプリ層の基準 TZ</b>（＝ JST 壁時計）。
     *
     * <p>{@code .claudecode.md} §20「格納基準 UTC の二層モデル」のうち<b>アプリ層</b>側の壁時計であり、
     * DB 格納値の壁時計（UTC・{@code hibernate.jdbc.time_zone}）とは別物である。</p>
     */
    public static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Tokyo");

    private UserZoneLocalDateTimeParser() {
    }

    /**
     * 日時文字列をサーバー保持形式（{@link #SERVER_ZONE} の壁時計）へ解釈する。
     *
     * @param text 入力文字列（トリム済みであること・空でないこと）
     * @return サーバー保持形式の {@link LocalDateTime}
     * @throws DateTimeParseException オフセット付き・オフセット無しのいずれとしても解釈できない場合
     */
    public static LocalDateTime parse(String text) {
        try {
            // 1. オフセット付き: 瞬間が確定しているのでサーバー基準 TZ の壁時計へ変換する
            return OffsetDateTime.parse(text)
                    .atZoneSameInstant(SERVER_ZONE)
                    .toLocalDateTime();
        } catch (DateTimeParseException withOffsetFailed) {
            // 2. オフセット無し: 解決済みならユーザー TZ の壁時計、未解決ならサーバー基準 TZ の壁時計
            //    ここで再度失敗した場合は DateTimeParseException がそのまま呼び出し側へ伝播する
            //    （握り潰さず 400 として失敗させるのが契約）
            return toServerWallClock(LocalDateTime.parse(text));
        }
    }

    /**
     * オフセット無しで受け取った壁時計を、サーバー保持形式（{@link #SERVER_ZONE} の壁時計）へ変換する。
     *
     * @param wallClock 入力の壁時計
     * @return サーバー保持形式の {@link LocalDateTime}
     */
    private static LocalDateTime toServerWallClock(LocalDateTime wallClock) {
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
    }
}

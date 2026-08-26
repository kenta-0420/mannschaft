package com.mannschaft.app.common.timezone;

import java.time.DateTimeException;
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
 *     <td>解釈不能・または値域超過（{@code year +999999999} 等の TZ 変換で EpochDay が
 *         {@link LocalDateTime} の表現域を超える場合）</td>
 *     <td>{@link DateTimeParseException}。呼び出し側が各経路の 400 応答へ翻訳する</td>
 *   </tr>
 * </table>
 *
 * <h2>値域超過（Issue #2508 Phase 2）</h2>
 *
 * <p>{@code OffsetDateTime.parse(text)} は<b>構文解析には成功</b>したうえで、続く
 * {@code atZoneSameInstant(SERVER_ZONE).toLocalDateTime()} が値域超過（例:
 * {@code +999999999-12-31T23:59:59-18:00}）で {@link DateTimeException}（
 * {@link DateTimeParseException} のスーパークラスであり、そのサブクラスではない）や
 * {@link ArithmeticException} を投げることがある。オフセット無し分岐の
 * {@code atZone(...).withZoneSameInstant(...)} も同じ穴を持つ。これらを補足せずに伝播させると
 * 呼び出し側の {@code catch (DateTimeParseException)} を素通りし、400 ではなく 500 になる
 * （実測: {@code LocalDateTimeQueryParamBindingTest#ac13_outOfRangeOffsetInput_returns400NotServerError}
 * / {@code LocalDateTimeTimezoneDeserializerTest} の AC-13 で確認）。
 * よって本メソッドは {@link DateTimeException} と {@link ArithmeticException} を
 * {@link DateTimeParseException} へ正規化し、既存の「不正入力→400」経路に合流させる。</p>
 *
 * <h2>未解決を Asia/Tokyo として扱う理由</h2>
 *
 * <p>{@link TimezoneContextHolder#get()} は未セット時に UTC を返し、
 * {@link UserTimezoneFilter} も未認証時に UTC を明示セットする。そのため
 * {@code get()} を無条件に信じると、フィルターを通らないバッチスレッドのオフセット無し入力が
 * UTC 壁時計として解釈され −9 時間ずれる。よって {@link TimezoneContextHolder#isResolved()} が
 * {@code true} のときだけユーザー TZ を採用する。</p>
 *
 * <h2>夏時間（DST）の gap / overlap の扱い（Issue #2508 Phase 2・意図的に JDK 既定へ委譲）</h2>
 *
 * <p>オフセット無し入力をユーザー TZ の壁時計として解釈する際（{@link #toServerWallClock}）、
 * {@link LocalDateTime#atZone(ZoneId)} の<b>JDK 既定規則</b>にそのまま従う（独自のガード・補正は
 * 意図して入れていない）。</p>
 *
 * <ul>
 *   <li><b>gap（夏時間開始で存在しない時刻。例: America/Los_Angeles の
 *       {@code 2027-03-14T02:30}）</b> — ギャップ長だけ<b>前送り</b>され、遷移後（夏時間側）の
 *       オフセットが採用される（実測: {@code 02:30} → {@code 03:30 PDT(-07:00)}）。</li>
 *   <li><b>overlap（夏時間終了で 2 回存在する時刻。例: America/Los_Angeles の
 *       {@code 2027-11-07T01:30}）</b> — <b>早い方（遷移前＝夏時間側）</b>のオフセットが採用される
 *       （実測: {@code 01:30} → {@code -07:00 PDT}）。</li>
 * </ul>
 *
 * <p>いずれも 400 では弾かない（overlap は時刻自体が実在するため片手落ちの拒否になる。
 * gap も含め、影響範囲が読み切れない拒否より現状の JDK 既定固定を優先する）。
 * 実測値は {@code LocalDateTimeTimezoneDeserializerTest} の
 * 「DST_gap_存在しない時刻は前送りされる」「DST_overlap_早い方のオフセットが採用される」で固定している。</p>
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
     * @throws DateTimeParseException オフセット付き・オフセット無しのいずれとしても解釈できない場合、
     *      または解釈自体は成功しても TZ 変換の結果が値域超過になる場合
     */
    public static LocalDateTime parse(String text) {
        OffsetDateTime withOffset;
        try {
            withOffset = OffsetDateTime.parse(text);
        } catch (DateTimeParseException withOffsetFailed) {
            // 2. オフセット無し: 解決済みならユーザー TZ の壁時計、未解決ならサーバー基準 TZ の壁時計
            //    ここで再度失敗した場合は DateTimeParseException がそのまま呼び出し側へ伝播する
            //    （握り潰さず 400 として失敗させるのが契約）
            return toServerWallClock(LocalDateTime.parse(text));
        }
        try {
            // 1. オフセット付き: 瞬間が確定しているのでサーバー基準 TZ の壁時計へ変換する
            return withOffset.atZoneSameInstant(SERVER_ZONE).toLocalDateTime();
        } catch (DateTimeException | ArithmeticException outOfRange) {
            // 構文解析には成功したが、TZ 変換の結果が LocalDateTime の表現域を超えた
            // （DateTimeException は DateTimeParseException のスーパークラスなので素通りしうる）。
            // 握り潰さず、既存の 400 経路（DateTimeParseException）へ正規化する。
            throw outOfRangeAsParseException(text, outOfRange);
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
        try {
            return wallClock.atZone(inputZone)
                    .withZoneSameInstant(SERVER_ZONE)
                    .toLocalDateTime();
        } catch (DateTimeException | ArithmeticException outOfRange) {
            // parse() 側と同じ穴（値域超過）。同じく 400 経路へ正規化する
            throw outOfRangeAsParseException(wallClock.toString(), outOfRange);
        }
    }

    /**
     * 値域超過（{@link DateTimeException} / {@link ArithmeticException}）を、
     * 呼び出し元 3 箇所（{@code UserZoneLocalDateTimeFormatter} /
     * {@code LocalDateTimeTimezoneDeserializer}）が握っている既存の
     * {@code catch (DateTimeParseException)} 経路へ合流させるための正規化。
     *
     * <p>ここで個別に catch を広げさせず 1 箇所に集約するのは、このクラスの存在理由
     * （解釈規則の一元化＝片方だけ直る事故の防止）と同じ理由による。</p>
     */
    private static DateTimeParseException outOfRangeAsParseException(String text, RuntimeException cause) {
        DateTimeParseException wrapped = new DateTimeParseException(
                "日時が表現可能な範囲を超えています: " + text, text, 0, cause);
        return wrapped;
    }
}

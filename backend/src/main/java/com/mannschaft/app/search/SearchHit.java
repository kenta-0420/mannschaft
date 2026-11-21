package com.mannschaft.app.search;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Map;

/**
 * 検索結果 1 件（設計書 §4 レスポンス {@code results[]}）。
 *
 * @param type       種別
 * @param id         対象 ID
 * @param title      プレーンテキストの題名（{@code <mark>} なし。題名を持たない種別は null）
 * @param snippet    HTML エスケープ後に一致箇所のみ {@code <mark>} で囲んだ抜粋（最大 200 文字）
 * @param highlights 一致位置（例 {@code title -> [start, end]}、プレーンテキスト基準）
 * @param relevance  FULLTEXT 種別の関連度（LIKE 種別は null）
 * @param author     作成者（無い種別は null）
 * @param scope      所属スコープ（無い種別は null）
 * @param createdAt  作成日時（起きた瞬間なので OffsetDateTime。時刻方針 datetime_policy_utc_instant_vs_wallclock §1）
 * @param url        遷移先の実在 FE ルート（リンク不可は null）
 * @param actions    実行可能アクション
 */
public record SearchHit(
        SearchType type,
        Long id,
        String title,
        String snippet,
        Map<String, List<Integer>> highlights,
        Double relevance,
        Author author,
        ScopeRef scope,
        OffsetDateTime createdAt,
        String url,
        List<String> actions) {

    /** {@link #createdAtOfStoredUtc} が受け取る UTC 格納値の書式（小数秒は 0〜6 桁）。 */
    private static final DateTimeFormatter STORED_UTC_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 6, true)
            .optionalEnd()
            .toFormatter();

    /**
     * エンティティの作成日時（アプリ層の {@code LocalDateTime}）を {@code createdAt} の瞬間へ直す。
     *
     * <p>Hibernate が読み戻す {@code LocalDateTime} は DB の UTC 格納値ではなく、アプリ層の壁時計
     * （{@link UserZoneLocalDateTimeParser#SERVER_ZONE} = JST）である（.claudecode.md §20 の二層モデル）。
     * したがって JST として解釈してから瞬間へ変換する。UTC 固定の {@code Clock} のゾーンで解釈すると
     * 9 時間未来へずれる（CMP-261116-0722 PR-2 検分 P2-1）。</p>
     *
     * @param appWallClock アプリ層の壁時計（null 可）
     * @return 同一瞬間の {@link OffsetDateTime}（オフセットは SERVER_ZONE のもの）。入力が null なら null
     */
    public static OffsetDateTime createdAtOf(LocalDateTime appWallClock) {
        return appWallClock == null ? null
                : appWallClock.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toOffsetDateTime();
    }

    /**
     * 生 SQL が {@code DATE_FORMAT(x.created_at, '%Y-%m-%d %H:%i:%s.%f')} で読んだ DB の格納値（UTC 壁時計）を
     * {@code createdAt} の瞬間へ直す。
     *
     * <p>検索 Contributor は可視性を判定した同じ SQL の行から返却列を取る（ID で行を読み直さない。CMP-261116-0722
     * PR-6 検分 P1）。DB の時刻列の格納基準は {@code hibernate.jdbc.time_zone: UTC} による UTC 壁時計なので、
     * UTC として解釈してから {@link UserZoneLocalDateTimeParser#SERVER_ZONE} のオフセットで表す
     * （エンティティ経由の {@link #createdAtOf} と同じ瞬間・同じオフセットになる）。</p>
     *
     * @param storedUtc {@code yyyy-MM-dd HH:mm:ss[.SSSSSS]} 形式の UTC 格納値（null 可）
     * @return 同一瞬間の {@link OffsetDateTime}（オフセットは SERVER_ZONE のもの）。入力が null なら null
     */
    public static OffsetDateTime createdAtOfStoredUtc(String storedUtc) {
        if (storedUtc == null || storedUtc.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(storedUtc.trim(), STORED_UTC_FORMAT)
                .atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(UserZoneLocalDateTimeParser.SERVER_ZONE)
                .toOffsetDateTime();
    }

    /** 作成者。 */
    @Schema(name = "SearchHitAuthor")
    public record Author(Long id, String displayName, String avatarUrl) {
    }

    /** 所属スコープ。 */
    @Schema(name = "SearchHitScope")
    public record ScopeRef(String type, Long id, String name) {
    }
}

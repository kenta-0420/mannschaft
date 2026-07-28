package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.AuditLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 監査ログリポジトリ。
 */
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    /**
     * 指定日時より前に作成された監査ログをページング取得する（アーカイブバッチ用）。
     *
     * @param threshold 基準日時（この日時より前のログが対象）
     * @param pageable  ページング情報
     * @return スライス形式の監査ログ一覧
     */
    @Query("SELECT a FROM AuditLogEntity a WHERE a.createdAt < :threshold ORDER BY a.id ASC")
    Slice<AuditLogEntity> findOlderThan(@Param("threshold") LocalDateTime threshold, Pageable pageable);

    /**
     * 指定 ID より前の監査ログを物理削除する（アーカイブ完了後のクリーンアップ用）。
     *
     * @param maxId    削除対象の最大 ID（この ID 以下のレコードを削除）
     * @param threshold 基準日時（この日時より前かつ maxId 以下のレコードを削除。二重チェック）
     * @return 削除件数
     */
    @Modifying
    @Query("DELETE FROM AuditLogEntity a WHERE a.id <= :maxId AND a.createdAt < :threshold")
    int deleteArchivedLogs(@Param("maxId") Long maxId, @Param("threshold") LocalDateTime threshold);

    /**
     * 指定ユーザーの、指定日時以降のアクティブ日数（ログイン成功日の distinct DATE 数）を数える。
     *
     * <p>F20.3 ベータ特典の {@code activeDays} メトリクスの唯一の計測源（設計書 F20.3 02 §2・README §7）。
     * {@code eventType='LOGIN_SUCCESS'}（{@code AuditEventType.LOGIN_SUCCESS} の name()）を
     * {@code COUNT(DISTINCT DATE(...))} で数える。scalar（{@code long}）を返すため、呼び出し側
     * （{@code billing.beta.LoginActivityQueryService}）は {@code AuditLogEntity} に依存しない
     * （クロスドメイン Entity 参照 D-1 を回避）。</p>
     *
     * <h4>日境界は {@code tzOffset}（ユーザー各自の TZ）で切る</h4>
     * <p>{@code created_at} は<b>格納基準 TZ</b>（{@code storedZoneOffset}。既定 {@code "+00:00"}＝UTC。
     * 環境によっては UTC でない可能性があり、その場合は呼び出し側
     * （{@code billing.beta.LoginActivityQueryService}）が {@code mannschaft.audit.stored-zone-offset}
     * から実態値を注入する）で格納されている。{@code DATE(created_at)} を素で切ると日境界が格納基準 TZ に寄り、
     * JST 深夜帯（格納基準 TZ では前日）のログインが前日に数えられて活動日数がユーザー体感とずれる。そこで
     * {@code CONVERT_TZ(created_at, :storedZoneOffset, :tzOffset)} で<b>そのユーザーの現地時刻へ変換してから</b>
     * 日付を切る。{@code storedZoneOffset} / {@code tzOffset} はいずれも {@code "+09:00"} / {@code "-07:00"} 形式の
     * <b>数値オフセット</b>であること（{@code 'Asia/Tokyo'} のような IANA 名は MySQL の tz テーブル未投入環境で黙って
     * NULL を返し、集計が静かに 0 になる）。</p>
     *
     * <h4>実装メモ（native / 性能）</h4>
     * <ul>
     *   <li><b>{@code nativeQuery=true}</b>: 汎用 {@code FUNCTION()} の引数位置に名前付きパラメータを置く前例が
     *       本リポジトリに無く、Hibernate が起動時にパラメータ型を決定できず落ちる危険がある。本クエリは Entity を
     *       返さない scalar ゆえ D-1 番人に無害で、{@code AuditLogEntity} には {@code @SQLRestriction}/{@code @Where}
     *       が無いため native がフィルタを貫通する事故も起きない（2026-07-28 実確認）。</li>
     *   <li><b>{@code WHERE} は生カラムのまま</b>: {@code created_at >= :since}（格納基準 TZ 同士の比較）に変換関数を
     *       掛けると {@code audit_logs(user_id, created_at)} のインデックスが効かなくなる。変換は
     *       {@code COUNT(DISTINCT ...)} の中だけに閉じる。</li>
     *   <li><b>{@code storedZoneOffset} はバインドパラメータ</b>: SQL 文字列連結にせずプレースホルダで渡す
     *       （SQL インジェクション回避・検分指摘 2026-07-28 是正）。</li>
     * </ul>
     *
     * @param userId          対象ユーザー
     * @param since           評価ウィンドウ起点（格納基準 TZ の壁時計。この日時以降のログインを数える）
     * @param storedZoneOffset {@code created_at} の格納基準 TZ（{@code "+00:00"} 形式。既定 UTC）
     * @param tzOffset        日境界を切るための変換先 数値オフセット（{@code "+09:00"} 形式）
     * @return アクティブ日数（現地日付の distinct 数）
     */
    @Query(value = "SELECT COUNT(DISTINCT DATE(CONVERT_TZ(a.created_at, :storedZoneOffset, :tzOffset))) "
            + "FROM audit_logs a "
            + "WHERE a.user_id = :userId AND a.event_type = 'LOGIN_SUCCESS' AND a.created_at >= :since",
            nativeQuery = true)
    long countDistinctLoginDaysSince(@Param("userId") Long userId, @Param("since") LocalDateTime since,
                                     @Param("storedZoneOffset") String storedZoneOffset,
                                     @Param("tzOffset") String tzOffset);

    /**
     * 複数ユーザーの {@code activeDays}（ログイン成功日の distinct DATE 数）を <b>1 クエリ</b>で一括集計する
     * （F20.3 Phase2 自動付与バッチの N+1 回避・設計書 F20.3 03 §6）。
     *
     * <p>{@link #countDistinctLoginDaysSince} の bulk 版。{@code GROUP BY a.user_id} で userId ごとの distinct 日数を
     * 返し、{@code List<Object[]>}（{@code [0]=userId(Long), [1]=days(Long)}）を呼び出し側
     * （{@code billing.beta.LoginActivityQueryService}）が Map 化する。scalar のみ返すため {@link AuditLogEntity} を
     * 呼び出し側に露出しない（クロスドメイン Entity 参照 D-1 を回避）。</p>
     *
     * <p><b>{@code tzOffset} は per-user 版と同一の意味</b>（{@code storedZoneOffset} の格納値を当該オフセットへ
     * 変換してから日付を切る・{@code "+09:00"} 形式）。<b>1 回の呼び出しで渡せるオフセットは 1 つ</b>ゆえ、
     * 呼び出し側は<b>同一オフセットのユーザーを 1 群に束ねて</b>群ごとに 1 回だけ呼ぶ（ユーザー数に比例した
     * クエリを撃たない）。</p>
     *
     * <p><b>ログイン記録の無いユーザーは結果行に現れない</b>（GROUP BY の性質）。呼び出し側は欠損を 0 日で埋める。
     * 空の {@code userIds} は {@code IN ()} で不正 SQL になるため、呼び出し側でガードして本メソッドを呼ばない。</p>
     *
     * @param userIds         対象ユーザーID群（非空・全員が {@code tzOffset} と同一オフセット）
     * @param since           評価ウィンドウ起点（格納基準 TZ の壁時計。この日時以降のログインを数える）
     * @param storedZoneOffset {@code created_at} の格納基準 TZ（{@code "+00:00"} 形式。既定 UTC）
     * @param tzOffset        日境界を切るための変換先 数値オフセット（{@code "+09:00"} 形式）
     * @return {@code [userId, days]} の配列リスト（記録の無いユーザーは含まれない）
     */
    @Query(value = "SELECT a.user_id, COUNT(DISTINCT DATE(CONVERT_TZ(a.created_at, :storedZoneOffset, :tzOffset))) "
            + "FROM audit_logs a "
            + "WHERE a.user_id IN (:userIds) AND a.event_type = 'LOGIN_SUCCESS' AND a.created_at >= :since "
            + "GROUP BY a.user_id",
            nativeQuery = true)
    List<Object[]> countDistinctLoginDaysSinceByUsers(
            @Param("userIds") Collection<Long> userIds, @Param("since") LocalDateTime since,
            @Param("storedZoneOffset") String storedZoneOffset,
            @Param("tzOffset") String tzOffset);
}

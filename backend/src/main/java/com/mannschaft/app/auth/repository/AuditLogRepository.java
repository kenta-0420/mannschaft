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
     * {@code COUNT(DISTINCT DATE(created_at))} で数える。scalar（{@code long}）を返すため、呼び出し側
     * （{@code billing.beta.LoginActivityQueryService}）は {@code AuditLogEntity} に依存しない
     * （クロスドメイン Entity 参照 D-1 を回避）。</p>
     *
     * @param userId 対象ユーザー
     * @param since  評価ウィンドウ起点（この日時以降のログインを数える）
     * @return アクティブ日数（distinct DATE 数）
     */
    @Query("SELECT COUNT(DISTINCT FUNCTION('DATE', a.createdAt)) FROM AuditLogEntity a "
            + "WHERE a.userId = :userId AND a.eventType = 'LOGIN_SUCCESS' AND a.createdAt >= :since")
    long countDistinctLoginDaysSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /**
     * 複数ユーザーの {@code activeDays}（ログイン成功日の distinct DATE 数）を <b>1 クエリ</b>で一括集計する
     * （F20.3 Phase2 自動付与バッチの N+1 回避・設計書 F20.3 03 §6）。
     *
     * <p>{@link #countDistinctLoginDaysSince} の bulk 版。{@code GROUP BY a.userId} で userId ごとの distinct 日数を
     * 返し、{@code List<Object[]>}（{@code [0]=userId(Long), [1]=days(Long)}）を呼び出し側
     * （{@code billing.beta.LoginActivityQueryService}）が Map 化する。scalar のみ返すため {@link AuditLogEntity} を
     * 呼び出し側に露出しない（クロスドメイン Entity 参照 D-1 を回避）。</p>
     *
     * <p><b>ログイン記録の無いユーザーは結果行に現れない</b>（GROUP BY の性質）。呼び出し側は欠損を 0 日として扱う。
     * 空の {@code userIds} は {@code IN ()} で不正 SQL になるため、呼び出し側でガードして本メソッドを呼ばない。</p>
     *
     * @param userIds 対象ユーザーID群（非空）
     * @param since   評価ウィンドウ起点（この日時以降のログインを数える）
     * @return {@code [userId, days]} の配列リスト（記録の無いユーザーは含まれない）
     */
    @Query("SELECT a.userId, COUNT(DISTINCT FUNCTION('DATE', a.createdAt)) FROM AuditLogEntity a "
            + "WHERE a.userId IN :userIds AND a.eventType = 'LOGIN_SUCCESS' AND a.createdAt >= :since "
            + "GROUP BY a.userId")
    List<Object[]> countDistinctLoginDaysSinceByUsers(
            @Param("userIds") Collection<Long> userIds, @Param("since") LocalDateTime since);
}

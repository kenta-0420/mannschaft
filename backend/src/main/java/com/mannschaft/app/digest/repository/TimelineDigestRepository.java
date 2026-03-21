package com.mannschaft.app.digest.repository;

import com.mannschaft.app.digest.DigestScopeType;
import com.mannschaft.app.digest.DigestStatus;
import com.mannschaft.app.digest.entity.TimelineDigestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * タイムラインダイジェスト履歴リポジトリ。
 */
public interface TimelineDigestRepository extends JpaRepository<TimelineDigestEntity, Long> {

    /**
     * スコープ別のダイジェスト履歴をカーソルベースで取得する。
     */
    @Query("SELECT d FROM TimelineDigestEntity d " +
           "WHERE d.scopeType = :scopeType AND d.scopeId = :scopeId " +
           "AND (:status IS NULL OR d.status = :status) " +
           "AND (:cursor IS NULL OR d.id < :cursor) " +
           "ORDER BY d.createdAt DESC")
    List<TimelineDigestEntity> findByScopeWithCursor(
            @Param("scopeType") DigestScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("status") DigestStatus status,
            @Param("cursor") Long cursor);

    /**
     * 同一スコープ・同一期間のダイジェストが存在するか確認する。
     */
    @Query("SELECT COUNT(d) > 0 FROM TimelineDigestEntity d " +
           "WHERE d.scopeType = :scopeType AND d.scopeId = :scopeId " +
           "AND d.periodStart = :periodStart AND d.periodEnd = :periodEnd " +
           "AND d.status IN (:statuses)")
    boolean existsByScopeAndPeriodAndStatusIn(
            @Param("scopeType") DigestScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("periodStart") LocalDateTime periodStart,
            @Param("periodEnd") LocalDateTime periodEnd,
            @Param("statuses") List<DigestStatus> statuses);

    /**
     * 同一スコープで GENERATING 中のダイジェストが存在するか確認する。
     */
    boolean existsByScopeTypeAndScopeIdAndStatus(DigestScopeType scopeType, Long scopeId, DigestStatus status);

    /**
     * 月次 AI 生成カウント（GENERATED + PUBLISHED + GENERATING）。
     */
    @Query("SELECT COUNT(d) FROM TimelineDigestEntity d " +
           "WHERE d.scopeType = :scopeType AND d.scopeId = :scopeId " +
           "AND d.digestStyle <> com.mannschaft.app.digest.DigestStyle.TEMPLATE " +
           "AND d.status IN (:statuses) " +
           "AND d.createdAt >= :monthStart")
    long countAiDigestsInMonth(
            @Param("scopeType") DigestScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("statuses") List<DigestStatus> statuses,
            @Param("monthStart") LocalDateTime monthStart);

    /**
     * GENERATING タイムアウト検知：タイムアウト期限を超過したレコードを FAILED に遷移する。
     */
    @Modifying
    @Query("UPDATE TimelineDigestEntity d SET d.status = 'FAILED', d.errorMessage = 'Generation timed out' " +
           "WHERE d.status = 'GENERATING' AND d.generatingTimeoutAt < :now")
    int failTimedOutDigests(@Param("now") LocalDateTime now);

    /**
     * 未使用 GENERATED のクリーンアップ（30日経過）。
     */
    @Modifying
    @Query("UPDATE TimelineDigestEntity d SET d.status = 'DISCARDED' " +
           "WHERE d.status = 'GENERATED' AND d.createdAt < :threshold")
    int discardStaleDigests(@Param("threshold") LocalDateTime threshold);

    /**
     * DISCARDED の物理削除（90日経過）。
     */
    @Modifying
    @Query("DELETE FROM TimelineDigestEntity d " +
           "WHERE d.status = 'DISCARDED' AND d.createdAt < :threshold")
    int deleteOldDiscardedDigests(@Param("threshold") LocalDateTime threshold);

    /**
     * ブログ記事 ID で検索（ブログ記事削除時のステータス差し戻し用）。
     */
    Optional<TimelineDigestEntity> findByBlogPostId(Long blogPostId);

    /**
     * 同一スコープ・同一 schedule_type の直前の PUBLISHED ダイジェストを取得する（差分ハイライト用）。
     */
    @Query("SELECT d FROM TimelineDigestEntity d " +
           "WHERE d.scopeType = :scopeType AND d.scopeId = :scopeId " +
           "AND d.status = 'PUBLISHED' " +
           "ORDER BY d.createdAt DESC")
    List<TimelineDigestEntity> findLatestPublishedByScope(
            @Param("scopeType") DigestScopeType scopeType,
            @Param("scopeId") Long scopeId);
}

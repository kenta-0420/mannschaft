package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * F03.11 募集型予約: 参加者リポジトリ。
 */
public interface RecruitmentParticipantRepository extends JpaRepository<RecruitmentParticipantEntity, Long> {

    Page<RecruitmentParticipantEntity> findByListingIdOrderByAppliedAtAsc(Long listingId, Pageable pageable);

    List<RecruitmentParticipantEntity> findByListingIdAndStatusOrderByAppliedAtAsc(
            Long listingId, RecruitmentParticipantStatus status);

    Optional<RecruitmentParticipantEntity> findByListingIdAndUserIdAndStatusNot(
            Long listingId, Long userId, RecruitmentParticipantStatus status);

    Optional<RecruitmentParticipantEntity> findByListingIdAndTeamIdAndStatusNot(
            Long listingId, Long teamId, RecruitmentParticipantStatus status);

    Optional<RecruitmentParticipantEntity> findByIdAndListingId(Long id, Long listingId);

    /** 自分の有効な参加レコードを取得 (キャンセル等の本人操作で使用)。 */
    @Query("""
            SELECT p FROM RecruitmentParticipantEntity p
            WHERE p.listingId = :listingId
              AND p.userId = :userId
              AND p.status IN ('APPLIED', 'CONFIRMED', 'WAITLISTED')
            """)
    Optional<RecruitmentParticipantEntity> findActiveByListingAndUser(
            @Param("listingId") Long listingId, @Param("userId") Long userId);

    /** 行ロック付きで取得 (本人キャンセル時の整合性確保用)。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM RecruitmentParticipantEntity p WHERE p.id = :id")
    Optional<RecruitmentParticipantEntity> findByIdForUpdate(@Param("id") Long id);

    /** 自分の参加履歴一覧 (個人マイページ §9.4)。 */
    @Query("""
            SELECT p FROM RecruitmentParticipantEntity p
            WHERE p.userId = :userId
              AND p.status IN ('APPLIED', 'CONFIRMED', 'WAITLISTED')
            ORDER BY p.appliedAt DESC
            """)
    List<RecruitmentParticipantEntity> findMyActiveParticipations(@Param("userId") Long userId);

    /**
     * Phase 4 レート制限: 指定ユーザーの最近の申込件数をカウント。
     */
    @Query("""
            SELECT COUNT(p) FROM RecruitmentParticipantEntity p
            WHERE p.userId = :userId
              AND p.appliedAt >= :since
            """)
    long countRecentApplicationsByUser(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /**
     * §5.4 自動キャンセルバッチ: 指定募集の指定ステータスの参加者をページング取得。
     * 100件/チャンクで処理するために Pageable を使う。
     */
    Page<RecruitmentParticipantEntity> findByListingIdAndStatusIn(
            Long listingId,
            List<RecruitmentParticipantStatus> statuses,
            Pageable pageable);

    /**
     * §5.3 キャンセル待ちの先頭1件をロック取得（昇格用）。
     * PESSIMISTIC_WRITE を使い、同一トランザクション内で重複昇格を防ぐ。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p FROM RecruitmentParticipantEntity p
            WHERE p.listingId = :listingId
              AND p.status = com.mannschaft.app.recruitment.RecruitmentParticipantStatus.WAITLISTED
            ORDER BY p.waitlistPosition ASC
            LIMIT 1
            """)
    Optional<RecruitmentParticipantEntity> findFirstWaitlistedForUpdate(@Param("listingId") Long listingId);

    /**
     * Phase 5b 自動検出バッチ用: 指定スコープの終了済み募集でまだ CONFIRMED の参加者を取得。
     * 募集の endAt が :threshold 以前のものが対象。
     */
    @Query("""
            SELECT p FROM RecruitmentParticipantEntity p
            JOIN RecruitmentListingEntity l ON l.id = p.listingId
            WHERE l.scopeType = :scopeType
              AND l.scopeId = :scopeId
              AND l.endAt <= :threshold
              AND p.status = com.mannschaft.app.recruitment.RecruitmentParticipantStatus.CONFIRMED
              AND p.userId IS NOT NULL
            """)
    List<RecruitmentParticipantEntity> findConfirmedInEndedListings(
            @Param("scopeType") com.mannschaft.app.recruitment.RecruitmentScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("threshold") LocalDateTime threshold);
}

package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * F03.11 Phase 5b: 無断キャンセル記録リポジトリ。
 */
public interface RecruitmentNoShowRecordRepository extends JpaRepository<RecruitmentNoShowRecordEntity, Long> {

    List<RecruitmentNoShowRecordEntity> findByUserId(Long userId);

    Optional<RecruitmentNoShowRecordEntity> findByParticipantId(Long participantId);

    /** 確定済みNO_SHOWのうち指定期間内のユーザー件数（ペナルティ閾値判定用）。 */
    @Query("""
            SELECT COUNT(r) FROM RecruitmentNoShowRecordEntity r
            WHERE r.userId = :userId
              AND r.confirmed = true
              AND (r.disputeResolution <> 'REVOKED' OR r.disputeResolution IS NULL)
              AND r.recordedAt >= :since
            """)
    long countConfirmedNoShows(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /** 仮マーク（confirmed=FALSE）かつ指定時間を経過したレコード（確定バッチ用）。 */
    @Query("""
            SELECT r FROM RecruitmentNoShowRecordEntity r
            WHERE r.confirmed = false
              AND r.recordedAt <= :before
            """)
    List<RecruitmentNoShowRecordEntity> findUnconfirmedBefore(@Param("before") LocalDateTime before);

    /** スコープ内の NO_SHOW 記録一覧（管理者用）。 */
    @Query("""
            SELECT r FROM RecruitmentNoShowRecordEntity r
            JOIN RecruitmentListingEntity l ON l.id = r.listingId
            WHERE l.scopeType = :scopeType
              AND l.scopeId = :scopeId
            ORDER BY r.recordedAt DESC
            """)
    List<RecruitmentNoShowRecordEntity> findByScopeTypeAndScopeId(
            @Param("scopeType") com.mannschaft.app.recruitment.RecruitmentScopeType scopeType,
            @Param("scopeId") Long scopeId);
}

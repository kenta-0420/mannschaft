package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * F03.11 Phase 5b: ユーザーペナルティリポジトリ。
 */
public interface RecruitmentUserPenaltyRepository extends JpaRepository<RecruitmentUserPenaltyEntity, Long> {

    /** アクティブペナルティの取得（liftedAt IS NULL かつ expiresAt 未来）。 */
    @Query("""
            SELECT p FROM RecruitmentUserPenaltyEntity p
            WHERE p.userId = :userId
              AND p.scopeType = :scopeType
              AND p.scopeId = :scopeId
              AND p.liftedAt IS NULL
              AND p.expiresAt > :now
            """)
    Optional<RecruitmentUserPenaltyEntity> findActivePenalty(
            @Param("userId") Long userId,
            @Param("scopeType") RecruitmentScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("now") LocalDateTime now);

    /** 行ロック付きでアクティブペナルティ取得（PESSIMISTIC_WRITE 用）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p FROM RecruitmentUserPenaltyEntity p
            WHERE p.userId = :userId
              AND p.scopeType = :scopeType
              AND p.scopeId = :scopeId
              AND p.liftedAt IS NULL
              AND p.expiresAt > :now
            """)
    Optional<RecruitmentUserPenaltyEntity> findActivePenaltyForUpdate(
            @Param("userId") Long userId,
            @Param("scopeType") RecruitmentScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("now") LocalDateTime now);

    /** 期限切れかつ未解除のペナルティ（自動解除バッチ用）。 */
    @Query("""
            SELECT p FROM RecruitmentUserPenaltyEntity p
            WHERE p.liftedAt IS NULL
              AND p.expiresAt <= :now
            """)
    List<RecruitmentUserPenaltyEntity> findExpiredPenalties(@Param("now") LocalDateTime now);

    /** ユーザーの全ペナルティ履歴（マイページ用）。 */
    List<RecruitmentUserPenaltyEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** スコープ内のアクティブペナルティ一覧（管理者用）。 */
    @Query("""
            SELECT p FROM RecruitmentUserPenaltyEntity p
            WHERE p.scopeType = :scopeType
              AND p.scopeId = :scopeId
              AND p.liftedAt IS NULL
              AND p.expiresAt > :now
            ORDER BY p.createdAt DESC
            """)
    List<RecruitmentUserPenaltyEntity> findActivePenaltiesByScope(
            @Param("scopeType") RecruitmentScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("now") LocalDateTime now);
}

package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
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

    /**
     * アクティブペナルティを <b>キーセットページング</b>（{@code id > cursor}）で id 昇順に取得する
     * （再計算バッチ用）。
     *
     * <p>このバッチはループ内で解除条件を満たした行の {@code liftedAt} をセットするため、
     * その行は次回の絞り込み（{@code liftedAt IS NULL}）から外れる。OFFSET ページングで
     * 「取得済み件数ぶん進める」方式にすると、母集合が縮んだ分だけ後続の行が
     * OFFSET の網から漏れて読み飛ばされる（解除すべきペナルティが解除されないまま残る）。
     * カーソルを直前チャンクの最終 {@code id} まで前進させることで、この読み飛ばしを防ぐ。</p>
     *
     * @param now      現在日時
     * @param cursor   直前チャンクの最終 ID（初回は 0）
     * @param pageable ページング情報（サイズのみ使用。ソートは本クエリで固定）
     */
    @Query("""
            SELECT p FROM RecruitmentUserPenaltyEntity p
            WHERE p.liftedAt IS NULL
              AND p.expiresAt > :now
              AND p.id > :cursor
            ORDER BY p.id ASC
            """)
    List<RecruitmentUserPenaltyEntity> findActivePenaltiesAfterId(
            @Param("now") LocalDateTime now,
            @Param("cursor") Long cursor,
            Pageable pageable);

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

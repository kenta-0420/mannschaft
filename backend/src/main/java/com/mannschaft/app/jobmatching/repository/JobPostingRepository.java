package com.mannschaft.app.jobmatching.repository;

import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.visibility.JobPostingVisibilityProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 求人投稿リポジトリ。
 */
public interface JobPostingRepository extends JpaRepository<JobPostingEntity, Long> {

    /**
     * チーム配下のステータス別求人一覧を新しい順で取得する。
     */
    List<JobPostingEntity> findByTeamIdAndStatusOrderByCreatedAtDesc(Long teamId, JobPostingStatus status);

    /**
     * チーム配下のステータス別求人一覧をページング取得する（Service の一覧 API 用）。
     */
    Page<JobPostingEntity> findByTeamIdAndStatus(Long teamId, JobPostingStatus status, Pageable pageable);

    /**
     * チーム配下の求人一覧をページング取得する（status 無指定）。
     */
    Page<JobPostingEntity> findByTeamId(Long teamId, Pageable pageable);

    /**
     * 投稿者の求人一覧を新しい順で取得する。
     */
    List<JobPostingEntity> findByCreatedByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 投稿者の求人一覧をページング取得する（マイ投稿画面用）。
     */
    Page<JobPostingEntity> findByCreatedByUserId(Long userId, Pageable pageable);

    /**
     * チーム内の求人をIDで取得する（チーム越権アクセス防止）。
     */
    Optional<JobPostingEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * 求人を排他ロック付きで取得する（応募締切判定・採用確定等の競合制御用）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM JobPostingEntity p WHERE p.id = :id")
    Optional<JobPostingEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * F00 共通可視性基盤（{@link com.mannschaft.app.jobmatching.visibility.JobPostingVisibilityResolver}）
     * 向けのバルク射影取得。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} は {@link JobPostingEntity} に付与されているが、
     * constructor expression を使う本クエリでは適用されないため WHERE 句で明示的に
     * {@code deleted_at IS NULL} を指定する。</p>
     *
     * <p>SQL 1 本で {@link JobPostingVisibilityProjection} を生成し、N+1 を防ぐ。
     * {@code job_postings} は {@code team_id} のみを持つため scopeType は常に {@code 'TEAM'} で固定する。</p>
     *
     * @param ids 射影対象 job_posting_id 集合（空でない）
     * @return 実存する求人投稿の Projection リスト
     */
    @Query("""
            SELECT new com.mannschaft.app.jobmatching.visibility.JobPostingVisibilityProjection(
                p.id,
                'TEAM',
                p.teamId,
                p.createdByUserId,
                p.status,
                p.visibilityScope)
            FROM JobPostingEntity p
            WHERE p.id IN :ids AND p.deletedAt IS NULL
            """)
    List<JobPostingVisibilityProjection> findVisibilityProjectionsByIdIn(
            @Param("ids") Collection<Long> ids);

    /**
     * CMP-028 Phase C: チーム求人一覧の SQL 述語版（{@code JobPostingService#listByTeamForViewer} 用）。
     *
     * <p>{@code MembershipBatchQueryService#resolveVisibleLevels} が返したラダー集合を
     * {@link com.mannschaft.app.common.visibility.mapping.JobMatchingVisibilityMapper#toFunctional}
     * で機能 enum に逆写像した {@code visibilities} と、{@code JOBBER_INTERNAL}
     * （{@code StandardVisibility.CUSTOM}）の個別述語を OR で組み合わせる。</p>
     *
     * <p><b>{@code JOBBER_INTERNAL} の SQL 述語</b>:
     * {@code JobPostingVisibilityResolver#evaluateCustom} と同一の判定
     * （「対象求人のチームで viewer が {@code JOBBER} ロールを保有するか」）を
     * {@code user_roles × roles} への {@code EXISTS} サブクエリとして書き下す。</p>
     *
     * <p><b>{@code CUSTOM_TEMPLATE} は意図的に対象外</b>: テンプレート評価は行ごとの動的判定が
     * 必要で SQL 述語に落とせない。現行 MVP では {@code JobPostingService.MVP_ALLOWED_SCOPES}
     * が書き込みを禁止しており到達しないため、{@code visibilities} にも専用 OR 分岐にも
     * 現れない（fail-closed。殿の判断待ち、設計書の当該節を参照）。</p>
     *
     * <p><b>status 正規化（{@code JobPostingVisibilityResolver#mapStatus} と同一の意味論）</b>:</p>
     * <ul>
     *   <li>{@code DRAFT}: 作成者本人または SystemAdmin のみ可視</li>
     *   <li>{@code OPEN/CLOSED}（PUBLISHED 区分）: visibility ラダー述語 OR JOBBER_INTERNAL
     *       EXISTS 述語 OR SystemAdmin</li>
     *   <li>{@code CANCELLED}（ARCHIVED 区分）: SystemAdmin のみ可視</li>
     * </ul>
     *
     * @param teamId              チーム ID
     * @param statusFilter        絞り込みステータス（{@code null} で全ステータス対象）
     * @param visibilities        {@code JobMatchingVisibilityMapper#toFunctional} が返した
     *                            可視ラダーの enum 集合（呼び出し側で非空を保証する）
     * @param viewerUserId        閲覧者 user_id（{@code null} 可、未認証）
     * @param viewerIsSystemAdmin 閲覧者が SystemAdmin か
     * @param pageable            ページング指定
     * @return 閲覧者に可視な求人エンティティのページ
     */
    @Query("""
            SELECT p FROM JobPostingEntity p
            WHERE p.teamId = :teamId
              AND (:statusFilter IS NULL OR p.status = :statusFilter)
              AND (
                (p.status = com.mannschaft.app.jobmatching.enums.JobPostingStatus.DRAFT
                    AND (p.createdByUserId = :viewerUserId OR :viewerIsSystemAdmin = true))
                OR (p.status IN (com.mannschaft.app.jobmatching.enums.JobPostingStatus.OPEN,
                                  com.mannschaft.app.jobmatching.enums.JobPostingStatus.CLOSED)
                    AND (
                      p.visibilityScope IN :visibilities
                      OR :viewerIsSystemAdmin = true
                      OR (p.visibilityScope = com.mannschaft.app.jobmatching.enums.VisibilityScope.JOBBER_INTERNAL
                          AND EXISTS (
                              SELECT 1 FROM com.mannschaft.app.role.entity.UserRoleEntity ur
                              JOIN com.mannschaft.app.role.entity.RoleEntity r ON r.id = ur.roleId
                              WHERE ur.userId = :viewerUserId AND ur.teamId = p.teamId AND r.name = 'JOBBER'
                          ))
                    ))
                OR (p.status = com.mannschaft.app.jobmatching.enums.JobPostingStatus.CANCELLED
                    AND :viewerIsSystemAdmin = true)
              )
            ORDER BY p.createdAt DESC
            """)
    Page<JobPostingEntity> findVisibleByTeamId(
            @Param("teamId") Long teamId,
            @Param("statusFilter") JobPostingStatus statusFilter,
            @Param("visibilities") Collection<VisibilityScope> visibilities,
            @Param("viewerUserId") Long viewerUserId,
            @Param("viewerIsSystemAdmin") boolean viewerIsSystemAdmin,
            Pageable pageable);
}

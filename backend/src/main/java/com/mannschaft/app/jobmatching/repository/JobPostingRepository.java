package com.mannschaft.app.jobmatching.repository;

import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
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
}

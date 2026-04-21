package com.mannschaft.app.jobmatching.repository;

import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * 投稿者の求人一覧を新しい順で取得する。
     */
    List<JobPostingEntity> findByCreatedByUserIdOrderByCreatedAtDesc(Long userId);

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
}

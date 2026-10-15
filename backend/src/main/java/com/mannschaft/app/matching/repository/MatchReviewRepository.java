package com.mannschaft.app.matching.repository;

import com.mannschaft.app.matching.entity.MatchReviewEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * レビューリポジトリ。
 */
public interface MatchReviewRepository extends JpaRepository<MatchReviewEntity, Long> {

    /**
     * 同一proposalへのレビュー重複チェック。
     */
    boolean existsByProposalIdAndReviewerTeamId(Long proposalId, Long reviewerTeamId);

    /**
     * チームのレビュー一覧（直近2年間）。
     */
    Page<MatchReviewEntity> findByRevieweeTeamIdAndCreatedAtAfterOrderByCreatedAtDesc(
            Long revieweeTeamId, LocalDateTime since, Pageable pageable);

    /**
     * チームの平均評価（直近2年間）。
     */
    @Query("""
            SELECT COALESCE(ROUND(AVG(CAST(mr.rating AS double)), 1), 0)
            FROM MatchReviewEntity mr
            WHERE mr.revieweeTeamId = :teamId
              AND mr.createdAt >= :since
            """)
    Double findAverageRating(@Param("teamId") Long teamId, @Param("since") LocalDateTime since);

    /**
     * チームのレビュー数（直近2年間）。
     */
    long countByRevieweeTeamIdAndCreatedAtAfter(Long revieweeTeamId, LocalDateTime since);
}

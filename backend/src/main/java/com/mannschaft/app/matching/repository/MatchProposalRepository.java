package com.mannschaft.app.matching.repository;

import com.mannschaft.app.matching.MatchProposalStatus;
import com.mannschaft.app.matching.entity.MatchProposalEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 応募リポジトリ。
 */
public interface MatchProposalRepository extends JpaRepository<MatchProposalEntity, Long> {

    /**
     * 募集への応募一覧を取得する。
     */
    Page<MatchProposalEntity> findByRequestIdOrderByCreatedAtDesc(Long requestId, Pageable pageable);

    /**
     * チームの応募一覧を取得する。
     */
    Page<MatchProposalEntity> findByProposingTeamIdOrderByCreatedAtDesc(Long proposingTeamId, Pageable pageable);

    /**
     * チームの応募一覧をステータスで絞り込む。
     */
    Page<MatchProposalEntity> findByProposingTeamIdAndStatusOrderByCreatedAtDesc(
            Long proposingTeamId, MatchProposalStatus status, Pageable pageable);

    /**
     * 同一募集・同一チームの応募が存在するか確認する。
     */
    boolean existsByRequestIdAndProposingTeamId(Long requestId, Long proposingTeamId);

    /**
     * 募集IDとPENDINGステータスの応募を取得する。
     */
    List<MatchProposalEntity> findByRequestIdAndStatus(Long requestId, MatchProposalStatus status);

    /**
     * チームのキャンセル履歴（一方的キャンセルのみ、直近2年間）。
     */
    @Query("""
            SELECT mp FROM MatchProposalEntity mp
            WHERE mp.cancelledByTeamId = :teamId
              AND mp.status = 'CANCELLED'
              AND mp.cancellationType != 'MUTUAL'
              AND mp.updatedAt >= :since
            ORDER BY mp.updatedAt DESC
            """)
    Page<MatchProposalEntity> findCancellationsByTeam(
            @Param("teamId") Long teamId,
            @Param("since") LocalDateTime since,
            Pageable pageable);

    /**
     * チームが実行した一方的キャンセル数（直近2年間）。
     */
    @Query("""
            SELECT COUNT(mp) FROM MatchProposalEntity mp
            WHERE mp.cancelledByTeamId = :teamId
              AND mp.status = 'CANCELLED'
              AND mp.cancellationType != 'MUTUAL'
              AND mp.updatedAt >= :since
            """)
    long countCancellationsByTeam(@Param("teamId") Long teamId, @Param("since") LocalDateTime since);

    /**
     * 合意キャンセル期限切れのMUTUAL_PENDINGを取得する。
     */
    @Query("""
            SELECT mp FROM MatchProposalEntity mp
            WHERE mp.status = 'CANCELLED'
              AND mp.cancellationType = 'MUTUAL_PENDING'
              AND mp.updatedAt < :deadline
            """)
    List<MatchProposalEntity> findExpiredMutualPending(@Param("deadline") LocalDateTime deadline);
}

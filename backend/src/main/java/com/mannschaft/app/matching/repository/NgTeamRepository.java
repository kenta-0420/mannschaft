package com.mannschaft.app.matching.repository;

import com.mannschaft.app.matching.entity.NgTeamEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * NGチームリポジトリ。
 */
public interface NgTeamRepository extends JpaRepository<NgTeamEntity, Long> {

    /**
     * チームのNGリストを取得する。
     */
    List<NgTeamEntity> findByTeamIdOrderByCreatedAtDesc(Long teamId);

    /**
     * 特定のNGペアが存在するか確認する。
     */
    boolean existsByTeamIdAndBlockedTeamId(Long teamId, Long blockedTeamId);

    /**
     * 特定のNGペアを取得する。
     */
    Optional<NgTeamEntity> findByTeamIdAndBlockedTeamId(Long teamId, Long blockedTeamId);

    /**
     * 双方向のNGチームIDリストを取得する（募集検索フィルタ用）。
     */
    @Query("""
            SELECT ng.blockedTeamId FROM NgTeamEntity ng WHERE ng.teamId = :teamId
            UNION
            SELECT ng.teamId FROM NgTeamEntity ng WHERE ng.blockedTeamId = :teamId
            """)
    List<Long> findBidirectionalBlockedTeamIds(@Param("teamId") Long teamId);
}

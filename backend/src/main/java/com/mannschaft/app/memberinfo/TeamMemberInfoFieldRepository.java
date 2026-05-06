package com.mannschaft.app.memberinfo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TeamMemberInfoFieldRepository extends JpaRepository<TeamMemberInfoFieldEntity, Long> {

    List<TeamMemberInfoFieldEntity> findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(Long teamId);

    long countByTeamId(Long teamId);

    Optional<TeamMemberInfoFieldEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * refreshIntervalMonths が設定されているアクティブフィールドを持つ
     * チームID一覧を重複なしで取得する（バッチリマインド用）。
     */
    @Query("SELECT DISTINCT f.teamId FROM TeamMemberInfoFieldEntity f " +
            "WHERE f.refreshIntervalMonths IS NOT NULL AND f.isActive = true")
    List<Long> findDistinctTeamIdsWithRefreshInterval();
}

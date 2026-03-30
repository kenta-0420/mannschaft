package com.mannschaft.app.gamification.repository;

import com.mannschaft.app.gamification.PeriodType;
import com.mannschaft.app.gamification.entity.RankingSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * ランキングスナップショットリポジトリ。
 */
public interface RankingSnapshotRepository extends JpaRepository<RankingSnapshotEntity, Long> {

    /**
     * スコープ・期間種別・期間ラベルでランキングを順位昇順で取得する。
     */
    List<RankingSnapshotEntity> findByScopeTypeAndScopeIdAndPeriodTypeAndPeriodLabelOrderByRankPositionAsc(
            String scopeType, Long scopeId, PeriodType periodType, String periodLabel);
}

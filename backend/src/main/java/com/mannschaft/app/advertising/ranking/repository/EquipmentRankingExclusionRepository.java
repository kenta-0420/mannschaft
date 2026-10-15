package com.mannschaft.app.advertising.ranking.repository;

import com.mannschaft.app.advertising.ranking.entity.EquipmentRankingExclusionEntity;
import com.mannschaft.app.advertising.ranking.entity.ExclusionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * 備品ランキング除外設定リポジトリ。
 */
public interface EquipmentRankingExclusionRepository
        extends JpaRepository<EquipmentRankingExclusionEntity, Long> {

    /**
     * オプトアウト済みチームの team_id 一覧を取得する（バッチ集計で使用）。
     *
     * @return オプトアウト済みチームIDリスト
     */
    @Query("SELECT e.teamId FROM EquipmentRankingExclusionEntity e WHERE e.exclusionType = 'TEAM_OPT_OUT'")
    List<Long> findOptOutTeamIds();

    /**
     * 除外対象の正規化済み備品名一覧を取得する（バッチ集計で使用）。
     *
     * @return 除外対象備品名リスト
     */
    @Query("SELECT e.normalizedName FROM EquipmentRankingExclusionEntity e WHERE e.exclusionType = 'ITEM_EXCLUSION'")
    List<String> findExcludedNormalizedNames();

    /**
     * チームのオプトアウト状態を確認する。
     *
     * @param teamId        チームID
     * @param exclusionType 除外種別
     * @return オプトアウト済みの場合 true
     */
    boolean existsByTeamIdAndExclusionType(Long teamId, ExclusionType exclusionType);

    /**
     * チームのオプトアウトレコードを取得する。
     *
     * @param teamId        チームID
     * @param exclusionType 除外種別
     * @return 除外設定（存在しない場合は空）
     */
    Optional<EquipmentRankingExclusionEntity> findByTeamIdAndExclusionType(
            Long teamId, ExclusionType exclusionType);

    /**
     * 全件を作成日時降順で取得する（SYSTEM_ADMIN 管理画面用）。
     *
     * @return 除外設定リスト（作成日時降順）
     */
    List<EquipmentRankingExclusionEntity> findAllByOrderByCreatedAtDesc();

    /**
     * 指定種別の除外設定件数を返す（統計情報用）。
     *
     * @param exclusionType 除外種別
     * @return 件数
     */
    int countByExclusionType(ExclusionType exclusionType);
}

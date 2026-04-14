package com.mannschaft.app.advertising.ranking.repository;

import com.mannschaft.app.advertising.ranking.entity.EquipmentRankingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 備品ランキングリポジトリ。
 */
public interface EquipmentRankingRepository extends JpaRepository<EquipmentRankingEntity, Long> {

    /**
     * チームテンプレート＋カテゴリで順位昇順に取得する。
     *
     * @param teamTemplate チームテンプレート
     * @param category     カテゴリ
     * @return ランキングリスト（順位昇順）
     */
    List<EquipmentRankingEntity> findByTeamTemplateAndCategoryOrderByRankAsc(
            String teamTemplate, String category);

    /**
     * 全件削除（バッチ再構築用）。
     */
    @Modifying
    @Query("DELETE FROM EquipmentRankingEntity")
    void deleteAll();

    /**
     * ランキングデータが存在するチームテンプレートの一覧を取得する。
     *
     * @return チームテンプレート一覧（重複なし）
     */
    @Query("SELECT DISTINCT r.teamTemplate FROM EquipmentRankingEntity r")
    List<String> findDistinctTeamTemplates();

    /**
     * 最終集計日時を取得する。
     *
     * @return 最終集計日時（データなしの場合は空）
     */
    @Query("SELECT MAX(r.calculatedAt) FROM EquipmentRankingEntity r")
    Optional<LocalDateTime> findLastCalculatedAt();

    /**
     * 指定チーム数以上の team_count を持つランキングアイテム数を返す。
     *
     * @param minCount 最小チーム数
     * @return 件数
     */
    @Query("SELECT COUNT(r) FROM EquipmentRankingEntity r WHERE r.teamCount >= :minCount")
    long countByTeamCountGreaterThanEqual(@Param("minCount") int minCount);

    /**
     * 指定チーム数未満の team_count を持つランキングアイテム数を返す（統計情報用）。
     *
     * @param threshold しきい値チーム数
     * @return 件数
     */
    @Query("SELECT COUNT(r) FROM EquipmentRankingEntity r WHERE r.teamCount < :threshold")
    long countByTeamCountLessThan(@Param("threshold") int threshold);

    /**
     * ASIN が設定されているランキングアイテム数を返す（統計情報用）。
     *
     * @return 件数
     */
    @Query("SELECT COUNT(r) FROM EquipmentRankingEntity r WHERE r.amazonAsin IS NOT NULL")
    long countByAmazonAsinIsNotNull();
}

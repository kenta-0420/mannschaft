package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.entity.AdDailyStatsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface AdDailyStatsRepository extends JpaRepository<AdDailyStatsEntity, Long> {

    List<AdDailyStatsEntity> findByCampaignIdAndDateBetween(Long campaignId, LocalDate from, LocalDate to);

    List<AdDailyStatsEntity> findByAdIdAndDateBetween(Long adId, LocalDate from, LocalDate to);

    @Query("SELECT s FROM AdDailyStatsEntity s WHERE s.campaignId IN :campaignIds AND s.date BETWEEN :from AND :to")
    List<AdDailyStatsEntity> findByCampaignIdsAndDateBetween(
            @Param("campaignIds") List<Long> campaignIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(s.cost), 0) FROM AdDailyStatsEntity s WHERE s.campaignId IN :campaignIds AND s.date BETWEEN :from AND :to")
    BigDecimal sumCostByCampaignIdsAndDateBetween(
            @Param("campaignIds") List<Long> campaignIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT s FROM AdDailyStatsEntity s WHERE s.date BETWEEN :from AND :to")
    List<AdDailyStatsEntity> findByDateBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * F09.19.3 日次集計 UPSERT: {@code uk_campaign_ad_date (campaign_id, ad_id, date)} を利用して
     * 冪等に集計値を書き込む（正本 §7.3）。同一キーが存在すれば impressions / clicks / cost を上書きする
     * ため、バッチ再実行で行数・金額が変化しない（§16 AC-3.1）。
     */
    @Modifying
    @Query(value = "INSERT INTO ad_daily_stats "
            + "(campaign_id, ad_id, date, impressions, clicks, cost, created_at, updated_at) "
            + "VALUES (:campaignId, :adId, :date, :impressions, :clicks, :cost, NOW(), NOW()) "
            + "ON DUPLICATE KEY UPDATE "
            + "impressions = VALUES(impressions), clicks = VALUES(clicks), cost = VALUES(cost), "
            + "updated_at = NOW()", nativeQuery = true)
    void upsertDailyStat(
            @Param("campaignId") Long campaignId,
            @Param("adId") Long adId,
            @Param("date") LocalDate date,
            @Param("impressions") long impressions,
            @Param("clicks") long clicks,
            @Param("cost") BigDecimal cost);
}

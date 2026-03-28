package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.entity.AdDailyStatsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
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
}

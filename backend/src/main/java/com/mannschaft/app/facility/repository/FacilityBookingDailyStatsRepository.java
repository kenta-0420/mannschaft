package com.mannschaft.app.facility.repository;

import com.mannschaft.app.facility.entity.FacilityBookingDailyStatsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 施設予約日次統計リポジトリ。
 */
public interface FacilityBookingDailyStatsRepository extends JpaRepository<FacilityBookingDailyStatsEntity, Long> {

    List<FacilityBookingDailyStatsEntity> findByScopeTypeAndScopeIdAndStatDateBetween(
            String scopeType, Long scopeId, LocalDate from, LocalDate to);

    @Query("SELECT COALESCE(SUM(s.revenueTotal), 0) FROM FacilityBookingDailyStatsEntity s "
            + "WHERE s.scopeType = :scopeType AND s.scopeId = :scopeId")
    BigDecimal sumRevenueTotal(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);

    @Query("SELECT COALESCE(SUM(s.platformFeeTotal), 0) FROM FacilityBookingDailyStatsEntity s "
            + "WHERE s.scopeType = :scopeType AND s.scopeId = :scopeId")
    BigDecimal sumPlatformFeeTotal(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);
}

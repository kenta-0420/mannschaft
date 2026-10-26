package com.mannschaft.app.common.repository;

import com.mannschaft.app.common.entity.HolidayMasterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 祝日マスタリポジトリ。
 */
public interface HolidayMasterRepository extends JpaRepository<HolidayMasterEntity, Long> {

    /**
     * 指定日がシステム共通の祝日か判定する。
     */
    boolean existsByScopeTypeAndScopeIdAndHolidayDate(String scopeType, Long scopeId, LocalDate holidayDate);

    /**
     * 指定日がシステム共通または指定スコープの祝日か判定する。
     */
    @Query("SELECT COUNT(h) > 0 FROM HolidayMasterEntity h " +
            "WHERE h.holidayDate = :date " +
            "AND ((h.scopeType = 'SYSTEM' AND h.scopeId = 0) " +
            "     OR (h.scopeType = :scopeType AND h.scopeId = :scopeId))")
    boolean isHoliday(@Param("date") LocalDate date,
                      @Param("scopeType") String scopeType,
                      @Param("scopeId") Long scopeId);

    /**
     * 期間内の祝日一覧を取得する（システム共通 + 指定スコープ）。
     */
    @Query("SELECT h FROM HolidayMasterEntity h " +
            "WHERE h.holidayDate BETWEEN :from AND :to " +
            "AND ((h.scopeType = 'SYSTEM' AND h.scopeId = 0) " +
            "     OR (h.scopeType = :scopeType AND h.scopeId = :scopeId)) " +
            "ORDER BY h.holidayDate")
    List<HolidayMasterEntity> findHolidaysInRange(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * システム共通の祝日一覧を年度で取得する。
     */
    @Query("SELECT h FROM HolidayMasterEntity h " +
            "WHERE h.scopeType = 'SYSTEM' AND h.scopeId = 0 " +
            "AND YEAR(h.holidayDate) = :year ORDER BY h.holidayDate")
    List<HolidayMasterEntity> findSystemHolidaysByYear(@Param("year") int year);
}

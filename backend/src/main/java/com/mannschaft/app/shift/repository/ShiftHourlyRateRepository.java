package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * シフト時給設定リポジトリ。
 */
public interface ShiftHourlyRateRepository extends JpaRepository<ShiftHourlyRateEntity, Long> {

    /**
     * ユーザーとチームの時給履歴を適用開始日降順で取得する。
     */
    List<ShiftHourlyRateEntity> findByUserIdAndTeamIdOrderByEffectiveFromDesc(Long userId, Long teamId);

    /**
     * 特定日時点で有効な時給を取得する。
     */
    @Query("SELECT r FROM ShiftHourlyRateEntity r WHERE r.userId = :userId AND r.teamId = :teamId " +
            "AND r.effectiveFrom <= :date ORDER BY r.effectiveFrom DESC LIMIT 1")
    Optional<ShiftHourlyRateEntity> findEffectiveRate(
            @Param("userId") Long userId,
            @Param("teamId") Long teamId,
            @Param("date") LocalDate date);
}

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
     * ユーザー・チーム・適用開始日が完全一致する時給設定を取得する（CMP-260910-1555）。
     *
     * <p>{@code uq_shr_user_team_from (user_id, team_id, effective_from)} と同じ組であり、
     * 登録時に「同じ適用開始日の既存行があるか」を判定して更新に振り分けるために使う。
     * これが無いと、当日登録した時給の打ち間違いを同じ日付で訂正する操作が
     * 一意制約違反で必ず失敗する。</p>
     */
    Optional<ShiftHourlyRateEntity> findByUserIdAndTeamIdAndEffectiveFrom(
            Long userId, Long teamId, LocalDate effectiveFrom);

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

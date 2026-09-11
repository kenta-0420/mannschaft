package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
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
     * 時給を「同じ適用開始日なら訂正、無ければ追加」で原子的に書き込む（CMP-260910-1555）。
     *
     * <p><b>なぜ SELECT してから INSERT/UPDATE を分岐してはいけないか</b>:
     * 「既存を探して、無ければ INSERT」は読みと書きの間に窓が開く。同じ
     * {@code (user_id, team_id, effective_from)} への初回リクエストが並行すると、
     * 両方が「既存なし」を見てから両方が INSERT へ進み、片方が
     * {@code uq_shr_user_team_from} 違反で失敗する。二重送信やリトライで普通に起きるため、
     * アプリ層の分岐では冪等性を保証できない。</p>
     *
     * <p>そこで一意制約そのものに衝突解決させる。{@code ON DUPLICATE KEY UPDATE} は
     * MySQL が行ロックのもとで判定するので、並行しても必ずどちらか一方が INSERT、
     * もう一方が UPDATE になり、制約違反は発生しない。</p>
     *
     * <p>{@code created_at} / {@code updated_at} は列に指定しない。DDL が
     * {@code DEFAULT CURRENT_TIMESTAMP} と {@code ON UPDATE CURRENT_TIMESTAMP} を
     * 持っており DB 側が入れるため、アプリ側で時刻を作る必要がない
     * （引数なし {@code LocalDateTime.now()} は番人 {@code DateTimeAndZoneGuardTest} が禁じている）。</p>
     *
     * @param userId        対象ユーザーID
     * @param teamId        チームID
     * @param hourlyRate    時給
     * @param effectiveFrom 適用開始日
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO shift_hourly_rates (user_id, team_id, hourly_rate, effective_from) "
            + "VALUES (:userId, :teamId, :hourlyRate, :effectiveFrom) "
            + "ON DUPLICATE KEY UPDATE hourly_rate = :hourlyRate",
            nativeQuery = true)
    void upsertHourlyRate(@Param("userId") Long userId,
                          @Param("teamId") Long teamId,
                          @Param("hourlyRate") BigDecimal hourlyRate,
                          @Param("effectiveFrom") LocalDate effectiveFrom);

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

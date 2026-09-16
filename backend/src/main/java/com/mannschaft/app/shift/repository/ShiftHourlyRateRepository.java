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

    /**
     * チーム全員ぶんの「基準日時点で有効な時給」を 1 クエリで取得する（CMP-260912-1525）。
     *
     * <p>{@link #findEffectiveRate} をメンバーの人数ぶん呼ぶと、1 画面で N クエリ・
     * N 往復になる。ユーザーごとに「基準日以下で最も新しい適用開始日」の行だけを
     * 相関副問い合わせで選び、1 回で返す。</p>
     *
     * <p>一意制約 {@code uq_shr_user_team_from (user_id, team_id, effective_from)} が
     * あるため、ユーザーごとに該当行はちょうど 1 行に定まる。</p>
     *
     * <h2>なぜ userIds で絞るのか（Codex 検分 P1）</h2>
     * <p>{@code teamId} だけで引くと、<b>時給を設定されたあとにチームを脱退した元メンバーの
     * 金銭情報まで返る</b>。単数取得の経路は {@code checkHourlyRateAccess} が対象ユーザーの
     * 現在の所属まで確認しており、一括化でその絞り込みが落ちると認可の回帰になる。
     * 呼び出し元は在籍中メンバーの ID を渡すこと（{@code AccessControlService#listActiveMemberIds}）。</p>
     *
     * <p>検索は {@code idx_shr_team_user_from (team_id, user_id, effective_from)} が支える。
     * 既存の一意インデックスは左端が {@code user_id} のため {@code r.teamId = :teamId} を絞れず、
     * 履歴が育つと表全体の走査になる（V107 で追加）。</p>
     *
     * @param teamId  チームID
     * @param date    基準日
     * @param userIds 取得対象（在籍中メンバーの userId）。<b>空リストを渡してはならない</b>
     * @return ユーザーごとの有効時給（基準日時点で時給が無いユーザーは含まれない）
     */
    @Query("SELECT r FROM ShiftHourlyRateEntity r "
            + "WHERE r.teamId = :teamId AND r.userId IN :userIds AND r.effectiveFrom <= :date "
            + "AND r.effectiveFrom = ("
            + "  SELECT MAX(r2.effectiveFrom) FROM ShiftHourlyRateEntity r2 "
            + "  WHERE r2.userId = r.userId AND r2.teamId = :teamId AND r2.effectiveFrom <= :date"
            + ") ORDER BY r.userId")
    List<ShiftHourlyRateEntity> findEffectiveRatesByTeam(
            @Param("teamId") Long teamId,
            @Param("date") LocalDate date,
            @Param("userIds") List<Long> userIds);
}

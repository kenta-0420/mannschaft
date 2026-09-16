package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * シフト枠リポジトリ。
 */
public interface ShiftSlotRepository extends JpaRepository<ShiftSlotEntity, Long> {

    /**
     * スケジュールの全シフト枠を日付・開始時刻順で取得する。
     */
    List<ShiftSlotEntity> findByScheduleIdOrderBySlotDateAscStartTimeAsc(Long scheduleId);

    /**
     * スケジュールの特定日のシフト枠を取得する。
     */
    List<ShiftSlotEntity> findByScheduleIdAndSlotDateOrderByStartTimeAsc(Long scheduleId, LocalDate slotDate);

    /**
     * ID一覧でシフト枠を一括取得する（N+1 防止用）。
     */
    List<ShiftSlotEntity> findAllByIdIn(Collection<Long> ids);

    /**
     * スケジュールIDで全シフト枠を削除する。
     */
    void deleteByScheduleId(Long scheduleId);

    /**
     * スケジュールのシフト枠数を取得する。
     */
    long countByScheduleId(Long scheduleId);

    /**
     * 指定ユーザーが割り当てられている全シフト枠を取得する（CMP-260908-2117）。
     *
     * <p><b>現在の割当状態の正本は {@code shift_slots.assigned_user_ids} である。</b>
     * 従来この経路は {@code shift_assignments.status = CONFIRMED} を引いており、
     * 同表に書き込むのが自動割当だけだったため、<b>手動割当したユーザーが
     * 「自分のシフト」に一切現れなかった</b>（本 CMP の本体）。</p>
     *
     * <p>可視性（未公開シフト表の遮断）は本クエリでは行わず、呼び出し側が
     * {@code ShiftScheduleVisibilityPolicy} で判定する。判定を SQL とポリシークラスの
     * 2 箇所に分散させないためである（削除済みスケジュールの枠だけは、
     * ネイティブクエリに {@code @SQLRestriction} が効かないので JOIN 側で落とす）。</p>
     *
     * <p>{@code JSON_CONTAINS(assigned_user_ids, CAST(:userId AS JSON))} は
     * 「JSON 配列が当該数値を要素として含むか」を判定する。列が {@code NULL} の場合
     * {@code JSON_CONTAINS} は {@code NULL} を返すため、割当なしの枠は自然に除外される。</p>
     *
     * @param userId 対象ユーザー ID
     * @return 当該ユーザーが割り当てられている枠（削除済みスケジュールの枠は除く）
     */
    @Query(value = "SELECT s.* FROM shift_slots s "
            + "JOIN shift_schedules sc ON sc.id = s.schedule_id "
            + "WHERE sc.deleted_at IS NULL "
            + "AND JSON_CONTAINS(s.assigned_user_ids, CAST(:userId AS JSON))",
            nativeQuery = true)
    List<ShiftSlotEntity> findAllAssignedToUser(@Param("userId") Long userId);

    /**
     * 指定ユーザーの「今後の予定」用に、公開済みシフト表の割当枠を期間 {@code [fromDate, untilDate)}
     * で取得する（CMP-260908-2117。個人ダッシュボード {@code GET /api/v1/dashboard/upcoming-events}）。
     *
     * <p>従来は {@code ShiftAssignmentRepository#findUpcomingByUserIdBetween} を使っており、
     * 手動割当が現れなかった。割当の正本である JSON 列を引くよう置き換える。</p>
     *
     * <p><b>未公開シフト表の遮断</b>: 旧実装は {@code status = 'CONFIRMED'} が偶然の公開ガードに
     * なっていた（自動割当の確定は公開前後を問わないので、これは保証ですらなかった）。
     * JSON 参照へ移すと偶然のガードすら消えるため、{@link ShiftScheduleEntity#FULLY_VISIBLE_SQL} を
     * 明示条件として置く。MASKED（COLLECTING / ADJUSTING）も通さない。</p>
     *
     * <p>返却は枠エンティティそのもの（件数に関わらず 1 クエリ。N+1 回避）。
     * <b>スカラー列の投影（{@code Object[]}）にしない理由</b>: ネイティブクエリのスカラー投影は
     * JDBC 既定型で戻るため {@code DATE}/{@code TIME} 列が {@code java.sql.Date}/{@code java.sql.Time}
     * になり、呼び出し側の {@code (LocalDate)} キャストが実行時に落ちる。
     * エンティティで受ければ Hibernate の属性型変換が効く。
     * スケジュール名・チーム ID は呼び出し側が ID 一括取得で解決する（追加 1 クエリで定数本数）。</p>
     *
     * @param userId    対象ユーザー ID
     * @param fromDate  取得期間の開始日（含む）
     * @param untilDate 取得期間の終了日（含まない）
     */
    @Query(value = "SELECT s.* "
            + "FROM shift_slots s "
            + "JOIN shift_schedules sc ON sc.id = s.schedule_id "
            + "WHERE JSON_CONTAINS(s.assigned_user_ids, CAST(:userId AS JSON)) "
            + "AND " + ShiftScheduleEntity.FULLY_VISIBLE_SQL + " "
            + "AND s.slot_date >= :fromDate AND s.slot_date < :untilDate "
            + "ORDER BY s.slot_date ASC, s.start_time ASC",
            nativeQuery = true)
    List<ShiftSlotEntity> findUpcomingAssignedByUserIdBetween(
            @Param("userId") Long userId,
            @Param("fromDate") LocalDate fromDate,
            @Param("untilDate") LocalDate untilDate);
}

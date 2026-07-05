package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 予約スロットリポジトリ。
 */
public interface ReservationSlotRepository extends JpaRepository<ReservationSlotEntity, Long> {

    /**
     * チームのスロットを日付範囲で取得する。
     */
    List<ReservationSlotEntity> findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
            Long teamId, LocalDate from, LocalDate to);

    /**
     * チームの利用可能なスロットを日付範囲で取得する。
     */
    List<ReservationSlotEntity> findByTeamIdAndSlotStatusAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
            Long teamId, SlotStatus status, LocalDate from, LocalDate to);

    /**
     * 担当者のスロットを日付範囲で取得する。
     */
    List<ReservationSlotEntity> findByStaffUserIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
            Long staffUserId, LocalDate from, LocalDate to);

    /**
     * IDとチームIDでスロットを取得する。
     */
    Optional<ReservationSlotEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * チームの特定日のスロット数を取得する。
     */
    long countByTeamIdAndSlotDate(Long teamId, LocalDate slotDate);

    /**
     * 生成冪等の一括先読み（F03.4.2 §5.3）: 対象期間内の「テンプレ生成済みセル」の
     * {@code (template_id, slot_date, start_time)} 組を 1 クエリで取得する。
     *
     * <p>セル単位の {@code existsBy...} を都度発行すると最悪 13,440 クエリの N+1 になるため、
     * 呼び出し側（{@code ReservationSlotGenerationService}）はこの結果を Set 化してメモリ突合で
     * スキップ判定する。{@code @SQLRestriction} により論理削除済みセルは含まれない（purge 済みセルへの
     * 再生成は DB の UNIQUE 制約 {@code uq_rs_template_cell} が最終防御し、INSERT IGNORE でスキップされる）。</p>
     *
     * @return {@code [templateId(UUID), slotDate(LocalDate), startTime(LocalTime)]} の配列リスト
     */
    @Query("SELECT s.templateId, s.slotDate, s.startTime FROM ReservationSlotEntity s "
            + "WHERE s.teamId = :teamId AND s.slotDate BETWEEN :from AND :to "
            + "AND s.templateId IS NOT NULL")
    List<Object[]> findGeneratedCellKeysByTeamIdAndSlotDateBetween(
            @Param("teamId") Long teamId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * テンプレートが生成したセルの最終日を導出する（日次バッチの差分レンジ計算・F03.4.2 §5.4）。
     *
     * <p>専用カラムは持たず生成実績そのものを正とする（二重管理しない）。生成実績ゼロ（新規テンプレ）は
     * {@code null}。導出が並行 generate とズレても冪等キー（§5.3）が二重生成を最終防御するため安全。</p>
     */
    @Query("SELECT MAX(s.slotDate) FROM ReservationSlotEntity s WHERE s.templateId = :templateId")
    LocalDate findMaxGeneratedSlotDateByTemplateId(@Param("templateId") java.util.UUID templateId);

    /**
     * テンプレートが生成した枠数を数える（テンプレ物理削除時の {@code orphanedSlotCount}・F03.4.2 §4）。
     */
    long countByTemplateId(java.util.UUID templateId);

    /**
     * 指定ラインのライン軸枠を対象日以降で取得する（ライン削除フロー手順3の purge 対象列挙・F03.4.2 §5.5）。
     */
    List<ReservationSlotEntity> findByLineIdAndSlotDateGreaterThanEqual(Long lineId, LocalDate date);

    /**
     * 生成セル 1 枠の INSERT（冪等の最終防御込み・F03.4.2 §5.2/§5.3）。
     *
     * <p>{@code INSERT IGNORE} は冪等 UNIQUE {@code uq_rs_template_cell} との衝突
     * （並行 generate / 日次バッチ競合・purge 済み論理削除行との衝突）を<b>例外ではなく 0 行更新</b>として
     * 返す — §5.3「UNIQUE 制約違反を捕捉してスキップ扱い（エラーにしない）」の実装。
     * 例外方式（DataIntegrityViolationException 捕捉）だと Hibernate セッションが汚染され
     * 日付チャンク内の後続セルを巻き込むため、IGNORE 方式で同一意味論を実現する。</p>
     *
     * <p>Hibernate のネイティブクエリとして実行することで、TIME/DATE/BINARY(16) のパラメータバインドが
     * エンティティ永続化と<b>同一の変換規則</b>になる（素の JdbcTemplate 直挿入は MySQL Connector/J の
     * タイムゾーン変換が Hibernate 読取と非対称になり、JVM≠DB タイムゾーン環境で時刻が +9h ずれる
     * 実測バグがあったため禁止）。{@code booked_count}/{@code slot_status}/{@code is_exception} は
     * DDL 既定値（0 / 'AVAILABLE' / FALSE）に委ねる。</p>
     *
     * @param templateId 生成元テンプレート ID（UUIDv7 の BINARY(16) 表現・{@code UuidV7Entity} と同じ
     *                   ビッグエンディアン MSB→LSB）
     * @return 1 = 挿入成功 / 0 = UNIQUE 衝突スキップ
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO reservation_slots "
            + "(team_id, line_id, staff_user_id, template_id, slot_date, start_time, end_time, "
            + " capacity, title, price, approval_mode, created_by, created_at, updated_at) "
            + "VALUES (:teamId, :lineId, :staffUserId, :templateId, :slotDate, :startTime, :endTime, "
            + " :capacity, :title, :price, :approvalMode, :createdBy, NOW(6), NOW(6))",
            nativeQuery = true)
    int insertGeneratedCellIgnoreDuplicate(
            @Param("teamId") Long teamId,
            @Param("lineId") Long lineId,
            @Param("staffUserId") Long staffUserId,
            @Param("templateId") byte[] templateId,
            @Param("slotDate") LocalDate slotDate,
            @Param("startTime") java.time.LocalTime startTime,
            @Param("endTime") java.time.LocalTime endTime,
            @Param("capacity") Integer capacity,
            @Param("title") String title,
            @Param("price") java.math.BigDecimal price,
            @Param("approvalMode") String approvalMode,
            @Param("createdBy") Long createdBy);

    /**
     * 予約数を +1 し、定員に達したら FULL 化する<b>条件付きアトミック UPDATE</b>（オーバーブッキング防止の並行制御）。
     *
     * <p>設計書 F03.4 §3 の「booked_count の並行更新」に従い、単一行のアトミック更新で満席超過を防ぐ
     * （{@code SELECT ... FOR UPDATE} 等のペシミスティックロックは不要）。
     * {@code WHERE slot_status = 'AVAILABLE' AND booked_count < capacity} を満たす場合のみ 1 行更新し、
     * 更新後に {@code booked_count >= capacity} なら {@code slot_status} を {@code 'FULL'} にする。</p>
     *
     * <p>複数ユーザーが同一枠へ同時予約しても、最後の 1 枠を確保できるのは 1 トランザクションのみで、
     * 残りは <b>0 行更新</b>となる。呼び出し側は戻り値 0 を「満席で確保失敗」として扱い、トランザクションを
     * ロールバックする（予約 INSERT ごと巻き戻る）。</p>
     *
     * @param slotId 予約枠ID
     * @return 更新行数（1 = 確保成功、0 = 満席 or CLOSED で確保失敗）
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    // 注意: MySQL は単一テーブル UPDATE の SET を左から右へ評価し、後続の式は先に代入された列の
    // 新値を参照する。そのため slot_status の CASE を booked_count の代入より <b>前</b> に置き、
    // CASE が更新前（＝元の）booked_count を読むようにする（設計書 §3 の IF(booked_count + 1 >= ...) と同義）。
    // 順序を逆にすると CASE が booked_count+1 を二重に見て 1 件早く FULL 化する（capacity-1 で満席になるバグ）。
    @Query(value = "UPDATE reservation_slots "
            + "SET slot_status = CASE WHEN booked_count + 1 >= capacity THEN 'FULL' ELSE slot_status END, "
            + "    booked_count = booked_count + 1 "
            + "WHERE id = :slotId "
            + "  AND slot_status = 'AVAILABLE' "
            + "  AND booked_count < capacity "
            + "  AND deleted_at IS NULL",
            nativeQuery = true)
    int incrementBookedCountIfAvailable(@Param("slotId") Long slotId);

    /**
     * 予約数を -1 し、満席（FULL）が解消されたら AVAILABLE へ復帰させる<b>アトミック UPDATE</b>（キャンセル時）。
     *
     * <p>{@code booked_count} は 0 未満にならないよう下限 0 でクランプする。
     * {@code slot_status = 'FULL'} かつ {@code booked_count - 1 < capacity} の場合のみ {@code 'AVAILABLE'} へ戻す
     * （CLOSED＝スタッフ操作による受付終了は据え置く）。</p>
     *
     * @param slotId 予約枠ID
     * @return 更新行数
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    // increment 同様、slot_status の CASE を booked_count 減算より前に置き、CASE が更新前の
    // booked_count（と slot_status）を読むようにする。満席枠が定員未満へ戻れば AVAILABLE に復帰する。
    @Query(value = "UPDATE reservation_slots "
            + "SET slot_status = CASE WHEN slot_status = 'FULL' AND booked_count - 1 < capacity "
            + "                       THEN 'AVAILABLE' ELSE slot_status END, "
            + "    booked_count = CASE WHEN booked_count > 0 THEN booked_count - 1 ELSE 0 END "
            + "WHERE id = :slotId "
            + "  AND deleted_at IS NULL",
            nativeQuery = true)
    int decrementBookedCountAndReopen(@Param("slotId") Long slotId);
}

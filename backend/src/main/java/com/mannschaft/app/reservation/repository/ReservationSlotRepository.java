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

    /** endDate を含む業務日範囲検索（日跨ぎ枠を slotDate だけで落とさない）。 */
    @Query("SELECT s FROM ReservationSlotEntity s WHERE s.teamId = :teamId "
            + "AND s.slotDate <= :to AND s.endDate >= :from AND s.deletedAt IS NULL")
    List<ReservationSlotEntity> findByTeamIdAndBusinessDateOverlap(@Param("teamId") Long teamId,
                                                                     @Param("from") LocalDate from,
                                                                     @Param("to") LocalDate to);

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
     * テンプレートが生成したセルの最終日を<b>horizon 上限でクランプして</b>導出する
     * （日次バッチの差分レンジ計算・F03.4.2 §5.4 追補 / F03.4.5 §3.4）。
     *
     * <p>専用カラムは持たず生成実績そのものを正とする（二重管理しない）。生成実績ゼロ（新規テンプレ）は
     * {@code null}。導出が並行 generate とズレても冪等キー（§5.3）が二重生成を最終防御するため安全。</p>
     *
     * <p><b>クランプの根拠（F03.4.5 §3.4/S-8③）</b>: 臨時営業（{@code generate-single-day}・最大 +90日）が
     * horizon（tomorrow+27）の<b>外</b>に {@code template_id} 付きセルを作ると、素の {@code MAX(slot_date)} では
     * ウォーターマークが跳ねて差分レンジが恒久に空になり、当該テンプレの通常週次枠が最大 2 ヶ月未生成になる。
     * {@code slot_date <= :maxDate}（= tomorrow+27）で絞ることで horizon 外セルを無視し、通常の週次生成が
     * 欠落しないようにする。horizon 内生成はクランプ後も従来どおりウォーターマークを前進させ末尾 1 日へ収束する。</p>
     */
    @Query("SELECT MAX(s.slotDate) FROM ReservationSlotEntity s "
            + "WHERE s.templateId = :templateId AND s.slotDate <= :maxDate")
    LocalDate findMaxGeneratedSlotDateByTemplateIdClamped(
            @Param("templateId") java.util.UUID templateId, @Param("maxDate") LocalDate maxDate);

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
     * 実測バグがあったため禁止）。{@code booked_count}/{@code slot_status} は
     * DDL 既定値（0 / 'AVAILABLE'）に委ねる。</p>
     *
     * @param templateId 生成元テンプレート ID（UUIDv7 の BINARY(16) 表現・{@code UuidV7Entity} と同じ
     *                   ビッグエンディアン MSB→LSB）
     * @return 1 = 挿入成功 / 0 = UNIQUE 衝突スキップ
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO reservation_slots "
            + "(team_id, line_id, staff_user_id, template_id, slot_date, end_date, start_time, end_time, "
            + " capacity, title, price, approval_mode, created_by, created_at, updated_at) "
            + "VALUES (:teamId, :lineId, :staffUserId, :templateId, :slotDate, :slotDate, :startTime, :endTime, "
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

    /** 日跨ぎテンプレート用。end_date を slot_date の翌日へ設定する。 */
    @Modifying
    @Query(value = "INSERT IGNORE INTO reservation_slots "
            + "(team_id, line_id, staff_user_id, template_id, slot_date, end_date, start_time, end_time, "
            + " capacity, title, price, approval_mode, created_by, created_at, updated_at) "
            + "VALUES (:teamId, :lineId, :staffUserId, :templateId, :slotDate, DATE_ADD(:slotDate, INTERVAL 1 DAY), "
            + " :startTime, :endTime, :capacity, :title, :price, :approvalMode, :createdBy, NOW(6), NOW(6))",
            nativeQuery = true)
    int insertGeneratedOvernightCellIgnoreDuplicate(
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
     * 定期予約（F03.4.5 §6.2 W2-5）の週次枠解決に使う<b>唯一の範囲検索</b>。
     *
     * <p>起点枠の {@code (start_time, end_time)} に完全一致する枠を、{@code slot_date} の
     * <b>範囲 1 回</b>で引く（{@code from} = 起点日+7日 / {@code to} = 起点日+7×(repeatWeeks-1)日）。</p>
     *
     * <p><b>なぜ週ごとに投げないか（AC-5-10）</b>: 「起点日 + 7k」を 1 日ずつ
     * {@code findBy...SlotDateAndStartTime...} で解決すると 12 週で 12 クエリになり、
     * 会員の予約作成というホットパスに N+1 を作る。範囲 1 回で取得し、
     * 「7 の倍数日か」「同一ラインか」の絞り込みは呼び出し側がメモリで行う
     * （単発 blocked_times の日付ロードと同じ作法）。</p>
     *
     * <p><b>ライン軸を SQL 条件に入れない理由</b>: 共通枠（{@code line_id IS NULL}）とライン軸枠を
     * 「起点枠と同じ帰属か」で突合する必要があり、{@code null} 同値比較を JPQL の
     * {@code :param IS NULL} イディオムで書くと Hibernate の型推論が方言依存になる。
     * 判定を呼び出し側の {@code Objects.equals} に寄せて曖昧さを消す。</p>
     *
     * <p>並び順は {@code slot_date} 昇順・同日内は {@code id} 昇順。これは
     * <b>ロック順序（AC-5-6）</b>をそのまま与えるためで、呼び出し側は取得順に確保していけばよい。</p>
     *
     * @param teamId    チームID
     * @param from      検索開始日（含む）
     * @param to        検索終了日（含む）
     * @param startTime 起点枠の開始時刻（完全一致）
     * @param endTime   起点枠の終了時刻（完全一致）
     * @return 該当枠（日付昇順・同日は id 昇順）
     */
    @Query("SELECT s FROM ReservationSlotEntity s "
            + "WHERE s.teamId = :teamId "
            + "  AND s.slotDate BETWEEN :from AND :to "
            + "  AND s.startTime = :startTime "
            + "  AND s.endTime = :endTime "
            + "ORDER BY s.slotDate ASC, s.id ASC")
    List<ReservationSlotEntity> findRecurringCandidateSlots(
            @Param("teamId") Long teamId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("startTime") java.time.LocalTime startTime,
            @Param("endTime") java.time.LocalTime endTime);

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
     * 予約数を -1 する<b>アトミック UPDATE</b>（キャンセル時・下限 0 クランプ）。ステータスは変更しない。
     *
     * <p>F03.4.5 §6.1 の lost wakeup / 二重発火根治のため、従来「デクリメント＋AVAILABLE 復帰」を
     * 1 SQL で行っていた {@code decrementBookedCountAndReopen} を<b>分離</b>した。本メソッドは
     * {@code booked_count} の減算のみを担い、FULL→AVAILABLE 遷移は {@link #reopenSlotIfFull(Long)} が
     * 担う。呼び出しは必ず「デクリメント → reopen」の順で行う（reopen は減算後の {@code booked_count} を見て
     * 遷移可否を判定するため）。</p>
     *
     * @param slotId 予約枠ID
     * @return 更新行数
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE reservation_slots "
            + "SET booked_count = CASE WHEN booked_count > 0 THEN booked_count - 1 ELSE 0 END "
            + "WHERE id = :slotId "
            + "  AND deleted_at IS NULL",
            nativeQuery = true)
    int decrementBookedCount(@Param("slotId") Long slotId);

    /**
     * 満席（FULL）枠が定員未満になっていれば AVAILABLE へ復帰させ、<b>実際に遷移を起こしたか</b>を返す
     * （F03.4.5 §6.1・キャンセル待ち通知の発火ゲート）。
     *
     * <p><b>なぜ affected-rows でゲートするか（根治の核心）:</b> 従来はイベント発火可否を
     * サービス層の in-memory スナップショット（{@code entity.getSlotStatus()==FULL}）で判定していたが、
     * 実際の FULL→AVAILABLE 遷移は DB の UPDATE が確定させるため、両者が乖離すると
     * (A) 通知漏れ（lost wakeup: スナップショットが AVAILABLE でも DB が FULL→AVAILABLE 遷移）や
     * (B) 二重発火（capacity≥2 で複数 tx が wasFull=true）が起きる。
     * 本 UPDATE は {@code WHERE slot_status='FULL'} を直列化ガードとし、<b>遷移を起こした唯一の tx だけ</b>が
     * 1 を返す（行ロックにより並行 tx は逐次化され、後続は既に AVAILABLE を見て 0 を返す）。
     * 呼び出し側は戻り値が 1 のときだけ {@code ReservationSlotReopenedEvent} を発行する。</p>
     *
     * <p>{@code booked_count < capacity} 条件により、デクリメント後も定員に満たない場合のみ復帰する。
     * CLOSED（スタッフ操作による受付終了）は {@code slot_status='FULL'} 条件で対象外となり据え置かれる。</p>
     *
     * @param slotId 予約枠ID
     * @return 1 = FULL→AVAILABLE 遷移を起こした / 0 = 遷移なし（既に AVAILABLE・CLOSED・まだ定員以上 等）
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE reservation_slots "
            + "SET slot_status = 'AVAILABLE' "
            + "WHERE id = :slotId "
            + "  AND slot_status = 'FULL' "
            + "  AND booked_count < capacity "
            + "  AND deleted_at IS NULL",
            nativeQuery = true)
    int reopenSlotIfFull(@Param("slotId") Long slotId);
}

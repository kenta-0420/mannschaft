package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 予約リポジトリ。
 */
public interface ReservationRepository extends JpaRepository<ReservationEntity, Long> {

    /**
     * チームの予約をステータス指定でページング取得する。
     */
    Page<ReservationEntity> findByTeamIdAndStatusOrderByBookedAtDesc(
            Long teamId, ReservationStatus status, Pageable pageable);

    /**
     * チームの予約をページング取得する。
     */
    Page<ReservationEntity> findByTeamIdOrderByBookedAtDesc(Long teamId, Pageable pageable);

    /**
     * ユーザーの予約をステータス指定で取得する。
     */
    List<ReservationEntity> findByUserIdAndStatusOrderByBookedAtDesc(
            Long userId, ReservationStatus status);

    /**
     * ユーザーの予約一覧を取得する。
     */
    List<ReservationEntity> findByUserIdOrderByBookedAtDesc(Long userId);

    /**
     * IDとチームIDで予約を取得する。
     */
    Optional<ReservationEntity> findByIdAndTeamId(Long id, Long teamId);

    // ===== F03.4.3 機能G: 予約グループ（案(b) 兄弟行方式）=====

    /**
     * グループの兄弟行をチームスコープで取得する（F03.4.3 §5.1。
     * 他チームの groupId は空 → 404 = RESERVATION_040 で存在秘匿）。
     */
    List<ReservationEntity> findByGroupIdAndTeamIdOrderById(UUID groupId, Long teamId);

    /**
     * 複数グループの「枠数・末尾枠終了時刻」を 1 クエリで集約する
     * （一覧の {@code GroupSummaryDto} 一括解決・N+1 回避・F03.4.3 §5.6 #10）。
     *
     * @return {@code [groupId(UUID), count(Long), maxEndTime(LocalTime)]} の配列リスト
     */
    @Query("SELECT r.groupId, COUNT(r), MAX(s.endTime) FROM ReservationEntity r, ReservationSlotEntity s "
            + "WHERE r.reservationSlotId = s.id AND r.groupId IN :groupIds "
            + "GROUP BY r.groupId")
    List<Object[]> aggregateGroupSummaries(@Param("groupIds") Collection<UUID> groupIds);

    /**
     * IDとユーザーIDで予約を取得する。
     */
    Optional<ReservationEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * スロットIDとユーザーIDで有効な予約が存在するか確認する。
     */
    boolean existsByReservationSlotIdAndUserIdAndStatusIn(
            Long slotId, Long userId, List<ReservationStatus> statuses);

    /**
     * スロットに紐付く予約を取得する。
     */
    List<ReservationEntity> findByReservationSlotIdOrderByBookedAtAsc(Long slotId);

    /**
     * スロットに active（指定ステータスのいずれか）な予約が存在するか確認する。
     * スロット削除ガード（オーファン化防止）で使用する。
     * {@code @SQLRestriction("deleted_at IS NULL")} により論理削除済み予約は自動除外される。
     */
    boolean existsByReservationSlotIdAndStatusIn(
            Long slotId, List<ReservationStatus> statuses);

    /**
     * 指定ラインに active（指定ステータスのいずれか）な予約が存在するか確認する
     * （ライン削除フロー手順2の唯一の 409 ガード・F03.4.2 §5.5）。
     */
    boolean existsByLineIdAndStatusIn(Long lineId, List<ReservationStatus> statuses);

    /**
     * 指定スロット群のうち active（指定ステータスのいずれか）な予約が紐づくスロット ID を列挙する
     * （ライン削除フロー手順3の purge 除外判定・F03.4.2 §5.5。N+1 回避の一括クエリ）。
     */
    @Query("SELECT DISTINCT r.reservationSlotId FROM ReservationEntity r "
            + "WHERE r.reservationSlotId IN :slotIds AND r.status IN :statuses")
    List<Long> findSlotIdsWithActiveReservations(
            @Param("slotIds") List<Long> slotIds,
            @Param("statuses") List<ReservationStatus> statuses);

    /**
     * チームの予約統計: ステータス別件数を取得する。
     */
    long countByTeamIdAndStatus(Long teamId, ReservationStatus status);

    /**
     * F10.1.1 / P3b: 指定チームの「指定時刻以降に作成された」指定ステータス予約の件数を取得する
     * （管理者レンズ ⑤ ADMIN_TEAM_ALERT の「新規予約」用・本日 CONFIRMED）。
     * {@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みは自動除外される。
     */
    long countByTeamIdAndStatusAndCreatedAtGreaterThanEqual(
            Long teamId, ReservationStatus status, LocalDateTime createdAtFrom);

    /**
     * ユーザーの直近の予約を取得する（CONFIRMED かつ「来店日時（枠の日付＋開始時刻）が現在以降」）。
     *
     * <p>直近予約は「申込時刻（{@code booked_at}）」ではなく「来店日時（予約枠 {@code reservation_slots}
     * の {@code slot_date} ＋ {@code start_time}）」で判定する。過去に申し込んだ未来枠の予約を正しく
     * 直近予約として拾うため、{@link ReservationSlotEntity} を結合して枠の来店日時で絞り込む。</p>
     *
     * <p>来店日時の比較は「日付が今日より後」または「日付が今日ちょうどで開始時刻が現在時刻以降」で表現する。
     * これにより枠開始ちょうど（{@code start_time == :nowTime}）の予約も直近予約に含める（半開ではなく閉区間の下限）。
     * 並び順は来店日時（日付→開始時刻）の昇順。{@code @SQLRestriction("deleted_at IS NULL")} により
     * 論理削除済みの予約・枠は自動除外される。</p>
     *
     * @param userId  対象ユーザーID
     * @param today   現在の日付（{@code LocalDateTime.now(clock).toLocalDate()}）
     * @param nowTime 現在の時刻（{@code LocalDateTime.now(clock).toLocalTime()}）
     */
    @Query("SELECT r FROM ReservationEntity r, ReservationSlotEntity s " +
            "WHERE r.reservationSlotId = s.id " +
            "AND r.userId = :userId AND r.status = 'CONFIRMED' " +
            "AND (s.slotDate > :today OR (s.slotDate = :today AND s.startTime >= :nowTime)) " +
            "ORDER BY s.slotDate ASC, s.startTime ASC")
    List<ReservationEntity> findUpcomingByUserId(
            @Param("userId") Long userId,
            @Param("today") LocalDate today,
            @Param("nowTime") LocalTime nowTime);

    /**
     * 指定期間内のチームの予約件数を取得する。
     */
    long countByTeamIdAndBookedAtBetween(Long teamId, LocalDateTime from, LocalDateTime to);

    /**
     * F10.1.1 / P3b Wave2: 指定チームの「指定ステータス群」かつ「booked_at が半開区間 [from, to) 内」の
     * 予約件数を取得する（管理者レンズ「予約サマリ」の本日の予約数用・本日 JST に予約された CONFIRMED/PENDING の有効予約）。
     *
     * <p>上限を排他（{@code < :to}）にすることで、翌日 0:00 ちょうどの予約を本日分に二重計上しない。
     * {@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みは自動除外される。</p>
     */
    @Query("SELECT COUNT(r) FROM ReservationEntity r " +
            "WHERE r.teamId = :teamId AND r.status IN :statuses " +
            "AND r.bookedAt >= :from AND r.bookedAt < :to")
    long countByTeamIdAndStatusInAndBookedAtRange(
            @Param("teamId") Long teamId,
            @Param("statuses") List<ReservationStatus> statuses,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * 指定スロットIDリストに紐付くアクティブ予約を取得する（臨時休業通知用）。
     */
    List<ReservationEntity> findByReservationSlotIdInAndStatusIn(
            List<Long> slotIds, List<ReservationStatus> statuses);

    /**
     * 当日（JST 0時以降）に作成された CONFIRMED 予約をチーム別に集計する（F10.7 業務アラート用）。
     *
     * @param teamIds    対象チーム ID リスト
     * @param todayStart 本日 0:00:00 JST を UTC に変換した LocalDateTime
     * @return [teamId, count] の配列リスト
     */
    @Query(value =
            "SELECT r.team_id, COUNT(*) as cnt FROM reservations r " +
            "WHERE r.team_id IN (:teamIds) " +
            "AND r.status = 'CONFIRMED' " +
            "AND r.created_at >= :todayStart " +
            "AND r.deleted_at IS NULL " +
            "GROUP BY r.team_id",
            nativeQuery = true)
    List<Object[]> countTodayConfirmedByTeamIds(@Param("teamIds") List<Long> teamIds,
                                                @Param("todayStart") LocalDateTime todayStart);

    /**
     * PENDING 状態の予約をチーム別に集計する（F10.7 業務アラート用）。
     *
     * @param teamIds 対象チーム ID リスト
     * @return [teamId, count] の配列リスト
     */
    @Query(value =
            "SELECT r.team_id, COUNT(*) as cnt FROM reservations r " +
            "WHERE r.team_id IN (:teamIds) " +
            "AND r.status = 'PENDING' " +
            "AND r.deleted_at IS NULL " +
            "GROUP BY r.team_id",
            nativeQuery = true)
    List<Object[]> countPendingByTeamIds(@Param("teamIds") List<Long> teamIds);

    /**
     * 機能B（§5.B・§4.B）: 提案された予約不可枠と時間帯 overlap する active 予約を取得する。
     *
     * <p>「当該 {@code teamId} × {@code blockedDate} の slot（{@code resourceType='STAFF'} のときは
     * {@code staff_user_id = resourceId} に絞る）に紐づき、時間帯が半開区間で overlap する
     * {@code status IN (PENDING, CONFIRMED)} の予約」を引く。予約不可枠 作成/更新の 409 ガード
     * （RESERVATION_027）と impact API の観測点。</p>
     *
     * <p>対象軸の切り替えは {@code :resourceId} で行う: TEAM 軸のときは {@code null} を渡すと
     * {@code (:resourceId IS NULL OR ...)} が常に真になり全 slot を対象にする。STAFF 軸のときは
     * 対象スタッフ user_id を渡すと {@code s.staffUserId = :resourceId} で絞られる。</p>
     *
     * <p>時間帯 overlap は<b>半開区間</b>（{@code s.startTime < :endTimeExclusive AND
     * :startTimeInclusive < s.endTime}）。全日ブロックは呼び出し側で
     * {@code [LocalTime.MIN, LocalTime.MAX]} を渡すことで「その日の全 slot」に一致させる
     * （{@link ReservationUnavailabilityChecker} の全日 = 真と結果一致）。</p>
     *
     * <p>{@code ReservationEntity} / {@code ReservationSlotEntity} 双方の {@code @SQLRestriction}
     * （{@code deleted_at IS NULL}）により論理削除済みは自動除外される。</p>
     *
     * @param teamId             チームID
     * @param blockedDate        予約不可にしたい日
     * @param resourceId         STAFF 軸のときの対象スタッフ user_id。TEAM 軸のときは null（全 slot）
     * @param startTimeInclusive overlap 判定の開始（全日は {@code LocalTime.MIN}）
     * @param endTimeExclusive   overlap 判定の終了（全日は {@code LocalTime.MAX}）
     * @param statuses           active とみなすステータス（PENDING / CONFIRMED）
     * @return overlap する active 予約のリスト
     */
    @Query("SELECT r FROM ReservationEntity r, ReservationSlotEntity s " +
            "WHERE r.reservationSlotId = s.id " +
            "AND r.teamId = :teamId AND r.status IN :statuses " +
            "AND s.slotDate = :blockedDate " +
            "AND (:resourceId IS NULL OR s.staffUserId = :resourceId) " +
            "AND s.startTime < :endTimeExclusive AND :startTimeInclusive < s.endTime")
    List<ReservationEntity> findActiveReservationsOverlappingUnavailability(
            @Param("teamId") Long teamId,
            @Param("blockedDate") LocalDate blockedDate,
            @Param("resourceId") Long resourceId,
            @Param("startTimeInclusive") LocalTime startTimeInclusive,
            @Param("endTimeExclusive") LocalTime endTimeExclusive,
            @Param("statuses") List<ReservationStatus> statuses);
}

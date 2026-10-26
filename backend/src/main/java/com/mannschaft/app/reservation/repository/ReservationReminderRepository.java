package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.ReminderStatus;
import com.mannschaft.app.reservation.entity.ReservationReminderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 予約リマインダーリポジトリ。
 */
public interface ReservationReminderRepository extends JpaRepository<ReservationReminderEntity, Long> {

    /**
     * 予約IDに紐付くリマインダーを取得する。
     */
    List<ReservationReminderEntity> findByReservationIdOrderByRemindAtAsc(Long reservationId);

    /**
     * 送信対象のリマインダーを取得する（PENDING かつ送信時刻を過ぎたもの）。
     */
    List<ReservationReminderEntity> findByStatusAndRemindAtBefore(
            ReminderStatus status, LocalDateTime now);

    /**
     * 送信済みの印を条件付きで先取りする（PENDING → SENT。配送リスナーが配送の前に呼ぶ）。
     *
     * <p>{@code WHERE status = PENDING} のため、別の配送が先に取っていれば 0 件になる（二重配送の排他）。
     * {@code sentAt} には先取り時刻を書き、{@link #releaseClaim} の照合キーにする。
     * 戻り値が 1 のときだけ取得成功。</p>
     */
    @Modifying
    @Query("UPDATE ReservationReminderEntity r SET r.status = com.mannschaft.app.reservation.ReminderStatus.SENT, "
            + "r.sentAt = :claimedAt "
            + "WHERE r.id = :id AND r.status = com.mannschaft.app.reservation.ReminderStatus.PENDING")
    int claimPending(@Param("id") Long id, @Param("claimedAt") LocalDateTime claimedAt);

    /**
     * 先取りした印を PENDING に戻す（配送が例外で失敗したとき。次周回で再送される）。
     *
     * <p>{@code WHERE sent_at = claimedAt} のため、自分の先取りだけを戻す。
     * 別の周回が取り直した印は消さない。</p>
     */
    @Modifying
    @Query("UPDATE ReservationReminderEntity r SET r.status = com.mannschaft.app.reservation.ReminderStatus.PENDING, "
            + "r.sentAt = NULL "
            + "WHERE r.id = :id AND r.status = com.mannschaft.app.reservation.ReminderStatus.SENT "
            + "AND r.sentAt = :claimedAt")
    int releaseClaim(@Param("id") Long id, @Param("claimedAt") LocalDateTime claimedAt);

    /**
     * 予約IDに紐付くリマインダー数を取得する。
     */
    long countByReservationId(Long reservationId);

    /**
     * 予約IDに紐付くリマインダーを全削除する。
     */
    void deleteAllByReservationId(Long reservationId);
}

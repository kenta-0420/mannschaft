package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.ReminderStatus;
import com.mannschaft.app.reservation.entity.ReservationReminderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
     * 予約IDに紐付くリマインダー数を取得する。
     */
    long countByReservationId(Long reservationId);

    /**
     * 予約IDに紐付くリマインダーを全削除する。
     */
    void deleteAllByReservationId(Long reservationId);
}

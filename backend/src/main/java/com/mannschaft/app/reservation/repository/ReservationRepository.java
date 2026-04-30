package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
     * チームの予約統計: ステータス別件数を取得する。
     */
    long countByTeamIdAndStatus(Long teamId, ReservationStatus status);

    /**
     * ユーザーの直近の予約を取得する（CONFIRMED かつ booked_at が未来）。
     */
    @Query("SELECT r FROM ReservationEntity r WHERE r.userId = :userId AND r.status = 'CONFIRMED' AND r.bookedAt >= :now ORDER BY r.bookedAt ASC")
    List<ReservationEntity> findUpcomingByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * 指定期間内のチームの予約件数を取得する。
     */
    long countByTeamIdAndBookedAtBetween(Long teamId, LocalDateTime from, LocalDateTime to);

    /**
     * 指定スロットIDリストに紐付くアクティブ予約を取得する（臨時休業通知用）。
     */
    List<ReservationEntity> findByReservationSlotIdInAndStatusIn(
            List<Long> slotIds, List<ReservationStatus> statuses);
}

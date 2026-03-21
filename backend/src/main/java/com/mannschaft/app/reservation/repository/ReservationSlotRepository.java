package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
     * 親スロットに紐付く子スロットを取得する。
     */
    List<ReservationSlotEntity> findByParentSlotIdOrderBySlotDateAsc(Long parentSlotId);

    /**
     * チームの特定日のスロット数を取得する。
     */
    long countByTeamIdAndSlotDate(Long teamId, LocalDate slotDate);
}

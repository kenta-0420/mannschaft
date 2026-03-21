package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 予約ブロック時間リポジトリ。
 */
public interface ReservationBlockedTimeRepository extends JpaRepository<ReservationBlockedTimeEntity, Long> {

    /**
     * チームのブロック時間を日付範囲で取得する。
     */
    List<ReservationBlockedTimeEntity> findByTeamIdAndBlockedDateBetweenOrderByBlockedDateAscStartTimeAsc(
            Long teamId, LocalDate from, LocalDate to);

    /**
     * チームの特定日のブロック時間を取得する。
     */
    List<ReservationBlockedTimeEntity> findByTeamIdAndBlockedDateOrderByStartTimeAsc(
            Long teamId, LocalDate date);

    /**
     * IDとチームIDでブロック時間を取得する。
     */
    Optional<ReservationBlockedTimeEntity> findByIdAndTeamId(Long id, Long teamId);
}

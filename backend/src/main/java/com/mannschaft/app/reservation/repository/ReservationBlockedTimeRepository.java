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

    /** 指定業務日に実効区間が掛かる日跨ぎブロックも含める。 */
    @org.springframework.data.jpa.repository.Query("SELECT b FROM ReservationBlockedTimeEntity b "
            + "WHERE b.teamId = :teamId AND b.blockedDate <= :date "
            + "AND (b.blockedDate = :date OR (b.endsNextDay = true AND b.blockedDate = :dateMinusOne)) "
            + "ORDER BY b.blockedDate ASC, b.startTime ASC")
    List<ReservationBlockedTimeEntity> findEffectiveOnDate(@org.springframework.data.repository.query.Param("teamId") Long teamId,
                                                             @org.springframework.data.repository.query.Param("date") LocalDate date,
                                                             @org.springframework.data.repository.query.Param("dateMinusOne") LocalDate dateMinusOne);

    @org.springframework.data.jpa.repository.Query("SELECT b FROM ReservationBlockedTimeEntity b WHERE b.teamId = :teamId "
            + "AND b.blockedDate <= :to AND (b.blockedDate >= :from OR (b.endsNextDay = true AND b.blockedDate = :fromMinusOne)) "
            + "ORDER BY b.blockedDate ASC, b.startTime ASC")
    List<ReservationBlockedTimeEntity> findEffectiveBetween(@org.springframework.data.repository.query.Param("teamId") Long teamId,
                                                              @org.springframework.data.repository.query.Param("from") LocalDate from,
                                                              @org.springframework.data.repository.query.Param("to") LocalDate to,
                                                              @org.springframework.data.repository.query.Param("fromMinusOne") LocalDate fromMinusOne);

    /**
     * IDとチームIDでブロック時間を取得する。
     */
    Optional<ReservationBlockedTimeEntity> findByIdAndTeamId(Long id, Long teamId);
}

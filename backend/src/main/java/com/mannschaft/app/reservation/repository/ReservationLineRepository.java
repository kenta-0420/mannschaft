package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 予約ラインリポジトリ。
 */
public interface ReservationLineRepository extends JpaRepository<ReservationLineEntity, Long> {

    /**
     * チームの予約ラインを表示順で取得する。
     */
    List<ReservationLineEntity> findByTeamIdOrderByDisplayOrderAsc(Long teamId);

    /**
     * チームの有効な予約ラインを表示順で取得する。
     */
    List<ReservationLineEntity> findByTeamIdAndIsActiveTrueOrderByDisplayOrderAsc(Long teamId);

    /**
     * IDとチームIDで予約ラインを取得する。
     */
    Optional<ReservationLineEntity> findByIdAndTeamId(Long id, Long teamId);
}

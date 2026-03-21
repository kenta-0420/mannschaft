package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.entity.ReservationBusinessHourEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 予約営業時間リポジトリ。
 */
public interface ReservationBusinessHourRepository extends JpaRepository<ReservationBusinessHourEntity, Long> {

    /**
     * チームの営業時間設定を全て取得する。
     */
    List<ReservationBusinessHourEntity> findByTeamIdOrderByIdAsc(Long teamId);

    /**
     * チームの特定曜日の営業時間を取得する。
     */
    Optional<ReservationBusinessHourEntity> findByTeamIdAndDayOfWeek(Long teamId, String dayOfWeek);

    /**
     * チームの営業時間設定が存在するか確認する。
     */
    boolean existsByTeamId(Long teamId);
}

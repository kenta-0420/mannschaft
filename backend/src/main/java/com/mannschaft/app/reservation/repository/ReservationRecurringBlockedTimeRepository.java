package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.entity.ReservationRecurringBlockedTimeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 定期予約不可枠リポジトリ（F03.4.5 §4 W2-2）。
 */
public interface ReservationRecurringBlockedTimeRepository
        extends JpaRepository<ReservationRecurringBlockedTimeEntity, UUID> {

    /**
     * チームの定期予約不可枠数を数える（上限50行判定・RESERVATION_052）。
     */
    long countByTeamId(Long teamId);

    /**
     * チームの全ルールを取得する（一覧表示用。並び順は Service 層で曜日→開始時刻に整列）。
     */
    List<ReservationRecurringBlockedTimeEntity> findByTeamId(Long teamId);

    /**
     * チームの active ルールを取得する（判定対象の列挙・§4.2・上限50行のメモリ突合）。
     */
    List<ReservationRecurringBlockedTimeEntity> findByTeamIdAndIsActiveTrue(Long teamId);

    /**
     * ID とチーム ID でルールを取得する（IDOR 秘匿: 他チームは 404=RESERVATION_051）。
     */
    Optional<ReservationRecurringBlockedTimeEntity> findByIdAndTeamId(UUID id, Long teamId);

    /**
     * 指定ラインを対象とする active ルールを取得する（ライン削除フロー §4.1 手順1.5 の一時停止対象）。
     */
    List<ReservationRecurringBlockedTimeEntity> findByLineIdAndIsActiveTrue(Long lineId);
}

package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.entity.ReservationTeamSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * チームごとの予約設定リポジトリ。
 *
 * <p>organization_id ではなく team_id スコープのため、
 * {@code AbstractTenantAwareRepository} は使用しない。</p>
 */
public interface ReservationTeamSettingRepository extends JpaRepository<ReservationTeamSettingEntity, UUID> {

    /**
     * チームIDで設定を取得する。
     * 設定レコードが存在しない場合は {@link Optional#empty()} を返す。
     * 呼び出し元は empty の場合に {@code allowPublicReservation = false} として扱うこと。
     *
     * @param teamId チームID
     * @return 該当チームの予約設定（存在しない場合は empty）
     */
    Optional<ReservationTeamSettingEntity> findByTeamId(Long teamId);

    /**
     * チームIDに対応する設定レコードが存在するか確認する。
     *
     * @param teamId チームID
     * @return 存在する場合 true
     */
    boolean existsByTeamId(Long teamId);
}

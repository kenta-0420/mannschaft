package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * チームごとの予約既定ポリシーリポジトリ。
 *
 * <p>team_id スコープ（organization_id ではない）のため、
 * {@code AbstractTenantAwareRepository} は使用しない。</p>
 */
public interface ReservationPolicyRepository extends JpaRepository<ReservationPolicyEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ReservationPolicyEntity p WHERE p.teamId = :teamId")
    Optional<ReservationPolicyEntity> findPendingExpirePolicyForUpdate(@Param("teamId") Long teamId);

    /**
     * チームIDでポリシーを取得する。
     * レコードが存在しない場合は {@link Optional#empty()} を返す。
     * 呼び出し元は empty の場合に既定値（approvalMode=AUTO 等）として扱うこと。
     *
     * @param teamId チームID
     * @return 該当チームの予約ポリシー（存在しない場合は empty）
     */
    Optional<ReservationPolicyEntity> findByTeamId(Long teamId);

    /**
     * チームIDに対応するポリシーレコードが存在するか確認する。
     *
     * @param teamId チームID
     * @return 存在する場合 true
     */
    boolean existsByTeamId(Long teamId);
}

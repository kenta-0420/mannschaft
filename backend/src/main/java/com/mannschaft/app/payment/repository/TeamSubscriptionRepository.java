package com.mannschaft.app.payment.repository;

import com.mannschaft.app.payment.entity.TeamSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * チームサブスクリプションリポジトリ。
 */
public interface TeamSubscriptionRepository extends JpaRepository<TeamSubscriptionEntity, Long> {

    /**
     * チームの有効なサブスクリプションを取得する。
     */
    List<TeamSubscriptionEntity> findByTeamIdAndStatus(Long teamId,
            TeamSubscriptionEntity.SubscriptionStatus status);

    /**
     * チームが有料プラン（ACTIVE かつ FREE 以外）を持つか判定する。
     */
    @Query("SELECT COUNT(s) > 0 FROM TeamSubscriptionEntity s " +
            "WHERE s.teamId = :teamId AND s.status = 'ACTIVE' AND s.planType <> 'FREE'")
    boolean hasActivePaidPlan(@Param("teamId") Long teamId);

    /**
     * チームの最新サブスクリプションを取得する。
     */
    Optional<TeamSubscriptionEntity> findFirstByTeamIdOrderByCreatedAtDesc(Long teamId);
}

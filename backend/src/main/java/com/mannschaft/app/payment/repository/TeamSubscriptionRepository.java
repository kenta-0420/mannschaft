package com.mannschaft.app.payment.repository;

import com.mannschaft.app.payment.entity.TeamSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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
     * 指定チーム群のいずれかが ACTIVE かつ有料プラン（FREE 以外）を持つか判定する（F09.19.2 PERSONAL ゲート）。
     */
    @Query("SELECT COUNT(s) > 0 FROM TeamSubscriptionEntity s " +
            "WHERE s.teamId IN :teamIds AND s.status = 'ACTIVE' AND s.planType <> 'FREE'")
    boolean existsActivePaidPlanByTeamIds(@Param("teamIds") Collection<Long> teamIds);

    /**
     * 指定チーム群のいずれかが ACTIVE かつ ORGANIZATION プランを持つか判定する（F09.19.2 ORGANIZATION ゲート）。
     */
    @Query("SELECT COUNT(s) > 0 FROM TeamSubscriptionEntity s " +
            "WHERE s.teamId IN :teamIds AND s.status = 'ACTIVE' AND s.planType = 'ORGANIZATION'")
    boolean existsActiveOrganizationPlanByTeamIds(@Param("teamIds") Collection<Long> teamIds);

    /**
     * チームの最新サブスクリプションを取得する。
     */
    Optional<TeamSubscriptionEntity> findFirstByTeamIdOrderByCreatedAtDesc(Long teamId);

    /**
     * ACTIVE かつ有料プランのサブスクリプション数を取得する（Analytics 集計用）。
     */
    @Query("SELECT COUNT(s) FROM TeamSubscriptionEntity s " +
            "WHERE s.status = 'ACTIVE' AND s.planType <> 'FREE'")
    int countActivePaidSubscriptions();
}

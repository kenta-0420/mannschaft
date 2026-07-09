package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.repository.TeamSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * チームプラン状態確認サービス。
 * 有料プラン加入判定を一元管理し、モジュール有効化等の各サービスから参照する。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamPlanService {

    private final TeamSubscriptionRepository teamSubscriptionRepository;

    /**
     * チームが有料プランに加入しているか判定する。
     * Valkey キャッシュで高速化する。
     *
     * @param teamId チームID
     * @return 有料プラン加入中なら true
     */
    @Cacheable(value = "teamPlan", key = "#teamId")
    public boolean hasPaidPlan(Long teamId) {
        return teamSubscriptionRepository.hasActivePaidPlan(teamId);
    }

    /**
     * 指定チーム群のいずれかが有料プラン（ACTIVE かつ FREE 以外）を持つか判定する（F09.19.2 PERSONAL ゲート）。
     *
     * <p>キャッシュしない（サブスク状態変更を即時反映するため。広告非表示ゲートは判定材料の鮮度を優先する）。</p>
     *
     * @param teamIds 判定対象チーム ID 群（空なら false）
     * @return いずれか 1 件以上が有料なら true
     */
    public boolean hasAnyActivePaidPlan(java.util.Collection<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return false;
        }
        return teamSubscriptionRepository.existsActivePaidPlanByTeamIds(teamIds);
    }

    /**
     * 指定チーム群のいずれかが ORGANIZATION プラン（ACTIVE）を持つか判定する（F09.19.2 ORGANIZATION ゲート）。
     *
     * <p>組織自体のサブスクリプションは存在しないため、配下チームの {@code plan_type='ORGANIZATION'} で判定する。
     * キャッシュしない（同上）。</p>
     *
     * @param teamIds 組織配下のチーム ID 群（空なら false）
     * @return いずれか 1 件以上が ORGANIZATION プラン ACTIVE なら true
     */
    public boolean hasActiveOrganizationPlan(java.util.Collection<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return false;
        }
        return teamSubscriptionRepository.existsActiveOrganizationPlanByTeamIds(teamIds);
    }
}

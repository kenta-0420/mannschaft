package com.mannschaft.app.payment.service;

import com.mannschaft.app.billing.EntitlementQueryService;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.FeatureKeys;
import com.mannschaft.app.payment.repository.TeamSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * チームプラン状態確認サービス。
 * 有料プラン加入判定を一元管理し、モジュール有効化等の各サービスから参照する。
 *
 * <p>F20.1（課金・エンタイトルメント基盤）の Expand 期移行: {@link #hasPaidPlan(Long)} は既存
 * {@code team_subscriptions} 判定に加え、{@code isEntitled(TEAM, teamId, "legacy.paid_plan_bundle")} の
 * OR 委譲でエンタイトルメント発行の有料プランも true にする（後方互換ブリッジ・README §4.1・AC-14）。
 * シグネチャ・{@code @Cacheable("teamPlan")} は<b>変更しない</b>（呼び出し元 3 箇所を壊さない）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamPlanService {

    private final TeamSubscriptionRepository teamSubscriptionRepository;
    private final EntitlementQueryService entitlementQueryService;

    /**
     * チームが有料プランに加入しているか判定する。
     * Valkey キャッシュで高速化する。
     *
     * <p>Expand 期: {@code team_subscriptions} 判定 OR エンタイトルメントブリッジ
     * （{@code legacy.paid_plan_bundle}）のどちらかで true（README §4.1・AC-14）。</p>
     *
     * @param teamId チームID
     * @return 有料プラン加入中なら true
     */
    @Cacheable(value = "teamPlan", key = "#teamId")
    public boolean hasPaidPlan(Long teamId) {
        return teamSubscriptionRepository.hasActivePaidPlan(teamId)
                || entitlementQueryService.isEntitled(
                        EntitlementScopeKind.TEAM, teamId, FeatureKeys.LEGACY_PAID_PLAN_BUNDLE);
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

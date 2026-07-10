package com.mannschaft.app.payment.service;

import com.mannschaft.app.billing.EntitlementQueryService;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.FeatureKeys;
import com.mannschaft.app.payment.repository.TeamSubscriptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TeamPlanService#hasPaidPlan} Expand 期委譲の単体テスト（試練先行）。
 *
 * <p>対象 AC: AC-14（後方互換ブリッジ）。既存 {@code team_subscriptions} 判定 OR
 * {@code isEntitled(TEAM, teamId, legacy.paid_plan_bundle)} のどちらかで true。
 * 既存が true のときはブリッジを評価しない（OR 短絡）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamPlanService.hasPaidPlan Expand ブリッジ（AC-14）")
class TeamPlanServiceBridgeTest {

    @Mock
    private TeamSubscriptionRepository teamSubscriptionRepository;
    @Mock
    private EntitlementQueryService entitlementQueryService;

    @InjectMocks
    private TeamPlanService teamPlanService;

    @Test
    @DisplayName("既存 team_subscriptions が有料なら true（ブリッジ未評価・OR 短絡）")
    void legacyPaidShortCircuits() {
        given(teamSubscriptionRepository.hasActivePaidPlan(10L)).willReturn(true);

        assertThat(teamPlanService.hasPaidPlan(10L)).isTrue();
        verify(entitlementQueryService, never())
                .isEntitled(EntitlementScopeKind.TEAM, 10L, FeatureKeys.LEGACY_PAID_PLAN_BUNDLE);
    }

    @Test
    @DisplayName("AC-14: team_subscriptions 無しでも legacy.paid_plan_bundle の権利があれば true")
    void bridgeGrantsPaidPlan() {
        given(teamSubscriptionRepository.hasActivePaidPlan(10L)).willReturn(false);
        given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, 10L, FeatureKeys.LEGACY_PAID_PLAN_BUNDLE))
                .willReturn(true);

        assertThat(teamPlanService.hasPaidPlan(10L)).isTrue();
    }

    @Test
    @DisplayName("両方 false なら false")
    void bothFalseReturnsFalse() {
        given(teamSubscriptionRepository.hasActivePaidPlan(10L)).willReturn(false);
        given(entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, 10L, FeatureKeys.LEGACY_PAID_PLAN_BUNDLE))
                .willReturn(false);

        assertThat(teamPlanService.hasPaidPlan(10L)).isFalse();
    }
}

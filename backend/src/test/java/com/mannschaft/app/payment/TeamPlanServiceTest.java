package com.mannschaft.app.payment;

import com.mannschaft.app.billing.EntitlementQueryService;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.FeatureKeys;
import com.mannschaft.app.payment.repository.TeamSubscriptionRepository;
import com.mannschaft.app.payment.service.TeamPlanService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link TeamPlanService} の単体テスト。
 *
 * <p>F20.1 Expand 期ブリッジ: {@code hasPaidPlan} は {@code team_subscriptions} 判定 OR
 * {@code isEntitled(TEAM, teamId, legacy.paid_plan_bundle)} で判定するため、
 * {@link EntitlementQueryService} をモックする（未加入ケースは isEntitled=false で既存挙動不変・OR 短絡）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamPlanService 単体テスト")
class TeamPlanServiceTest {

    @Mock
    private TeamSubscriptionRepository teamSubscriptionRepository;

    @Mock
    private EntitlementQueryService entitlementQueryService;

    @InjectMocks
    private TeamPlanService service;

    @Nested
    @DisplayName("hasPaidPlan")
    class HasPaidPlan {

        @Test
        @DisplayName("正常系: 有料プラン加入中ならtrueを返す")
        void 有料プラン加入中() {
            given(teamSubscriptionRepository.hasActivePaidPlan(1L)).willReturn(true);

            assertThat(service.hasPaidPlan(1L)).isTrue();
        }

        @Test
        @DisplayName("正常系: 有料プラン未加入かつエンタイトルメントブリッジも無ければfalseを返す")
        void 有料プラン未加入() {
            given(teamSubscriptionRepository.hasActivePaidPlan(1L)).willReturn(false);
            // ブリッジ（legacy.paid_plan_bundle）も無い → 既存挙動どおり false（OR の第二項）。
            given(entitlementQueryService.isEntitled(
                    EntitlementScopeKind.TEAM, 1L, FeatureKeys.LEGACY_PAID_PLAN_BUNDLE)).willReturn(false);

            assertThat(service.hasPaidPlan(1L)).isFalse();
        }
    }
}

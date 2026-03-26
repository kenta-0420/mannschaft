package com.mannschaft.app.payment;

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
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamPlanService 単体テスト")
class TeamPlanServiceTest {

    @Mock
    private TeamSubscriptionRepository teamSubscriptionRepository;

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
        @DisplayName("正常系: 有料プラン未加入ならfalseを返す")
        void 有料プラン未加入() {
            given(teamSubscriptionRepository.hasActivePaidPlan(1L)).willReturn(false);

            assertThat(service.hasPaidPlan(1L)).isFalse();
        }
    }
}

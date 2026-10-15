package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.dto.StripeConnectStatusResponse;
import com.mannschaft.app.parking.entity.StripeConnectAccountEntity;
import com.mannschaft.app.parking.repository.StripeConnectAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link StripeConnectService} の単体テスト。
 * Stripe Connect のステータス取得ロジックを検証する。
 * オンボーディング開始はStripe APIに依存するため、getStatusのみテストする。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StripeConnectService 単体テスト")
class StripeConnectServiceTest {

    @Mock
    private StripeConnectAccountRepository stripeConnectAccountRepository;

    @InjectMocks
    private StripeConnectService stripeConnectService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 100L;
    private static final String STRIPE_ACCOUNT_ID = "acct_1234567890";

    private StripeConnectAccountEntity createAccount(boolean chargesEnabled, boolean payoutsEnabled, boolean onboardingCompleted) {
        StripeConnectAccountEntity entity = StripeConnectAccountEntity.builder()
                .userId(USER_ID)
                .stripeAccountId(STRIPE_ACCOUNT_ID)
                .build();
        entity.updateStripeStatus(chargesEnabled, payoutsEnabled, onboardingCompleted);
        return entity;
    }

    // ========================================
    // getStatus
    // ========================================

    @Nested
    @DisplayName("getStatus")
    class GetStatus {

        @Test
        @DisplayName("正常系: ステータスが取得できる")
        void getStatus_正常_ステータス取得() {
            // Given
            StripeConnectAccountEntity account = createAccount(true, true, true);
            given(stripeConnectAccountRepository.findByUserId(USER_ID)).willReturn(Optional.of(account));

            // When
            StripeConnectStatusResponse result = stripeConnectService.getStatus(USER_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getUserId()).isEqualTo(USER_ID);
            assertThat(result.getStripeAccountId()).isEqualTo(STRIPE_ACCOUNT_ID);
            assertThat(result.getChargesEnabled()).isTrue();
            assertThat(result.getPayoutsEnabled()).isTrue();
            assertThat(result.getOnboardingCompleted()).isTrue();
        }

        @Test
        @DisplayName("正常系: オンボーディング未完了のステータスが取得できる")
        void getStatus_オンボーディング未完了_ステータス取得() {
            // Given
            StripeConnectAccountEntity account = createAccount(false, false, false);
            given(stripeConnectAccountRepository.findByUserId(USER_ID)).willReturn(Optional.of(account));

            // When
            StripeConnectStatusResponse result = stripeConnectService.getStatus(USER_ID);

            // Then
            assertThat(result.getChargesEnabled()).isFalse();
            assertThat(result.getPayoutsEnabled()).isFalse();
            assertThat(result.getOnboardingCompleted()).isFalse();
        }

        @Test
        @DisplayName("異常系: アカウントが見つからない場合PARKING_029例外")
        void getStatus_アカウント不在_PARKING029例外() {
            // Given
            given(stripeConnectAccountRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> stripeConnectService.getStatus(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_029"));
        }
    }
}

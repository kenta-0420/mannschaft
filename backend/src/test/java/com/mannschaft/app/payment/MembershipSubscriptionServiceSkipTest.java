package com.mannschaft.app.payment;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.MembershipSubscriptionRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.MembershipSubscriptionService;
import com.mannschaft.app.payment.service.PaymentAuthorizationService;
import com.mannschaft.app.payment.service.PaymentItemService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import com.mannschaft.app.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F08.9 P5 第四波: {@link MembershipSubscriptionService#skip} の単体テスト。
 *
 * <p>ACTIVE のみスキップ可・二重スキップ 409・認可 403・pause 引数（behavior=void・resumes_at 計算値）・
 * skip_until の反映を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipSubscriptionService skip 単体テスト")
class MembershipSubscriptionServiceSkipTest {

    @Mock private MembershipSubscriptionRepository membershipSubscriptionRepository;
    @Mock private PaymentItemService paymentItemService;
    @Mock private PaymentItemRepository paymentItemRepository;
    @Mock private PaymentAuthorizationService paymentAuthorizationService;
    @Mock private ConnectAccountRepository connectAccountRepository;
    @Mock private ConnectChargeService connectChargeService;
    @Mock private FeePolicyResolver feePolicyResolver;
    @Mock private StripeCustomerRepository stripeCustomerRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private MemberPaymentService memberPaymentService;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private MembershipSubscriptionService service;

    private static final UUID SUB_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000cc");
    private static final Long PAYER = 100L;
    private static final Long BENEFICIARY = 100L;
    private static final Long OTHER = 999L;

    /** ACTIVE なサブスク（current_period_end=2026-09-30）。 */
    private MembershipSubscriptionEntity activeSubscription() {
        MembershipSubscriptionEntity sub = MembershipSubscriptionEntity.builder()
                .paymentItemId(1L)
                .beneficiaryUserId(BENEFICIARY)
                .payerUserId(PAYER)
                .scopeKind(ScopeKind.TEAM).scopeId(50L)
                .payeeConnectAccountId(UUID.randomUUID())
                .billingInterval(BillingInterval.MONTHLY)
                .status(MembershipSubscriptionStatus.ACTIVE)
                .feePolicyKey("DEFAULT")
                .faceAmount(3000).currency("JPY")
                .stripeSubscriptionId("sub_abc")
                .cancelAtPeriodEnd(false)
                .build();
        sub.setId(SUB_ID);
        // current_period_end = 2026-09-30（期末）。
        sub.applyCurrentPeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        return sub;
    }

    @Test
    @DisplayName("skip 正常系: ACTIVE → skipUntil=2026-10-31（period_end+1ヶ月）・Stripe pause 呼び出し確認")
    void skip_active_success() {
        MembershipSubscriptionEntity sub = activeSubscription();
        given(membershipSubscriptionRepository.findByIdAndDeletedAtIsNull(SUB_ID))
                .willReturn(Optional.of(sub));
        given(membershipSubscriptionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        MembershipSubscriptionEntity result = service.skip(SUB_ID, PAYER);

        // skip_until = current_period_end + 1 month = 2026-09-30 + 1ヶ月 = 2026-10-31 (月末)。
        assertThat(result.getSkipUntil()).isNotNull();
        LocalDate expectedResumesAt = LocalDate.of(2026, 9, 30).plusMonths(1); // 2026-10-30
        assertThat(result.getSkipUntil()).isEqualTo(expectedResumesAt);

        // Stripe pause 呼び出し: behavior=void・resumes_at 計算値を確認。
        ArgumentCaptor<Long> resumesAtCaptor = ArgumentCaptor.forClass(Long.class);
        verify(stripePaymentProvider).pauseSubscriptionCollection(
                eq("sub_abc"), resumesAtCaptor.capture(), anyString());
        long expectedEpochSec = expectedResumesAt.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        assertThat(resumesAtCaptor.getValue()).isEqualTo(expectedEpochSec);
    }

    @Test
    @DisplayName("skip: PENDING → SUBSCRIPTION_NOT_ACTIVE 409")
    void skip_pending_throwsNotActive() {
        MembershipSubscriptionEntity sub = MembershipSubscriptionEntity.builder()
                .paymentItemId(1L).beneficiaryUserId(BENEFICIARY).payerUserId(PAYER)
                .scopeKind(ScopeKind.TEAM).scopeId(50L).payeeConnectAccountId(UUID.randomUUID())
                .billingInterval(BillingInterval.MONTHLY)
                .status(MembershipSubscriptionStatus.PENDING)
                .feePolicyKey("DEFAULT").faceAmount(3000).currency("JPY")
                .stripeSubscriptionId("sub_abc").cancelAtPeriodEnd(false)
                .build();
        sub.setId(SUB_ID);
        given(membershipSubscriptionRepository.findByIdAndDeletedAtIsNull(SUB_ID))
                .willReturn(Optional.of(sub));

        assertThatThrownBy(() -> service.skip(SUB_ID, PAYER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo("MEMBERSHIP_BILLING_016");
        verify(stripePaymentProvider, never()).pauseSubscriptionCollection(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("skip: 既にスキップ中 → SUBSCRIPTION_ALREADY_SKIPPED 409")
    void skip_alreadySkipped_throwsAlreadySkipped() {
        MembershipSubscriptionEntity sub = activeSubscription();
        // 既にスキップ済み。
        sub.applySkipUntil(LocalDate.of(2026, 10, 30));
        given(membershipSubscriptionRepository.findByIdAndDeletedAtIsNull(SUB_ID))
                .willReturn(Optional.of(sub));

        assertThatThrownBy(() -> service.skip(SUB_ID, PAYER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo("MEMBERSHIP_BILLING_017");
        verify(stripePaymentProvider, never()).pauseSubscriptionCollection(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("skip: 所有者でない → SUBSCRIPTION_NOT_AUTHORIZED 403")
    void skip_notOwner_throwsNotAuthorized() {
        MembershipSubscriptionEntity sub = activeSubscription();
        given(membershipSubscriptionRepository.findByIdAndDeletedAtIsNull(SUB_ID))
                .willReturn(Optional.of(sub));
        // 後見判定も拒否。
        given(paymentAuthorizationService.authorizePayment(eq(OTHER), anyLong(), anyLong(), eq(false)))
                .willThrow(new BusinessException(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED));

        assertThatThrownBy(() -> service.skip(SUB_ID, OTHER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo("MEMBERSHIP_BILLING_018");
    }

    @Test
    @DisplayName("skip: 見つからない → SUBSCRIPTION_NOT_FOUND 404")
    void skip_notFound_throwsNotFound() {
        given(membershipSubscriptionRepository.findByIdAndDeletedAtIsNull(SUB_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.skip(SUB_ID, PAYER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo("MEMBERSHIP_BILLING_015");
    }
}

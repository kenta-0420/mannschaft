package com.mannschaft.app.payment;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
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
 * F08.9 P5 第四波: {@link MembershipSubscriptionService#resume} の単体テスト。
 *
 * <p>スキップ解除呼び出し・clearSkip・未スキップ 409・認可 403 を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipSubscriptionService resume 単体テスト")
class MembershipSubscriptionServiceResumeTest {

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

    private static final UUID SUB_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000dd");
    private static final Long PAYER = 100L;
    private static final Long BENEFICIARY = 100L;
    private static final Long OTHER = 999L;

    /** スキップ中のサブスク（skipUntil=2026-10-30）。 */
    private MembershipSubscriptionEntity skippedSubscription() {
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
        sub.applyCurrentPeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        sub.applySkipUntil(LocalDate.of(2026, 10, 30));
        return sub;
    }

    @Test
    @DisplayName("resume 正常系: スキップ解除・skipUntil=null・Stripe resume 呼び出し確認")
    void resume_success() {
        MembershipSubscriptionEntity sub = skippedSubscription();
        given(membershipSubscriptionRepository.findByIdAndDeletedAtIsNull(SUB_ID))
                .willReturn(Optional.of(sub));
        given(membershipSubscriptionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        MembershipSubscriptionEntity result = service.resume(SUB_ID, PAYER);

        // skipUntil がクリアされていること。
        assertThat(result.getSkipUntil()).isNull();

        // Stripe resume 呼び出し確認。
        verify(stripePaymentProvider).resumeSubscriptionCollection(eq("sub_abc"), anyString());
    }

    @Test
    @DisplayName("resume: スキップ未適用 → SUBSCRIPTION_NOT_SKIPPED 409（MEMBERSHIP_BILLING_022）")
    void resume_notSkipped_throwsNotSkipped() {
        // スキップなし（skipUntil=null）のサブスク。
        MembershipSubscriptionEntity sub = MembershipSubscriptionEntity.builder()
                .paymentItemId(1L).beneficiaryUserId(BENEFICIARY).payerUserId(PAYER)
                .scopeKind(ScopeKind.TEAM).scopeId(50L).payeeConnectAccountId(UUID.randomUUID())
                .billingInterval(BillingInterval.MONTHLY)
                .status(MembershipSubscriptionStatus.ACTIVE)
                .feePolicyKey("DEFAULT").faceAmount(3000).currency("JPY")
                .stripeSubscriptionId("sub_abc").cancelAtPeriodEnd(false)
                .build();
        sub.setId(SUB_ID);
        given(membershipSubscriptionRepository.findByIdAndDeletedAtIsNull(SUB_ID))
                .willReturn(Optional.of(sub));

        assertThatThrownBy(() -> service.resume(SUB_ID, PAYER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo("MEMBERSHIP_BILLING_022");
        verify(stripePaymentProvider, never()).resumeSubscriptionCollection(anyString(), anyString());
    }

    @Test
    @DisplayName("resume: 所有者でない → SUBSCRIPTION_NOT_AUTHORIZED 403")
    void resume_notOwner_throwsNotAuthorized() {
        MembershipSubscriptionEntity sub = skippedSubscription();
        given(membershipSubscriptionRepository.findByIdAndDeletedAtIsNull(SUB_ID))
                .willReturn(Optional.of(sub));
        given(paymentAuthorizationService.authorizePayment(eq(OTHER), anyLong(), anyLong(), eq(false)))
                .willThrow(new BusinessException(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED));

        assertThatThrownBy(() -> service.resume(SUB_ID, OTHER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo("MEMBERSHIP_BILLING_018");
    }

    @Test
    @DisplayName("resume: 見つからない → SUBSCRIPTION_NOT_FOUND 404")
    void resume_notFound_throwsNotFound() {
        given(membershipSubscriptionRepository.findByIdAndDeletedAtIsNull(SUB_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.resume(SUB_ID, PAYER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo("MEMBERSHIP_BILLING_015");
    }
}

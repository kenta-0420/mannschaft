package com.mannschaft.app.payment;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.repository.MembershipSubscriptionRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.MembershipSubscriptionService;
import com.mannschaft.app.payment.service.PaymentAuthorizationService;
import com.mannschaft.app.payment.service.PaymentItemService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F08.9 P5 第二波: {@link MembershipSubscriptionService#cancel} の単体テスト。
 *
 * <p>認可（払い手本人/後見/無権原）・cancel_at_period_end 反映・期末日（current_period_end）の応答反映・
 * 状態制約（ACTIVE/PAST_DUE 以外は 409）・404 を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipSubscriptionService cancel 単体テスト")
class MembershipSubscriptionServiceCancelTest {

    @Mock private MembershipSubscriptionRepository membershipSubscriptionRepository;
    @Mock private PaymentItemService paymentItemService;
    @Mock private PaymentAuthorizationService paymentAuthorizationService;
    @Mock private ConnectAccountRepository connectAccountRepository;
    @Mock private ConnectChargeService connectChargeService;
    @Mock private FeePolicyResolver feePolicyResolver;
    @Mock private StripeCustomerRepository stripeCustomerRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private MemberPaymentService memberPaymentService;

    @InjectMocks
    private MembershipSubscriptionService service;

    private static final UUID SUB_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000bb");
    private static final Long PAYER = 100L;
    private static final Long BENEFICIARY = 100L;
    private static final Long OTHER = 999L;
    private static final long PERIOD_END_EPOCH = LocalDate.of(2026, 9, 30)
            .atStartOfDay(ZoneId.systemDefault()).toEpochSecond();

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
        return sub;
    }

    @Test
    @DisplayName("正常系: 払い手本人が期末解約予約・cancel_at_period_end=true・期末日を応答に反映")
    void 本人が期末解約() {
        MembershipSubscriptionEntity sub = activeSubscription();
        given(membershipSubscriptionRepository.findByIdAndDeletedAtIsNull(SUB_ID)).willReturn(Optional.of(sub));
        given(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq("sub_abc"), anyString()))
                .willReturn(new StripePaymentProvider.SubscriptionInfo("sub_abc", "active", PERIOD_END_EPOCH));
        given(membershipSubscriptionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        MembershipSubscriptionEntity result = service.cancel(SUB_ID, PAYER);

        verify(stripePaymentProvider).cancelSubscriptionAtPeriodEnd(eq("sub_abc"), anyString());
        assertThat(result.getCancelAtPeriodEnd()).isTrue();
        assertThat(result.getCurrentPeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(result.getStatus()).isEqualTo(MembershipSubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("正常系: 後見保護者が期末解約（権原評価が通れば許可）")
    void 後見が期末解約() {
        MembershipSubscriptionEntity sub = activeSubscription();
        // 払い手は別人だが、操作者(OTHER)は受益者の保護者として権原評価が通る。
        sub = sub.toBuilder().payerUserId(PAYER).beneficiaryUserId(BENEFICIARY).build();
        sub.setId(SUB_ID);
        given(membershipSubscriptionRepository.findByIdAndDeletedAtIsNull(SUB_ID)).willReturn(Optional.of(sub));
        given(paymentAuthorizationService.authorizePayment(OTHER, BENEFICIARY, 1L, false))
                .willReturn(PayerRelationship.GUARDIAN);
        given(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq("sub_abc"), anyString()))
                .willReturn(new StripePaymentProvider.SubscriptionInfo("sub_abc", "active", PERIOD_END_EPOCH));
        given(membershipSubscriptionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        MembershipSubscriptionEntity result = service.cancel(SUB_ID, OTHER);

        assertThat(result.getCancelAtPeriodEnd()).isTrue();
    }

    @Test
    @DisplayName("異常系: 所有者でも後見でもない操作者は 403・Stripe 呼ばない")
    void 無権原は403() {
        MembershipSubscriptionEntity sub = activeSubscription();
        given(membershipSubscriptionRepository.findByIdAndDeletedAtIsNull(SUB_ID)).willReturn(Optional.of(sub));
        given(paymentAuthorizationService.authorizePayment(OTHER, BENEFICIARY, 1L, false))
                .willThrow(new BusinessException(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED));

        assertThatThrownBy(() -> service.cancel(SUB_ID, OTHER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBillingErrorCode.SUBSCRIPTION_NOT_AUTHORIZED);

        verify(stripePaymentProvider, never()).cancelSubscriptionAtPeriodEnd(anyString(), anyString());
    }

    @Test
    @DisplayName("異常系: 存在しないサブスクは 404")
    void 不在は404() {
        given(membershipSubscriptionRepository.findByIdAndDeletedAtIsNull(SUB_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(SUB_ID, PAYER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBillingErrorCode.SUBSCRIPTION_NOT_FOUND);
    }

    @Test
    @DisplayName("異常系: PENDING は ACTIVE/PAST_DUE でないため 409・Stripe 呼ばない")
    void PENDINGは409() {
        MembershipSubscriptionEntity sub = activeSubscription().toBuilder()
                .status(MembershipSubscriptionStatus.PENDING).build();
        sub.setId(SUB_ID);
        given(membershipSubscriptionRepository.findByIdAndDeletedAtIsNull(SUB_ID)).willReturn(Optional.of(sub));

        assertThatThrownBy(() -> service.cancel(SUB_ID, PAYER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBillingErrorCode.SUBSCRIPTION_NOT_ACTIVE);

        verify(stripePaymentProvider, never()).cancelSubscriptionAtPeriodEnd(anyString(), anyString());
    }
}

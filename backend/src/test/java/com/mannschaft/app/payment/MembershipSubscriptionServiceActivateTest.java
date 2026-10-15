package com.mannschaft.app.payment;

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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F08.9 P5 第三波: {@link MembershipSubscriptionService#activateOnInitialChargeIfPending} の単体テスト。
 *
 * <p>初回 charge CAPTURED 経由の PENDING→ACTIVE（唯一の活性化点）・冪等（PENDING 以外 no-op）・current_period 設定を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipSubscriptionService activateOnInitialChargeIfPending 単体テスト (P5-3)")
class MembershipSubscriptionServiceActivateTest {

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

    private static final UUID SUB_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000c1");

    private MembershipSubscriptionEntity subscription(MembershipSubscriptionStatus status, BillingInterval interval) {
        MembershipSubscriptionEntity sub = MembershipSubscriptionEntity.builder()
                .paymentItemId(1L).beneficiaryUserId(200L).payerUserId(100L)
                .scopeKind(ScopeKind.TEAM).scopeId(50L).payeeConnectAccountId(UUID.randomUUID())
                .billingInterval(interval).status(status).feePolicyKey("DEFAULT")
                .faceAmount(10_000).currency("JPY").cancelAtPeriodEnd(false)
                .build();
        sub.setId(SUB_ID);
        return sub;
    }

    @Test
    @DisplayName("PENDING → ACTIVE（唯一の活性化点・MONTHLY: current_period_end=今日+1ヶ月）")
    void pending_activates() {
        MembershipSubscriptionEntity sub = subscription(MembershipSubscriptionStatus.PENDING, BillingInterval.MONTHLY);
        given(membershipSubscriptionRepository.findByIdForUpdate(SUB_ID)).willReturn(Optional.of(sub));
        given(membershipSubscriptionRepository.save(org.mockito.ArgumentMatchers.any()))
                .willAnswer(inv -> inv.getArgument(0));

        service.activateOnInitialChargeIfPending(SUB_ID);

        ArgumentCaptor<MembershipSubscriptionEntity> captor =
                ArgumentCaptor.forClass(MembershipSubscriptionEntity.class);
        verify(membershipSubscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(MembershipSubscriptionStatus.ACTIVE);
        assertThat(captor.getValue().getCurrentPeriodEnd()).isEqualTo(LocalDate.now().plusMonths(1));
        assertThat(captor.getValue().getCurrentPeriodStart()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("冪等: 既に ACTIVE は no-op（save しない・二重活性化しない）")
    void alreadyActive_noOp() {
        given(membershipSubscriptionRepository.findByIdForUpdate(SUB_ID))
                .willReturn(Optional.of(subscription(MembershipSubscriptionStatus.ACTIVE, BillingInterval.MONTHLY)));

        service.activateOnInitialChargeIfPending(SUB_ID);

        verify(membershipSubscriptionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("null ID は no-op（行ロック取得もしない）")
    void nullId_noOp() {
        service.activateOnInitialChargeIfPending(null);
        verify(membershipSubscriptionRepository, never()).findByIdForUpdate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("対象不在は no-op（save しない）")
    void notFound_noOp() {
        given(membershipSubscriptionRepository.findByIdForUpdate(SUB_ID)).willReturn(Optional.empty());
        service.activateOnInitialChargeIfPending(SUB_ID);
        verify(membershipSubscriptionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
